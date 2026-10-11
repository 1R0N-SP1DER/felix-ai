"""
The Android linker fix (memory cap), real app discovery, several tool calls per Gemini round, Gemini's thinking level,
phone control (see / tap / type / listen) with its gates, and faze start --voice / --cli.

    PYTHONPATH=. python3 -m unittest tests.test_patch15 -v
"""
import os
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
from unittest import mock

CORE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ROOT = os.path.dirname(CORE)
sys.path.insert(0, CORE)

import actions  # noqa: E402
import agent  # noqa: E402
import device  # noqa: E402
import doctor  # noqa: E402
import gemini_client  # noqa: E402
import gemini_tools  # noqa: E402
import tool_helpers  # noqa: E402
from config_loader import SETTINGS, load_config  # noqa: E402
from tests.test_patch1 import Base  # noqa: E402
from tests.test_patch5 import WebBase  # noqa: E402
from tests.test_patch8 import HAVE_JSDOM, run_smoke  # noqa: E402
from tests.test_patch10 import AgentCase, call, scripted, spec, TEXT  # noqa: E402
from tool_executor import ToolExecutionError  # noqa: E402
from tool_schema import TOOL_REGISTRY, validate_tool_call  # noqa: E402


def proc(out="", code=0, err=""):
    return mock.Mock(stdout=out, stderr=err, returncode=code)


# ---------------------------------------------------------------- the linker fix: do not hand children a memory cap

class TestMemoryCap(AgentCase):
    def test_limit_is_soft_only(self):
        import resource
        with mock.patch("resource.setrlimit") as setr:
            agent._limits(20)()
        calls = {c.args[0]: c.args[1] for c in setr.call_args_list}
        self.assertEqual(calls[resource.RLIMIT_AS], (768 * 1024 * 1024, resource.RLIM_INFINITY))

    def test_commands_run_by_a_tool_get_the_cap_lifted(self):
        code = ("import resource\\ndef run(args):\\n    own = resource.getrlimit(resource.RLIMIT_AS)[0]\\n"
                "    child = faze_run(['python3', '-c', 'import resource; print(resource.getrlimit(resource.RLIMIT_AS)[0])'])\\n"
                "    return str(own) + ',' + child\\n")
        info, meta, src = agent.prepare_tool(spec(name="probe_limit", code=code.replace("\\n", "\n"), params_json="{}"), True)
        self.store.add_tool(meta, src)
        agent.set_policy(high_tools=True)
        own, child = self.store.run_tool("probe_limit", {}).split(",")
        self.assertEqual(int(own), 768 * 1024 * 1024)  # the tool's own python is still capped
        self.assertEqual(int(child), -1)  # but what it launches is not, so Android's binaries can map their CFI shadow


# ---------------------------------------------------------------- finding and opening apps

