"""
Tools and actions in the sidebar and Tools menu, quick-run endpoint, Copy/Listen on your own prompts.

    PYTHONPATH=. python3 -m unittest tests.test_patch14 -v
"""
import os
import sys
import unittest
from unittest import mock

CORE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, CORE)

import agent  # noqa: E402
import web_server  # noqa: E402
from tests.test_patch5 import WebBase  # noqa: E402
from tests.test_patch8 import HAVE_JSDOM, run_smoke  # noqa: E402
from tests.test_patch10 import AgentCase, call, scripted, spec  # noqa: E402
from tests.test_patch1 import Base  # noqa: E402
from tests.test_patch8 import node  # noqa: E402
import subprocess  # noqa: E402
import tool_helpers  # noqa: E402


class TestCoerce(unittest.TestCase):
    def test_form_values_become_declared_types(self):
        from tool_schema import TOOL_REGISTRY
        with mock.patch.dict(TOOL_REGISTRY, {"t_all": {"a": int, "b": float, "c": bool, "d": str}}):
            self.assertEqual(web_server.coerce_params("t_all", {"a": "7", "b": "2.5", "c": "yes", "d": 12, "extra": 1}),
                             {"a": 7, "b": 2.5, "c": True, "d": "12"})
            self.assertEqual(web_server.coerce_params("t_all", {"a": 3.0, "b": 1, "c": False, "d": ""})["c"], False)
            self.assertIs(web_server.coerce_params("t_all", {"a": 1, "b": 1, "c": "off", "d": "x"})["c"], False)
            for bad, word in (({"a": "x", "b": 1, "c": 1, "d": "d"}, "'a' must be a int"), ({"a": 1, "b": 1, "c": 1}, "missing 'd'"),
                              ({"a": 1, "b": "z", "c": 1, "d": "d"}, "'b' must be a float")):
                with self.assertRaises(ValueError) as cm:
                    web_server.coerce_params("t_all", bad)
                self.assertIn(word, str(cm.exception))


class TestStoreRevision(AgentCase):
    def test_every_change_bumps_the_revision_and_params_are_listed(self):
        rev = self.store.rev
        info, meta, code = agent.prepare_tool(spec(), True)
        self.store.add_tool(meta, code)
        self.assertGreater(self.store.rev, rev)
        rev = self.store.rev
        self.store.save_task("convert miles to km", [{"tool": "miles_to_km", "args": {}, "ok": True}], "r")
        self.assertGreater(self.store.rev, rev)
        rev = self.store.rev
        self.store.run_tool("miles_to_km", {"miles": 1})
        self.assertGreater(self.store.rev, rev)
        snap = self.store.snapshot()
        self.assertEqual(snap["rev"], self.store.rev)
        self.assertEqual(snap["tools"][0]["params"], {"miles": {"type": "number", "description": "distance in miles"}})
        rev = self.store.rev
        self.store.remove_tool("miles_to_km")
        self.assertGreater(self.store.rev, rev)
        self.assertIsNone(self.store.code("miles_to_km"))

    def test_code_is_only_served_for_saved_tools(self):
        info, meta, code = agent.prepare_tool(spec(), True)
        self.store.add_tool(meta, code)
        self.assertIn("def run(args)", self.store.code("miles_to_km"))
        for name in ("../tools.json", "nope", ""):
            self.assertIsNone(self.store.code(name))


