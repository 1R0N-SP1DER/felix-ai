"""
Wake word, push events, lazy Qwen start, Gemini offline fallback, model settings.

    PYTHONPATH=. python3 -m unittest tests.test_patch7 -v
"""

import array
import json
import math
import os
import sys
import threading
import time
import unittest
from unittest import mock

CORE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, CORE)

from tests.test_patch1 import TMP, free_port  # noqa: E402
from tests.test_patch4 import BootBase, make_server, wait_for  # noqa: E402
from tests.test_patch5 import WebBase  # noqa: E402
from tests.test_patch6 import fake_whisper, model_file  # noqa: E402

import config_loader  # noqa: E402
import gemini_client  # noqa: E402
import wake  # noqa: E402
from config_loader import load_config  # noqa: E402
from gemini_client import GeminiError  # noqa: E402


def pcm(seconds, amp=0.0, hz=440):
    n = int(wake.RATE * seconds)
    a = array.array("h", (int(amp * 32767 * math.sin(2 * math.pi * hz * i / wake.RATE)) for i in range(n)))
    return a.tobytes()


def frames(data):
    return [data[i:i + wake.FRAME_BYTES] for i in range(0, len(data) - wake.FRAME_BYTES + 1, wake.FRAME_BYTES)]


def fake_mic(name, chunks_py):
    """A script that writes audio to stdout like parec, then idles."""
    path = os.path.join(TMP, name)
    with open(path, "w") as f:
        f.write("import sys, time, array, math\nR = 16000\n"
                "def pcm(sec, amp=0.0):\n"
                "    n = int(R * sec)\n"
                "    return array.array('h', (int(amp * 32767 * math.sin(2 * math.pi * 440 * i / R)) for i in range(n))).tobytes()\n"
                f"for chunk in [{chunks_py}]:\n    sys.stdout.buffer.write(chunk); sys.stdout.buffer.flush()\n"
                "time.sleep(60)\n")
    return f"{sys.executable} {path}"


SPEECH_THEN_QUIET = "pcm(1.0), pcm(0.8, 0.3), pcm(1.5)"


class TestPhraseMatching(unittest.TestCase):
    def test_hears_the_name_through_mishearings(self):
        for heard in ("Hey, Faze.", "hey phase", "Hey fays what's up", "faze", "hay face", "HEY FAZE!"):
            self.assertTrue(wake.matches(heard, "hey faze"), heard)

    def test_ignores_other_speech(self):
        for heard in ("okay so the phase of the moon is full", "Thank you.", "", "turn the lights on", "hey google"):
            self.assertFalse(wake.matches(heard, "hey faze"), heard)

    def test_custom_phrases_and_names(self):
        self.assertTrue(wake.matches("yo jarvas", "yo jarvis"))
        self.assertFalse(wake.matches("yo service", "yo jarvis"))
        cfg = load_config()
        self.assertEqual(wake.phrase_for(cfg), "hey Faze")
        cfg["assistant"]["name"] = "Nova"
        self.assertEqual(wake.phrase_for(cfg), "hey Nova")
        cfg["wake"]["phrase"] = "computer"
        self.assertEqual(wake.phrase_for(cfg), "computer")

    def test_phrase_validation(self):
        self.assertEqual(config_loader.set_user_setting.__name__, "set_user_setting")
        os.environ["FAZE_SETTINGS"] = os.path.join(TMP, f"w-{time.time_ns()}.json")
        self.assertEqual(config_loader.set_user_setting("wake.phrase", "yo jarvis")[0], "yo jarvis")
        for bad in ("x", "a;b", "z" * 41):
            with self.assertRaises(ValueError):
                config_loader.set_user_setting("wake.phrase", bad)


