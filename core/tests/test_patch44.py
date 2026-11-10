"""Patch 44: phone control (tap, long press, swipe, type, keys) through root `input`, behind one approval for two minutes."""
import os
import unittest

ROOT = os.path.join(os.path.dirname(__file__), "..", "..")
KT = os.path.join(ROOT, "android", "app", "src", "main", "java", "com", "felix", "faze")


def read(n):
    with open(os.path.join(KT, n), encoding="utf-8") as f:
        return f.read()


class PhoneControl(unittest.TestCase):
    def test_tools_exist_and_ask(self):
        p = read("Permissions.kt")
        for t in ["tap", "long_press", "swipe", "type_text", "press_key"]:
            self.assertIn('"%s" to Tier.CONFIRM' % t, p)
            self.assertIn('tool("%s"' % t, read("Tools.kt"))

    def test_one_yes_is_a_short_lease_that_stop_revokes(self):
        p = read("Permissions.kt")
        self.assertIn("CONTROL_MINUTES = 2L", p)
        self.assertIn("fun revokeControl()", p)
        c = read("FazeCore.kt")
        self.assertIn("gate.revokeControl()", c[c.index("fun stop()"):][:200])

    def test_commands_are_fixed_shapes_with_no_power_key(self):
        h = read("Hands.kt")
        self.assertIn('"input tap ', h)
        self.assertNotIn("KEYCODE_POWER", h)
        self.assertNotIn("KEYCODE_ENDCALL", h)
        self.assertIn("input keyevent 279", h)
        self.assertIn("MAX_TEXT = 300", h)

    def test_text_with_shell_characters_never_reaches_the_command_line(self):
        h = read("Hands.kt")
        self.assertIn("[A-Za-z0-9 .,@_:/+=!?()#-]*", h)  # no quotes, ; & | $ ` % or newlines
        self.assertIn("InputCommands.isPlain(text)", h)

    def test_control_waits_for_speech(self):
        s = read("SpeechAwareTools.kt")
        for t in ["tap", "swipe", "type_text", "press_key", "long_press"]:
            self.assertIn('"%s"' % t, s)

    def test_wired_into_the_app(self):
        self.assertIn("AndroidHands(app)", read("FazeCore.kt"))


if __name__ == "__main__":
    unittest.main()
