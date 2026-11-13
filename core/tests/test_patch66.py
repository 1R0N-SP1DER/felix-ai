"""Patch 66: natural online voice with the phone voice as the offline fallback, voice picker."""
import os
import unittest

KT = os.path.join(os.path.dirname(__file__), "..", "..", "android", "app", "src", "main", "java", "com", "felix", "faze")


def read(n):
    with open(os.path.join(KT, n), encoding="utf-8") as f:
        return f.read()


class Voices(unittest.TestCase):
    def test_core_uses_the_smart_speaker(self):
        c = read("FazeCore.kt")
        self.assertIn("SmartSpeaker(", c)
        self.assertIn("deviceVoices()", c)

    def test_settings_have_real_controls(self):
        s = read("SettingsScreen.kt")
        self.assertIn("Offline voices only", s)
        self.assertNotIn('WideButton("Browse voices", soon = true)', s)
        self.assertIn("TTS_SETTINGS", s)

    def test_gemini_endpoint(self):
        self.assertIn("v1beta/interactions", read("SmartSpeech.kt"))


if __name__ == "__main__":
    unittest.main()
