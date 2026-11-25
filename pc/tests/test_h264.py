import os
import unittest

from faze_pc import portal, stream
from faze_pc.remote import RemoteControl
from faze_pc.config import Config
from tests.test_stream import tmp, NoUInput

SC = b"\x00\x00\x00\x01"


def nal(typ, body=b"\x80abc"):
    return SC + bytes([0x60 | typ]) + body


AUD, SPS, PPS = nal(9, b"\xf0"), nal(7, b"\x42\x00\x1e"), nal(8, b"\xce")
IDR, P = nal(5, b"\x88IDRDATA"), nal(1, b"\x9aPDATA")


class SplitterTest(unittest.TestCase):
    def test_units_split_on_the_delimiter_and_flag_keyframes(self):
        s = stream.H264Splitter()
        out = s.feed(AUD + SPS + PPS + IDR + AUD + P + AUD + P)
        self.assertEqual([k for _, k in out], [True, False])
        self.assertTrue(out[0][0].startswith(AUD + SPS))
        self.assertTrue(out[0][0].endswith(IDR))
        out += s.flush()
        self.assertEqual([k for _, k in out], [True, False, False])

    def test_split_without_delimiters_uses_the_first_macroblock(self):
        s = stream.H264Splitter()
        out = s.feed(P + P + P) + s.flush()
        self.assertEqual(len(out), 3)

    def test_bytes_arriving_in_pieces(self):
        s = stream.H264Splitter()
        data = AUD + IDR + AUD + P
        out = []
        for i in range(0, len(data), 3):
            out += s.feed(data[i:i + 3])
        out += s.flush()
        self.assertEqual([k for _, k in out], [True, False])

    def test_two_slices_of_one_picture_stay_together(self):
        s = stream.H264Splitter()
        slice2 = nal(1, b"\x1aMORE")   # first bit 0: not macroblock 0
        out = s.feed(AUD + nal(1, b"\x9aA") + slice2 + AUD + P) + s.flush()
        self.assertEqual(len(out), 2)


class ArgsTest(unittest.TestCase):
    def test_bitrate_grows_with_size_and_rate(self):
        self.assertLess(stream.kbps(640, 30, 60), stream.kbps(1280, 30, 60))
        self.assertLess(stream.kbps(1280, 15, 60), stream.kbps(1280, 60, 60))

    def test_ffmpeg_h264_is_low_latency(self):
        a = stream.x11_args(1280, 30, 60, ":0", "h264")
        for w in ("libx264", "zerolatency", "ultrafast", "h264"):
            self.assertIn(w, a)
        self.assertEqual(stream.x11_args(1280, 30, 60, ":0")[-3:], ["-f", "mjpeg", "-"])

    def test_portal_gets_the_codec_word(self):
        s = stream.ScreenStream(lambda *a: 0, lambda m: 0, env={"XDG_SESSION_TYPE": "wayland"}, which=lambda n: "/bin/" + n, python="/bin/sh")
        self.assertEqual(s.engines(960, 30, 60, "h264")[0][1][-1], "h264")
        self.assertNotIn("h264", s.engines(960, 30, 60)[0][1])

    def test_encoder_choice(self):
        self.assertEqual(portal.pick_encoder(lambda n: n in ("nvh264enc", "x264enc")), "x264enc")
        self.assertEqual(portal.pick_encoder(lambda n: n == "vah264enc"), "vah264enc")
        self.assertEqual(portal.pick_encoder(lambda n: True, "nvh264enc"), "nvh264enc")
        self.assertIsNone(portal.pick_encoder(lambda n: False))

    def test_sizes_are_even_and_keep_the_proportions(self):
        self.assertEqual(portal.even_size(1280, (1920, 1080)), (1280, 720))
        self.assertEqual(portal.even_size(1281, (1366, 768)), (1280, 720))
        w, h = portal.even_size(1000, (1366, 767))
        self.assertEqual((w % 2, h % 2), (0, 0))
        self.assertIsNone(portal.even_size(1280, None))
        self.assertIsNone(portal.even_size(1280, (0, 0)))

    def test_pipeline_uses_the_even_size_when_it_is_known(self):
        a = " ".join(portal.gst_args(7, 42, 1280, 30, 60, "x264enc", 2000, (1366, 768)))
        self.assertIn("width=1280,height=720", a)
        self.assertIn("video/x-raw,width=1280", " ".join(portal.gst_args(7, 42, 1280, 30, 60, "x264enc", 2000)))

    def test_gstreamer_h264_pipeline(self):
        a = portal.gst_args(7, 42, 1281, 30, 60, "x264enc", 2000)
        self.assertIn("x264enc", a)
        self.assertIn("video/x-raw,width=1280", a)
        self.assertIn("alignment=au", " ".join(a))
        self.assertNotIn("jpegenc", a)


class FakeStream:
    def __init__(self, on_frame, on_error):
        self.calls = []

    def start(self, w, fps, q, codec="jpeg"):
        self.calls.append((w, fps, q, codec))
        return "portal"

    def stop(self): pass


class RemoteH264Test(unittest.TestCase):
    def make(self):
        cfg = Config(os.path.join(tmp(), "c.json"))
        cfg.data["allow_remote"] = True
        self.sent = []
        return RemoteControl(cfg, stream_factory=FakeStream, uinput=NoUInput(), notify=self.sent.append)

    def test_the_phone_can_ask_for_h264(self):
        r = self.make()
        self.assertEqual(r.handle({"t": "pc_stream_start", "w": 1280, "fps": 60, "q": 60, "codec": "h264"}), {"t": "pc_stream_ok", "engine": "portal", "codec": "h264"})
        self.assertEqual(r.stream.calls, [(1280, 60, 60, "h264")])

    def test_frames_are_labelled(self):
        r = self.make()
        r._frame("QQ==", 3, True)
        r._frame("QQ==", 4)
        self.assertEqual(self.sent[0], {"t": "pc_frame", "codec": "h264", "data": "QQ==", "key": True, "n": 3})
        self.assertEqual(self.sent[1], {"t": "pc_frame", "jpeg": "QQ==", "n": 4})


class HardwareFirstTest(unittest.TestCase):
    def test_a_working_hardware_encoder_beats_the_cpu(self):
        from faze_pc import portal
        have = lambda n: True
        self.assertEqual("nvh264enc", portal.pick_encoder(have, "", lambda n: True))
        self.assertEqual("vah264enc", portal.pick_encoder(have, "", lambda n: n == "vah264enc"))

    def test_a_broken_hardware_encoder_falls_back_to_the_cpu(self):
        from faze_pc import portal
        self.assertEqual("x264enc", portal.pick_encoder(lambda n: True, "", lambda n: False))
        self.assertEqual("x264enc", portal.pick_encoder(lambda n: True, "x264enc", lambda n: True))
        self.assertIsNone(portal.pick_encoder(lambda n: False, "", lambda n: True))

    def test_without_a_probe_the_cpu_stays_first(self):
        from faze_pc import portal
        self.assertEqual("x264enc", portal.pick_encoder(lambda n: True))

    def test_probe_reports_failure(self):
        from faze_pc import portal
        class R:
            returncode = 1
        self.assertFalse(portal.probe("vah264enc", lambda *a, **k: R()))
        def boom(*a, **k):
            raise OSError("no gst")
        self.assertFalse(portal.probe("vah264enc", boom))