class TestQuickRun(AgentCase, WebBase):
    def setUp(self):
        AgentCase.setUp(self)
        WebBase.setUp(self)
        info, meta, code = agent.prepare_tool(spec(), True)
        agent.STORE.add_tool(meta, code)

    def tearDown(self):
        WebBase.tearDown(self)

    def test_runs_a_saved_tool_with_form_values(self):
        code, data = self.jpost("/api/tools/run", {"name": "miles_to_km", "params": {"miles": "10"}})
        self.assertEqual((code, data), (200, {"ok": True, "text": "16.09"}))
        self.assertEqual(agent.STORE.tools["miles_to_km"]["uses"], 1)
        code, status = self.jget("/api/status")
        self.assertEqual(status["agent"]["rev"], agent.STORE.rev)

    def test_built_in_tools_and_failures(self):
        code, data = self.jpost("/api/tools/run", {"name": "get_current_datetime", "params": {}})
        self.assertEqual((code, data["ok"]), (200, True))
        agent.set_policy(high_tools=False)
        info, meta, src = agent.prepare_tool(spec(name="shell_thing", level="high", params_json="{}",
                                                  code="import subprocess\ndef run(a):\n    return 'x'\n"), True)
        agent.STORE.add_tool(meta, src)
        code, data = self.jpost("/api/tools/run", {"name": "shell_thing", "params": {}})
        self.assertEqual(code, 200)
        self.assertFalse(data["ok"])
        self.assertIn("/elevated on", data["text"])

    def test_bad_requests(self):
        self.assertEqual(self.jpost("/api/tools/run", {"name": "miles_to_km", "params": {}})[0], 422)
        code, data = self.jpost("/api/tools/run", {"name": "miles_to_km", "params": {"miles": "far"}})
        self.assertEqual(code, 422)
        self.assertIn("must be a float", data["error"])
        for name in ("delegate_to_gemini", "create_tool", "enable_thinking_mode", "nope", "../x", ""):
            self.assertEqual(self.jpost("/api/tools/run", {"name": name, "params": {}})[0], 404, name)
        self.assertEqual(self.request("POST", "/api/tools/run", {"name": "miles_to_km", "params": {"miles": 1}}, token=None)[0].status, 401)

    def test_code_endpoint(self):
        code, data = self.jget("/api/tools/code?name=miles_to_km")
        self.assertEqual((code, data["name"]), (200, "miles_to_km"))
        self.assertIn("def run(args)", data["code"])
        self.assertEqual(self.jget("/api/tools/code?name=nope")[0], 404)
        self.assertEqual(self.jget("/api/tools/code?name=..%2Ftools")[0], 404)
        self.assertEqual(self.request("GET", "/api/tools/code?name=miles_to_km", token=None)[0].status, 401)

    def test_agent_endpoint_lists_params_for_the_forms(self):
        code, data = self.jget("/api/agent")
        self.assertEqual(data["tools"][0]["params"]["miles"]["type"], "number")
        self.assertEqual(data["rev"], agent.STORE.rev)


