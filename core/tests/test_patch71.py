"""Patch 71: Auto mode."""
import os
import unittest

KT = os.path.join(os.path.dirname(__file__), "..", "..", "android", "app", "src", "main", "java", "com", "felix", "faze")


def read(n):
    with open(os.path.join(KT, n), encoding="utf-8") as f:
        return f.read()


class Patch71(unittest.TestCase):
    def test_auto_is_a_real_provider(self):
        self.assertIn('it == "auto"', read("Settings.kt"))
        self.assertIn('"auto", "groq"', read("ModelSheet.kt"))
        self.assertIn("planFor", read("Resilient.kt"))
        self.assertIn("autoPlan", read("FazeCore.kt"))

    def test_settings_card(self):
        self.assertIn('SettingsCard("Auto mode")', read("SettingsScreen.kt"))


if __name__ == "__main__":
    unittest.main()
