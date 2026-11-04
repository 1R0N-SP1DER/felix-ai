"""Patch 38: busy providers are retried, fall back with a notice, and OpenRouter picks a free model."""
import os
import unittest

ROOT = os.path.join(os.path.dirname(__file__), "..", "..")
KT = os.path.join(ROOT, "android", "app", "src", "main", "java", "com", "felix", "faze")


def read(*parts):
    with open(os.path.join(*parts), encoding="utf-8") as f:
        return f.read()


class Resilience(unittest.TestCase):
    def test_core_uses_the_resilient_layer_and_says_what_it_did(self):
        core = read(KT, "FazeCore.kt")
        self.assertIn("ResilientChat(", core)
        self.assertNotIn("ProviderChat", core)
        self.assertIn("status = text", core)
        self.assertIn("GatedTools(Tools(", core)  # the permission gate is still in front

    def test_requests_cannot_hang_for_a_minute(self):
        self.assertIn('"application/json", 30000)', read(KT, "Gemini.kt"))

    def test_no_hardcoded_openrouter_model(self):
        self.assertNotIn("gpt-oss-120b:free", read(KT, "Gemini.kt"))
        self.assertNotIn("DEFAULT_MODEL", read(KT, "Gemini.kt").split("object GeminiApi")[0])
        self.assertIn("blank: a free model is picked automatically", read(KT, "Settings.kt"))

    def test_refused_requests_are_never_retried_or_hidden(self):
        src = read(KT, "Resilient.kt")
        self.assertNotIn('"bad_request" ->', src)
        self.assertIn("if (emitted || e.kind !in fallbackKinds) throw e", src)


if __name__ == "__main__":
    unittest.main()