class TestVad(unittest.TestCase):
    def run_vad(self, data):
        vad, out = wake.Vad(), []
        for fr in frames(data):
            u = vad.feed(fr)
            if u is not None:
                out.append(u)
        return out

    def test_one_utterance_with_context(self):
        out = self.run_vad(pcm(1.0) + pcm(0.8, 0.3) + pcm(1.5))
        self.assertEqual(len(out), 1)
        seconds = len(out[0]) / 2 / wake.RATE
        self.assertTrue(1.0 < seconds < 2.3, seconds)

    def test_clicks_and_silence_are_ignored(self):
        self.assertEqual(self.run_vad(pcm(3.0)), [])
        self.assertEqual(self.run_vad(pcm(1.0) + pcm(0.1, 0.4) + pcm(1.5)), [])

    def test_long_speech_is_cut(self):
        out = self.run_vad(pcm(0.5) + pcm(6.0, 0.3) + pcm(1.5))
        self.assertGreaterEqual(len(out), 1)
        self.assertLessEqual(max(len(u) for u in out) / 2 / wake.RATE, 4.1)

    def test_wav_wrapper_passes_the_servers_check(self):
        import stt
        stt.check_wav(wake.wav_from_pcm(pcm(0.5, 0.2)))


class TestListener(unittest.TestCase):
    def setUp(self):
        self.cfg = load_config()
        self.cfg["wake"].update({"mic_cmd": fake_mic("mic1.py", SPEECH_THEN_QUIET), "cooldown": 30})
        self.cfg["stt"].update({"bin": fake_whisper("w-ok", "echo hey faze"), "model_path": model_file("m.bin")})
        self.heard = []
        self.listeners = []

    def tearDown(self):
        for l in self.listeners:
            l.stop()

    def make(self, **kw):
        kw.setdefault("transcribe", lambda wav, cfg: "Hey Faze")
        kw.setdefault("retry_s", 0.2)
        l = wake.WakeListener(self.cfg, on_wake=lambda p, t: self.heard.append(p), **kw)
        self.listeners.append(l)
        return l

    def test_wake_fires_once(self):
        l = self.make()
        l.start()
        self.assertTrue(wait_for(lambda: self.heard))
        self.assertEqual(self.heard, ["hey Faze"])
        self.assertEqual(l.status()["state"], "listening")

    def test_cooldown_blocks_a_second_trigger(self):
        self.cfg["wake"]["mic_cmd"] = fake_mic("mic2.py", SPEECH_THEN_QUIET + ", " + SPEECH_THEN_QUIET)
        l = self.make()
        l.start()
        self.assertTrue(wait_for(lambda: self.heard))
        time.sleep(1.5)
        self.assertEqual(len(self.heard), 1)

    def test_other_speech_does_not_trigger(self):
        seen = []
        l = self.make(transcribe=lambda wav, cfg: seen.append(1) or "turn the lights on")
        l.start()
        self.assertTrue(wait_for(lambda: seen))
        time.sleep(0.3)
        self.assertEqual(self.heard, [])

    def test_busy_chat_skips_transcription(self):
        seen = []
        l = self.make(is_busy=lambda: True, transcribe=lambda wav, cfg: seen.append(1) or "hey faze")
        l.start()
        time.sleep(1.5)
        self.assertEqual((seen, self.heard), ([], []))

    def test_broken_mic_command_is_reported(self):
        self.cfg["wake"]["mic_cmd"] = "/nonexistent/parec --raw"
        l = self.make()
        l.start()
        self.assertTrue(wait_for(lambda: l.status()["state"] == "error"))
        self.assertIn("mic command failed", l.status()["detail"])

    def test_missing_whisper_is_reported(self):
        self.cfg["stt"]["bin"] = "/nonexistent/whisper-cli"
        l = self.make()
        l.start()
        self.assertTrue(wait_for(lambda: l.status()["state"] == "error"))
        self.assertIn("whisper-cli not found", l.status()["detail"])

    def test_silent_mic_is_diagnosed(self):
        self.cfg["wake"]["mic_cmd"] = fake_mic("mic-silent.py", "bytes(2 * 16000 * 10)")
        l = self.make()
        l.start()
        self.assertTrue(wait_for(lambda: l.status()["state"] == "silent", 20))
        self.assertIn("pure silence", l.status()["detail"])

    def test_stop_ends_the_mic_process(self):
        l = self.make()
        l.start()
        self.assertTrue(wait_for(lambda: l._proc is not None))
        proc = l._proc
        l.stop()
        self.assertIsNotNone(proc.poll())
        self.assertEqual(l.status()["state"], "off")

    def test_check_mic(self):
        self.assertEqual(wake.check_mic(self.cfg, 2.2), 0)
        self.cfg["wake"]["mic_cmd"] = fake_mic("mic-zero.py", "bytes(2 * 16000 * 2)")
        self.assertEqual(wake.check_mic(self.cfg, 1.0), 1)
        self.cfg["wake"]["mic_cmd"] = "/nonexistent/parec"
        self.assertEqual(wake.check_mic(self.cfg, 1.0), 1)


