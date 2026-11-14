"""Patch 67: DeepSeek removed, pictures made by Gemini."""
import os
import unittest

KT = os.path.join(os.path.dirname(__file__), "..", "..", "android", "app", "src", "main", "java", "com", "felix", "faze")


def read(n):
    with open(os.path.join(KT, n), encoding="utf-8") as f:
        return f.read()


class Patch67(unittest.TestCase):
    def test_deepseek_gone_from_the_app(self):
        for n in ["OpenAiProviders.kt", "ModelSheet.kt", "FazeCore.kt", "ModelCatalog.kt", "CoreShapes.kt"]:
            self.assertNotIn('"deepseek"', read(n))

    def test_gemini_makes_pictures(self):
        self.assertIn("class GeminiImages", read("Attachments.kt"))
        self.assertNotIn("RunwareImages", read("FazeCore.kt"))
        self.assertNotIn("runwareKey", read("Settings.kt"))


if __name__ == "__main__":
    unittest.main()
