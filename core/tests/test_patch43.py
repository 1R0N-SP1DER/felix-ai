"""Patch 43: screen reading (read_screen): root dump of the UI tree, turned into text for the model."""
import os
import unittest

ROOT = os.path.join(os.path.dirname(__file__), "..", "..")
KT = os.path.join(ROOT, "android", "app", "src", "main", "java", "com", "felix", "faze")


def read(n):
    with open(os.path.join(KT, n), encoding="utf-8") as f:
        return f.read()


class ScreenReading(unittest.TestCase):
    def test_tool_is_listed_and_routed(self):
        t = read("Tools.kt")
        self.assertIn('tool("read_screen"', t)
        self.assertIn('"read_screen" -> ok(device.readScreen())', t)
        self.assertIn("fun readScreen(): String", t)

    def test_reading_the_screen_asks_first(self):
        p = read("Permissions.kt")
        self.assertIn('"read_screen" to Tier.CONFIRM', p)
        self.assertIn('"read_screen" ->', p[p.index("fun summary"):])

    def test_device_uses_root_dump_and_cleans_up(self):
        d = read("AndroidDevice.kt")
        self.assertIn("uiautomator dump", d)
        self.assertIn('"su", "-c"', d)
        self.assertIn("rm -f", d)
        self.assertIn("ScreenReader.describe(last, context.packageName)", d)

    def test_parser_is_safe_and_bounded(self):
        s = read("ScreenReader.kt")
        self.assertIn("disallow-doctype-decl", s)
        self.assertIn("MAX_ITEMS", s)
        self.assertIn("MAX_CHARS", s)
        self.assertIn("This is Faze itself", s)

    def test_no_keys_or_network(self):
        s = read("ScreenReader.kt")
        for bad in ["http", "gsk_", "AIza"]:
            self.assertNotIn(bad, s.replace("http://apache.org", ""))


if __name__ == "__main__":
    unittest.main()
