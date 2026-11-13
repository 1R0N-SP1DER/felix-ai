"""Patch 65: Faze takes over when Google's assistant pops up."""
import os
import unittest

SRC = os.path.join(os.path.dirname(__file__), "..", "..", "android", "app", "src", "main")
KT = os.path.join(SRC, "java", "com", "felix", "faze")


def read(*p):
    with open(os.path.join(*p), encoding="utf-8") as f:
        return f.read()


class Takeover(unittest.TestCase):
    def test_service_watches_only_google_windows(self):
        s = read(KT, "FazeAccessibilityService.kt")
        self.assertIn("GoogleTakeover.matches", s)
        self.assertIn("GLOBAL_ACTION_BACK", s)
        self.assertNotIn("force-stop", s)
        self.assertNotIn("rootInActiveWindow", s)

    def test_overlay_morphs_and_settings_exist(self):
        self.assertIn("morphFromGoogle", read(KT, "Overlay.kt"))
        self.assertIn("Take over from Google's assistant", read(KT, "SettingsScreen.kt"))
        self.assertIn("Did you mean me", read(KT, "GoogleTakeover.kt"))

    def test_description_is_honest(self):
        self.assertIn("Google", read(SRC, "res", "values", "strings.xml"))


if __name__ == "__main__":
    unittest.main()
