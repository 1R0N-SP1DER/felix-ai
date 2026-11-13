"""Patch 64: five effort levels, web pictures not downloaded, viewer, Sources button."""
import os
import unittest

KT = os.path.join(os.path.dirname(__file__), "..", "..", "android", "app", "src", "main", "java", "com", "felix", "faze")


def read(n):
    with open(os.path.join(KT, n), encoding="utf-8") as f:
        return f.read()


class Sources(unittest.TestCase):
    def test_levels(self):
        s = read("ResponseStyle.kt")
        self.assertIn('"low" to "Low", "medium" to "Medium", "high" to "High", "extra" to "Extra", "max" to "Max"', s)
        self.assertIn("Effort.index(effort)", read("ChatChrome.kt"))

    def test_web_pictures_are_not_downloaded(self):
        self.assertNotIn("getBytes(url", read("WebImages.kt"))
        self.assertIn("PictureViewer", read("Thread.kt"))
        self.assertIn("Pictures/Faze", read("AndroidAttachments.kt"))

    def test_sources_button(self):
        t = read("Thread.kt")
        self.assertIn('"Sources" to onSources', t)
        self.assertIn("SourcesDialog", t)
        self.assertIn("SourceTracker.from", read("Agent.kt"))


if __name__ == "__main__":
    unittest.main()
