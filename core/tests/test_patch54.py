"""Patch 54: the offline "null" bug and tools for the offline model."""
import os
import unittest

KT = os.path.join(os.path.dirname(__file__), "..", "..", "android", "app", "src", "main", "java", "com", "felix", "faze")


def read(n):
    with open(os.path.join(KT, n), encoding="utf-8") as f:
        return f.read()


class OfflineFix(unittest.TestCase):
    def test_null_content_is_not_read_as_text(self):
        s = read("Remote.kt")
        self.assertIn('if (delta.isNull("content")) ""', s)
        self.assertNotIn('val piece = delta.optString("content", "")', s)

    def test_server_started_with_jinja_and_tools_offered(self):
        s = read("LocalLlama.kt")
        self.assertIn("--jinja", s)
        self.assertIn("compactTools(tools)", s)
        self.assertIn("noToolResultYet(messages)", s)

    def test_system_prompt_stays_short(self):
        import re
        m = re.search(r'const val SYSTEM = "(.*)"\n', read("LocalLlama.kt"))
        self.assertLess(len(m.group(1)), 300)


if __name__ == "__main__":
    unittest.main()
