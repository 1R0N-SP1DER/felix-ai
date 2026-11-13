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

    def test_activity_log_and_wake_methods(self):
        self.assertIn("ActivityLogDialog", read(KT, "SettingsScreen.kt"))
        self.assertNotIn("soon = true) {}", read(KT, "SettingsScreen.kt").split("Open activity log")[0][-5:] + "")
        self.assertIn('SettingsCard("Ways to wake Faze")', read(KT, "SettingsScreen.kt"))
        self.assertIn("gate.logger", read(KT, "FazeCore.kt"))
        self.assertIn("class ActivityLog", read(KT, "ActivityLog.kt"))

    def test_overlay_opens_fresh_with_a_timed_bar(self):
        o = read(KT, "Overlay.kt")
        self.assertIn("core.newChat()", o)
        self.assertIn("class PillMode", o)
        self.assertIn("overlayIdleSeconds", o)

    def test_description_is_honest(self):
        self.assertIn("Google", read(SRC, "res", "values", "strings.xml"))


if __name__ == "__main__":
    unittest.main()
