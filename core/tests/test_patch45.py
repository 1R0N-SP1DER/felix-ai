"""Patch 45: scheduler (tasks that run later or on repeat)."""
import os
import unittest

ROOT = os.path.join(os.path.dirname(__file__), "..", "..")
KT = os.path.join(ROOT, "android", "app", "src", "main", "java", "com", "felix", "faze")


def read(n):
    with open(os.path.join(KT, n), encoding="utf-8") as f:
        return f.read()


class Scheduler(unittest.TestCase):
    def test_tools_and_tiers(self):
        t = read("Tools.kt")
        for n in ["schedule_task", "list_scheduled", "cancel_scheduled"]:
            self.assertIn('"%s"' % n, t)
        p = read("Permissions.kt")
        self.assertIn('"schedule_task" to Tier.CONFIRM', p)
        self.assertIn('"cancel_scheduled" to Tier.SAFE', p)

    def test_manifest_has_receiver_and_permissions(self):
        m = open(os.path.join(ROOT, "android", "app", "src", "main", "AndroidManifest.xml"), encoding="utf-8").read()
        for needle in ["SCHEDULE_EXACT_ALARM", "RECEIVE_BOOT_COMPLETED", ".ScheduleReceiver", "BOOT_COMPLETED", "com.felix.faze.SCHEDULE_DUE"]:
            self.assertIn(needle, m)

    def test_alarm_falls_back_when_exact_is_not_allowed(self):
        s = read("ScheduleAndroid.kt")
        self.assertIn("canScheduleExactAlarms()", s)
        self.assertIn("setAndAllowWhileIdle", s)

    def test_due_tasks_are_taken_before_they_run(self):
        s = read("ScheduleAndroid.kt")
        self.assertLess(s.index("takeDue("), s.index("queue.addAll(due)"))

    def test_settings_lists_and_cancels(self):
        s = read("SettingsScreen.kt")
        self.assertIn("ScheduledTasksCard(core)", s)
        self.assertIn("core.scheduler.cancel(t.id)", s)


if __name__ == "__main__":
    unittest.main()
