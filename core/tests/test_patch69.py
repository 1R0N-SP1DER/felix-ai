"""Patch 69: opening lines saved as sound, played offline."""
import os
import re
import unittest

KT = os.path.join(os.path.dirname(__file__), "..", "..", "android", "app", "src", "main", "java", "com", "felix", "faze")


def read(n):
    with open(os.path.join(KT, n), encoding="utf-8") as f:
        return f.read()


class Patch69(unittest.TestCase):
    def test_lines_count(self):
        g = read("GoogleTakeover.kt")
        block = g[g.index("val LINES"):g.index("fun pick(")]
        self.assertTrue(30 <= len(re.findall(r'^\s+"', block, re.M)) <= 40)

    def test_wiring(self):
        self.assertIn("speakOpening", read("Overlay.kt"))
        self.assertIn("pickOpening", read("FazeAccessibilityService.kt"))
        self.assertIn("downloadLines", read("SettingsScreen.kt"))
        self.assertIn("class VoiceLines", read("VoiceLines.kt"))


if __name__ == "__main__":
    unittest.main()