class TestGeminiStreaming(Base):
    def chunks(self, *items):
        def gen(system, history, tools_schema=None, **kw):
            for item in items:
                if isinstance(item, Exception):
                    raise item
                yield item
        return gen

    def round(self, convo, *items, hook=True):
        got = []
        convo.on_token = (lambda kind, text: got.append((kind, text))) if hook else None
        with mock.patch("orchestrator.ask_gemini_stream", side_effect=self.chunks(*items)), \
                mock.patch("orchestrator.ask_gemini", return_value={"type": "text", "content": "plain"}) as plain:
            return convo._gemini_round("sys", [{"role": "user", "content": "hi"}], None), got, plain

    def test_text_streams_through_the_token_hook(self):
        c = self.convo()
        result, got, plain = self.round(c, {"type": "text_chunk", "content": "Hel"}, {"type": "text_chunk", "content": "lo"})
        self.assertEqual(result, {"type": "text", "content": "Hello"})
        self.assertEqual(got, [("answer", "Hel"), ("answer", "lo")])
        plain.assert_not_called()

    def test_text_before_a_tool_call_is_dropped(self):
        c = self.convo()
        call_chunk = {"type": "function_call", "name": "get_battery_status", "args": {}}
        result, got, _ = self.round(c, {"type": "text_chunk", "content": "Let me check"}, call_chunk)
        self.assertEqual(result, call_chunk)
        self.assertEqual(got, [("answer", "Let me check"), ("reset", "")])
        result, got, _ = self.round(c, call_chunk)
        self.assertEqual(got, [])  # nothing to reset when nothing was shown

    def test_fallbacks_and_switches(self):
        from gemini_client import GeminiError
        c = self.convo()
        result, got, plain = self.round(c, GeminiError("stream endpoint down"))
        self.assertEqual(result["content"], "plain")  # nothing shown yet, so the plain call gets a turn
        plain.assert_called_once()
        c = self.convo()
        c.on_token = lambda k, t: None
        with mock.patch("orchestrator.ask_gemini_stream", side_effect=self.chunks({"type": "text_chunk", "content": "half"}, GeminiError("cut"))), \
                self.assertRaises(GeminiError):
            c._gemini_round("sys", [], None)  # mid-answer failure is not papered over
        result, got, plain = self.round(self.convo(), {"type": "text_chunk", "content": "x"}, hook=False)
        self.assertEqual(result["content"], "plain")  # no hook (terminal) means the plain call
        c = self.convo()
        c.config["gemini"]["stream"] = False
        result, got, plain = self.round(c, {"type": "text_chunk", "content": "x"})
        self.assertEqual((result["content"], got), ("plain", []))

    def test_end_to_end_reply_and_agent_loop_stream(self):
        c = self.convo()
        c.handle_command("/model gemini")
        tokens = []
        c.on_token = lambda kind, text: tokens.append((kind, text))
        script = [[{"type": "text_chunk", "content": "Checking. "}, {"type": "function_call", "name": "get_current_datetime", "args": {}}],
                  [{"type": "text_chunk", "content": "It is "}, {"type": "text_chunk", "content": "noon."}]]

        def stream(system, history, tools_schema=None, **kw):
            yield from script.pop(0)
        with mock.patch("orchestrator.ask_gemini_stream", side_effect=stream), mock.patch("orchestrator.execute_tool", return_value="12:00"):
            reply = c.send("check the clock, then tell me one fact about noon")
        self.assertEqual(reply, "It is noon.")
        self.assertEqual([k for k, _ in tokens], ["answer", "reset", "answer", "answer"])
        self.assertEqual(c.config["gemini"]["stream"], True)
        from config_loader import SETTINGS
        self.assertEqual(SETTINGS["gemini.stream"], (bool, None))


class TestFazeRun(AgentCase):
    def test_runs_commands_and_reports_real_errors(self):
        self.assertEqual(tool_helpers.run(["echo", "hi"]), "hi")
        self.assertEqual(tool_helpers.run("echo 'two words'"), "two words")
        with self.assertRaises(RuntimeError) as cm:
            tool_helpers.run(["false"])
        self.assertIn("false exited 1", str(cm.exception))
        with self.assertRaises(RuntimeError) as cm:
            tool_helpers.run(["/no/such/binary"])
        self.assertIn("could not run", str(cm.exception))
        with self.assertRaises(RuntimeError) as cm:
            tool_helpers.run(["sleep", "5"], timeout=0.3)
        self.assertIn("timed out", str(cm.exception))
        with self.assertRaises(RuntimeError):
            tool_helpers.run([])
        self.assertEqual(len(tool_helpers.run(["python3", "-c", "print('x' * 9000)"])), tool_helpers.OUTPUT_CAP)

    def test_system_commands_get_a_clean_environment_and_crashes_are_retried(self):
        ok = mock.Mock(stdout="done", stderr="", returncode=0)
        crash = mock.Mock(stdout="", stderr="CFI: /system/bin/linker64: runtime error: control flow integrity check failed", returncode=1)
        with mock.patch.dict(os.environ, {"LD_PRELOAD": "/x.so", "LD_LIBRARY_PATH": "/l", "PATH": "/p"}, clear=True), \
                mock.patch("subprocess.run", return_value=ok) as run:
            tool_helpers.run(["am", "start"])
        self.assertEqual(run.call_args.kwargs["env"], {"PATH": "/p"})
        with mock.patch.dict(os.environ, {"LD_PRELOAD": "/x.so", "PATH": "/p"}, clear=True), mock.patch("subprocess.run", side_effect=[crash, ok]) as run:
            self.assertEqual(tool_helpers.run(["termux-open-url", "https://a.com"]), "done")
        self.assertIsNone(run.call_args_list[0].kwargs["env"])
        self.assertEqual(run.call_args_list[1].kwargs["env"], {"PATH": "/p"})
        with mock.patch("subprocess.run", return_value=crash), self.assertRaises(RuntimeError) as cm:
            tool_helpers.run(["am", "start"])
        self.assertIn("CFI", str(cm.exception))

    def test_root_wraps_the_command_and_unsets_the_preload(self):
        ok = mock.Mock(stdout="", stderr="", returncode=0)
        with mock.patch("subprocess.run", return_value=ok) as run:
            tool_helpers.run(["settings", "put", "system", "screen_brightness", "120"], root=True)
        self.assertEqual(run.call_args[0][0], ["su", "-c", "unset LD_PRELOAD LD_LIBRARY_PATH; settings put system screen_brightness 120"])

    def test_tools_can_call_it_without_imports_and_it_counts_as_higher_level(self):
        code = "def run(args):\n    return faze_run(['echo', args['word']])\n"
        check = agent.assess_code(code)
        self.assertEqual((check["ok"], check["level"], check["reasons"]), (True, "high", ["runs system commands (faze_run)"]))
        with self.assertRaises(agent.ToolCreateError):
            agent.prepare_tool(spec(name="say_it", code=code, params_json='{"word": "string"}', level="normal"), high_allowed=False)
        info, meta, src = agent.prepare_tool(spec(name="say_it", code=code, params_json='{"word": "string"}', level="normal"), high_allowed=True)
        self.assertEqual(info["level"], "high")
        self.store.add_tool(meta, src)
        agent.set_policy(high_tools=True)
        self.assertEqual(self.store.run_tool("say_it", {"word": "hello"}), "hello")
        broken = "def run(args):\n    return faze_run(['false'])\n"
        info, meta, src = agent.prepare_tool(spec(name="fails_loudly", code=broken, params_json="{}"), True)
        self.store.add_tool(meta, src)
        with self.assertRaises(agent.ToolExecutionError) as cm:
            self.store.run_tool("fails_loudly", {})
        self.assertIn("false exited 1", str(cm.exception))
        self.assertIn("faze_run", agent.agent_note({"agent": {"mode": "ask", "high_tools": True}}))


