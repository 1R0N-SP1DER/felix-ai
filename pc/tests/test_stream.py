import io
import os
import threading
import time
import unittest

from faze_pc import stream
from faze_pc.config import Config
from faze_pc.remote import RemoteControl
from tests.test_tasks import tmp


def jpeg(tag):
    return b"\xff\xd8" + tag + b"\xff\xd9"


class SplitterTest(unittest.TestCase):
    def test_pictures_in_any_chunking(self):
        data = jpeg(b"one") + jpeg(b"22") + jpeg(b"three")
        for size in (1, 3, 7, len(data)):
            sp = stream.JpegSplitter()
            got = []
            for i in range(0, len(data), size):
                got += sp.feed(data[i:i + size])
            self.assertEqual(got, [jpeg(b"one"), jpeg(b"22"), jpeg(b"three")], size)

    def test_junk_before_and_a_half_picture_waiting(self):
        sp = stream.JpegSplitter()
        self.assertEqual(sp.feed(b"junk" + jpeg(b"a") + b"\xff\xd8half"), [jpeg(b"a")])
        self.assertEqual(sp.feed(b"rest\xff\xd9"), [b"\xff\xd8halfrest\xff\xd9"])

    def test_runaway_input_is_dropped(self):
        sp = stream.JpegSplitter()
        sp.feed(b"\xff\xd8" + b"x" * (stream.MAX_BUF + 10))
        self.assertEqual(len(sp.buf), 0)


class Hold:
    def __init__(self, data):
        self.data, self.ev = data, threading.Event()

    def read(self, n):
        if self.data:
            out, self.data = self.data[:n], b""
            return out
        self.ev.wait(5)
        return b""


class Proc:
    def __init__(self, data=b"", err=b""):
        self.stdout, self.stdin, self.stderr = Hold(data), io.BytesIO(), io.BytesIO(err)
        self.stopped = False

    def terminate(self):
        self.stopped = True
        self.stdout.ev.set()


class StreamTest(unittest.TestCase):
    def make(self, env, tools=("ffmpeg",), data=b"", err=b"", on_frame=None, python=None):
        self.frames, self.errors, self.cmds = [], [], []
        self.now = [100.0]

        def popen(argv, **kw):
            self.cmds.append(argv)
            self.proc = Proc(data, err)
            return self.proc

        s = stream.ScreenStream(on_frame or (lambda j, n: self.frames.append((j, n))), self.errors.append, env=env, which=lambda n: "/bin/" + n if n in tools else None, popen=popen, clock=lambda: self.now[0], python=python or os.path.realpath("/bin/sh"))
        return s

    def wait(self, cond):
        end = time.time() + 2
        while time.time() < end and not cond():
            time.sleep(0.01)

    def test_wayland_prefers_the_portal_then_kmsgrab(self):
        s = self.make({"XDG_SESSION_TYPE": "wayland"})
        self.assertEqual([e[0] for e in s.engines(960, 30, 60)], ["portal", "kmsgrab"])
        self.assertEqual(s.engines(960, 30, 60)[0][1][1:3], ["-m", "faze_pc.portal"])

    def test_x11_uses_x11grab(self):
        s = self.make({"XDG_SESSION_TYPE": "x11", "DISPLAY": ":1"})
        self.assertEqual(s.engines(800, 30, 60)[0][0], "x11grab")
        self.assertIn(":1", s.engines(800, 30, 60)[0][1])

    def test_pictures_flow_and_numbers_grow(self):
        s = self.make({"XDG_SESSION_TYPE": "x11"}, data=jpeg(b"a") + jpeg(b"b"))
        self.assertEqual(s.start(), "x11grab")
        self.wait(lambda: s.sent >= 1)
        s.stop()
        self.assertTrue(self.frames)
        import base64
        self.assertIn(base64.b64decode(self.frames[-1][0]), (jpeg(b"a"), jpeg(b"b")))

    def test_a_slow_phone_gets_the_newest_not_a_queue(self):
        gate = threading.Event()
        got = []

        def slow(j, n):
            got.append(n)
            gate.wait(0.3)

        s = self.make({"XDG_SESSION_TYPE": "x11"}, data=b"".join(jpeg(bytes([65 + i])) for i in range(20)), on_frame=slow)
        s.start()
        self.wait(lambda: len(got) >= 2)
        s.stop()
        self.assertLess(len(got), 20)
        self.assertEqual(got, sorted(got))

    def test_no_tool_gives_install_words(self):
        s = self.make({"XDG_SESSION_TYPE": "x11"}, tools=())
        with self.assertRaises(RuntimeError) as cm:
            s.start()
        self.assertIn("ffmpeg", str(cm.exception))
        w = self.make({"XDG_SESSION_TYPE": "wayland"}, tools=(), python="/nonexistent/python3")
        with self.assertRaises(RuntimeError) as cm:
            w.start()
        self.assertIn("python3-gi", str(cm.exception))

    def test_the_capture_dying_reports_its_last_error_line(self):
        s = self.make({"XDG_SESSION_TYPE": "x11"}, err=b"boot\nCannot open display\n")
        s.start()
        self.proc.stdout.ev.set()
        self.wait(lambda: self.errors)
        self.assertEqual(self.errors, ["Cannot open display"])

    def test_it_stops_by_itself_when_the_phone_stops_pinging(self):
        s = self.make({"XDG_SESSION_TYPE": "x11"})
        s.start()
        self.now[0] += stream.IDLE + 5
        self.wait(lambda: not s.running)
        self.assertFalse(s.running)
        self.assertTrue(self.proc.stopped)

    def test_ping_keeps_it_alive(self):
        s = self.make({"XDG_SESSION_TYPE": "x11"})
        s.start()
        self.now[0] += stream.IDLE - 1
        s.ping()
        self.now[0] += stream.IDLE - 1
        time.sleep(1.3)
        self.assertTrue(s.running)
        s.stop()


