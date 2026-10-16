"""
Patch 23: type to Faze (a text box in the overlay, for classic and live), and a step-by-step Live connection check
(`faze livecheck`, and a row in /doctor) that shows where the connection breaks inside the process that matters.

    PYTHONPATH=. python3 -m unittest tests.test_patch23 -v
"""

import os
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest import mock

CORE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, CORE)

import tests.test_patch21 as p21  # noqa: E402
from tests.test_patch16 import VoiceBase, HAVE_JSDOM, run_node  # noqa: E402
from tests.test_patch17 import TestOverlayButtonCommands  # noqa: E402,F401

import doctor  # noqa: E402
import live_client  # noqa: E402
import stt  # noqa: E402
from config_loader import load_config  # noqa: E402


# ---------------------------------------------------------------- the connection check

class Peer:
    def __init__(self, ip="142.250.1.2"):
        self.ip, self.closed = ip, False

    def getpeername(self):
        return (self.ip, 443)

    def close(self):
        self.closed = True


class TestLiveCheck(unittest.TestCase):
    def run_check(self, key="AQ.test", resolve=None, sock=None, connect=None, which=lambda n: "/bin/" + n, config=None):
        resolve = resolve or (lambda host, port, family, kind: [(2, 1, 6, "", ("142.250.1.2", 443))])
        sock_patch = mock.patch.object(live_client, "_open_socket", sock or (lambda host, port, timeout: Peer()))
        player = mock.patch.object(live_client.PcmPlayer, "command_line", lambda self: ["pacat"] if which("pacat") else None)
        with sock_patch, player:
            return live_client.check(config or load_config(), key=key, connect=connect, resolve=resolve)

    def names(self, steps):
        return [s[0] for s in steps]

    def test_everything_fine_without_a_real_server(self):
        steps = self.run_check(connect=lambda url, **kw: (_ for _ in ()).throw(AssertionError("replaced below")))
        self.assertEqual(self.names(steps), ["websockets", "key", "dns", "dns ipv4", "tcp", "websocket", "audio player"])

    @unittest.skipUnless(p21.HAVE_WS, "websockets not installed")
    def test_a_working_setup(self):
        fake = p21.FakeLive(scripted=False)
        self.addCleanup(fake.close)
        steps = self.run_check(connect=fake.connect)
        by = {name: (ok, detail) for name, ok, detail in steps}
        self.assertTrue(all(ok for ok, _ in by.values()), by)
        self.assertIn("142.250.1.2", by["dns"][1])
        self.assertIn("setup accepted for gemini-3.8-live (shape generation)", by["websocket"][1])
        self.assertEqual(by["audio player"][1].split()[0], "pacat")
        self.assertEqual(len(fake.setups), 1)
        self.assertEqual(fake.setups[0]["setup"]["model"], "models/gemini-3.8-live")

    def test_the_phone_failure_is_shown_as_it_is(self):
        def broken(host, port, family, kind):
            raise socket.gaierror(7, "No address associated with hostname")

        def no_tcp(host, port, timeout):
            raise socket.gaierror(7, "No address associated with hostname")
        steps = self.run_check(resolve=broken, sock=no_tcp)
        by = {name: (ok, detail) for name, ok, detail in steps}
        self.assertFalse(by["dns"][0])
        self.assertIn("Errno 7", by["dns"][1])
        self.assertFalse(by["dns ipv4"][0])
        self.assertFalse(by["tcp"][0])
        self.assertIn("skipped", by["websocket"][1])

    def test_a_resolver_that_only_fails_without_a_family_points_at_ipv4(self):
        def resolve(host, port, family, kind):
            if family == 0:
                raise socket.gaierror(7, "No address associated with hostname")
            return [(2, 1, 6, "", ("142.250.1.2", 443))]
        by = {n: ok for n, ok, _ in self.run_check(resolve=resolve)}
        self.assertFalse(by["dns"])
        self.assertTrue(by["dns ipv4"])
        self.assertTrue(by["tcp"])

    def test_no_key_and_no_package(self):
        by = {n: (ok, d) for n, ok, d in self.run_check(key="")}
        self.assertFalse(by["key"][0])
        self.assertIn("~/.felix/env", by["key"][1])
        self.assertIn("skipped", by["websocket"][1])
        with mock.patch.object(live_client, "_ws_connect", None):
            by = {n: (ok, d) for n, ok, d in self.run_check()}
        self.assertFalse(by["websockets"][0])
        self.assertIn("pip install websockets", by["websockets"][1])

    def test_a_refused_setup_names_the_server_reason_and_never_the_key(self):
        def refuse(url, **kw):
            raise OSError("handshake failed for wss://x?key=SECRETKEY")
        steps = self.run_check(key="SECRETKEY", connect=refuse)
        by = {n: (ok, d) for n, ok, d in steps}
        self.assertFalse(by["websocket"][0])
        self.assertIn("could not connect", by["websocket"][1])
        self.assertNotIn("SECRETKEY", repr(steps))

    def test_no_audio_player(self):
        by = {n: (ok, d) for n, ok, d in self.run_check(which=lambda n: None)}
        self.assertFalse(by["audio player"][0])
        self.assertIn("pulseaudio", by["audio player"][1])

    def test_format(self):
        text = live_client.format_check([("dns", True, "any 1.2.3.4 (0.0s)"), ("tcp", False, "gaierror: x")])
        self.assertEqual(text.splitlines(), ["[ok  ] dns: any 1.2.3.4 (0.0s)", "[FAIL] tcp: gaierror: x"])

    def test_command_line_usage(self):
        out = subprocess.run([sys.executable, os.path.join(CORE, "live_client.py")], capture_output=True, text=True, cwd=CORE,
                             env=dict(os.environ, PYTHONPATH=CORE))
        self.assertEqual(out.returncode, 2)
        self.assertIn("usage: python3 live_client.py check", out.stdout)


