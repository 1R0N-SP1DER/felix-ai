"""Patch 62: + menu (Photos, Camera, Files) and real attachments."""
import os
import unittest

SRC = os.path.join(os.path.dirname(__file__), "..", "..", "android", "app", "src", "main")
KT = os.path.join(SRC, "java", "com", "felix", "faze")


def read(*p):
    with open(os.path.join(*p), encoding="utf-8") as f:
        return f.read()


class Attach(unittest.TestCase):
    def test_menu_has_three_items(self):
        s = read(KT, "ChatChrome.kt")
        for w in ['MenuItem("Photos")', 'MenuItem("Camera")', 'MenuItem("Files")']:
            self.assertIn(w, s)
        self.assertNotIn('MenuItem("Activity log"', s)

    def test_pickers_and_provider(self):
        s = read(KT, "ChatScreen.kt")
        for w in ["PickVisualMedia", "OpenDocument", "TakePicture", "Manifest.permission.CAMERA"]:
            self.assertIn(w, s)
        self.assertIn("FileProvider", read(SRC, "AndroidManifest.xml"))
        self.assertTrue(os.path.exists(os.path.join(SRC, "res", "xml", "file_paths.xml")))

    def test_send_goes_through_attachments(self):
        self.assertIn("sendWithAttachments", read(KT, "ChatScreen.kt"))
        self.assertIn("AttachmentReader", read(KT, "FazeCore.kt"))


if __name__ == "__main__":
    unittest.main()
