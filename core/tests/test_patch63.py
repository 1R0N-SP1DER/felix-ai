"""Patch 63: effort level, response length slider, web pictures, faster listening bar."""
import os
import unittest

KT = os.path.join(os.path.dirname(__file__), "..", "..", "android", "app", "src", "main", "java", "com", "felix", "faze")


def read(n):
    with open(os.path.join(KT, n), encoding="utf-8") as f:
        return f.read()


class Style(unittest.TestCase):
    def test_sheet_has_effort_and_slider(self):
        s = read("ChatChrome.kt")
        self.assertIn('SheetHeading("Effort level")', s)
        self.assertIn('SheetHeading("Response length")', s)
        self.assertIn("Slider(", s)

    def test_engine_uses_them(self):
        s = read("ChatEngine.kt")
        for w in ["Effort.directive", "ResponseLength.directive", "ReplyLimit.tokens", "agent.review"]:
            self.assertIn(w, s)

    def test_find_images_tool(self):
        self.assertIn("find_images", read("Tools.kt"))
        self.assertIn("find_images", read("Permissions.kt"))
        self.assertIn("getBytes", read("Http.kt"))

    def test_bar_reacts_to_sound(self):
        self.assertIn("PillState.heard", read("Overlay.kt"))


if __name__ == "__main__":
    unittest.main()
