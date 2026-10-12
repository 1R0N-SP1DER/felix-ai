"""
Patch 16: Tasker/service launch fixes (WIP part), the thinking-level dropdown, and the server-side voice session
behind `faze start --voice` (no app opens; /overlay shows it).

    PYTHONPATH=. python3 -m unittest tests.test_patch16 -v
"""

import json
import os
import queue
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from http.server import HTTPServer
from unittest import mock

CORE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ROOT = os.path.dirname(CORE)
sys.path.insert(0, CORE)

from tests.test_patch1 import TMP, Base  # noqa: E402
from tests.test_patch5 import WebBase, TOKEN  # noqa: E402
from tests.test_patch15 import Quiet  # noqa: E402

import config_loader  # noqa: E402
import gemini_client  # noqa: E402
import voice_session  # noqa: E402
from config_loader import SETTINGS, load_config  # noqa: E402
from orchestrator import COMMANDS  # noqa: E402
from voice_session import VoiceSession, speakable  # noqa: E402

HAVE_JSDOM = subprocess.run(["node", "-e", "require('jsdom')"], capture_output=True, cwd=os.path.join(CORE, "tests")).returncode == 0


def run_node(script):
    out = subprocess.run(["node", os.path.join(CORE, "tests", script)], capture_output=True, text=True, timeout=60)
    return json.loads(out.stdout.strip().splitlines()[-1]) if out.stdout.strip() else {"crash": out.stderr}


# ---------------------------------------------------------------- thinking level

class TestThinkingLevel(Base):
    def setUp(self):
        super().setUp()
        self.addCleanup(lambda: gemini_client.configure(thinking="low"))

    def test_defaults_and_settings_table(self):
        self.assertEqual(SETTINGS["reasoning.level"], (str, ("auto", "off", "low", "medium", "high", "max")))
        self.assertEqual(SETTINGS["reasoning.mode"], (str, ("auto", "on", "off")))  # still there, still compatible
        self.assertEqual(load_config()["reasoning"], {"mode": "auto", "level": "auto"})

    def test_think_accepts_levels_and_keeps_mode_in_step(self):
        c = self.convo()
        expect = {"auto": "auto", "off": "off", "low": "on", "medium": "on", "high": "on", "max": "on"}
        for level, mode in expect.items():
            self.assertTrue(c.handle_command(f"/think {level}").startswith(f"Reasoning: {level}"), level)
            self.assertEqual((c.config["reasoning"]["level"], c.config["reasoning"]["mode"]), (level, mode))
            self.assertEqual(c.handle_command("/think"), f"Reasoning: {level}")
        self.assertEqual(c.handle_command("/think maybe"), "Usage: /think auto|off|low|medium|high|max")

    def test_old_on_still_works_and_picks_medium_unless_a_level_is_set(self):
        c = self.convo()
        self.assertTrue(c.handle_command("/think on").startswith("Reasoning: on"))
        self.assertEqual((c.config["reasoning"]["level"], c.config["reasoning"]["mode"]), ("medium", "on"))
        c.handle_command("/think high")
        c.handle_command("/think on")
        self.assertEqual(c.config["reasoning"]["level"], "high")  # an existing level is kept

    def test_any_level_but_off_makes_qwen_think_every_reply(self):
        for level, thinks in (("low", True), ("max", True), ("auto", False)):
            self.llama.requests.clear()
            self.llama.script = [{"content": "ok"}]
            c = self.convo()
            c.handle_command(f"/think {level}")
            c.send("a question")
            self.assertEqual(self.llama.requests[0]["chat_template_kwargs"]["enable_thinking"], thinks, level)

    def test_off_still_refuses_the_thinking_tool(self):
        self.llama.script = [{"tool": ("enable_thinking_mode", "{}")}]
        c = self.convo()
        c.handle_command("/think off")
        self.assertIn("thinking is turned off", c.send("prove something"))

    def test_gemini_level_mapping(self):
        c = self.convo()
        table = {"off": "minimal", "low": "low", "medium": "medium", "high": "high", "max": "high"}
        for level, gem in table.items():
            c.handle_command(f"/think {level}")
            self.assertEqual(gemini_client._THINKING["level"], gem, level)
        c.handle_command("/settings set gemini.thinking minimal")
        c.handle_command("/think auto")
        self.assertEqual(gemini_client._THINKING["level"], "minimal")  # auto follows gemini.thinking
        c.handle_command("/settings set gemini.thinking default")
        self.assertEqual(gemini_client._THINKING["level"], "")  # and "default" leaves it to the model
        c.handle_command("/think high")
        c.handle_command("/settings set gemini.thinking low")
        self.assertEqual(gemini_client._THINKING["level"], "high")  # an explicit level beats gemini.thinking

    def test_both_halves_are_saved_and_the_level_wins_at_startup(self):
        c = self.convo()
        c.handle_command("/think max")
        saved = config_loader.load_user_overrides()["reasoning"]
        self.assertEqual(saved, {"level": "max", "mode": "on"})
        c.handle_command("/think auto")
        c.handle_command("/think on")  # mode saved after a level of auto: the level must follow, not stay stale
        self.assertEqual(config_loader.load_user_overrides()["reasoning"], {"level": "medium", "mode": "on"})
        again = self.convo()
        self.assertEqual((again.config["reasoning"]["level"], again.config["reasoning"]["mode"]), ("medium", "on"))
        self.assertEqual(gemini_client._THINKING["level"], "medium")

    def test_a_mode_only_override_from_before_this_patch_still_applies(self):
        config_loader.set_user_setting("reasoning.mode", "off")
        c = self.convo()
        self.assertEqual((c.config["reasoning"]["level"], c.config["reasoning"]["mode"]), ("off", "off"))
        config_loader.set_user_setting("reasoning.mode", "on")
        self.assertEqual(self.convo().config["reasoning"]["level"], "medium")

    def test_reset_clears_both(self):
        c = self.convo()
        c.handle_command("/think high")
        self.assertTrue(c.reset_setting("reasoning.level"))
        self.assertEqual((c.config["reasoning"]["level"], c.config["reasoning"]["mode"]), ("auto", "auto"))
        self.assertNotIn("reasoning", config_loader.load_user_overrides())
        self.assertEqual(gemini_client._THINKING["level"], "low")

    def test_status_text_and_command_list(self):
        c = self.convo()
        c.handle_command("/think high")
        self.assertIn("think: high", c.handle_command("/status"))
        think = [x for x in COMMANDS if x["name"] == "think"][0]
        self.assertEqual([a["value"] for a in think["args"]], ["auto", "off", "low", "medium", "high", "max"])
        self.assertEqual(think["usage"], "/think [auto|off|low|medium|high|max]")


