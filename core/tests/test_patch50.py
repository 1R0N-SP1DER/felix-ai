"""Patch 50: Settings for offline threads/context and the vision model."""
import os
import unittest

KT = os.path.join(os.path.dirname(__file__), "..", "..", "android", "app", "src", "main", "java", "com", "felix", "faze")


def read(n):
    with open(os.path.join(KT, n), encoding="utf-8") as f:
        return f.read()


class SettingsUi(unittest.TestCase):
    def test_cards_exist_and_write_settings(self):
        s = read("SettingsScreen.kt")
        for needle in ['SettingsCard("Offline model")', 'SettingsCard("Vision model")', "core.settings.offlineThreads = it.toInt()", "core.settings.offlineCtx = it.toInt()", "core.settings.visionModel = it"]:
            self.assertIn(needle, s)

    def test_values_are_clamped_on_the_way_in_and_out(self):
        s = read("Settings.kt")
        self.assertIn("OfflineLimits.threads(value)", s)
        self.assertIn("OfflineLimits.ctx(value)", s)


if __name__ == "__main__":
    unittest.main()
