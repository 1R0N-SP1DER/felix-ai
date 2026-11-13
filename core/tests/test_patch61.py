"""Patch 61: Runware key and picture tool, attachments on messages."""
import os
import unittest

KT = os.path.join(os.path.dirname(__file__), "..", "..", "android", "app", "src", "main", "java", "com", "felix", "faze")


def read(n):
    with open(os.path.join(KT, n), encoding="utf-8") as f:
        return f.read()


class Runware(unittest.TestCase):
    def test_tool_and_settings(self):
        self.assertIn("generate_image", read("Tools.kt"))
        self.assertIn("generate_image", read("Permissions.kt"))
        self.assertIn("runwareKey", read("Settings.kt"))
        self.assertIn("Runware key", read("SettingsScreen.kt"))
        self.assertIn("https://api.runware.ai/v1", read("Attachments.kt"))

    def test_messages_carry_attachments(self):
        self.assertIn("attachments: List<Attachment>", read("ChatEngine.kt"))
        self.assertIn("AttachmentRow", read("Thread.kt"))


if __name__ == "__main__":
    unittest.main()