class TestWakeServer(WebBase):
    def open_events(self):
        import http.client
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=15)
        conn.request("GET", "/api/events", headers={"Authorization": "Bearer " + "t" * 43})
        resp = conn.getresponse()
        self.assertEqual(resp.status, 200)
        lines = []
        done = threading.Event()

        def read():
            try:
                for raw in resp:
                    lines.append(raw.decode().strip())
            except Exception:
                pass
            done.set()
        threading.Thread(target=read, daemon=True).start()
        self.assertTrue(wait_for(lambda: "event: hello" in lines, 5))
        self.addCleanup(conn.close)
        return lines

    def test_wake_endpoint_security(self):
        self.assertEqual(self.jpost("/api/wake", {}, token=None)[0], 401)
        self.assertEqual(self.jget("/api/events", token=None)[0], 401)
        resp, _ = self.request("POST", "/api/wake", body={}, headers={"Origin": "http://evil.example.com"})
        self.assertEqual(resp.status, 403)

    def test_wake_reaches_open_apps_once_per_second(self):
        lines = self.open_events()
        self.assertEqual(self.jpost("/api/wake", {})[1], {"ok": True, "fired": True})
        self.assertTrue(wait_for(lambda: "event: wake" in lines, 5))
        data = [l for l in lines if l.startswith("data: ") and "seq" in l]
        self.assertEqual(json.loads(data[0][6:])["seq"], 1)
        self.assertEqual(self.jpost("/api/wake", {})[1]["fired"], False)
        self.assertEqual(self.jget("/api/status")[1]["wake"]["seq"], 1)

    def test_app_is_opened_only_when_it_is_not_already_open(self):
        with mock.patch("web_server.shutil.which", return_value="/bin/termux-open-url"), \
                mock.patch("web_server.subprocess.Popen") as popen:
            self.httpd.trigger_wake("test")
            popen.assert_called_once()
            self.assertEqual(popen.call_args.args[0], ["termux-open-url", f"http://localhost:{self.port}/#voice"])
        with mock.patch("web_server.shutil.which", return_value="/bin/termux-open-url"), \
                mock.patch("web_server.subprocess.Popen") as popen:
            self.httpd._wake_last = 0
            self.open_events()
            self.httpd.trigger_wake("test")
            popen.assert_not_called()
        with mock.patch("web_server.shutil.which", return_value="/bin/termux-open-url"), \
                mock.patch("web_server.subprocess.Popen") as popen:
            self.httpd._wake_last = 0
            self.httpd._subs.clear()
            self.convo.config["wake"]["open_app"] = False
            self.httpd.trigger_wake("test")
            popen.assert_not_called()

    def test_listener_follows_settings_and_reaches_the_app(self):
        self.convo.config["wake"].update({"mic_cmd": fake_mic("mic-e2e.py", SPEECH_THEN_QUIET), "cooldown": 30})
        self.convo.config["stt"].update({"bin": fake_whisper("w-e2e", "echo hey faze"), "model_path": model_file("e2e.bin")})
        lines = self.open_events()
        self.assertFalse(self.jget("/api/status")[1]["wake"]["enabled"])
        code, d = self.jpost("/api/settings", {"key": "wake.enabled", "value": "true"})
        self.assertEqual(code, 200)
        self.assertTrue(self.jget("/api/status")[1]["wake"]["enabled"])
        self.assertTrue(wait_for(lambda: "event: wake" in lines, 15), lines)
        w = self.jget("/api/status")[1]["wake"]
        self.assertEqual((w["state"], w["phrase"]), ("listening", "hey Faze"))
        self.jpost("/api/command", {"line": "/settings set wake.enabled false"})
        self.assertEqual(self.jget("/api/status")[1]["wake"]["state"], "off")
        self.assertIsNone(self.httpd.wake)
        self.httpd.stop_wake()


