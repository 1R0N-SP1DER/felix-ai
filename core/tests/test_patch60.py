"""Patch 60: the pill overlay, spoken replies only for spoken messages, Android recogniser fallback."""
import os
import unittest

ROOT = os.path.join(os.path.dirname(__file__), "..", "..", "android", "app", "src", "main")
KT = os.path.join(ROOT, "java", "com", "felix", "faze")


def read(n):
    with open(os.path.join(KT, n), encoding="utf-8") as f:
        return f.read()


class Overlay(unittest.TestCase):
    def test_pill_pieces(self):
        s = read("Overlay.kt")
        for word in ["GlyphView", "PillState", "dictateSink", "EXTRA_LIVE", "Message FAZE"]:
            self.assertIn(word, s)

    def test_typed_is_not_read_aloud(self):
        c = read("FazeCore.kt")
        self.assertIn("settings.speakReplies || nextAloud", c)
        self.assertIn("fun send(text: String, aloud: Boolean = false)", c)
        self.assertIn("send(text, true)", c)
        self.assertNotIn("core.send(t, true)", read("Overlay.kt"))

    def test_fallback_and_manifest(self):
        self.assertIn("fallBackToAndroid", read("FazeCore.kt"))
        self.assertIn("SpeechRecognizer", read("AndroidListener.kt"))
        with open(os.path.join(ROOT, "AndroidManifest.xml"), encoding="utf-8") as f:
            self.assertIn("android.speech.RecognitionService", f.read())


if __name__ == "__main__":
    unittest.main()