class TestSpeechSplitting(unittest.TestCase):
    def split(self, buffer, final=False):
        return node("console.log(JSON.stringify(a.splitSpeakable(%s, %s)));" % (json_str(buffer), "true" if final else "false"))

    def test_sentences_come_out_as_they_finish(self):
        r = self.split("First sentence is right here. Second one follows. Third is still being wri")
        self.assertEqual(r["sentences"], ["First sentence is right here."])
        self.assertEqual(r["rest"], "Second one follows. Third is still being wri")  # under 20 characters waits for company

    def test_short_fragments_wait_and_numbers_are_not_sentence_ends(self):
        r = self.split("Yes. It costs 3.50 dollars and that is fine. Next")
        self.assertEqual(r["sentences"], ["Yes. It costs 3.50 dollars and that is fine."])
        self.assertEqual(r["rest"], "Next")
        self.assertEqual(self.split("It is 3.", False)["sentences"], [])  # no space after the dot yet

    def test_final_flushes_the_tail_and_newlines_end_lines(self):
        self.assertEqual(self.split("Tail without a stop", True), {"sentences": ["Tail without a stop"], "rest": ""})
        r = self.split("- one\n- two\nthree")
        self.assertEqual(r["sentences"], ["- one", "- two"])

    def test_code_is_skipped_and_held_until_closed(self):
        r = self.split("Here is the fix for you. ```python\nprint(1)\n``` And that is the whole thing. ")
        self.assertEqual(r["sentences"], ["Here is the fix for you.", "Code omitted. And that is the whole thing."])
        r = self.split("Here is the fix for you. ```python\nprint(")
        self.assertEqual((r["sentences"], r["rest"]), (["Here is the fix for you."], "```python\nprint("))
        self.assertEqual(self.split("```python\nprint(", True)["sentences"], [])

    def test_empty(self):
        self.assertEqual(self.split(""), {"sentences": [], "rest": ""})


def json_str(s):
    import json
    return json.dumps(s)