class TestThinkingWeb(WebBase):
    def test_status_exposes_level_and_gemini_setting(self):
        s = self.jget("/api/status")[1]
        self.assertEqual((s["think"], s["think_level"], s["gemini_thinking"]), ("auto", "auto", "low"))
        self.jpost("/api/command", {"line": "/think max"})
        s = self.jget("/api/status")[1]
        self.assertEqual((s["think"], s["think_level"]), ("on", "max"))
        self.assertEqual(gemini_client._THINKING["level"], "high")
        self.addCleanup(lambda: gemini_client.configure(thinking="low"))

    def test_settings_row_for_the_level(self):
        code, d = self.jpost("/api/settings", {"key": "reasoning.level", "value": "low"})
        self.assertEqual((code, d["value"]), (200, "low"))
        self.assertEqual(self.jget("/api/status")[1]["think"], "on")
        self.addCleanup(lambda: gemini_client.configure(thinking="low"))
        self.assertEqual(self.jpost("/api/settings", {"key": "reasoning.level", "value": "huge"})[0], 400)


@unittest.skipUnless(HAVE_JSDOM, "jsdom not installed")
class TestThinkingDropdownUi(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.res = run_node("ui_smoke.js")

    def test_dropdown_replaces_the_seg(self):
        self.assertNotIn("crash", self.res, self.res.get("crash"))
        self.assertEqual(self.res["errors"], [])
        t = self.res["think"]
        self.assertEqual(t["options"], ["auto", "off", "low", "medium", "high", "max"])
        self.assertFalse(t["oldSeg"])
        self.assertEqual((t["value"], t["menuVal"]), ("auto", "auto"))

    def test_hint_follows_the_selected_model(self):
        t = self.res["think"]
        self.assertIn("whichever model answers", t["hintAuto"])
        self.assertIn("one thinking mode", t["hintQwen"])
        self.assertIn("Max is High", t["hintGemini"])
        self.assertIn("cannot think", t["hintLfm"])
        self.assertIn("minimal", t["hintAutoHigh"])  # picks up gemini.thinking from status

    def test_lfm_disables_it_and_a_choice_sends_think(self):
        t = self.res["think"]
        self.assertEqual((t["disabledAuto"], t["disabledQwen"], t["disabledGemini"], t["disabledLfm"]), (False, False, False, True))
        self.assertEqual(t["cmds"], ["/think high"])
        self.assertEqual((t["valueAfter"], t["menuAfter"]), ("high", "high"))


# ---------------------------------------------------------------- voice session

class FakeListener:
    def __init__(self, pcm=b"\x01\x02" * 480, live=True):
        self.pcm, self.live, self.holds, self.args = pcm, live, [], None

    def hold(self, on):
        self.holds.append(on)

    def capture_utterance(self, max_seconds=12.0, start_timeout=6.0):
        self.args = (max_seconds, start_timeout)
        return self.pcm

    def _live(self):
        return self.live


class FakeProc:
    def __init__(self, argv, hang=False):
        self.argv, self.hang, self.terminated, self.polls = argv, hang, False, 0

    def poll(self):
        if self.terminated:
            return -15
        if self.hang:
            return None
        self.polls += 1
        return None if self.polls < 3 else 0

    def terminate(self):
        self.terminated = True


class VoiceBase(WebBase):
    hang_tts = False

    def setUp(self):
        super().setUp()
        self.procs, self.launched = [], []

        def popen(argv, **kw):
            self.procs.append(FakeProc(argv, hang=self.hang_tts))
            return self.procs[-1]

        self.listener = FakeListener()
        self.httpd.wake = self.listener
        self.httpd.voice = self.voice = VoiceSession(self.httpd, popen=popen, run=lambda argv: self.launched.append(argv))
        self.events = self.httpd.subscribe()
        p = mock.patch("stt.transcribe_best", return_value="what is the time")
        p.start()
        self.addCleanup(p.stop)

    def states(self):
        seen = []
        while True:
            try:
                ev, snap = self.events.get_nowait()
            except queue.Empty:
                return seen
            if ev == "voice":
                seen.append(snap["state"])

    def wait_state(self, *wanted, seconds=5):
        end = time.time() + seconds
        while time.time() < end:
            if self.voice.state()["state"] in wanted:
                return self.voice.state()
            time.sleep(0.02)
        self.fail(f"never reached {wanted}; at {self.voice.state()}")

    def say(self, reply="It is half past four.", tokens=("It is ", "half past four.")):
        def send(text):
            for t in tokens:
                self.convo.on_token("text", t)
            return reply
        return mock.patch.object(self.convo, "send", side_effect=send)


class TestVoiceExchange(VoiceBase):
    def test_full_exchange_streams_and_speaks(self):
        with self.say("It is **half past** four.") as send:
            started = self.voice.start()
            self.assertTrue(started["started"])
            done = self.wait_state("done")
        send.assert_called_once_with("what is the time")
        self.assertEqual((done["transcript"], done["reply"]), ("what is the time", "It is **half past** four."))
        seen = self.states()
        self.assertEqual([x for i, x in enumerate(seen) if i == 0 or x != seen[i - 1]],
                         ["listening", "transcribing", "thinking", "speaking", "done"])

        self.assertEqual(self.procs[0].argv, ["termux-tts-speak", "It is half past four."])
        self.assertEqual(self.listener.holds, [True, False])  # wake word held off during, released after
        self.assertEqual(self.listener.args[1], 8.0)
        self.assertFalse(self.httpd.chat_lock.locked())
        self.assertIsNone(self.convo.on_token)

    def test_reset_token_replaces_streamed_text(self):
        def send(text):
            self.convo.on_token("text", "Let me think. ")
            self.convo.on_token("reset", "")
            self.convo.on_token("text", "Four thirty.")
            return "Four thirty."
        with mock.patch.object(self.convo, "send", side_effect=send):
            self.voice.start()
            self.assertEqual(self.wait_state("done")["reply"], "Four thirty.")

    def test_start_while_running_does_not_restart(self):
        gate = threading.Event()
        with mock.patch.object(self.convo, "send", side_effect=lambda t: (gate.wait(5), "ok")[1]):
            self.assertTrue(self.voice.start()["started"])
            self.wait_state("thinking")
            again = self.voice.start()
            self.assertFalse(again["started"])
            self.assertEqual(again["state"], "thinking")
            gate.set()
            self.wait_state("done")

    def test_nobody_spoke(self):
        self.listener.pcm = None
        self.voice.start()
        s = self.wait_state("idle")
        self.assertEqual(s["note"], "I did not hear anything")
        self.assertEqual(self.listener.holds, [True, False])

    def test_unintelligible_audio(self):
        with mock.patch("stt.transcribe_best", return_value="   "):
            self.voice.start()
            self.assertEqual(self.wait_state("idle")["note"], "I did not catch that")

    def test_transcription_failure_is_an_error_not_a_crash(self):
        import stt
        with mock.patch("stt.transcribe_best", side_effect=stt.TranscribeError("no engine")):
            self.voice.start()
            s = self.wait_state("error")
        self.assertIn("no engine", s["error"])
        self.assertEqual(self.listener.holds, [True, False])

    def test_busy_chat_is_reported(self):
        self.httpd.chat_lock.acquire()
        try:
            self.voice.start()
            self.assertIn("still answering", self.wait_state("error")["error"])
        finally:
            self.httpd.chat_lock.release()

    def test_model_failure_releases_everything(self):
        with mock.patch.object(self.convo, "send", side_effect=RuntimeError("boom")):
            self.voice.start()
            self.wait_state("error")
        self.assertFalse(self.httpd.chat_lock.locked())
        self.assertIsNone(self.convo.on_token)
        self.assertEqual(self.listener.holds, [True, False])
        with self.say():  # and it can go again
            self.voice.start()
            self.wait_state("done")

    def test_speaking_can_be_switched_off(self):
        self.convo.config["voice"]["speak"] = False
        with self.say():
            self.voice.start()
            self.wait_state("done")
        self.assertEqual(self.procs, [])

    def test_missing_tts_binary_still_finishes(self):
        def broken(argv, **kw):
            raise FileNotFoundError(2, "No such file")
        self.voice._popen = broken
        with self.say():
            self.voice.start()
            self.wait_state("done")

    def test_without_the_wake_stream_it_records_directly(self):
        self.httpd.wake = None
        with mock.patch("device.record_pcm", return_value=b"\x05\x06" * 800) as rec, self.say():
            self.voice.start()
            self.wait_state("done")
        rec.assert_called_once_with(voice_session.FALLBACK_SECONDS)

    def test_a_silent_direct_recording_is_nothing_heard(self):
        self.httpd.wake = None
        with mock.patch("device.record_pcm", return_value=b"\x00" * 1600):
            self.voice.start()
            self.assertEqual(self.wait_state("idle")["note"], "I did not hear anything")

    def test_no_microphone_at_all_is_an_error(self):
        import device
        self.httpd.wake = None
        with mock.patch("device.record_pcm", side_effect=device.DeviceError("no recorder")):
            self.voice.start()
            self.assertIn("no microphone", self.wait_state("error")["error"])

    def test_speakable_strips_markdown(self):
        self.assertEqual(speakable("**Bold** `code` [a link](http://x.y) ```\nprint(1)\n``` # Head\nSee https://example.com now"),
                         "Bold code a link Head See link now")
        self.assertEqual(len(speakable("word " * 1000)), voice_session.SPEAK_CHARS)


class TestVoiceStop(VoiceBase):
    hang_tts = True

    def test_stop_while_speaking_ends_the_speech(self):
        with self.say():
            self.voice.start()
            self.wait_state("speaking")
            end = time.time() + 5
            while time.time() < end and not self.procs:  # "speaking" is set as the first sentence is queued
                time.sleep(0.02)
            s = self.voice.stop()
        self.assertEqual((s["state"], s["note"], s["active"]), ("idle", "Stopped", False))
        end = time.time() + 3
        while time.time() < end and not self.procs[0].terminated:
            time.sleep(0.02)
        self.assertTrue(self.procs[0].terminated)
        end = time.time() + 3
        while time.time() < end and self.listener.holds != [True, False]:
            time.sleep(0.02)
        self.assertEqual(self.listener.holds, [True, False])
        self.assertEqual(self.voice.state()["state"], "idle")  # the old exchange cannot overwrite it afterwards

    def test_stop_while_thinking_cancels_the_turn(self):
        gate = threading.Event()
        with mock.patch.object(self.convo, "send", side_effect=lambda t: (gate.wait(5), "late reply")[1]), \
                mock.patch.object(self.convo, "cancel") as cancel:
            self.voice.start()
            self.wait_state("thinking")
            self.voice.stop()
            cancel.assert_called_once()
            gate.set()
            time.sleep(0.3)
        self.assertEqual(self.voice.state()["state"], "idle")
        self.assertEqual(self.procs, [])  # nothing is spoken for a stopped exchange

    def test_stop_when_idle_changes_nothing(self):
        self.assertEqual(self.voice.stop()["state"], "idle")
        self.assertEqual(self.states(), [])

    def test_a_new_exchange_after_stop_works(self):
        self.listener.pcm = None
        self.voice.start()
        self.voice.stop()
        self.listener.pcm = b"\x01\x02" * 480
        self.hang_tts = False
        with self.say():
            self.assertTrue(self.voice.start()["started"])
            self.wait_state("done")


class TestVoiceLaunches(VoiceBase):
    def test_overlay_start_asks_tasker_for_the_scene(self):
        self.listener.pcm = None
        self.voice.start(overlay=True)
        self.assertEqual(self.launched, [["am", "broadcast", "-a", "net.dinglisch.android.tasker.ACTION_TASK", "--es", "task_name", "Faze Overlay"]])

    def test_plain_start_does_not_touch_tasker(self):
        self.listener.pcm = None
        self.voice.start()
        self.wait_state("idle")
        self.assertEqual(self.launched, [])

    def test_task_names_are_settings_and_empty_means_off(self):
        self.convo.config["voice"]["overlay_task"] = "My Scene"
        self.voice.tasker("My Scene")
        self.assertEqual(self.launched[-1][-1], "My Scene")
        self.launched.clear()
        self.convo.config["voice"]["overlay_task"] = ""
        self.listener.pcm = None
        self.voice.start(overlay=True)
        self.assertEqual(self.launched, [])
        for key in ("voice.overlay_task", "voice.overlay_close_task", "voice.speak", "voice.listen_timeout"):
            self.assertIn(key, SETTINGS)

    def test_stop_with_close_asks_tasker_to_close_it(self):
        self.voice.stop(close_overlay=True)
        self.assertEqual(self.launched[-1][-1], "Faze Overlay Close")

    def test_open_app_uses_the_token_link_and_falls_back(self):
        self.assertTrue(self.voice.open_app(close_overlay=True))
        link = f"http://localhost:{self.httpd.port}/#t={TOKEN}"
        self.assertEqual(self.launched[0], ["am", "start", "-a", "android.intent.action.VIEW", "-d", link])
        self.assertEqual(self.launched[1][-1], "Faze Overlay Close")
        calls = []

        def flaky(argv):
            calls.append(argv)
            if argv[0] == "am":
                raise OSError("am failed")
        self.voice._run_cmd = flaky
        self.assertTrue(self.voice.open_app())
        self.assertEqual([c[0] for c in calls], ["am", "termux-open-url"])
        self.voice._run_cmd = lambda argv: (_ for _ in ()).throw(OSError("nothing works"))
        self.assertFalse(self.voice.open_app())


class TestVoiceEndpoints(VoiceBase):
    def test_all_need_the_token(self):
        for method, path in (("GET", "/api/voice/state"), ("POST", "/api/voice/start"), ("POST", "/api/voice/stop"), ("POST", "/api/open")):
            resp, _ = self.request(method, path, body={} if method == "POST" else None, token=None)
            self.assertEqual(resp.status, 401, path)

    def test_start_state_stop(self):
        self.listener.pcm = None
        self.assertEqual(self.jget("/api/voice/state")[1]["state"], "idle")
        code, d = self.jpost("/api/voice/start", {"overlay": True})
        self.assertEqual((code, d["ok"], d["started"], d["state"]), (200, True, True, "listening"))
        self.assertEqual(self.launched[0][-1], "Faze Overlay")
        self.wait_state("idle")
        code, d = self.jpost("/api/voice/stop", {})
        self.assertEqual((code, d["ok"], d["state"]), (200, True, "idle"))

    def test_open_endpoint(self):
        code, d = self.jpost("/api/open", {"close_overlay": True})
        self.assertEqual((code, d), (200, {"ok": True}))
        self.assertEqual(self.launched[0][:3], ["am", "start", "-a"])

    def test_voice_events_reach_event_subscribers(self):
        with self.say():
            self.jpost("/api/voice/start", {})
            self.wait_state("done")
        self.assertIn("done", self.states())

    def test_overlay_assets_are_served_with_the_csp(self):
        for path, ctype in (("/overlay", "text/html"), ("/overlay.js", "text/javascript"), ("/overlay.css", "text/css")):
            resp, body = self.request("GET", path, token=None)
            self.assertEqual(resp.status, 200, path)
            self.assertTrue(resp.getheader("Content-Type").startswith(ctype), path)
            self.assertTrue(body, path)
        resp, body = self.request("GET", "/overlay", token=None)
        self.assertIn("script-src 'self'", resp.getheader("Content-Security-Policy"))
        self.assertNotIn(b"<script>", body)  # CSP: no inline script


@unittest.skipUnless(HAVE_JSDOM, "jsdom not installed")
class TestOverlayPage(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.res = run_node("overlay_smoke.js")

    def test_no_crash_and_no_page_errors(self):
        self.assertNotIn("crash", self.res, self.res.get("crash"))
        self.assertEqual(self.res["errors"], [])

    def test_loads_authenticated_and_autostarts_once(self):
        r = self.res
        self.assertTrue(r["auth"])
        self.assertEqual((r["autoStarted"], r["loaded"]["state"], r["token"]), (1, "listening", "tok"))
        self.assertEqual(r["hashAfter"], "#auto")  # the token is not left in the address

    def test_every_state_renders(self):
        r = self.res
        self.assertEqual((r["transcribing"]["label"], r["thinking"]["you"], r["streaming"]["reply"]), ("Got it...", "what is the time", "It is"))
        self.assertEqual((r["speaking"]["label"], r["speaking"]["reply"]), ("Speaking", "It is half past four."))
        self.assertEqual((r["done"]["again"], r["done"]["stop"]), (True, False))
        self.assertEqual((r["speaking"]["again"], r["speaking"]["stop"]), (False, True))
        self.assertEqual((r["error"]["state"], r["error"]["note"]), ("error", "no microphone"))

    def test_buttons_call_the_api(self):
        r = self.res
        self.assertEqual((r["again"]["starts"], r["again"]["view"]["state"]), (2, "listening"))
        self.assertEqual((r["stop"]["call"], r["stop"]["view"]["note"]), ([{}], "Stopped"))
        self.assertEqual(r["open"], [{"close_overlay": True}])
        self.assertEqual(r["close"][-1], {"close": True})

    def test_running_exchange_is_shown_not_restarted_and_missing_token_is_explained(self):
        r = self.res
        self.assertEqual((r["running"]["view"], r["running"]["newStarts"]), ({"state": "thinking", "you": "hi"}, 0))
        self.assertEqual(r["noToken"]["state"], "error")
        self.assertIn("no token", r["noToken"]["note"])


# ---------------------------------------------------------------- faze start --voice / --app / overlay

class TestServiceVoice(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp(prefix="faze-svc16-")
        self.script = os.path.join(ROOT, "scripts", "faze_service.sh")
        self.fake = os.path.join(self.dir, "fake_faze.sh")
        self.opened = os.path.join(self.dir, "opened.txt")
        self.posted = os.path.join(self.dir, "posted.txt")
        self.reply = os.path.join(self.dir, "reply.txt")
        self.write("opener.sh", f'echo "$1" >> "{self.opened}"')
        self.write("http.sh", f'echo "$1 $2 $3" >> "{self.posted}"; cat "{self.reply}"')
        with open(self.reply, "w") as f:
            f.write('{"ok": true, "started": true, "state": "listening"}')
        self.write("fake_faze.sh", 'echo "app started" >> "$FAZE_LOG"; sleep 60')
        sock = socket.socket()
        sock.bind(("127.0.0.1", 0))
        self.port = sock.getsockname()[1]
        sock.close()
        self.state = os.path.join(self.dir, "state")
        os.makedirs(self.state)
        with open(os.path.join(self.state, "token"), "w") as f:
            f.write("tok123")
        self.env = dict(os.environ, FAZE_STATE=self.state, FAZE_LOG=os.path.join(self.dir, "faze.log"), FAZE_CMD=self.fake,
                        FAZE_RESTART_DELAY="0.2", FAZE_PORT=str(self.port), FAZE_OPEN=os.path.join(self.dir, "opener.sh"),
                        FAZE_HTTP=os.path.join(self.dir, "http.sh"), FAZE_READY_TIMEOUT="5")
        httpd = HTTPServer(("127.0.0.1", self.port), Quiet)
        threading.Thread(target=httpd.serve_forever, daemon=True).start()
        self.addCleanup(httpd.shutdown)
        self.addCleanup(httpd.server_close)
        self.addCleanup(lambda: self.run_svc("stop"))

    def write(self, name, body):
        path = os.path.join(self.dir, name)
        with open(path, "w") as f:
            f.write("#!/bin/bash\n" + body + "\n")
        os.chmod(path, 0o755)

    def run_svc(self, *args):
        return subprocess.run(["bash", self.script, *args], capture_output=True, text=True, env=self.env, timeout=30)

    def test_voice_listens_through_the_service_and_does_not_open_the_app(self):
        out = self.run_svc("start", "--voice")
        self.assertEqual(out.returncode, 0, out.stderr)
        self.assertIn("listening", out.stdout)
        self.assertFalse(os.path.exists(self.opened), "--voice must not open the app")
        with open(self.posted) as f:
            self.assertEqual(f.read().strip(), '/api/voice/start {"overlay": true} tok123')

    def test_voice_alias_and_already_listening(self):
        with open(self.reply, "w") as f:
            f.write('{"ok": true, "started": false, "state": "thinking"}')
        out = self.run_svc("voice")
        self.assertEqual(out.returncode, 0, out.stderr)
        self.assertIn("already listening", out.stdout)
        self.assertFalse(os.path.exists(self.opened))

    def test_voice_without_a_token_or_service_fails_clearly(self):
        os.remove(os.path.join(self.state, "token"))
        out = self.run_svc("start", "--voice")
        self.assertEqual(out.returncode, 1)
        self.assertIn("could not start listening", out.stdout)
        self.assertFalse(os.path.exists(self.opened))

    def test_app_keeps_the_old_open_the_app_behaviour(self):
        out = self.run_svc("start", "--app")
        self.assertEqual(out.returncode, 0, out.stderr)
        self.assertIn("opened in voice mode", out.stdout)
        with open(self.opened) as f:
            self.assertEqual(f.read().strip(), f"http://localhost:{self.port}/#t=tok123&voice")
        self.assertFalse(os.path.exists(self.posted))
        self.assertEqual(self.run_svc("app").returncode, 0)

    def test_overlay_prints_the_scene_link(self):
        out = self.run_svc("overlay")
        self.assertEqual(out.stdout.strip(), f"http://localhost:{self.port}/overlay#t=tok123&auto")
        os.remove(os.path.join(self.state, "token"))
        self.assertEqual(self.run_svc("overlay").returncode, 1)

    def test_modes_are_exclusive(self):
        for args in (("start", "--voice", "--app"), ("start", "--app", "--cli"), ("start", "--voice", "--cli")):
            out = self.run_svc(*args)
            self.assertEqual(out.returncode, 2, args)
            self.assertIn("--voice|--app|--cli", out.stdout)

    def test_the_real_http_path_posts_json_with_the_token(self):
        seen = {}

        class Capture(Quiet):
            def do_POST(self):
                seen["auth"] = self.headers.get("Authorization")
                seen["type"] = self.headers.get("Content-Type")
                seen["path"] = self.path
                seen["body"] = self.rfile.read(int(self.headers.get("Content-Length", 0))).decode()
                data = b'{"ok": true, "started": true}'
                self.send_response(200)
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

        httpd = HTTPServer(("127.0.0.1", 0), Capture)
        threading.Thread(target=httpd.serve_forever, daemon=True).start()
        self.addCleanup(httpd.shutdown)
        self.addCleanup(httpd.server_close)
        env = dict(self.env, FAZE_PORT=str(httpd.server_address[1]))
        env.pop("FAZE_HTTP")
        out = subprocess.run(["bash", self.script, "start", "--voice"], capture_output=True, text=True, env=env, timeout=30)
        subprocess.run(["bash", self.script, "stop"], env=env, capture_output=True)
        self.assertEqual(out.returncode, 0, out.stderr)
        self.assertIn("listening", out.stdout)
        self.assertEqual((seen["auth"], seen["type"], seen["path"], seen["body"]),
                         ("Bearer tok123", "application/json", "/api/voice/start", '{"overlay": true}'))


if __name__ == "__main__":
    unittest.main()