class TestLazyBoot(BootBase):
    def test_nothing_starts_at_launch_and_qwen_starts_on_first_need(self):
        c = self.convo(qwen_delay=1.0, qwen="lazy", chat="lfm")
        c.start_background()
        self.assertEqual(c.local.state, "idle")
        self.assertIsNone(c.local.proc)
        self.assertIsNone(c.lfm_process)
        self.assertIn("qwen: idle", c.handle_command("/status"))
        self.assertEqual(c.send("yo"), "lfm says yo")  # Qwen kicked off, LFM covers meanwhile
        self.assertIn(c.local.state, ("starting", "warming", "ready"))
        self.assertTrue(wait_for(lambda: c.local.state == "ready"))
        self.assertEqual(c.send("yo again"), "qwen says hi")

    def test_attaches_to_a_server_that_is_already_running(self):
        c = self.convo(qwen_delay=0.1, qwen="eager", lfm=False)
        c.start_background()
        self.assertTrue(wait_for(lambda: c.local.state == "ready"))
        second = self.convo(qwen_delay=0.1, qwen="lazy", lfm=False)
        second.config["llm"]["port"] = c.config["llm"]["port"]
        second.local.config = second.config
        second.url = c.url
        second.start_background()
        self.assertNotEqual(second.local.state, "idle")

    def test_forced_qwen_starts_it_and_waits(self):
        c = self.convo(qwen_delay=1.0, qwen="lazy", lfm=False)
        c.start_background()
        c.handle_command("/model qwen")
        self.assertEqual(c.send("hello"), "qwen says hi")
        self.assertEqual(c.local.state, "ready")

    def test_eager_still_boots_at_launch(self):
        c = self.convo(qwen_delay=0.5, qwen="eager", lfm=False)
        c.start_background()
        self.assertNotEqual(c.local.state, "idle")


