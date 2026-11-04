"""Patch 35: Gemini and OpenRouter in the app."""
import os
import re
import unittest

ROOT = os.path.join(os.path.dirname(__file__), "..", "..")
KT = os.path.join(ROOT, "android", "app", "src", "main", "java", "com", "felix", "faze")


def read(*parts):
    with open(os.path.join(*parts), encoding="utf-8") as f:
        return f.read()


class Providers(unittest.TestCase):
    def test_gemini_defaults_match_the_python_side(self):
        cfg = read(ROOT, "core", "config_loader.py")
        self.assertIn('"model": "gemini-3.1-flash-lite"', cfg)
        self.assertIn('DEFAULT_MODEL = "gemini-3.1-flash-lite"', read(KT, "Gemini.kt"))
        self.assertIn("https://openrouter.ai/api/v1", read(ROOT, "core", "remote_models.py"))
        self.assertIn('BASE = "https://openrouter.ai/api/v1"', read(KT, "Gemini.kt"))

    def test_keys_travel_in_headers_never_in_urls(self):
        gem = read(KT, "Gemini.kt")
        self.assertIn('mapOf("x-goog-api-key" to apiKey)', gem)
        self.assertNotIn("?key=", gem)

    def test_all_three_providers_are_wired_into_the_core(self):
        core = read(KT, "FazeCore.kt")
        for name in ['"groq" to RemoteClient', '"gemini" to GeminiClient', '"openrouter" to RemoteClient']:
            self.assertIn(name, core)
        self.assertIn("GatedTools(Tools(", core)  # the permission gate still sits in front of every provider

    def test_no_key_like_strings(self):
        pat = re.compile(r"(gsk_[A-Za-z0-9]{20,}|AIza[A-Za-z0-9_-]{30,}|sk-or-[A-Za-z0-9-]{20,})")
        for f in os.listdir(KT):
            if f.endswith(".kt"):
                self.assertIsNone(pat.search(read(KT, f)), f)


if __name__ == "__main__":
    unittest.main()
