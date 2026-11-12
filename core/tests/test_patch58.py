"""Patch 58: phone control that survives the app going to the background, with visible touches."""
import os
import unittest

KT = os.path.join(os.path.dirname(__file__), "..", "..", "android", "app", "src", "main", "java", "com", "felix", "faze")


def read(n):
    with open(os.path.join(KT, n), encoding="utf-8") as f:
        return f.read()


class PhoneControl(unittest.TestCase):
    def test_screen_tools_are_never_answered_from_the_cache(self):
        s = read("Agent.kt")
        self.assertIn("c.name in PermissionPolicy.phoneTools) runIt()", s)
        self.assertIn("Int.MAX_VALUE", s)

    def test_keep_alive_and_background_approval(self):
        c = read("FazeCore.kt")
        self.assertIn("KeepAliveTools(tools)", c)
        self.assertIn("approvalNotice.show(r)", c)
        self.assertIn("stopWorkIfIdle()", c)
        m = open(os.path.join(KT, "..", "..", "..", "..", "AndroidManifest.xml"), encoding="utf-8").read()
        for needle in [".WorkService", "specialUse", ".ApprovalActionReceiver", "FOREGROUND_SERVICE_SPECIAL_USE"]:
            self.assertIn(needle, m)

    def test_touch_markers_follow_the_setting(self):
        self.assertIn("MarkedHands(AndroidHands(app)", read("FazeCore.kt"))
        self.assertIn("FLAG_NOT_TOUCHABLE", read("TouchMarks.kt"))
        self.assertIn("core.settings.showTouches = it", read("SettingsScreen.kt"))
        self.assertIn('"read_screen"', read("LocalLlama.kt"))



class ScheduledTaskNotice(unittest.TestCase):
    def test_a_task_announces_itself_when_it_starts(self):
        s = read("ScheduleAndroid.kt")
        self.assertIn('notify("Running now: ${t.prompt}"', s)
        self.assertIn('notify("Running: ${t.prompt}"', s)
        self.assertIn("scheduled_running", s)
        self.assertLess(s.index('notify("Running now:'), s.index("core.send("))


if __name__ == "__main__":
    unittest.main()