class TestGeminiFirstWithFallback(BootBase):
    def gem(self, c, effect):
        return mock.patch("orchestrator.ask_gemini", side_effect=effect)

    def test_offline_falls_back_to_lfm_then_skips_gemini_until_retry(self):
        c = self.convo(qwen="lazy", chat="lfm")
        c.start_background()
        c.handle_command("/model gemini")
        calls = []

        def down(*a, **k):
            calls.append(1)
            raise GeminiError("Network error calling Gemini: no route to host")
        with self.gem(c, down):
            self.assertEqual(c.send("yo"), "lfm says yo")
            self.assertEqual([t for t, _ in self.routes[-2:]], ["gemini", "lfm"])
            self.assertIn("forced", self.routes[-2][1])
            self.assertIn("offline fallback", self.routes[-1][1])
            self.assertEqual(c.send("again"), "lfm says yo")
            self.assertEqual(len(calls), 1)  # cooling down: Gemini not asked again
            c._gemini_down_until = 0
            c.send("and again")
            self.assertEqual(len(calls), 2)
        with self.gem(c, lambda *a, **k: {"type": "text", "content": "gemini is back"}):
            c._gemini_down_until = 0
            self.assertEqual(c.send("hello"), "gemini is back")
        self.assertEqual(c.forced_model, "gemini")  # still on Gemini the whole time

    def test_quota_and_server_errors_also_fall_back(self):
        for message in ("Gemini API returned 429: quota", "Gemini API returned 503: busy"):
            c = self.convo(qwen="lazy", chat="lfm")
            with self.gem(c, GeminiError(message)):
                c.handle_command("/model gemini")
                self.assertEqual(c.send("yo"), "lfm says yo", message)

    def test_bad_key_stays_loud(self):
        c = self.convo(qwen="lazy", chat="lfm")
        c.handle_command("/model gemini")
        with self.gem(c, GeminiError("Gemini API returned 401: API key not valid")):
            reply = c.send("yo")
        self.assertIn("[gemini failed:", reply)
        self.assertIn("key rejected (401)", reply)
        self.assertIsNone(c.lfm_process)

    def test_fallback_can_be_turned_off(self):
        c = self.convo(qwen="lazy", chat="lfm")
        c.handle_command("/settings set fallback.order off")
        c.handle_command("/model gemini")
        with self.gem(c, GeminiError("Network error calling Gemini: x")):
            self.assertIn("[gemini failed:", c.send("yo"))

    def test_qwen_as_the_fallback_starts_on_demand(self):
        c = self.convo(qwen="lazy", qwen_delay=0.5, lfm=False)
        c.start_background()
        c.handle_command("/settings set fallback.order qwen")
        c.handle_command("/model gemini")
        with self.gem(c, GeminiError("Network error calling Gemini: x")):
            self.assertEqual(c.send("yo"), "qwen says hi")
        self.assertEqual(self.routes[-1][0], "qwen")
        self.assertIn("offline fallback", self.routes[-1][1])

    def test_both_down_reports_both_causes(self):
        c = self.convo(qwen="lazy", lfm=False, qwen_bin="/nonexistent/server")
        c.start_background()
        c.handle_command("/model gemini")
        with self.gem(c, GeminiError("Network error calling Gemini: x")):
            reply = c.send("yo")
        self.assertIn("gemini:", reply)
        self.assertIn("qwen:", reply)


class TestGeminiModels(unittest.TestCase):
    def tearDown(self):
        gemini_client.configure(chat="gemini-3.1-flash-lite", search="gemini-3.1-flash-lite")

    def test_search_model_is_separate_and_validated(self):
        os.environ["FAZE_SETTINGS"] = os.path.join(TMP, f"g-{time.time_ns()}.json")
        with mock.patch("orchestrator.ask_needle", return_value=None):
            from orchestrator import Conversation
            c = Conversation(load_config())
        self.assertEqual(c.handle_command("/settings set gemini.search_model gemini-2.5-flash"),
                         "gemini.search_model = gemini-2.5-flash")
        self.assertTrue(gemini_client._base_url(True).endswith("/gemini-2.5-flash"))
        self.assertTrue(gemini_client._base_url(False).endswith("/gemini-3.1-flash-lite"))
        self.assertTrue(c.handle_command("/settings set gemini.model bad!name").startswith("Setting not changed"))
        c.handle_command("/settings set gemini.model gemini-2.5-pro")
        self.assertTrue(gemini_client._base_url(False).endswith("/gemini-2.5-pro"))
        c.handle_command("/settings unset gemini.model")
        self.assertTrue(gemini_client._base_url(False).endswith("/gemini-3.1-flash-lite"))

    def test_search_requests_use_the_search_model(self):
        gemini_client.configure(chat="chat-model", search="search-model")
        seen = []

        class R:
            status_code = 200

            def json(self):
                return {"candidates": [{"content": {"parts": [{"text": "ok"}]}}]}
        with mock.patch.dict(os.environ, {"GEMINI_API_KEY": "k"}), \
                mock.patch("gemini_client.requests.post", side_effect=lambda url, **kw: seen.append(url) or R()):
            gemini_client.ask_gemini("sys", [{"role": "user", "content": "x"}], use_search_grounding=True)
            gemini_client.ask_gemini("sys", [{"role": "user", "content": "x"}])
        self.assertIn("/search-model:generateContent", seen[0])
        self.assertIn("/chat-model:generateContent", seen[1])


