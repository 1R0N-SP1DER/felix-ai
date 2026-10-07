"""
Mic source loading (the launcher hid a missing microphone behind the sink's "sles" line) and clearer mic-check errors.

    PYTHONPATH=. python3 -m unittest tests.test_patch11 -v
"""
import contextlib
import io
import os
import re
import sys
import unittest
from unittest import mock

CORE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ROOT = os.path.dirname(CORE)
sys.path.insert(0, CORE)
sys.path.insert(0, os.path.join(ROOT, "scripts"))

import pa_record  # noqa: E402
import wake  # noqa: E402
from config_loader import load_config  # noqa: E402

MONITOR_ONLY = "1\tOpenSL_ES_sink.monitor\tmodule-sles-sink.c\ts16le 2ch 44100Hz\tRUNNING\n"
WITH_MIC = MONITOR_ONLY + "2\tOpenSL_ES_source\tmodule-sles-source.c\ts16le 2ch 44100Hz\tSUSPENDED\n"


def result(text, code=0):
    return mock.Mock(stdout=text, returncode=code)


class TestLauncher(unittest.TestCase):
    def test_launcher_checks_for_the_source_module_not_any_sles_line(self):
        line = [l for l in open(os.path.join(ROOT, "faze.sh")) if "load-module" in l][0]
        self.assertIn("grep -q 'module-sles-source'", line)
        self.assertNotRegex(line, r"grep -q sles")
        # the old test matched this sink line, so the mic was never loaded:
        self.assertTrue(re.search("sles", MONITOR_ONLY))
        self.assertFalse(re.search("module-sles-source", MONITOR_ONLY))


class TestSourceLoading(unittest.TestCase):
    def test_loads_the_module_when_only_monitors_exist(self):
        calls = []
        replies = {("list", "short", "sources"): [result(MONITOR_ONLY), result(WITH_MIC)], ("load-module", "module-sles-source"): [result("21\n")]}

        def fake(*args):
            calls.append(args)
            return replies[args].pop(0)
        with mock.patch.object(pa_record, "_pactl", side_effect=fake):
            self.assertEqual(pa_record.detect_source(), "OpenSL_ES_source")
        self.assertEqual(calls.count(("load-module", "module-sles-source")), 1)

    def test_does_not_reload_when_a_mic_exists(self):
        with mock.patch.object(pa_record, "_pactl", return_value=result(WITH_MIC)) as p:
            self.assertEqual(pa_record.detect_source(), "OpenSL_ES_source")
        self.assertEqual(p.call_count, 1)

    def test_gives_up_cleanly(self):
        with mock.patch.object(pa_record, "_pactl", return_value=None):
            self.assertIsNone(pa_record.detect_source())
        with mock.patch.object(pa_record, "_pactl", return_value=result(MONITOR_ONLY)):
            self.assertIsNone(pa_record.detect_source())  # loaded, still nothing

    def test_refuses_to_record_a_monitor_and_says_why(self):
        for info_code, code, words in ((0, 3, "no microphone source"), (1, 4, "pulseaudio is not running")):
            err = io.StringIO()
            with mock.patch.object(pa_record, "detect_source", return_value=None), \
                    mock.patch.object(pa_record, "_pactl", return_value=result("", info_code)), contextlib.redirect_stderr(err):
                self.assertEqual(pa_record.main(["pa_record.py"]), code)
            self.assertIn(words, err.getvalue())


class TestMicCheck(unittest.TestCase):
    def run_check(self, python_code):
        cfg = load_config()
        cfg["wake"]["mic_cmd"] = f"{sys.executable} -c {python_code!r}"
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = wake.check_mic(cfg, seconds=1.0)
        return code, out.getvalue()

    def test_failure_shows_what_the_mic_command_said(self):
        code, text = self.run_check("import sys; sys.stderr.write('pa_record: no microphone source\\n'); sys.exit(3)")
        self.assertEqual(code, 1)
        self.assertIn("FAIL: the mic command produced no audio. It said: pa_record: no microphone source", text)

    def test_silence_and_sound(self):
        code, text = self.run_check("import sys; sys.stdout.buffer.write(bytes(400000))")
        self.assertEqual(code, 1)
        self.assertIn("pure silence", text)
        code, text = self.run_check("import sys, struct; sys.stdout.buffer.write(struct.pack('<h', 3000) * 200000)")
        self.assertEqual(code, 0)
        self.assertIn("OK: peak level", text)


if __name__ == "__main__":
    unittest.main()
