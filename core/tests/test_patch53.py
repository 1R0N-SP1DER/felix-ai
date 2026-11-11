"""Patch 53: Yolo approval mode and Always-on phone control in Settings."""
import os
import unittest

KT = os.path.join(os.path.dirname(__file__), "..", "..", "android", "app", "src", "main", "java", "com", "felix", "faze")


def read(n):
    with open(os.path.join(KT, n), encoding="utf-8") as f:
        return f.read()


class YoloAndPhone(unittest.TestCase):
    def test_settings_are_real_not_soon(self):
        s = read("SettingsScreen.kt")
        self.assertNotIn('soonValues = setOf("yolo", "off")', s)
        self.assertNotIn('soonValues = setOf("ask", "on")', s)
        self.assertIn("core.settings.approvalMode = it", s)
        self.assertIn("core.settings.phoneControl = it", s)

    def test_gate_reads_the_settings(self):
        c = read("FazeCore.kt")
        self.assertIn("approvalMode = { settings.approvalMode }", c)
        self.assertIn("phoneControl = { settings.phoneControl }", c)

    def test_refusal_and_blocked_still_come_first(self):
        p = read("Permissions.kt")
        self.assertLess(p.index('phone == "off"'), p.index('PermissionPolicy.approvalMode(approvalMode()) == "yolo"'))
        self.assertIn("Tier.BLOCK -> ToolResult(false", p)


if __name__ == "__main__":
    unittest.main()