if __name__ == "__main__":
    unittest.main()


# ---------------------------------------------------------------- online voices

import base64  # noqa: E402
import struct  # noqa: E402

import tts  # noqa: E402


class FakeTtsResponse:
    def __init__(self, status=200, pcm=b"\x01\x00" * 480, mime="audio/L16;codec=pcm;rate=24000", body=None):
        self.status_code = status
        self.text = "{}"
        part = {"inlineData": {"mimeType": mime, "data": base64.b64encode(pcm).decode()}}
        self._body = body if body is not None else {"candidates": [{"content": {"parts": [part]}}]}

    def json(self):
        return self._body


class TestTts(unittest.TestCase):
    def setUp(self):
        os.environ["GEMINI_API_KEY"] = "test-key"

    def tearDown(self):
        os.environ.pop("GEMINI_API_KEY", None)

    def test_request_shape_and_wav(self):
        seen = {}

        def post(url, **kw):
            seen.update(url=url, **kw)
            return FakeTtsResponse()
        with mock.patch("tts.requests.post", side_effect=post):
            wav = tts.synthesize("Hello there", "kore", "gemini-3.1-flash-tts-preview")
        self.assertTrue(seen["url"].endswith("/models/gemini-3.1-flash-tts-preview:generateContent"))
        self.assertEqual(seen["headers"]["x-goog-api-key"], "test-key")
        cfg = seen["json"]["generationConfig"]
        self.assertEqual(cfg["responseModalities"], ["AUDIO"])
        self.assertEqual(cfg["speechConfig"]["voiceConfig"]["prebuiltVoiceConfig"]["voiceName"], "Kore")
        self.assertEqual(seen["json"]["contents"][0]["parts"][0]["text"], "Hello there")
        self.assertEqual(wav[:4] + wav[8:12], b"RIFFWAVE")
        self.assertEqual(struct.unpack("<I", wav[24:28])[0], 24000)
        self.assertEqual(len(wav), 44 + 960)

    def test_sample_rate_comes_from_the_response(self):
        with mock.patch("tts.requests.post", return_value=FakeTtsResponse(mime="audio/L16;codec=pcm;rate=16000")):
            self.assertEqual(struct.unpack("<I", tts.synthesize("x", "puck", "m")[24:28])[0], 16000)

    def test_failures_are_short_and_safe(self):
        cases = [
            (FakeTtsResponse(status=429), "quota exceeded (429"),
            (FakeTtsResponse(status=401), "key rejected (401)"),
            (FakeTtsResponse(body={"candidates": []}), "no audio"),
            (FakeTtsResponse(body={"candidates": [{"content": {"parts": [{"inlineData": {"data": "!!notbase64"}}]}}]}), "no audio"),
        ]
        for resp, want in cases:
            with mock.patch("tts.requests.post", return_value=resp):
                with self.assertRaises(tts.TtsError) as cm:
                    tts.synthesize("x", "kore", "m")
            self.assertIn(want, str(cm.exception))
        import requests
        with mock.patch("tts.requests.post", side_effect=requests.ConnectionError("boom")):
            with self.assertRaises(tts.TtsError) as cm:
                tts.synthesize("x", "kore", "m")
        self.assertIn("network error", str(cm.exception))

    def test_validation(self):
        for text, voice, want in (("", "kore", "nothing to say"), ("x" * 801, "kore", "over 800"),
                                  ("hi", "not-a-voice", "unknown voice")):
            with self.assertRaises(tts.TtsError) as cm:
                tts.synthesize(text, voice, "m")
            self.assertIn(want, str(cm.exception))
        os.environ.pop("GEMINI_API_KEY")
        with self.assertRaises(tts.TtsError) as cm:
            tts.synthesize("hi", "kore", "m")
        self.assertIn("GEMINI_API_KEY", str(cm.exception))

    def test_voice_list(self):
        self.assertEqual(len(tts.VOICES), 30)
        self.assertEqual(len(set(tts.VOICE_NAMES)), 30)
        self.assertIn("kore", tts.VOICE_NAMES)


