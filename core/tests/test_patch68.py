"""Patch 68: stop cuts the request, Gemini app stays openable, voice failures are logged."""
import os
import unittest

KT = os.path.join(os.path.dirname(__file__), "..", "..", "android", "app", "src", "main", "java", "com", "felix", "faze")


def read(n):
    with open(os.path.join(KT, n), encoding="utf-8") as f:
        return f.read()


class Patch68(unittest.TestCase):
    def test_stop_aborts_the_network(self):
        self.assertIn("abortAll", read("Http.kt"))
        self.assertIn("transport.abortAll()", read("FazeCore.kt"))
        self.assertIn("core.speaking || core.busy", read("ChatChrome.kt"))

    def test_leave_alone_list(self):
        self.assertIn("googleSkip", read("Settings.kt"))
        self.assertIn("Leave these alone", read("SettingsScreen.kt"))

    def test_voice_failure_logged(self):
        self.assertIn("Gemini voice failed", read("FazeCore.kt"))


if __name__ == "__main__":
    unittest.main()