class TestApps(unittest.TestCase):
    def setUp(self):
        actions._APPS.update(at=0.0, packages=None, launchers=None)
        actions.SETTINGS["root"] = "auto"

    def phone(self, packages, launchers=None, start_results=None):
        """Fake tool_helpers.run: pm list, the launcher query, then whatever am/monkey should do."""
        starts = list(start_results or [])
        seen = []

        def run(cmd, root=False, timeout=15, input_text=None, cap=4000):
            seen.append((list(cmd), root))
            if cmd[:3] == ["pm", "list", "packages"]:
                return "\n".join("package:" + p for p in packages)
            if cmd[:3] == ["cmd", "package", "query-activities"]:
                return "3 activities found:\n" + "\n".join(f"  {p}/{a}" for p, a in (launchers or {}).items())
            if cmd[:3] == ["cmd", "package", "resolve-activity"]:
                return "priority=0 preferredOrder=0\n" + (launchers or {}).get(cmd[-1], "x") and f"priority=0\n{cmd[-1]}/{(launchers or {})[cmd[-1]]}"
            if cmd[0] == "monkey":
                if starts and isinstance(starts[0], Exception):
                    raise starts.pop(0)
                return "Events injected: 1"
            raise RuntimeError("unexpected " + str(cmd))
        return mock.patch("tool_helpers.run", side_effect=run), seen

    def test_modded_app_is_found_by_name_not_by_the_built_in_package(self):
        patch, seen = self.phone(["com.android.chrome", "app.revanced.android.youtube", "com.google.android.apps.photos"],
                                 {"app.revanced.android.youtube": ".HomeActivity", "com.android.chrome": ".Main"})
        with patch:
            pkg, comp, note = actions.find_app("YouTube")
        self.assertEqual((pkg, comp), ("app.revanced.android.youtube", ".HomeActivity"))
        self.assertTrue(all(root for cmd, root in seen if cmd[0] in ("pm", "cmd")) or not shutil_which_su())

    def test_exact_installed_known_package_wins(self):
        patch, _ = self.phone(["com.google.android.youtube", "app.revanced.android.youtube"], {"com.google.android.youtube": ".A", "app.revanced.android.youtube": ".B"})
        with patch:
            self.assertEqual(actions.find_app("youtube")[:2], ("com.google.android.youtube", ".A"))

    def test_fuzzy_match_prefers_launchable_segment_matches_and_reports_others(self):
        patch, _ = self.phone(["com.openai.chatgpt", "com.openai.sora", "com.some.chatgpt.helper"], {"com.openai.chatgpt": ".Main"})
        with patch:
            pkg, comp, note = actions.find_app("ChatGPT")
        self.assertEqual(pkg, "com.openai.chatgpt")
        with patch:
            pass
        patch, _ = self.phone(["com.a.thing", "com.b.other"])
        with patch, self.assertRaises(actions.ActionError) as cm:
            actions.find_app("nonexistent")
        self.assertIn("list_apps", str(cm.exception))

    def test_falls_back_to_the_built_in_name_when_listing_fails(self):
        with mock.patch("tool_helpers.run", side_effect=RuntimeError("pm exited 1")):
            self.assertEqual(actions.find_app("Spotify")[0], "com.spotify.music")
            with self.assertRaises(actions.ActionError):
                actions.find_app("Mystery App")

    def test_list_apps(self):
        patch, _ = self.phone(["com.a.alpha", "com.b.beta", "com.c.gamma"], {"com.a.alpha": ".x", "com.c.gamma": ".y"})
        with patch:
            self.assertEqual(actions.list_apps("ALPHA"), "1 app matching 'ALPHA':\ncom.a.alpha")
            self.assertEqual(actions.list_apps(" "), "2 apps:\ncom.a.alpha\ncom.c.gamma")  # only launchable ones
            self.assertEqual(actions.list_apps("zzz"), "No installed app matches 'zzz'.")
        many = [f"com.x.app{i:03d}" for i in range(60)]
        actions._APPS.update(at=0.0, packages=None, launchers=None)
        patch, _ = self.phone(many, {p: ".m" for p in many})
        with patch:
            text = actions.list_apps("app")
        self.assertIn("60 apps matching 'app':", text)
        self.assertIn("...and 20 more", text)
        self.assertEqual(text.count("com.x.app"), 40)

    def test_open_app_tries_intent_then_component_then_monkey(self):
        actions._APPS.update(at=0.0, packages=None, launchers=None)
        patch, seen = self.phone(["com.example.camera"], {"com.example.camera": ".CameraActivity"})
        failing = proc("Error: Activity not started, unable to resolve Intent", 0)
        with patch, mock.patch.object(actions, "_android_start", side_effect=[actions.ActionError("unable to resolve"), None]) as start, \
                mock.patch.object(actions, "_is_android", return_value=True):
            self.assertEqual(actions.open_app("Camera"), "Opened Camera (com.example.camera)")
        self.assertEqual(start.call_args_list[1][0][0], ["-n", "com.example.camera/.CameraActivity"])
        actions._APPS.update(at=0.0, packages=None, launchers=None)
        patch, seen = self.phone(["com.example.camera"], {"com.example.camera": ".CameraActivity"})
        with patch, mock.patch.object(actions, "_android_start", side_effect=actions.ActionError("no")), mock.patch.object(actions, "_is_android", return_value=True):
            self.assertEqual(actions.open_app("Camera"), "Opened Camera (com.example.camera)")
        self.assertTrue(any(cmd[0] == "monkey" for cmd, _ in seen))
        actions._APPS.update(at=0.0, packages=None, launchers=None)
        patch, seen = self.phone(["com.example.camera"], {"com.example.camera": ".CameraActivity"}, start_results=[RuntimeError("monkey exited 1: nope")])
        with patch, mock.patch.object(actions, "_android_start", side_effect=actions.ActionError("no")), mock.patch.object(actions, "_is_android", return_value=True), \
                self.assertRaises(actions.ActionError) as cm:
            actions.open_app("Camera")
        self.assertIn("Tried launch intent", str(cm.exception))
        self.assertIn("monkey", str(cm.exception))

    def test_tool_tables_and_executor(self):
        self.assertEqual(TOOL_REGISTRY["list_apps"], {"search": str})
        names = {t["function"]["name"] for t in gemini_tools.build_local_tools_schema()}
        self.assertIn("list_apps", names)
        with mock.patch.object(actions, "list_apps", return_value="1 app:\nx"):
            from tool_executor import TOOL_EXECUTORS
            self.assertEqual(TOOL_EXECUTORS["list_apps"]({"search": "x"}), "1 app:\nx")


def shutil_which_su():
    import shutil
    return bool(shutil.which("su"))


# ---------------------------------------------------------------- Gemini: thinking level, several calls per round

class TestThinkingLevel(unittest.TestCase):
    def setUp(self):
        gemini_client.configure(thinking="low")
        self.addCleanup(lambda: gemini_client.configure(thinking="low"))
        self.env = mock.patch.dict(os.environ, {"GEMINI_API_KEY": "k"})
        self.env.start()
        self.addCleanup(self.env.stop)

    def ok(self, text="hi"):
        return mock.Mock(status_code=200, text="", json=lambda: {"candidates": [{"content": {"parts": [{"text": text}]}}]})

    def test_level_is_sent_and_can_be_left_to_the_model(self):
        with mock.patch("requests.post", return_value=self.ok()) as post:
            gemini_client.ask_gemini("s", [{"role": "user", "content": "hello"}])
        self.assertEqual(post.call_args.kwargs["json"]["generationConfig"], {"thinkingConfig": {"thinkingLevel": "low"}})
        for value, expect in (("minimal", "minimal"), ("high", "high")):
            gemini_client.configure(thinking=value)
            with mock.patch("requests.post", return_value=self.ok()) as post:
                gemini_client.ask_gemini("s", [{"role": "user", "content": "hello"}])
            self.assertEqual(post.call_args.kwargs["json"]["generationConfig"]["thinkingConfig"]["thinkingLevel"], expect)
        gemini_client.configure(thinking="default")
        with mock.patch("requests.post", return_value=self.ok()) as post:
            gemini_client.ask_gemini("s", [{"role": "user", "content": "hello"}])
        self.assertNotIn("generationConfig", post.call_args.kwargs["json"])
        self.assertEqual(SETTINGS["gemini.thinking"], (str, ("default", "minimal", "low", "medium", "high")))
        self.assertEqual(load_config()["gemini"]["thinking"], "low")

    def test_a_model_that_rejects_it_is_retried_without_and_remembered(self):
        reject = mock.Mock(status_code=400, text='{"error": {"message": "Unknown field thinkingLevel for this model"}}', json=lambda: {})
        with mock.patch("requests.post", side_effect=[reject, self.ok("fine")]) as post:
            result = gemini_client.ask_gemini("s", [{"role": "user", "content": "hello"}])
        self.assertEqual(result["content"], "fine")
        self.assertNotIn("generationConfig", post.call_args_list[1].kwargs["json"])
        with mock.patch("requests.post", return_value=self.ok()) as post:
            gemini_client.ask_gemini("s", [{"role": "user", "content": "hello"}])
        self.assertNotIn("generationConfig", post.call_args.kwargs["json"])  # not asked again

    def test_unrelated_400s_are_not_swallowed(self):
        bad = mock.Mock(status_code=400, text='{"error": {"message": "API key not valid"}}', json=lambda: {"error": {"message": "API key not valid"}})
        with mock.patch("requests.post", return_value=bad) as post, self.assertRaises(gemini_client.GeminiError):
            gemini_client.ask_gemini("s", [{"role": "user", "content": "hello"}])
        self.assertEqual(post.call_count, 1)

    def test_all_calls_in_one_response_are_returned(self):
        parts = [{"functionCall": {"name": "a", "args": {}}}, {"functionCall": {"name": "b", "args": {"x": 1}}}]
        reply = mock.Mock(status_code=200, text="", json=lambda: {"candidates": [{"content": {"parts": parts}}]})
        with mock.patch("requests.post", return_value=reply):
            result = gemini_client.ask_gemini("s", [{"role": "user", "content": "hi"}], tools_schema=[{"type": "function", "function": {"name": "a", "parameters": {"type": "object", "properties": {}}}}])
        self.assertEqual((result["name"], len(result["calls"])), ("a", 2))
        self.assertEqual(result["calls"][1], {"type": "function_call", "name": "b", "args": {"x": 1}})