class TestFontCss(unittest.TestCase):
    def test_system_font_follows_the_device(self):
        css = open(os.path.join(CORE, "web", "app.css")).read()
        self.assertIn('html[data-platform="android"] { --font-system: sans-serif; }', css)
        self.assertIn("--font-ui: var(--font-system); --font-read: var(--font-system);", css)
        self.assertIn('html[data-font="custom"] { --font-ui: "FazeCustom", var(--font-system);', css)
        self.assertIn("font: var(--fs) / 1.55 var(--font-ui)", css)


@unittest.skipUnless(HAVE_JSDOM, "jsdom not installed")
class TestBrowserToolsAndActions(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.res = run_smoke()

    def test_runs_clean(self):
        self.assertNotIn("crash", self.res, self.res.get("crash"))
        self.assertEqual(self.res["errors"], [])

    def test_sidebar_sections(self):
        s = self.res["side"]
        self.assertEqual((s["toolsCount"], s["actionsCount"], s["closedByDefault"]), ("1", "1", True))
        self.assertEqual(s["quick"], ["Open app", "Play on YouTube", "Open link"])
        self.assertTrue(s["opened"])
        self.assertEqual(s["remembered"], "true")
        self.assertEqual((s["toolRow"], s["taskRow"]), ("miles to km", "convert 5 miles to km"))
        self.assertEqual(s["taskToInput"], "convert 5 miles to km")  # tap to edit before sending
        self.assertTrue(s["rerun"])  # the play button sends it straight away
        self.assertEqual(s["quickSent"], {"name": "open_app", "params": {"app": "Work"}})
        self.assertEqual(s["deleted"], [True, True])

    def test_run_sheet(self):
        r = self.res["run"]
        self.assertTrue(r["open"])
        self.assertEqual((r["title"], r["desc"], r["level"], r["fields"]), ("miles to km", "Convert miles to km.", "normal", ["number:miles"]))
        self.assertEqual(r["emptyBlocked"], 0)  # nothing runs until the field is filled
        self.assertEqual(r["sent"], {"name": "miles_to_km", "params": {"miles": 3}})
        self.assertEqual(r["result"], "4.83")
        self.assertIn("def run(args)", r["code"])
        self.assertTrue(r["escCloses"])

    def test_tools_menu_lists_gemini_made_tools(self):
        m = self.res["menu"]
        self.assertEqual((m["custom"], m["heading"]), (["miles to km"], "Made by Gemini"))
        self.assertTrue(m["opensSheet"] and m["menuClosed"])

    def test_streamed_speech(self):
        dev = self.res["voiceDevice"]
        self.assertEqual(dev["spoken"], ["First sentence is right here.", "Second one follows it nicely.", "And a third trailing bit"])
        self.assertEqual((dev["atReply"], dev["clips"]), (2, 0))  # two sentences were already spoken before the reply finished
        g = self.res["voiceGemini"]
        self.assertEqual(g["clipTexts"], dev["spoken"])
        self.assertEqual((g["played"], g["maxParallel"], g["clipsBeforeReply"], g["deviceSpoke"]), (3, 1, 2, 0))

    def test_token_reset_replaces_text_before_a_tool_call(self):
        self.assertEqual(self.res["reset"]["text"], "The real answer is short and clear.")

    def test_font_choices(self):
        f = self.res["font"]
        self.assertEqual((f["serif"], f["stored"], f["fileRowHidden"], f["fileRowShown"]), ("serif", '"serif"', True, True))
        self.assertEqual(f["platform"], "other")
        self.assertEqual(f["custom"], {"attr": "custom", "registered": ["FazeCustom:6"], "name": "MyFont.ttf", "savedName": '"MyFont.ttf"', "hasData": True})
        self.assertEqual(f["backToSystem"], "system")

    def test_copy_and_listen_on_your_own_prompt(self):
        mine = self.res["mine"]
        self.assertEqual(mine["buttons"], ["Copy", "Listen"])
        self.assertEqual((mine["copied"], mine["spoken"]), (mine["text"], mine["text"]))


if __name__ == "__main__":
    unittest.main()
