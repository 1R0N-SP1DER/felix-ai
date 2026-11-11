"""Patch 49: end_live tool, and the volume-key overlay starting voice chat with swipe-up to the app."""
import os
import unittest

ROOT = os.path.join(os.path.dirname(__file__), "..", "..")
KT = os.path.join(ROOT, "android", "app", "src", "main", "java", "com", "felix", "faze")


def read(n):
    with open(os.path.join(KT, n), encoding="utf-8") as f:
        return f.read()


class EndLive(unittest.TestCase):
    def test_tool_only_exists_with_a_control(self):
        s = read("Tools.kt")
        self.assertIn("if (liveControl != null) out.put(tool(\"end_live\"", s)
        self.assertIn('"end_live" -> liveControl', s)
        self.assertIn('"end_live" to Tier.SAFE', read("Permissions.kt"))

    def test_core_closes_after_speech_on_main_thread(self):
        s = read("FazeCore.kt")
        self.assertIn("EndAfterSpeech.run", s)
        self.assertIn("main.post { closeLiveScreen() }", s)
        self.assertLess(s.index("private val liveControl"), s.index("AndroidHands(app), liveControl"))


class OverlayVoice(unittest.TestCase):
    def test_setting_defaults_on(self):
        self.assertIn('getBoolean("chord_voice", true)', read("Settings.kt"))
        self.assertIn("s.chordVoice", read("SettingsScreen.kt"))

    def test_overlay_starts_voice_and_swipes_up(self):
        s = read("Overlay.kt")
        self.assertIn("core.startVoiceChat()", s)
        self.assertIn("FLAG_ACTIVITY_REORDER_TO_FRONT", s)
        self.assertIn("downY - e.rawY > dp(60)", s)
        self.assertIn("core.toggleMute()", s)
        self.assertIn("core.overlayUp = true", s)

    def test_bubble_steps_aside_for_the_overlay(self):
        self.assertIn("core.overlayUp", read("LiveService.kt"))
        self.assertIn("overlayUp ||", read("FazeCore.kt"))


if __name__ == "__main__":
    unittest.main()