class FakeStream:
    def __init__(self, on_frame, on_error):
        self.calls = []
        self.fail = None

    def start(self, w, fps, q):
        if self.fail:
            raise RuntimeError(self.fail)
        self.calls.append(("start", w, fps, q))
        return "portal"

    def ping(self): self.calls.append(("ping",))
    def stop(self): self.calls.append(("stop",))


class RemoteStreamTest(unittest.TestCase):
    def make(self, on=True):
        cfg = Config(os.path.join(tmp(), "c.json"))
        cfg.data["allow_remote"] = on
        r = RemoteControl(cfg, stream_factory=FakeStream)
        return r

    def test_start_ping_stop(self):
        r = self.make()
        self.assertEqual(r.handle({"t": "pc_stream_start", "w": 800, "fps": 40, "q": 50}), {"t": "pc_stream_ok", "engine": "portal"})
        r.handle({"t": "pc_stream_ping"}); r.handle({"t": "pc_stream_stop"})
        self.assertEqual(r.stream.calls, [("start", 800, 40, 50), ("ping",), ("stop",)])

    def test_off_means_off(self):
        r = self.make(on=False)
        self.assertEqual(r.handle({"t": "pc_stream_start"})["t"], "error")
        self.assertEqual(r.stream.calls, [])

    def test_a_missing_tool_is_the_phones_error(self):
        r = self.make()
        r.stream.fail = "install ffmpeg"
        self.assertEqual(r.handle({"t": "pc_stream_start"}), {"t": "error", "message": "install ffmpeg"})


class PortalTest(unittest.TestCase):
    def test_the_gstreamer_command(self):
        from faze_pc import portal
        a = portal.gst_args(7, 42, 960, 30, 60)
        self.assertIn("fd=7", a)
        self.assertIn("path=42", a)
        self.assertIn("video/x-raw,width=960", a)
        self.assertIn("jpegenc", a)
        self.assertEqual(a[-2:], ["fdsink", "fd=1"])


if __name__ == "__main__":
    unittest.main()
