import subprocess
import threading
import unittest

from faze_pc import adbphone
from faze_pc.adbphone import Adb, AdbError


class R:
    def __init__(self, out=b"", code=0, err=b""):
        self.stdout, self.returncode, self.stderr = out, code, err


class AdbTest(unittest.TestCase):
    def make(self, size=b"Physical size: 1080x2400\n"):
        calls = []

        def run(cmd, **kw):
            calls.append(cmd)
            if cmd[-2:] == ["wm", "size"]:
                return R(size)
            if cmd[-1] == "devices":
                return R(b"List of devices attached\nABC123\tdevice\nXYZ\tunauthorized\n192.168.1.5:5555\tdevice\n")
            return R(b"")

        return Adb(run=run, which=lambda n: "/usr/bin/adb"), calls

    def test_lists_only_ready_devices(self):
        a, _ = self.make()
        self.assertEqual(["ABC123", "192.168.1.5:5555"], a.devices())

    def test_no_adb_means_no_devices(self):
        self.assertEqual([], Adb(which=lambda n: None).devices())

    def test_taps_and_swipes_use_pixels(self):
        a, calls = self.make()
        a.tap("ABC123", 0.5, 0.25)
        self.assertEqual(["adb", "-s", "ABC123", "shell", "input", "tap", "540", "600"], calls[-1])
        a.swipe("ABC123", 0.5, 0.9, 0.5, 0.1)
        self.assertEqual(["adb", "-s", "ABC123", "shell", "input", "swipe", "540", "2159", "540", "240", "300"], calls[-1])
        a.long_press("ABC123", 0.0, 1.0)
        self.assertEqual("700", calls[-1][-1])

    def test_keys_cover_everything_a_computer_has(self):
        a, calls = self.make()
        for k, code in (("tab", "KEYCODE_TAB"), ("escape", "KEYCODE_ESCAPE"), ("left", "KEYCODE_DPAD_LEFT"), ("recents", "KEYCODE_APP_SWITCH")):
            a.key("ABC123", k)
            self.assertEqual(code, calls[-1][-1])
        with self.assertRaises(AdbError):
            a.key("ABC123", "nope")

    def test_text_is_quoted_and_plain(self):
        self.assertEqual("hello%sworld", adbphone.text_arg("hello world"))
        self.assertEqual("a%25b", adbphone.text_arg("a%b"))
        self.assertEqual("'it'\"'\"'s'", adbphone.text_arg("it's"))
        self.assertIn("&", adbphone.text_arg("a&b"))
        with self.assertRaises(AdbError):
            adbphone.text_arg("héllo")

    def test_failures_say_what_adb_said(self):
        def run(cmd, **kw):
            return R(b"", 1, b"error: device unauthorized.\n")
        with self.assertRaises(AdbError) as c:
            Adb(run=run, which=lambda n: "adb").tap("X", 0.1, 0.1)
        self.assertIn("unauthorized", str(c.exception))

    def test_record_command_is_even_sized_and_bounded(self):
        a, _ = self.make()
        args = a.record_args("ABC123", 720, 6000)
        self.assertIn("720x1600", args)
        self.assertEqual("6000000", args[args.index("--bit-rate") + 1])

    def test_stream_restarts_and_stops(self):
        a, _ = self.make()
        got = []
        stop = threading.Event()
        starts = []

        class P:
            def __init__(self):
                self.n = 0
                self.stdout = self

            def read(self, n):
                self.n += 1
                return b"x" * 4 if self.n <= 2 else b""

            def kill(self):
                pass

        def popen(args, **kw):
            starts.append(args)
            if len(starts) == 2:
                stop.set()
            return P()

        a._popen = popen
        a.stream("ABC123", got.append, stop)
        self.assertEqual(2, len(starts))
        self.assertEqual(2, len(got))

    def test_a_silent_recorder_is_reported(self):
        a, _ = self.make()

        class P:
            stdout = None

            def __init__(self):
                self.stdout = self

            def read(self, n):
                return b""

            def kill(self):
                pass
        a._popen = lambda args, **kw: P()
        with self.assertRaises(AdbError):
            a.stream("ABC123", lambda b: None, threading.Event())


if __name__ == "__main__":
    unittest.main()


class DaemonAdbTest(unittest.TestCase):
    def daemon(self, backend="auto", serial="ABC", phone=False):
        import os
        import tempfile
        from faze_pc.daemon import Daemon
        from faze_pc.config import Config
        d = Daemon(Config(os.path.join(tempfile.mkdtemp(), "c.json")))
        d.config.data["phone_backend"] = backend

        class FakeAdb:
            def __init__(self):
                self.calls = []

            def devices(self):
                return [serial] if serial else []

            def tap(self, s, x, y):
                self.calls.append(("tap", s, x, y))

            def key(self, s, k):
                self.calls.append(("key", s, k))

            def text(self, s, t):
                raise adbphone.AdbError("only plain text")
        d.adb = FakeAdb()
        d._phone = lambda: "p1" if phone else None
        return d

    def test_input_goes_through_adb_while_it_is_the_screen(self):
        d = self.daemon()
        d.adb_active = "ABC"
        self.assertIsNone(d.mirror_input({"kind": "tap", "x": 0.1, "y": 0.2}))
        self.assertIsNone(d.mirror_input({"kind": "key", "key": "tab"}))
        self.assertEqual([("tap", "ABC", 0.1, 0.2), ("key", "ABC", "tab")], d.adb.calls)
        self.assertIn("plain", d.mirror_input({"kind": "text", "text": "é"})["message"])

    def test_adb_is_used_without_a_faze_phone_but_link_only_means_never(self):
        self.assertEqual("ABC", self.daemon()._adb_serial())
        d = self.daemon(backend="link")
        self.assertFalse(d._adb_instead("x"))
        d = self.daemon(serial=None)
        self.assertFalse(d._adb_instead("x"))


class StatusTest(unittest.TestCase):
    def test_status_lines(self):
        import os
        import tempfile
        from faze_pc.daemon import Daemon
        from faze_pc.config import Config
        d = Daemon(Config(os.path.join(tempfile.mkdtemp(), "c.json")))
        d.adb = Adb(which=lambda n: None)
        self.assertIn("not installed", d.adb_status())
        d.adb = Adb(which=lambda n: "adb", run=lambda cmd, **kw: R(b"List of devices attached\n"))
        self.assertIn("no authorised", d.adb_status())
        d.adb = Adb(which=lambda n: "adb", run=lambda cmd, **kw: R(b"List of devices attached\nABC\tdevice\n"))
        self.assertEqual("adb sees ABC.", d.adb_status())

    def test_backend_setting_is_known(self):
        import os
        import tempfile
        from faze_pc.config import Config
        c = Config(os.path.join(tempfile.mkdtemp(), "c.json"))
        self.assertEqual("auto", c.data["phone_backend"])
        c.set("phone_backend", "adb")
        self.assertEqual("adb", c.data["phone_backend"])
