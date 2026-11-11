"""Patch 55: the camera becomes the live screen's background while shared; taller, livelier voice bar."""
import os
import unittest

KT = os.path.join(os.path.dirname(__file__), "..", "..", "android", "app", "src", "main", "java", "com", "felix", "faze")


def read(n):
    with open(os.path.join(KT, n), encoding="utf-8") as f:
        return f.read()


class CameraBackground(unittest.TestCase):
    def test_preview_replaces_the_logo_while_sharing_camera(self):
        s = read("LiveScreen.kt")
        i = s.index("if (core.shareMode == ShareMode.CAMERA) {")
        self.assertIn("PreviewView", s[i:])
        self.assertLess(s.index("PreviewView(ctx)"), s.index("CoreView(mode, 150.dp)"))
        self.assertIn("showCameraPreview(null)", s)

    def test_preview_never_stops_the_pictures_for_the_model(self):
        s = read("ShareAndroid.kt")
        self.assertIn("fun showPreview", s)
        self.assertIn("the picture for the model matters more", s)

    def test_bar_is_taller_and_livelier(self):
        s = read("LiveScreen.kt")
        self.assertIn("height(76.dp)", s)
        self.assertIn("size.height * 0.88f", s)
        self.assertIn("val quick", read("LiveBars.kt"))


if __name__ == "__main__":
    unittest.main()