class TestTtsServer(WebBase):
    def setUp(self):
        super().setUp()
        os.environ["GEMINI_API_KEY"] = "test-key"

    def tearDown(self):
        os.environ.pop("GEMINI_API_KEY", None)
        super().tearDown()

    def test_voices_endpoint(self):
        self.assertEqual(self.jget("/api/voices", token=None)[0], 401)
        code, d = self.jget("/api/voices")
        self.assertEqual(code, 200)
        self.assertEqual(len(d["gemini"]), 30)
        self.assertEqual(d["gemini"][3], {"name": "kore", "label": "Kore", "style": "Firm"})

    def test_speak_returns_wav_and_uses_settings(self):
        seen = {}

        def post(url, **kw):
            seen.update(url=url, **kw)
            return FakeTtsResponse()
        self.jpost("/api/settings", {"key": "tts.voice", "value": "puck"})
        self.jpost("/api/settings", {"key": "tts.engine", "value": "gemini"})
        self.assertEqual(self.jget("/api/status")[1]["tts"]["engine"], "gemini")
        with mock.patch("tts.requests.post", side_effect=post):
            resp, body = self.request("POST", "/api/speak", body={"text": "hello"})
            self.assertEqual((resp.status, resp.getheader("Content-Type")), (200, "audio/wav"))
            self.assertEqual(body[:4], b"RIFF")
            self.assertEqual(seen["json"]["generationConfig"]["speechConfig"]["voiceConfig"]["prebuiltVoiceConfig"]["voiceName"], "Puck")
            self.request("POST", "/api/speak", body={"text": "hello", "voice": "Fenrir"})
            self.assertEqual(seen["json"]["generationConfig"]["speechConfig"]["voiceConfig"]["prebuiltVoiceConfig"]["voiceName"], "Fenrir")

    def test_speak_security_and_errors(self):
        self.assertEqual(self.jpost("/api/speak", {"text": "x"}, token=None)[0], 401)
        resp, _ = self.request("POST", "/api/speak", body={"text": "x"}, headers={"Origin": "http://evil.example.com"})
        self.assertEqual(resp.status, 403)
        with mock.patch("tts.requests.post", return_value=FakeTtsResponse(status=429)):
            code, d = self.jpost("/api/speak", {"text": "hello"})
        self.assertEqual(code, 422)
        self.assertIn("quota exceeded", d["error"])
        self.assertEqual(self.jpost("/api/speak", {"text": "hi", "voice": "../etc"})[0], 422)

    def test_tts_settings_validation(self):
        for body in ({"key": "tts.engine", "value": "robot"}, {"key": "tts.voice", "value": "nobody"},
                     {"key": "tts.model", "value": "bad name!"}):
            self.assertEqual(self.jpost("/api/settings", body)[0], 400, body)
        self.assertEqual(self.jpost("/api/settings", {"key": "tts.voice", "value": "Kore"})[1]["value"], "kore")

    def test_one_reply_at_a_time(self):
        gate = threading.Event()

        def slow(url, **kw):
            gate.wait(5)
            return FakeTtsResponse()
        out = {}
        with mock.patch("tts.requests.post", side_effect=slow):
            t = threading.Thread(target=lambda: out.setdefault("r", self.request("POST", "/api/speak", body={"text": "one"})))
            t.start()
            time.sleep(0.4)
            self.assertEqual(self.jpost("/api/speak", {"text": "two"})[0], 409)
            gate.set()
            t.join(10)
        self.assertEqual(out["r"][0].status, 200)
