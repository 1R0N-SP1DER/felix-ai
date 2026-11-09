"""Patch 43b: look_at_screen, a screenshot sent to a vision model when the UI tree is not enough."""
import os
import unittest

ROOT = os.path.join(os.path.dirname(__file__), "..", "..")
KT = os.path.join(ROOT, "android", "app", "src", "main", "java", "com", "felix", "faze")


def read(n):
    with open(os.path.join(KT, n), encoding="utf-8") as f:
        return f.read()


class Vision(unittest.TestCase):
    def test_model_is_a_groq_vision_model_on_the_chat_key(self):
        s = read("ScreenEyes.kt")
        self.assertIn('DEFAULT_MODEL = "qwen/qwen3.8-27b"', s)
        self.assertIn("data:image/jpeg;base64,", s)
        c = read("FazeCore.kt")
        self.assertIn("ScreenEyes(RemoteClient(transport, Groq.BASE, { settings.groqKey }", c)

    def test_screenshot_is_shrunk_and_cleaned_up(self):
        s = read("ScreenEyes.kt")
        self.assertIn("1280f", s)
        self.assertIn("CompressFormat.JPEG", s)
        self.assertIn("recycle()", s)

    def test_asks_first_and_is_a_setting(self):
        self.assertIn('"look_at_screen" to Tier.CONFIRM', read("Permissions.kt"))
        self.assertIn("var visionModel", read("Settings.kt"))

    def test_no_keys_in_the_file(self):
        s = read("ScreenEyes.kt")
        for bad in ["gsk_", "AIza"]:
            self.assertNotIn(bad, s)


if __name__ == "__main__":
    unittest.main()
