"""Patch 34: the approval dialog uses the web dialog's words, and the gate behind it is untouched."""
import os
import unittest

ROOT = os.path.join(os.path.dirname(__file__), "..", "..")
KT = os.path.join(ROOT, "android", "app", "src", "main", "java", "com", "felix", "faze")


def read(*parts):
    with open(os.path.join(*parts), encoding="utf-8") as f:
        return f.read()


class ApprovalDialog(unittest.TestCase):
    def test_words_match_the_web_dialog(self):
        html = read(ROOT, "core", "web", "index.html")
        sheet = read(KT, "ApprovalSheet.kt")
        for text in ["Deny", "Approve", "How it works", "Approve and stop asking (yolo mode)"]:
            self.assertIn(text, html)
            self.assertIn(text.upper() if text == "How it works" else text, sheet)

    def test_outside_tap_never_answers_and_back_denies(self):
        sheet = read(KT, "ApprovalSheet.kt")
        self.assertIn("BackHandler(enabled = true) { onDeny() }", sheet)
        self.assertRegex(sheet, r"indication = null\) \{ \}")  # the scrim swallows taps, answers nothing

    def test_gate_and_screen_are_wired(self):
        self.assertIn("GatedTools(Tools(", read(KT, "FazeCore.kt"))
        chat = read(KT, "ChatScreen.kt")
        self.assertIn("ApprovalSheet(it, onAllow", chat)
        self.assertNotIn("ApprovalCard", chat)


if __name__ == "__main__":
    unittest.main()