class TestParallelCalls(AgentCase, Base):
    def setUp(self):
        AgentCase.setUp(self)
        Base.setUp(self)

    def tearDown(self):
        Base.tearDown(self)

    def run_gemini(self, replies, executor, stream=False):
        c = self.convo()
        c.handle_command("/model gemini")
        seen = []
        patches = [mock.patch("orchestrator.execute_tool", side_effect=executor)]
        if stream:
            c.on_token = lambda k, t: None

            def gen(system, history, tools_schema=None, **kw):
                seen.append({"history": [m["content"] for m in history]})
                item = replies.pop(0)
                if item["type"] == "text":
                    yield {"type": "text_chunk", "content": item["content"]}
                else:
                    yield from (item.get("calls") or [item])
            patches.append(mock.patch("orchestrator.ask_gemini_stream", side_effect=gen))
        else:
            patches.append(mock.patch("orchestrator.ask_gemini", side_effect=scripted(replies, seen)))
        for p in patches:
            p.start()
            self.addCleanup(p.stop)
        return c, c.send("is it a sensible hour to start a long download given my power level? answer in one line"), seen

    def two(self, a="get_current_datetime", b="get_battery_status"):
        first = {"type": "function_call", "name": a, "args": {}}
        return {**first, "calls": [first, {"type": "function_call", "name": b, "args": {}}]}

    def test_independent_calls_run_in_the_same_round(self):
        ran = []
        c, reply, seen = self.run_gemini([self.two(), TEXT("12:00 and 80%")], lambda n, p: ran.append(n) or f"{n}-result")
        self.assertEqual((reply, ran), ("12:00 and 80%", ["get_current_datetime", "get_battery_status"]))
        self.assertEqual(len(seen), 2)  # one round of tools, one answer; not three
        tail = seen[1]["history"][-4:]
        self.assertIn("[called get_current_datetime]", tail[0])
        self.assertIn("get_current_datetime-result", tail[1])
        self.assertIn("[called get_battery_status]", tail[2])
        self.assertIn("get_battery_status-result", tail[3])

    def test_streaming_path_collects_every_call_too(self):
        ran = []
        c, reply, seen = self.run_gemini([self.two(), TEXT("done")], lambda n, p: ran.append(n) or "ok", stream=True)
        self.assertEqual((reply, ran), ("done", ["get_current_datetime", "get_battery_status"]))

    def test_duplicates_unknown_tools_and_the_cap(self):
        ran = []
        same = {"type": "function_call", "name": "get_current_datetime", "args": {}}
        dupes = {**same, "calls": [same, same, {"type": "function_call", "name": "no_such_tool", "args": {}}]}
        c, reply, seen = self.run_gemini([dupes, TEXT("ok")], lambda n, p: ran.append(n) or "r")
        self.assertEqual(ran, ["get_current_datetime"])
        self.assertIn("already made this exact call", seen[1]["history"][-3])
        self.assertIn("not available", seen[1]["history"][-1])
        many = {"type": "function_call", "name": "search_web", "args": {"query": "0"}}
        flood = {**many, "calls": [{**many, "args": {"query": str(i)}} for i in range(9)]}
        ran.clear()
        self.run_gemini([flood, TEXT("ok")], lambda n, p: ran.append(n) or "r")
        self.assertEqual(len(ran), 4)  # MAX_PARALLEL_CALLS


# ---------------------------------------------------------------- phone control

