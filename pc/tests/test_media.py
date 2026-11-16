import base64
import io
import os
import threading
import unittest

from faze_pc import media
from faze_pc.config import Config
from tests.test_tasks import tmp


class Hold:
    """A recorder's output: the data once, then silence until the recorder is stopped (like a real one)."""
    def __init__(self, data):
        self.data, self.ev = data, threading.Event()

    def read(self, n):
        if self.data:
            out, self.data = self.data[:n], self.data[n:]
            return out
        self.ev.wait(5)
        return b""


class FakeProc:
    def __init__(self, data):
        self.stdout = Hold(data)
        self.stopped = False

    def terminate(self):
        self.stopped = True
        self.stdout.ev.set()


class MediaTest(unittest.TestCase):
    def make(self, tools=("parec",), on=True, data=b"\x01\x02" * 100):
        cfg = Config(os.path.join(tmp(), "c.json"))
        cfg.data["allow_remote"] = on
        cfg.data["inbox"] = os.path.join(tmp(), "inbox")
        self.procs, self.now = [], [1000.0]

        def popen(argv, **kw):
            p = FakeProc(data)
            self.procs.append((argv, p))
            return p

        self.m = media.Media(cfg, which=lambda n: "/bin/" + n if n in tools else None, popen=popen, clock=lambda: self.now[0])
        return self.m

    def test_off_by_default_everything_is_refused(self):
        m = self.make(on=False)
        for t in ("pc_audio_get", "pc_cam_get", "pc_put"):
            self.assertIn("Let my phone", m.handle({"t": t})["message"])

    def test_other_messages_are_ignored(self):
        self.assertIsNone(self.make().handle({"t": "sync_list"}))

    def test_sound_comes_in_chunks_and_uses_the_monitor_or_the_mic(self):
        m = self.make()
        import time; 
        a = m.handle({"t": "pc_audio_get", "source": "monitor"})
        time.sleep(0.2)
        b = m.handle({"t": "pc_audio_get", "source": "monitor"})
        self.assertEqual(b["rate"], media.RATE)
        self.assertEqual(base64.b64decode(a["data"]) + base64.b64decode(b["data"]), b"\x01\x02" * 100)
        self.assertIn("@DEFAULT_MONITOR@", self.procs[0][0])
        m.handle({"t": "pc_audio_get", "source": "mic"})
        self.assertNotIn("@DEFAULT_MONITOR@", self.procs[1][0])
        self.assertEqual(len(self.procs), 2)

    def test_it_stops_recording_when_the_phone_stops_asking(self):
        m = self.make()
        m.handle({"t": "pc_audio_get"})
        self.now[0] += media.IDLE + 1
        m.reap()
        self.assertTrue(self.procs[0][1].stopped)

    def test_explicit_stop(self):
        m = self.make()
        m.handle({"t": "pc_audio_get", "source": "mic"})
        m.handle({"t": "pc_audio_stop"})
        self.assertTrue(self.procs[0][1].stopped)

    def test_no_recorder_and_bad_source(self):
        self.assertIn("recorder", self.make(tools=()).handle({"t": "pc_audio_get"})["message"])
        self.assertEqual(self.make().handle({"t": "pc_audio_get", "source": "x"})["t"], "error")

    def test_webcam_needs_a_tool(self):
        m = self.make(tools=())
        r = m.handle({"t": "pc_cam_get"})
        self.assertEqual(r["t"], "error")

    def test_webcam_picture(self):
        m = self.make(tools=("ffmpeg",))
        m.run = lambda argv, timeout=15: (0, b"\xff\xd8jpeg")
        if not any(n.startswith("video") for n in os.listdir("/dev")):
            self.skipTest("no video device here")
        self.assertEqual(base64.b64decode(m.handle({"t": "pc_cam_get"})["jpeg"]), b"\xff\xd8jpeg")

    def put(self, m, name, data, offset, eof):
        return m.handle({"t": "pc_put", "name": name, "offset": offset, "data": base64.b64encode(data).decode(), "eof": eof})

    def test_a_file_arrives_in_pieces_and_never_replaces_another(self):
        m = self.make()
        self.assertEqual(self.put(m, "a.txt", b"he", 0, False)["t"], "pc_put_ok")
        r = self.put(m, "a.txt", b"llo", 2, True)
        self.assertEqual(open(r["saved"], "rb").read(), b"hello")
        self.put(m, "a.txt", b"x", 0, True)
        self.assertEqual(sorted(os.listdir(m.inbox())), ["a (1).txt", "a.txt"])

    def test_names_cannot_escape_the_inbox(self):
        m = self.make()
        r = self.put(m, "../../etc/passwd", b"x", 0, True)
        self.assertEqual(os.path.dirname(r["saved"]), m.inbox())
        self.assertEqual(os.path.basename(r["saved"]), "passwd")
        r = self.put(m, "..", b"x", 0, True)
        self.assertEqual(os.path.dirname(r["saved"]), m.inbox())

    def test_out_of_order_and_damaged_pieces_are_refused(self):
        m = self.make()
        self.put(m, "b.bin", b"ab", 0, False)
        self.assertEqual(self.put(m, "b.bin", b"cd", 5, True)["t"], "error")
        self.assertEqual(m.handle({"t": "pc_put", "name": "c", "offset": 0, "data": "!!!notbase64", "eof": True})["t"], "error")


if __name__ == "__main__":
    unittest.main()
