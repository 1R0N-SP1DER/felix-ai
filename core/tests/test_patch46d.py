"""Patch 46d: live UI, composer buttons, foreground service, bubble, speech-aware tools."""
import os
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
KT = os.path.join(ROOT, "android/app/src/main/java/com/felix/faze")


def read(*p):
    with open(os.path.join(*p), encoding="utf-8") as f:
        return f.read()


class LiveUi(unittest.TestCase):
    def test_files_exist(self):
        for n in ["LiveScreen.kt", "LiveService.kt", "Bubble.kt", "Icons.kt", "LiveBars.kt", "SpeechAwareTools.kt"]:
            self.assertTrue(os.path.isfile(os.path.join(KT, n)), n)

    def test_service_is_a_microphone_service(self):
        m = read(ROOT, "android/app/src/main/AndroidManifest.xml")
        self.assertIn('android:name=".LiveService"', m)
        self.assertIn('foregroundServiceType="microphone"', m[m.index("LiveService"):])
        self.assertIn("SYSTEM_ALERT_WINDOW", m)

    def test_service_starts_while_the_app_is_visible(self):
        c = read(KT, "FazeCore.kt")
        self.assertIn("startLiveService()", c[c.index("fun startLive()"):])
        self.assertIn("startLiveService()", c[c.index("fun startVoiceChat()"):])

    def test_bubble_only_when_the_app_is_hidden(self):
        s = read(KT, "LiveService.kt")
        self.assertIn("core.appVisible", s)
        self.assertIn("canShow()", s)
        self.assertIn("TYPE_APPLICATION_OVERLAY", read(KT, "Bubble.kt"))
        m = read(KT, "MainActivity.kt")
        self.assertIn("core.appVisible = true", m)
        self.assertIn("core.appVisible = false", m)

    def test_foreground_tools_wait_for_speech(self):
        s = read(KT, "SpeechAwareTools.kt")
        self.assertIn('"open_app"', s)
        self.assertIn('"open_url"', s)
        c = read(KT, "FazeCore.kt")
        self.assertIn("SpeechAwareTools(tools", c)
        self.assertIn("bubbleReady()", c)

    def test_live_screen_bottom_bar(self):
        s = read(KT, "LiveScreen.kt")
        for ico in ["Ico.CAMERA", "Ico.SCREEN", "Ico.CLOSE"]:
            self.assertIn(ico, s)
        self.assertIn("Ico.MIC_OFF", s)
        self.assertIn("ListeningBar", s)
        self.assertIn("BackHandler", s)
        self.assertIn("LiveScreen(core)", read(KT, "MainActivity.kt"))

    def test_composer_live_button_replaces_send_when_empty(self):
        s = read(KT, "ChatChrome.kt")
        self.assertIn("Ico.WAVE", s)
        self.assertIn("Ico.ARROW_UP", s)
        self.assertIn("onLive", s)
        self.assertIn("onLive = { withMic { core.startLive() } }", read(KT, "ChatScreen.kt"))

    def test_default_live_model_is_2_5(self):
        self.assertIn('DEFAULT_MODEL = "gemini-2.5-flash-native-audio-preview-12-2025"', read(KT, "LiveProtocol.kt"))

    def test_mute_reaches_both_voice_paths(self):
        self.assertIn("!muted", read(KT, "LiveSession.kt"))
        self.assertIn("@Volatile var muted", read(KT, "VoiceLoop.kt"))
        c = read(KT, "FazeCore.kt")
        self.assertIn("fun toggleMute()", c)


if __name__ == "__main__":
    unittest.main()