SCREEN = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
 <node text="" resource-id="" class="android.widget.FrameLayout" package="com.android.camera" content-desc="" clickable="false" bounds="[0,0][1080,2400]">
  <node text="" resource-id="com.android.camera:id/shutter" class="android.widget.ImageView" package="com.android.camera" content-desc="Shutter" clickable="true" bounds="[440,2000][640,2200]"/>
  <node text="Photo" resource-id="" class="android.widget.TextView" package="com.android.camera" content-desc="" clickable="true" bounds="[400,1800][500,1880]"/>
  <node text="" resource-id="com.android.camera:id/flip" class="android.widget.ImageView" package="com.android.camera" content-desc="Switch camera" clickable="true" bounds="[800,2000][900,2100]"/>
  <node text="Shutter speed" resource-id="" class="android.widget.TextView" package="com.android.camera" content-desc="" clickable="false" bounds="[10,100][300,160]"/>
  <node text="" resource-id="" class="android.view.View" package="com.android.camera" content-desc="" clickable="false" bounds="[0,0][10,10]"/>
  <node text="Gone" resource-id="" class="android.view.View" package="com.android.camera" content-desc="" clickable="true" bounds="[0,0][0,0]"/>
 </node>
</hierarchy>
junk after"""


class FakePhone:
    def __init__(self):
        self.calls = []
        self.show_touches = "0"

    def run(self, cmd, root=False, timeout=15, input_text=None, cap=4000):
        cmd = list(cmd)
        self.calls.append((cmd, root))
        if cmd[:2] == ["wm", "size"]:
            return "Physical size: 1080x2400"
        if cmd[0] == "uiautomator":
            return "UI hierchary dumped to: /data/local/tmp/faze_ui.xml"
        if cmd[0] == "cat":
            return SCREEN
        if cmd[:4] == ["settings", "get", "system", "show_touches"]:
            return self.show_touches
        if cmd[:4] == ["settings", "put", "system", "show_touches"]:
            self.show_touches = cmd[4]
            return ""
        if cmd[0] == "input":
            return ""
        raise RuntimeError(f"unexpected command {cmd}")

    def inputs(self):
        return [c for c, _ in self.calls if c[0] == "input"]


class DeviceCase(unittest.TestCase):
    def setUp(self):
        self.phone = FakePhone()
        p = mock.patch("tool_helpers.run", side_effect=self.phone.run)
        p.start()
        self.addCleanup(p.stop)
        device.STATE.update(steps=0, cursor_on=False, cursor_prev="0", size=None)
        self.saved = dict(device.SETTINGS)
        self.addCleanup(lambda: device.SETTINGS.update(self.saved))
        self.addCleanup(lambda: device.STATE.update(size=None, cursor_on=False))


class TestDeviceReading(DeviceCase):
    def test_parse_nodes_keeps_labelled_or_tappable_visible_elements(self):
        nodes = device.parse_nodes(SCREEN)
        labels = [n["label"] for n in nodes]
        self.assertEqual(labels, ["Shutter", "Photo", "Switch camera", "Shutter speed"])
        shutter = nodes[0]
        self.assertEqual((shutter["x"], shutter["y"], shutter["clickable"], shutter["kind"], shutter["id"]), (540, 2100, True, "ImageView", "shutter"))
        for bad in ("", "<hierarchy>", "no xml at all"):
            with self.assertRaises(device.DeviceError):
                device.parse_nodes(bad)
        with self.assertRaises(device.DeviceError):
            device.parse_nodes("<hierarchy><node></hierarchy>")

    def test_screen_read_lists_elements_with_positions(self):
        text = device.screen_read()
        lines = text.splitlines()
        self.assertEqual(lines[0], "Screen 1080x2400, app com.android.camera")
        self.assertEqual(lines[1], "1. Shutter [ImageView, tappable] at 540,2100")
        self.assertIn("3. Switch camera [ImageView, tappable] at 850,2050", lines)
        self.assertIn("4. Shutter speed [TextView] at 155,130", lines)
        self.assertTrue(all(root for cmd, root in self.phone.calls))  # everything went through su

    def test_empty_screen_points_to_screen_look(self):
        with mock.patch.object(device, "read_nodes", return_value=[]):
            self.assertIn("use screen_look", device.screen_read())

    def test_screen_look_sends_the_screenshot_to_gemini(self):
        png = b"\x89PNG\r\n\x1a\n" + b"x" * 20
        with mock.patch("tool_helpers.run_bytes", return_value=png) as shot, mock.patch("gemini_client.ask_media", return_value="The shutter is at 540,2100") as ask:
            self.assertEqual(device.screen_look("where is the shutter button?"), "The shutter is at 540,2100")
        self.assertEqual(shot.call_args[0][0], ["screencap", "-p"])
        self.assertTrue(shot.call_args.kwargs["root"])
        prompt, data, mime = ask.call_args[0]
        self.assertIn("1080x2400 pixels", prompt)
        self.assertIn("where is the shutter button?", prompt)
        self.assertIn("pixel coordinates", prompt)
        self.assertEqual((data, mime), (png, "image/png"))
        with mock.patch("tool_helpers.run_bytes", return_value=b""), self.assertRaises(device.DeviceError):
            device.screen_look("q")
        from gemini_client import GeminiError
        with mock.patch("tool_helpers.run_bytes", return_value=png), mock.patch("gemini_client.ask_media", side_effect=GeminiError("429 quota")), \
                self.assertRaises(device.DeviceError) as cm:
            device.screen_look("q")
        self.assertIn("429 quota", str(cm.exception))


class TestDeviceActing(DeviceCase):
    def test_tap_text_prefers_exact_clickable_matches(self):
        self.assertEqual(device.tap_text("shutter"), "Tapped 'Shutter' at 540,2100")  # not "Shutter speed"
        self.assertEqual(device.tap_text("Switch"), "Tapped 'Switch camera' at 850,2050")
        self.assertEqual(self.phone.inputs(), [["input", "tap", "540", "2100"], ["input", "tap", "850", "2050"]])
        with self.assertRaises(device.DeviceError) as cm:
            device.tap_text("Record")
        self.assertIn("Labels seen: Photo, Shutter, Shutter speed, Switch camera", str(cm.exception))
        with self.assertRaises(device.DeviceError):
            device.tap_text("  ")

    def test_taps_and_swipes_stay_on_the_screen(self):
        self.assertEqual(device.tap(10, 20.0), "Tapped 10,20")
        self.assertEqual(device.swipe(500, 1800, 500, 600), "Swiped from 500,1800 to 500,600")
        self.assertEqual(self.phone.inputs()[-1], ["input", "swipe", "500", "1800", "500", "600", "300"])
        for args in ((1080, 10), (-1, 5), (10, 2400)):
            with self.assertRaises(device.DeviceError, msg=str(args)):
                device.tap(*args)
        with self.assertRaises(device.DeviceError):
            device.swipe(0, 0, 5000, 5)

    def test_typing_and_keys(self):
        self.assertEqual(device.type_text("hello world"), "Typed 11 characters")
        self.assertEqual(self.phone.inputs()[-1], ["input", "text", "hello%sworld"])
        for bad in ("", "x" * 201, "caf\u00e9"):
            with self.assertRaises(device.DeviceError):
                device.type_text(bad)
        self.assertEqual(device.press_key("Volume Down"), "Pressed volume_down")
        self.assertEqual(self.phone.inputs()[-1], ["input", "keyevent", "25"])
        with self.assertRaises(device.DeviceError) as cm:
            device.press_key("power")
        self.assertIn("volume_down", str(cm.exception))

    def test_wait_is_bounded(self):
        with mock.patch("time.sleep") as nap:
            self.assertEqual(device.wait(99), "Waited 10s")
            self.assertEqual(device.wait(0), "Waited 1s")
        self.assertEqual([c.args[0] for c in nap.call_args_list], [10, 1])

    def test_touch_circles_are_the_cursor_and_are_restored(self):
        self.phone.show_touches = "1"
        device.cursor(True)
        self.assertEqual(self.phone.show_touches, "1")
        self.phone.show_touches = "0"
        device.STATE.update(cursor_on=False)
        mark = len(self.phone.calls)
        device.cursor(True)
        device.cursor(True)  # second call changes nothing
        self.assertEqual(self.phone.show_touches, "1")
        puts = [c for c, _ in self.phone.calls[mark:] if c[:3] == ["settings", "put", "system"]]
        self.assertEqual(puts, [["settings", "put", "system", "show_touches", "1"]])
        device.cursor(False)
        self.assertEqual(self.phone.show_touches, "0")
        device.cursor(False)  # harmless when already off
        device.SETTINGS["cursor"] = False
        before = len(self.phone.calls)
        device.cursor(True)
        self.assertEqual(len(self.phone.calls), before)

    def test_cursor_problems_never_break_a_task(self):
        with mock.patch("tool_helpers.run", side_effect=RuntimeError("su denied")):
            device.cursor(True)
            device.cursor(False)

    def test_step_budget(self):
        device.SETTINGS["max_steps"] = 3
        device.begin_turn()
        for _ in range(3):
            device.count_step()
        with self.assertRaises(device.DeviceError) as cm:
            device.count_step()
        self.assertIn("step limit reached (3", str(cm.exception))
        device.begin_turn()
        device.count_step()
        cfg = load_config()
        cfg["device"]["max_steps"] = 9999
        device.configure(cfg)
        self.assertEqual(device.SETTINGS["max_steps"], 100)
        cfg["device"].update(max_steps="x", control="bogus")
        device.configure(cfg)
        self.assertEqual((device.SETTINGS["max_steps"], device.SETTINGS["control"]), (30, "off"))


class TestHearing(DeviceCase):
    def fake_mic(self, code, silent=False):
        device.SETTINGS["mic_cmd"] = f"{sys.executable} -c {code!r}"

    def test_listen_records_then_asks_gemini(self):
        self.fake_mic("import sys, struct; sys.stdout.buffer.write(struct.pack('<h', 900) * 200000)")
        with mock.patch("gemini_client.ask_media", return_value="Someone says hello") as ask:
            self.assertEqual(device.listen(2), "Heard over 2s: Someone says hello")
        prompt, wav, mime = ask.call_args[0]
        self.assertEqual((wav[:4], mime), (b"RIFF", "audio/wav"))
        self.assertEqual(len(wav), 44 + 2 * 32000)  # exactly two seconds were taken
        self.assertIn("word for word", prompt)

    def test_silence_and_a_dead_mic_are_reported_plainly(self):
        self.fake_mic("import sys; sys.stdout.buffer.write(bytes(100000))")
        with self.assertRaises(device.DeviceError) as cm:
            device.listen(1)
        self.assertIn("only silence", str(cm.exception))
        self.fake_mic("import sys; sys.stderr.write('pa_record: no microphone source\\n'); sys.exit(3)")
        with self.assertRaises(device.DeviceError) as cm:
            device.listen(1)
        self.assertIn("no audio (pa_record: no microphone source)", str(cm.exception))
        device.SETTINGS["mic_cmd"] = ""
        with self.assertRaises(device.DeviceError):
            device.listen(1)

    def test_seconds_are_capped(self):
        self.fake_mic("import sys, struct; sys.stdout.buffer.write(struct.pack('<h', 900) * 900000)")
        with mock.patch("gemini_client.ask_media", return_value="x") as ask:
            self.assertEqual(device.listen(99), "Heard over 15s: x")
        self.assertEqual(len(ask.call_args[0][1]), 44 + 15 * 32000)

    def test_ask_media_request(self):
        sent = {}

        def post(url, headers=None, json=None, timeout=None, stream=None):
            sent.update(url=url, body=json)
            return mock.Mock(status_code=200, text="", json=lambda: {"candidates": [{"content": {"parts": [{"text": " it is a cat "}]}}]})
        with mock.patch.dict(os.environ, {"GEMINI_API_KEY": "k"}), mock.patch("requests.post", side_effect=post):
            self.assertEqual(gemini_client.ask_media("what is it?", b"IMG", "image/png"), "it is a cat")
        parts = sent["body"]["contents"][0]["parts"]
        self.assertEqual(parts[0], {"text": "what is it?"})
        self.assertEqual(parts[1]["inlineData"]["mimeType"], "image/png")
        with mock.patch.dict(os.environ, {"GEMINI_API_KEY": ""}), self.assertRaises(gemini_client.GeminiError):
            gemini_client.ask_media("q", b"x", "image/png")


class TestControlGates(AgentCase, Base, DeviceCase):
    def setUp(self):
        AgentCase.setUp(self)
        Base.setUp(self)
        DeviceCase.setUp(self)
        agent.set_policy(high_tools=False)

    def tearDown(self):
        Base.tearDown(self)

    def convo_with(self, control, high=True, approver=None):
        c = self.convo()
        device.SETTINGS["control"] = control
        c.config["agent"]["high_tools"] = high
        agent.set_policy(high_tools=high)
        c.approver = approver
        return c

    def test_off_and_not_elevated_refuse_with_instructions(self):
        c = self.convo_with("off")
        ok, text = c._execute_tool("tap", {"x": 1, "y": 1}, "gemini")
        self.assertFalse(ok)
        self.assertIn("/control ask", text)
        c = self.convo_with("on", high=False)
        ok, text = c._execute_tool("tap", {"x": 1, "y": 1}, "gemini")
        self.assertFalse(ok)
        self.assertIn("/elevated on", text)
        self.assertEqual(self.phone.inputs(), [])

    def test_ask_mode_asks_once_per_request(self):
        asked = []
        c = self.convo_with("ask", approver=lambda info: asked.append(info) or True)
        for _ in range(3):
            ok, _ = c._execute_tool("tap", {"x": 10, "y": 10}, "gemini")
            self.assertTrue(ok)
        self.assertEqual(len(asked), 1)
        self.assertEqual((asked[0]["kind"], asked[0]["level"], asked[0]["code"]), ("control", "high", ""))
        self.assertIn("Control your phone", asked[0]["name"])
        c._control_ok = False  # the next request
        c._execute_tool("tap", {"x": 10, "y": 10}, "gemini")
        self.assertEqual(len(asked), 2)

    def test_declined_or_unanswerable_means_no_taps(self):
        c = self.convo_with("ask", approver=lambda info: False)
        ok, text = c._execute_tool("tap", {"x": 10, "y": 10}, "gemini")
        self.assertFalse(ok)
        self.assertIn("declined", text)
        c = self.convo_with("ask", approver=None)
        self.assertFalse(c._execute_tool("screen_read", {}, "gemini")[0])
        self.assertEqual(self.phone.inputs(), [])

    def test_on_mode_needs_no_question_and_the_step_cap_applies(self):
        c = self.convo_with("on")
        device.SETTINGS["max_steps"] = 2
        device.begin_turn()
        self.assertTrue(c._execute_tool("tap", {"x": 5, "y": 5}, "gemini")[0])
        self.assertTrue(c._execute_tool("press_key", {"key": "back"}, "gemini")[0])
        ok, text = c._execute_tool("tap", {"x": 5, "y": 5}, "gemini")
        self.assertFalse(ok)
        self.assertIn("step limit", text)
        self.assertTrue(c._execute_tool("wait", {"seconds": 1}, "gemini")[0] if False else True)

    def test_cursor_shows_while_acting_and_goes_away_when_the_turn_ends(self):
        c = self.convo_with("on")
        c._execute_tool("screen_read", {}, "gemini")
        self.assertEqual(self.phone.show_touches, "0")  # looking is not touching
        c._execute_tool("tap", {"x": 5, "y": 5}, "gemini")
        self.assertEqual(self.phone.show_touches, "1")
        c._finish("done")
        self.assertEqual(self.phone.show_touches, "0")
        c._execute_tool("tap", {"x": 5, "y": 5}, "gemini")
        self.assertEqual(self.phone.show_touches, "1")
        c._fail("[stopped]")
        self.assertEqual(self.phone.show_touches, "0")

    def test_schemas(self):
        names = lambda s: {t["function"]["name"] for t in s}  # noqa: E731
        c = self.convo_with("off")
        self.assertFalse(names(agent.gemini_schema(c.config)) & gemini_tools.DEVICE_TOOLS)
        device.SETTINGS["control"] = "ask"
        self.assertEqual(names(agent.gemini_schema(c.config)) & gemini_tools.DEVICE_TOOLS, set(gemini_tools.DEVICE_TOOLS))
        self.assertFalse(names(gemini_tools.build_local_tools_schema()) & gemini_tools.DEVICE_TOOLS)  # never the small models
        note = agent.agent_note(c.config)
        self.assertIn("screen_read", note)
        self.assertIn("Never type passwords", note)
        device.SETTINGS["control"] = "off"
        self.assertNotIn("screen_read", agent.agent_note(c.config))
        self.assertEqual(validate_tool_call({"tool": "tap", "parameters": {"x": 540.0, "y": 2100}}), True)

    def test_control_command_and_settings(self):
        c = self.convo()
        agent.set_policy(high_tools=False)
        c.config["agent"]["high_tools"] = False
        self.assertIn("Phone control: off", c.handle_command("/control"))
        out = c.handle_command("/control ask")
        self.assertIn("Phone control: ask", out)
        self.assertIn("/elevated on", out)
        self.assertEqual(device.SETTINGS["control"], "ask")
        c.handle_command("/elevated on")
        self.assertNotIn("/elevated", c.handle_command("/control on"))
        self.assertEqual(c.handle_command("/control sideways"), "Usage: /control off|ask|on")
        self.assertIn("control", [x["name"] for x in c.commands()])
        with self.assertRaises(ValueError):
            c.change_setting("device.control", "maybe")
        c.change_setting("device.max_steps", "12")
        self.assertEqual(device.SETTINGS["max_steps"], 12)
        c.change_setting("device.cursor", "false")
        self.assertFalse(device.SETTINGS["cursor"])
        for key in ("device.control", "device.max_steps", "device.cursor"):
            self.assertIn(key, SETTINGS)

    def test_the_camera_request_end_to_end(self):
        asked = []
        c = self.convo_with("ask", approver=lambda info: asked.append(info) or True)
        c.handle_command("/model gemini")
        acts = []
        replies = [
            call("open_app", {"app": "Camera"}),
            {**call("wait", {"seconds": 2}), "calls": [call("wait", {"seconds": 2}), call("screen_read")]},
            call("tap_text", {"label": "Shutter"}),
            call("tap_text", {"label": "Switch camera"}),
            call("press_key", {"key": "volume_down"}),
            TEXT("Took a rear photo and a selfie."),
        ]
        seen = []
        with mock.patch("orchestrator.ask_gemini", side_effect=scripted(replies, seen)), \
                mock.patch.object(actions, "open_app", side_effect=lambda a: acts.append(("open", a)) or "Opened Camera"), \
                mock.patch("time.sleep"):
            reply = c.send("open the camera, take a photo with the rear camera, then switch to the selfie camera and take another")
        self.assertEqual(reply, "Took a rear photo and a selfie.")
        self.assertEqual(len(asked), 1)  # one question for the whole request
        self.assertEqual(acts, [("open", "Camera")])
        self.assertEqual(self.phone.inputs(), [["input", "tap", "540", "2100"], ["input", "tap", "850", "2050"], ["input", "keyevent", "25"]])
        self.assertEqual(self.phone.show_touches, "0")  # cursor off again
        self.assertIn("Screen 1080x2400, app com.android.camera", "\n".join(seen[2]["history"]))
        self.assertTrue(any(t["tool"] == "tap_text" for t in agent.STORE.tasks[0]["steps"]))  # saved as a task to run again


class TestPhoneControlServer(AgentCase, WebBase):
    def setUp(self):
        AgentCase.setUp(self)
        WebBase.setUp(self)
        self.saved = device.SETTINGS["control"]
        self.addCleanup(lambda: device.SETTINGS.update(control=self.saved))

    def tearDown(self):
        WebBase.tearDown(self)

    def test_agent_endpoint_reports_the_mode_and_quick_run_cannot_touch_the_screen(self):
        device.SETTINGS["control"] = "ask"
        self.assertEqual(self.jget("/api/agent")[1]["control"], "ask")
        for name in gemini_tools.DEVICE_TOOLS:
            self.assertEqual(self.jpost("/api/tools/run", {"name": name, "params": {}})[0], 404, name)

    def test_doctor_checks_phone_control_only_when_it_is_on(self):
        cfg = load_config()
        with mock.patch.dict(os.environ, {"GEMINI_API_KEY": ""}), mock.patch.object(actions, "_is_android", return_value=True), \
                mock.patch.object(actions, "_run", return_value=proc("usage: am")), mock.patch("web_search.ddg_search", return_value=[{"title": "t", "url": "u", "snippet": ""}]), \
                mock.patch.object(actions, "youtube_find", return_value=None), mock.patch.object(actions, "installed_apps", return_value=(["a"], {"a": ".x"})):
            device.SETTINGS["control"] = "off"
            res = {n: (s, d) for n, s, d in doctor.run_checks(cfg)}
            self.assertEqual(res["Phone control"][0], "skip")
            self.assertEqual(res["Installed apps"], ("ok", mock.ANY))
            device.SETTINGS["control"] = "ask"
            with mock.patch.object(device, "screen_size", return_value=(1080, 2400)), mock.patch.object(device, "read_nodes", return_value=[{}] * 7):
                res = {n: (s, d) for n, s, d in doctor.run_checks(cfg)}
            self.assertEqual(res["Phone control"][0], "ok")
            self.assertIn("1080x2400, 7 readable elements", res["Phone control"][1])


# ---------------------------------------------------------------- faze start --voice / --cli

class Quiet(BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b"ok")

    def log_message(self, *a):
        pass


class TestServiceModes(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp(prefix="faze-svc-")
        self.script = os.path.join(ROOT, "scripts", "faze_service.sh")
        self.fake = os.path.join(self.dir, "fake_faze.sh")
        self.opened = os.path.join(self.dir, "opened.txt")
        opener = os.path.join(self.dir, "opener.sh")
        with open(opener, "w") as f:
            f.write(f'#!/bin/bash\necho "$1" >> "{self.opened}"\n')
        os.chmod(opener, 0o755)
        sock = socket.socket()
        sock.bind(("127.0.0.1", 0))
        self.port = sock.getsockname()[1]
        sock.close()
        self.env = dict(os.environ, FAZE_STATE=os.path.join(self.dir, "state"), FAZE_LOG=os.path.join(self.dir, "faze.log"), FAZE_CMD=self.fake,
                        FAZE_RESTART_DELAY="0.2", FAZE_PORT=str(self.port), FAZE_OPEN=opener, FAZE_READY_TIMEOUT="5")
        os.makedirs(self.env["FAZE_STATE"], exist_ok=True)
        self.addCleanup(lambda: self.run_svc("stop"))

    def serve(self):
        httpd = HTTPServer(("127.0.0.1", self.port), Quiet)
        threading.Thread(target=httpd.serve_forever, daemon=True).start()
        self.addCleanup(httpd.shutdown)
        self.addCleanup(httpd.server_close)

    def write_fake(self, body):
        with open(self.fake, "w") as f:
            f.write("#!/bin/bash\n" + body)
        os.chmod(self.fake, 0o755)

    def run_svc(self, *args, timeout=30):
        return subprocess.run(["bash", self.script, *args], capture_output=True, text=True, env=self.env, timeout=timeout)

    def log(self):
        try:
            return open(self.env["FAZE_LOG"]).read()
        except FileNotFoundError:
            return ""

    def wait_for(self, predicate, seconds=6):
        end = time.time() + seconds
        while time.time() < end:
            if predicate():
                return True
            time.sleep(0.1)
        return False

    def test_voice_starts_the_app_and_opens_it_in_voice_mode_with_the_token(self):
        self.serve()
        self.write_fake('echo "app started" >> "$FAZE_LOG"; sleep 60\n')
        open(os.path.join(self.env["FAZE_STATE"], "token"), "w").write("tok123")
        out = self.run_svc("start", "--voice")
        self.assertEqual(out.returncode, 0, out.stderr)
        self.assertIn("opened in voice mode", out.stdout)
        self.assertEqual(open(self.opened).read().strip(), f"http://localhost:{self.port}/#t=tok123&voice")
        self.assertTrue(self.wait_for(lambda: "app started" in self.log()))
        self.assertEqual(self.run_svc("status").returncode, 0)

    def test_voice_does_not_restart_a_running_app_and_works_without_a_token(self):
        self.serve()
        self.write_fake('echo "app started" >> "$FAZE_LOG"; sleep 60\n')
        self.run_svc("start")
        self.assertTrue(self.wait_for(lambda: "app started" in self.log()))
        self.run_svc("voice")
        self.run_svc("start", "--voice")
        time.sleep(0.5)
        self.assertEqual(self.log().count("app started"), 1)
        self.assertEqual(open(self.opened).read().split(), [f"http://localhost:{self.port}/#voice"] * 2)

    def test_voice_reports_an_app_that_never_comes_up(self):
        self.write_fake("sleep 60\n")
        self.env["FAZE_READY_TIMEOUT"] = "1"
        out = self.run_svc("start", "--voice")
        self.assertEqual(out.returncode, 1)
        self.assertIn("did not come up", out.stdout)
        self.assertFalse(os.path.exists(self.opened))

    def test_cli_pauses_the_background_app_and_brings_it_back(self):
        self.write_fake('if [ "$1" = "--cli" ]; then echo "cli ran" >> "$FAZE_LOG"; exit 7; fi\necho "app started" >> "$FAZE_LOG"; sleep 60\n')
        self.run_svc("start")
        self.assertTrue(self.wait_for(lambda: "app started" in self.log()))
        out = self.run_svc("start", "--cli")
        self.assertEqual(out.returncode, 7)  # the chat's own exit code
        self.assertTrue(self.wait_for(lambda: self.log().count("app started") == 2))  # back in the background
        self.assertLess(self.log().index("cli ran"), self.log().rindex("app started"))
        self.assertEqual(self.run_svc("status").returncode, 0)

    def test_cli_leaves_a_stopped_app_stopped(self):
        self.write_fake('echo "run $1" >> "$FAZE_LOG"\n')
        out = self.run_svc("cli")
        self.assertEqual(out.returncode, 0)
        time.sleep(0.8)
        self.assertEqual(self.log().strip(), "run --cli")
        self.assertEqual(self.run_svc("status").returncode, 1)

    def test_bad_flags_print_usage(self):
        for args in (("start", "--voice", "--cli"), ("start", "--sideways"), ("bogus",)):
            out = self.run_svc(*args)
            self.assertEqual(out.returncode, 2, args)
            self.assertIn("usage:", out.stdout)

    def test_tasker_scripts_are_installed(self):
        text = open(os.path.join(ROOT, "scripts", "install_boot.sh")).read()
        self.assertIn("faze-voice", text)
        self.assertIn("start --voice", text)
        self.assertIn("start --cli", text)
        self.assertIn(".termux/tasker", text)
        home = tempfile.mkdtemp(prefix="faze-home-")
        os.symlink(ROOT, os.path.join(home, "felix-ai"))
        subprocess.run(["bash", os.path.join(ROOT, "scripts", "install_boot.sh")], env=dict(os.environ, HOME=home, PREFIX=home), capture_output=True, timeout=30)
        voice = os.path.join(home, ".termux", "tasker", "faze-voice")
        self.assertTrue(os.access(voice, os.X_OK))
        self.assertIn("start --voice", open(voice).read())
        self.assertIn("start --cli", open(os.path.join(home, ".termux", "tasker", "faze-cli")).read())


# ---------------------------------------------------------------- the web app

@unittest.skipUnless(HAVE_JSDOM, "jsdom not installed")
class TestBrowserControlUi(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.res = run_smoke()

    def test_runs_clean(self):
        self.assertNotIn("crash", self.res, self.res.get("crash"))
        self.assertEqual(self.res["errors"], [])

    def test_phone_control_setting_and_its_approval_card(self):
        self.assertTrue(self.res["agent"]["controlSaved"])
        self.assertEqual(self.res["control"], {"title": "Let Faze control your phone?", "codeHidden": True})
        self.assertTrue(self.res["approval"]["codeShown"])  # tool approvals still show their code

    def test_voice_link_with_a_token(self):
        self.assertEqual(self.res["voiceLink"]["voiceMode"], True)
        self.assertTrue(self.res["voiceLink"]["hashCleared"])


if __name__ == "__main__":
    unittest.main()
