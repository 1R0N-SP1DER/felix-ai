"""Patch 56: a custom OpenAI-style provider (NVIDIA, DeepSeek, OpenAI, a local server...)."""
import os
import unittest

KT = os.path.join(os.path.dirname(__file__), "..", "..", "android", "app", "src", "main", "java", "com", "felix", "faze")


def read(n):
    with open(os.path.join(KT, n), encoding="utf-8") as f:
        return f.read()


class Custom(unittest.TestCase):
    def test_wired_everywhere_a_provider_is(self):
        self.assertIn('"custom" to CustomChat(', read("FazeCore.kt"))
        self.assertIn('"custom" -> customModel', read("Settings.kt"))
        self.assertIn('"custom" to "Custom"', read("ModelSheet.kt"))
        self.assertIn('"custom"', read("Resilient.kt"))

    def test_key_is_optional_but_plain_http_is_loopback_only(self):
        self.assertIn("keyRequired = false", read("Custom.kt"))
        self.assertIn('host == "127.0.0.1" || host == "localhost"', read("Custom.kt"))

    def test_no_key_in_the_repo(self):
        for n in ["Custom.kt", "Settings.kt"]:
            self.assertNotIn("nvapi-", read(n))


if __name__ == "__main__":
    unittest.main()