class TestDoctorRow(unittest.TestCase):
    def doctor(self, key="k", steps=None):
        steps = steps or [("websockets", True, "x"), ("websocket", True, "setup accepted for gemini-3.8-live (shape generation) (0.9s)"),
                          ("audio player", True, "pacat (0.0s)")]
        import actions
        with mock.patch.dict(os.environ, {"GEMINI_API_KEY": key}), \
                mock.patch("gemini_client.ask_gemini", return_value={"type": "text", "content": "OK"}), \
                mock.patch("web_search.ddg_search", return_value=[]), \
                mock.patch.object(actions, "youtube_find", return_value=("aaaaaaaaaaa", "v")), \
                mock.patch("tts.synthesize_audio", return_value=(b"x", "audio/wav")), \
                mock.patch.object(stt, "available", return_value=True), \
                mock.patch("live_client.check", return_value=steps):
            return {n: (s, d) for n, s, d in doctor.run_checks(load_config())}

    def test_ok_row_shows_the_handshake_and_player(self):
        status, detail = self.doctor()["Gemini Live"]
        self.assertEqual(status, "ok")
        self.assertIn("setup accepted", detail)
        self.assertIn("audio player: pacat", detail)

    def test_failure_shows_the_first_broken_step(self):
        status, detail = self.doctor(steps=[("dns", False, "gaierror: [Errno 7] No address associated with hostname"), ("tcp", False, "later")])["Gemini Live"]
        self.assertEqual(status, "fail")
        self.assertIn("dns: gaierror: [Errno 7]", detail)
        self.assertNotIn("later", detail)

    def test_no_key_skips_it(self):
        self.assertEqual(self.doctor(key="")["Gemini Live"][0], "skip")


class TestLivecheckScript(TestOverlayButtonCommands):
    def test_livecheck_runs_the_check_and_returns_its_exit_code(self):
        core = os.path.join(self.dir, "core")
        os.makedirs(core)
        with open(os.path.join(core, "live_client.py"), "w") as f:
            f.write("import sys\nprint('[FAIL] dns: Errno 7')\nsys.exit(1)\n" if sys.argv else "")
        self.env["FAZE_CORE"] = core
        out = self.run_svc("livecheck")
        self.assertEqual((out.returncode, out.stdout.strip()), (1, "[FAIL] dns: Errno 7"))
        with open(os.path.join(core, "live_client.py"), "w") as f:
            f.write("print('[ok  ] dns: fine')\n")
        out = self.run_svc("livecheck")
        self.assertEqual((out.returncode, out.stdout.strip()), (0, "[ok  ] dns: fine"))

    def test_usage_lists_it(self):
        self.assertIn("livecheck", self.run_svc("bogus").stdout)


