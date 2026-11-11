"""Patch 51: camera and screen share in the live bar."""
import os
import unittest

ROOT = os.path.join(os.path.dirname(__file__), "..", "..")
KT = os.path.join(ROOT, "android", "app", "src", "main", "java", "com", "felix", "faze")


def read(*p):
    with open(os.path.join(*p), encoding="utf-8") as f:
        return f.read()


class Share(unittest.TestCase):
    def test_buttons_are_live_not_soon(self):
        s = read(KT, "LiveScreen.kt")
        self.assertNotIn("Ico.CAMERA, soon = true", s)
        self.assertNotIn("Ico.SCREEN, soon = true", s)
        self.assertIn("toggleShare(ShareMode.CAMERA)", s)
        self.assertIn("Manifest.permission.CAMERA", s)

    def test_permissions_and_deps(self):
        self.assertIn("android.permission.CAMERA", read(ROOT, "android", "app", "src", "main", "AndroidManifest.xml"))
        g = read(ROOT, "android", "app", "build.gradle.kts")
        self.assertIn("androidx.camera:camera-lifecycle", g)

    def test_frames_are_throttled_and_stopped_with_live(self):
        c = read(KT, "FazeCore.kt")
        self.assertIn("FrameGate()", c)
        self.assertLess(c.index("private fun liveEnded()"), c.index("stopShare()", c.index("private fun liveEnded()")))

    def test_screen_share_reuses_root_screencap(self):
        self.assertIn("AndroidScreenCapture().jpeg(768)", read(KT, "ShareAndroid.kt"))


if __name__ == "__main__":
    unittest.main()
