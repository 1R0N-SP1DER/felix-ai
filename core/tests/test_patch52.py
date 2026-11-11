"""Patch 52: switches (mobile data, Wi-Fi, Bluetooth) that can be flipped at a scheduled time with the phone locked."""
import os
import unittest

KT = os.path.join(os.path.dirname(__file__), "..", "..", "android", "app", "src", "main", "java", "com", "felix", "faze")


def read(n):
    with open(os.path.join(KT, n), encoding="utf-8") as f:
        return f.read()


class ScheduledSwitches(unittest.TestCase):
    def test_actions_skip_the_chat_and_the_model(self):
        s = read("ScheduleAndroid.kt")
        body = s[s.index("val direct = due.filter"):][:900]
        self.assertIn("core.runApprovedAction", body)
        self.assertNotIn("core.send", body)

    def test_only_whitelisted_actions_run_from_the_alarm(self):
        c = read("FazeCore.kt")
        self.assertIn("tool !in ScheduledActions.ALLOWED", c)
        self.assertIn("rawTools.execute", c)

    def test_receiver_keeps_the_process_alive_for_the_switches(self):
        s = read("ScheduleAndroid.kt")
        self.assertIn("goAsync()", s)
        self.assertIn("pending.finish()", s)

    def test_root_svc_commands(self):
        s = read("Switches.kt")
        for c in ["svc data", "svc wifi", "svc bluetooth"]:
            self.assertIn(c, s)


if __name__ == "__main__":
    unittest.main()