# ---------------------------------------------------------------- typing to Faze

class BlockingListener:
    """A wake listener whose capture blocks until released, to catch an exchange while it is still listening."""

    def __init__(self):
        self.release, self.holds, self.captures = threading.Event(), [], 0

    def hold(self, on):
        self.holds.append(on)

    def _live(self):
        return True

    def capture_utterance(self, **kw):
        self.captures += 1
        self.release.wait(10)
        return b"\x01\x02" * 480


class TestTypedClassic(VoiceBase):
    def send_patch(self, reply="Half past four, if you must know."):
        return mock.patch.object(self.convo, "send", return_value=reply)

    def test_typed_text_runs_a_turn_without_the_microphone_or_stt(self):
        with mock.patch("stt.transcribe_best", side_effect=AssertionError("typed text needs no transcription")) as best, \
                self.send_patch() as send:
            started = self.voice.say("what is the time")
            self.assertTrue(started["started"])
            done = self.wait_state("done")
        send.assert_called_once_with("what is the time")
        self.assertEqual((done["transcript"], done["engine"]), ("what is the time", "classic"))
        self.assertEqual(done["reply"], "Half past four, if you must know.")
        best.assert_not_called()
        self.assertEqual(self.listener.holds, [])
        self.assertIsNone(self.listener.args)  # capture_utterance was never asked
        self.assertEqual(self.procs[0].argv[0], "termux-tts-speak")
        self.assertIn("stt_ms", done["timings"])

    def test_typed_text_is_trimmed_and_capped(self):
        with self.send_patch() as send:
            self.voice.say("  hello  " + "x" * 5000)
            self.wait_state("done")
        sent = send.call_args[0][0]
        self.assertTrue(sent.startswith("hello  xxx"))
        self.assertEqual(len(sent), 2000)

    def test_nothing_typed_starts_nothing(self):
        for blank in ("", "   ", None):
            r = self.voice.say(blank)
            self.assertEqual((r["started"], r["queued"], r["problem"]), (False, False, "nothing to say"))
        self.assertEqual(self.voice.state()["state"], "idle")
        self.assertEqual(self.states(), [])

    def test_typing_replaces_an_exchange_that_is_still_listening(self):
        blocker = BlockingListener()
        self.httpd.wake = blocker
        self.voice.start()
        self.wait_state("listening")
        time.sleep(0.1)
        with self.send_patch() as send:
            r = self.voice.say("typed instead")
            self.assertTrue(r["started"])
            done = self.wait_state("done")
            blocker.release.set()  # the old exchange wakes up late and must change nothing
            time.sleep(0.4)
        send.assert_called_once_with("typed instead")
        self.assertEqual(self.voice.state()["transcript"], "typed instead")
        self.assertEqual(self.voice.state()["state"], "done")
        self.assertEqual(done["transcript"], "typed instead")

    def test_typing_while_the_reply_is_being_spoken_starts_over(self):
        self.hang_tts = True
        with self.send_patch("An answer that takes a moment to say out loud."):
            self.voice.say("first question")
            self.wait_state("speaking")
            end = time.time() + 5
            while time.time() < end and not self.procs:  # "speaking" is set as the sentence is queued
                time.sleep(0.02)
        with self.send_patch("Second answer, short and sweet.") as second:
            self.voice.say("second question")
            self.wait_state("speaking")
            time.sleep(0.2)
        second.assert_called_once_with("second question")
        self.assertTrue(self.procs[0].terminated)  # the first was cut off
        self.voice.stop()

    def test_a_stale_error_from_an_earlier_exchange_does_not_block_typing(self):
        self.voice._snap["error"] = "an old failure"
        with self.send_patch():
            code, d = self.jpost("/api/voice/say", {"text": "still works"})
            self.assertEqual((code, d["ok"]), (200, True))
            self.wait_state("done")

    def test_the_endpoint(self):
        self.listener.pcm = None
        resp, _ = self.request("POST", "/api/voice/say", body={"text": "hi"}, token=None)
        self.assertEqual(resp.status, 401)
        code, d = self.jpost("/api/voice/say", {"text": "   "})
        self.assertEqual((code, d["ok"], d["problem"]), (400, False, "nothing to say"))
        with self.send_patch():
            code, d = self.jpost("/api/voice/say", {"text": "what is the time"})
            self.assertEqual((code, d["ok"], d["started"], d["engine"]), (200, True, True, "classic"))
            self.wait_state("done")
        self.assertEqual(self.jget("/api/voice/state")[1]["transcript"], "what is the time")


