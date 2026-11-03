"""Patch 33: the chat thread says what the web page says."""
import os
import re
import unittest

ROOT = os.path.join(os.path.dirname(__file__), "..", "..")
WEB = os.path.join(ROOT, "core", "web")
KT = os.path.join(ROOT, "android", "app", "src", "main", "java", "com", "felix", "faze")


def read(*parts):
    with open(os.path.join(*parts), encoding="utf-8") as f:
        return f.read()


class ThreadMatchesTheWebUI(unittest.TestCase):
    def setUp(self):
        self.js = read(WEB, "app.js")
        self.thread = read(KT, "Thread.kt")
        self.md = read(KT, "Markdown.kt")

    def test_labels(self):
        self.assertIn("<div class=\"who\">YOU</div>", self.js)
        self.assertIn('Who("YOU"', self.thread)
        self.assertIn('Who("FAZE"', self.thread)
        for label in ["Copy", "Listen"]:
            self.assertIn(">%s</button>" % label, self.js)
            self.assertIn('"%s"' % label, self.thread)

    def test_live_bar_words_are_the_same(self):
        for text in ["is thinking", "is working", "Done", "Stopped", "Something went wrong", "is ready"]:
            self.assertIn(text, self.js)
            self.assertIn(text, self.md)

    def test_markdown_rules_are_the_same(self):
        # the three line rules of renderMarkdown, written the same way in Kotlin
        self.assertIn(r"^\s*[*\-\u2022]\s+(.*)$", self.js.replace("\\\\", "\\"))
        self.assertIn(r"^\\s*\\d+[.)]\\s+(.*)$", self.md)
        self.assertIn("#{1,4}", self.js)
        self.assertIn("#{1,4}", self.md)

    def test_no_bubbles_left(self):
        self.assertNotIn("Bubble(", read(KT, "ChatScreen.kt"))


if __name__ == "__main__":
    unittest.main()
