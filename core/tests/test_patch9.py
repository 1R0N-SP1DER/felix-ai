"""
Voice activation groundwork + settings page.

    PYTHONPATH=. python3 -m unittest tests.test_patch9 -v
"""
import base64
import ctypes
import os
import shlex
import subprocess
import sys
import unittest
from unittest import mock

CORE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ROOT = os.path.dirname(CORE)
SCRIPT = os.path.join(ROOT, "scripts", "pa_record.py")
sys.path.insert(0, CORE)
sys.path.insert(0, os.path.join(ROOT, "scripts"))

import config_loader  # noqa: E402
import pa_record  # noqa: E402
import tts  # noqa: E402
from tests.test_patch8 import HAVE_JSDOM, run_smoke  # noqa: E402


class TestPaRecord(unittest.TestCase):
    def test_sample_spec_matches_libpulse_layout(self):
        self.assertEqual(ctypes.sizeof(pa_record.SampleSpec), 12)  # int, uint32, uint8 + padding
        self.assertEqual((pa_record.PA_SAMPLE_S16LE, pa_record.PA_STREAM_RECORD), (3, 2))

    def test_fails_cleanly_without_a_pulse_server(self):
        env = dict(os.environ, PULSE_SERVER="unix:/nonexistent/faze-test-socket", PULSE_RUNTIME_PATH="/nonexistent")
        out = subprocess.run([sys.executable, SCRIPT, "no-such-source"], capture_output=True, env=env, timeout=30)
        self.assertNotEqual(out.returncode, 0)
        self.assertEqual(out.stdout, b"")
        self.assertIn(b"pa_record:", out.stderr)
        self.assertNotIn(b"Traceback", out.stderr)

    def test_default_mic_command_points_at_the_script(self):
        parts = shlex.split(config_loader.load_config()["wake"]["mic_cmd"])
        self.assertEqual(os.path.realpath(parts[1]), os.path.realpath(SCRIPT))
        self.assertEqual(parts[2:], ["", "16000"])
        self.assertTrue(os.path.exists(parts[1]))


class FakeResponse:
    def __init__(self, status=200, payload=None, text=""):
        self.status_code, self._payload, self.text = status, payload, text
        self.headers = {}

    def json(self):
        if self._payload is None:
            raise ValueError("no json")
        return self._payload


def audio_reply(mime="audio/L16;codec=pcm;rate=24000", pcm=b"\x01\x00" * 100, extra_parts=()):
    parts = list(extra_parts) + [{"inlineData": {"mimeType": mime, "data": base64.b64encode(pcm).decode()}}]
    return FakeResponse(200, {"candidates": [{"content": {"parts": parts}}]})


EMPTY = FakeResponse(200, {"candidates": [{"finishReason": "OTHER", "content": {"parts": []}}]})


class TestGeminiVoice(unittest.TestCase):
    def setUp(self):
        self.env = mock.patch.dict(os.environ, {"GEMINI_API_KEY": "k"})
        self.env.start()
        self.addCleanup(self.env.stop)

    def run_with(self, *replies):
        calls = []
        queue = list(replies)

        def post(url, headers=None, json=None, timeout=None):
            calls.append(json)
            return queue.pop(0)
        try:
            return tts.synthesize("hello there", "Kore", "m", post=post), calls
        except tts.TtsError as e:
            return e, calls

    def test_plain_success_is_a_wav_at_the_reported_rate(self):
        wav, calls = self.run_with(audio_reply(mime="audio/L16;codec=pcm;rate=16000"))
        self.assertEqual(wav[:4], b"RIFF")
        self.assertEqual(int.from_bytes(wav[24:28], "little"), 16000)
        self.assertEqual(len(calls), 1)
        self.assertEqual(calls[0]["contents"][0]["parts"][0]["text"], "hello there")

    def test_audio_part_need_not_be_first(self):
        wav, _ = self.run_with(audio_reply(extra_parts=[{"text": "note"}]))
        self.assertEqual(wav[:4], b"RIFF")

    def test_empty_audio_retries_with_a_reading_instruction(self):
        wav, calls = self.run_with(EMPTY, audio_reply())
        self.assertEqual(wav[:4], b"RIFF")
        self.assertEqual(len(calls), 2)
        self.assertTrue(calls[1]["contents"][0]["parts"][0]["text"].endswith("hello there"))
        self.assertIn("Read aloud", calls[1]["contents"][0]["parts"][0]["text"])

    def test_server_errors_retry_but_quota_errors_do_not(self):
        wav, calls = self.run_with(FakeResponse(503, {"error": {"message": "overloaded"}}), audio_reply())
        self.assertEqual((wav[:4], len(calls)), (b"RIFF", 2))
        err, calls = self.run_with(FakeResponse(429, {"error": {"message": "quota"}}))
        self.assertIsInstance(err, tts.TtsError)
        self.assertEqual(len(calls), 1)

    def test_persistent_failure_names_the_reason(self):
        err, calls = self.run_with(EMPTY, EMPTY, EMPTY)
        self.assertEqual(len(calls), tts.ATTEMPTS)
        self.assertIn("finishReason OTHER", str(err))
        blocked, _ = self.run_with(FakeResponse(200, {"promptFeedback": {"blockReason": "SAFETY"}}), EMPTY, EMPTY)
        self.assertIsInstance(blocked, tts.TtsError)
        bad, _ = self.run_with(FakeResponse(200, None), FakeResponse(200, None), FakeResponse(200, None))
        self.assertIn("not JSON", str(bad))

    def test_input_checks_unchanged(self):
        for args in (("", "Kore"), ("x" * 801, "Kore"), ("hi", "Nobody")):
            with self.assertRaises(tts.TtsError):
                tts.synthesize(args[0], args[1], "m", post=lambda *a, **k: None)
        with mock.patch.dict(os.environ, {}, clear=True), self.assertRaises(tts.TtsError):
            tts.synthesize("hi", "Kore", "m")


@unittest.skipUnless(HAVE_JSDOM, "jsdom not installed")
class TestSettingsPage(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.res = run_smoke()

    def test_page_ran_without_errors(self):
        self.assertNotIn("crash", self.res, self.res.get("crash"))
        self.assertEqual(self.res["errors"], [])

    def test_settings_is_a_page_with_tabs(self):
        s = self.res["settings"]
        self.assertTrue(s["open"] and s["bodyClass"])
        self.assertEqual(s["hash"], "#settings")
        self.assertEqual(s["tabs"], ["General", "Voice", "Agent", "Advanced"])
        self.assertEqual((s["panel"], s["voicePanel"], s["advancedPanel"]), (["general"], ["voice"], ["advanced"]))
        self.assertTrue(s["closed"])
        self.assertEqual(s["hashAfter"], "")

    def test_wake_word_controls(self):
        w = self.res["settings"]["wake"]
        self.assertEqual(w, {"checked": True, "phrase": "hey faze", "status": "Listening for \u201Chey faze\u201D"})
        self.assertEqual(self.res["settings"]["wakeSaved"], "false")


if __name__ == "__main__":
    unittest.main()
