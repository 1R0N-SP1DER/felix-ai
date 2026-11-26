import base64
import time
import unittest

from faze_pc.phonemedia import PhoneMedia


class Pipe:
    def __init__(self):
        self.data = b""
        self.closed = False
        self.stdin = self

    def write(self, d):
        self.data += d

    def flush(self):
        pass

    def close(self):
        self.closed = True

    def terminate(self):
        self.closed = True


class PhoneMediaTest(unittest.TestCase):
    def make(self, have=("pactl", "pacat", "ffmpeg"), loop="/dev/video9", reply=None):
        self.said, self.cmds, self.sent, self.pipes = [], [], [], []
        replies = reply or {}

        def request(pid, msg, timeout):
            self.sent.append(msg["t"])
            return replies.get(msg["t"], {"t": "ok"})

        def runner(argv):
            self.cmds.append(argv)
            return 0, str(len(self.cmds))

        def popen(argv, **kw):
            p = Pipe()
            self.pipes.append((argv, p))
            return p
        return PhoneMedia(request, "ph", lambda t, ok: self.said.append((t, ok)), which=lambda n: n in have, runner=runner, popen=popen, sleep=lambda s: time.sleep(0.001), loopback=lambda: loop)

    def wait(self, cond):
        for _ in range(300):
            if cond():
                return True
            time.sleep(0.01)
        return False

    def test_mic_makes_a_virtual_input_and_feeds_it_then_cleans_up(self):
        pm = self.make(reply={"phone_audio_get": {"t": "phone_audio_ok", "data": base64.b64encode(b"abcd").decode()}})
        pm.mic_on()
        self.assertTrue(pm.mic)
        self.assertTrue(self.wait(lambda: self.pipes[0][1].data.startswith(b"abcd")))
        pm.mic_off()
        self.assertFalse(pm.mic)
        self.assertIn(["pactl", "unload-module", "2"], self.cmds)
        self.assertIn("phone_audio_stop", self.sent)

    def test_mic_says_what_is_missing(self):
        pm = self.make(have=())
        pm.mic_on()
        self.assertFalse(pm.mic)
        self.assertIn("pactl", self.said[-1][0])

    def test_the_phone_refusing_switches_it_off_with_its_reason(self):
        pm = self.make(reply={"phone_audio_get": {"t": "error", "message": "Camera and microphone sharing is off on the phone"}})
        pm.mic_on()
        self.assertTrue(self.wait(lambda: not pm.mic))
        self.assertIn("sharing is off", self.said[-1][0])

    def test_sound_plays_the_phones_audio_and_stops_cleanly(self):
        pm = self.make(reply={"phone_sound_get": {"t": "phone_sound_ok", "data": base64.b64encode(b"wxyz").decode()}})
        pm.sound_on()
        self.assertTrue(pm.sound)
        self.assertTrue(self.wait(lambda: self.pipes[0][1].data.startswith(b"wxyz")))
        self.assertIn("--channels=2", self.pipes[0][0])
        pm.sound_off()
        self.assertFalse(pm.sound)
        self.assertIn("phone_sound_stop", self.sent)

    def test_sound_says_when_the_phone_has_not_allowed_it(self):
        pm = self.make(reply={"phone_sound_get": {"t": "error", "message": "Phone sound is not allowed yet."}})
        pm.sound_on()
        self.assertTrue(self.wait(lambda: not pm.sound))
        self.assertIn("not allowed yet", self.said[-1][0])
        self.assertIn("Phone sound", self.said[-1][0])

    def test_camera_needs_a_virtual_webcam_and_ffmpeg(self):
        pm = self.make(loop=None)
        pm.cam_on()
        self.assertFalse(pm.cam)
        self.assertIn("v4l2loopback", self.said[-1][0])
        pm = self.make(have=("pactl",))
        pm.cam_on()
        self.assertIn("ffmpeg", self.said[-1][0])

    def test_camera_pictures_reach_ffmpeg(self):
        pm = self.make(reply={"phone_cam_get": {"t": "phone_cam_ok", "jpeg": base64.b64encode(b"JPEG").decode()}})
        pm.cam_on("back")
        self.assertTrue(self.wait(lambda: b"JPEG" in self.pipes[0][1].data))
        self.assertEqual("ffmpeg", self.pipes[0][0][0])
        pm.cam_off()
        self.assertIn("phone_cam_stop", self.sent)
