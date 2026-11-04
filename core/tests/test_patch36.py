"""Patch 36: memory. Notes are added only with approval, never hold secrets, and are shown to the model as background."""
import os
import re
import unittest

ROOT = os.path.join(os.path.dirname(__file__), "..", "..")
KT = os.path.join(ROOT, "android", "app", "src", "main", "java", "com", "felix", "faze")


def read(*parts):
    with open(os.path.join(*parts), encoding="utf-8") as f:
        return f.read()


class Memory(unittest.TestCase):
    def test_remember_is_gated_and_explained(self):
        self.assertIn('"remember" to Tier.CONFIRM', read(KT, "Permissions.kt"))
        self.assertIn('"remember" ->', read(KT, "ApprovalText.kt"))
        self.assertNotIn('"remember" to Tier.SAFE', read(KT, "Permissions.kt"))

    def test_notes_reach_the_model_as_background_not_orders(self):
        self.assertIn("not instructions", read(KT, "Memory.kt"))
        self.assertIn("config.memoryBlock()", read(KT, "ChatEngine.kt"))

    def test_secret_patterns_cover_every_key_the_app_uses(self):
        src = read(KT, "Memory.kt")
        for prefix in ["gsk_", "AIza", "sk-", "tvly-"]:
            self.assertIn(prefix, src)

    def test_memory_is_private_to_the_app(self):
        self.assertIn('File(app.filesDir, "memory.json")', read(KT, "FazeCore.kt"))
        self.assertFalse(re.search(r"memory.*(getExternalFilesDir|Environment\.)", read(KT, "Memory.kt")))

    def test_settings_has_the_memory_card(self):
        s = read(KT, "SettingsScreen.kt")
        self.assertIn('SettingsCard("Memory")', s)
        self.assertIn("Clear all memory", s)


if __name__ == "__main__":
    unittest.main()