@unittest.skipUnless(p21.HAVE_WS, "websockets not installed")
class TestTypedLive(p21.LiveBase):
    def wait_for(self, cond, what, seconds=8):
        end = time.time() + seconds
        while time.time() < end:
            if cond():
                return
            time.sleep(0.02)
        self.fail(f"timed out waiting for {what}")

    def test_typed_text_goes_into_a_running_conversation(self):
        fake = self.serve(scripted=False)
        self.convo.config["live"]["idle_seconds"] = 30
        self.voice.start(live=True)
        self.wait_state("listening")
        r = self.voice.say("what is two plus two")
        self.assertEqual((r["started"], r["queued"]), (False, True))
        self.wait_for(lambda: fake.texts == ["what is two plus two"], "the text to reach the server")
        s = self.voice.state()
        self.assertEqual((s["transcript"], s["reply"], s["state"]), ("what is two plus two", "", "thinking"))
        self.assertEqual(s["engine"], "live")
        self.voice.stop()

    def test_typing_with_no_conversation_starts_one_with_that_text_first(self):
        fake = self.serve(scripted=False)
        self.convo.config["live"]["idle_seconds"] = 30
        r = self.voice.say("hello there", live=True)
        self.assertEqual((r["started"], r["engine"]), (True, "live"))
        self.wait_for(lambda: fake.texts == ["hello there"], "the first text")
        self.voice.stop()

    def test_typing_interrupts_speech(self):
        fake = self.serve(hold_speech=1.5)
        self.voice.start(live=True)
        self.wait_state("speaking", seconds=15)
        self.voice.say("stop talking and listen")
        self.wait_for(lambda: fake.texts == ["stop talking and listen"], "the text")
        self.assertGreaterEqual(p21.FakePlayer.instances[0].stopped, 1)
        self.voice.stop()

    def test_when_live_is_unavailable_the_typed_text_is_answered_classically(self):
        self.serve()
        os.environ.pop("GEMINI_API_KEY")
        with mock.patch.object(self.convo, "send", return_value="Answered the old way, but answered.") as send:
            self.voice.say("what is the time", live=True)
            done = self.wait_state("done", seconds=10)
        send.assert_called_once_with("what is the time")
        self.assertEqual(done["engine"], "classic")
        self.assertIn("Live is unavailable", done["note"])


@unittest.skipUnless(HAVE_JSDOM, "jsdom not installed")
class TestOverlayTyping(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.res = run_node("overlay_smoke.js")

    def test_blank_is_not_sent_and_text_is(self):
        r = self.res
        self.assertNotIn("crash", r, r.get("crash"))
        self.assertEqual(r["errors"], [])
        self.assertEqual(r["typedBlank"], 0)
        c = r["typedClassic"]
        self.assertEqual(c["bodies"], [{"text": "what is the time", "live": False}])
        self.assertEqual((c["inputAfter"], c["you"], c["sendDisabled"]), ("", "what is the time", False))

    def test_a_failed_send_keeps_the_text_and_shows_the_error(self):
        f = self.res["typedFail"]
        self.assertEqual((f["state"], f["note"], f["keptText"], f["sendDisabled"]), ("error", "request failed (400)", "FAIL", False))

    def test_live_conversations_send_the_live_flag(self):
        self.assertEqual(self.res["typedLive"], {"text": "hello live", "live": True})


if __name__ == "__main__":
    unittest.main()
