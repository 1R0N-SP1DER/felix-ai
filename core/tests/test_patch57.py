"""Patch 57: the live stage (the model draws diagrams/code/demos as HTML in a locked webview)."""
import os
import unittest

KT = os.path.join(os.path.dirname(__file__), "..", "..", "android", "app", "src", "main", "java", "com", "felix", "faze")


def read(n):
    with open(os.path.join(KT, n), encoding="utf-8") as f:
        return f.read()


class Stage(unittest.TestCase):
    def test_webview_is_caged(self):
        s = read("StageView.kt")
        for needle in ["blockNetworkLoads = true", "allowFileAccess = false", "allowContentAccess = false", "shouldOverrideUrlLoading", "MIXED_CONTENT_NEVER_ALLOW"]:
            self.assertIn(needle, s)
        self.assertNotIn("addJavascriptInterface", s)

    def test_live_screen_shows_the_stage_and_it_closes_with_live(self):
        self.assertIn("StageCard(", read("LiveScreen.kt"))
        c = read("FazeCore.kt")
        self.assertLess(c.index("fun closeLiveScreen()"), c.index("stageControl.clear()", c.index("fun closeLiveScreen()")))

    def test_wired_into_tools(self):
        self.assertIn("liveControl, stageControl)", read("FazeCore.kt"))
        self.assertIn('"show_visual" to Tier.SAFE', read("Permissions.kt"))


if __name__ == "__main__":
    unittest.main()
