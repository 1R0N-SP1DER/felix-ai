"""Patch 70: the overlay over the lock screen, with Faze limited while locked."""
import os
import unittest

BASE = os.path.join(os.path.dirname(__file__), "..", "..", "android", "app", "src", "main")
KT = os.path.join(BASE, "java", "com", "felix", "faze")


def read(*p):
    with open(os.path.join(*p), encoding="utf-8") as f:
        return f.read()


class Patch70(unittest.TestCase):
    def test_activity_declared(self):
        m = read(BASE, "AndroidManifest.xml")
        self.assertIn(".LockOverlayActivity", m)
        self.assertIn('android:showWhenLocked="true"', m)
        self.assertIn("Theme.Faze.Lock", read(BASE, "res", "values", "themes.xml"))

    def test_service_uses_it_when_locked(self):
        s = read(KT, "FazeAccessibilityService.kt")
        self.assertIn("LockOverlayActivity.isLocked", s)
        self.assertIn("openOverlay(", s)

    def test_locked_limits_wired(self):
        self.assertIn("locked = {", read(KT, "FazeCore.kt"))
        self.assertIn("limitWhenLocked", read(KT, "Settings.kt"))
        self.assertIn("Limit Faze on the lock screen", read(KT, "SettingsScreen.kt"))


if __name__ == "__main__":
    unittest.main()
