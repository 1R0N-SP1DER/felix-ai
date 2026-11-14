"""Patch 59: DeepSeek and NVIDIA as built-in providers, each with its own key and model."""
import os
import unittest

KT = os.path.join(os.path.dirname(__file__), "..", "..", "android", "app", "src", "main", "java", "com", "felix", "faze")


def read(n):
    with open(os.path.join(KT, n), encoding="utf-8") as f:
        return f.read()


class Providers(unittest.TestCase):
    def test_registry_and_wiring(self):
        self.assertNotIn("deepseek", read("OpenAiProviders.kt"))
        self.assertIn("https://integrate.api.nvidia.com/v1", read("OpenAiProviders.kt"))
        self.assertIn("OpenAiProviders.all.values.map", read("FazeCore.kt"))
        self.assertIn("in OpenAiProviders.all -> compatKey(provider)", read("Settings.kt"))
        self.assertNotIn('"deepseek"', read("ModelSheet.kt"))

    def test_no_keys_in_the_repo(self):
        for n in ["OpenAiProviders.kt", "Settings.kt", "SettingsScreen.kt"]:
            s = read(n)
            self.assertNotIn("nvapi-", s)
            self.assertNotIn("sk-", s.replace("sk-test", ""))


if __name__ == "__main__":
    unittest.main()
