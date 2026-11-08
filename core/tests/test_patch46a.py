"""Patch 46a: live voice. A spoken conversation with Gemini Live (websocket, mic up, voice down, tools through the approval gate)."""
import os
import re
import unittest

ROOT = os.path.join(os.path.dirname(__file__), "..", "..")
APP = os.path.join(ROOT, "android", "app")
KT = os.path.join(APP, "src", "main", "java", "com", "felix", "faze")
TESTS = os.path.join(APP, "src", "test", "java", "com", "felix", "faze")


def read(*parts):
    with open(os.path.join(*parts), encoding="utf-8") as f:
        return f.read()


class LiveVoice(unittest.TestCase):
    def test_protocol_matches_the_python_reference(self):
        k, p = read(KT, "LiveProtocol.kt"), read(ROOT, "core", "live_client.py")
        self.assertIn("setupComplete", read(KT, "LiveClient.kt"))
        self.assertIn("setupComplete", p)
        for needle in ["BidiGenerateContent", "audio/pcm;rate=", "inputAudioTranscription", "outputAudioTranscription",
                       "audioStreamEnd", "functionResponses", "goAway", "serverContent", "toolCall"]:
            self.assertIn(needle, k)
            self.assertIn(needle, p)
        self.assertIn("INPUT_RATE = 16000", k)
        self.assertIn("OUTPUT_RATE = 24000", k)
        self.assertIn("INPUT_RATE = 16000", p)
        self.assertIn("OUTPUT_RATE = 24000", p)
        # the same retry rule and the same four setup attempts
        self.assertIn("unknown name|invalid json|invalid argument|cannot find field|unsupported|not supported", k)
        self.assertIn('"generation" to true, "flat" to true, "generation" to false, "flat" to false', k)
        self.assertIn('DEFAULT_MODEL = "gemini-2.5-flash-native-audio-preview-12-2025"', k)  # the one that works on a free key

    def test_the_key_is_scrubbed_from_errors(self):
        c = read(KT, "LiveClient.kt")
        self.assertGreaterEqual(c.count("LiveProtocol.scrub("), 3)
        self.assertNotRegex(read(KT, "LiveProtocol.kt"), r"AIza[A-Za-z0-9_\-]{20,}")

    def test_everything_the_phone_does_is_behind_an_interface(self):
        self.assertIn("interface LiveSocket", read(KT, "LiveClient.kt"))
        s = read(KT, "LiveSession.kt")
        self.assertIn("interface LiveAudioIn", s)
        self.assertIn("interface LiveAudioOut", s)
        for pure in ["LiveProtocol.kt", "LiveClient.kt", "LiveSession.kt"]:
            self.assertNotIn("import android.", read(KT, pure))

    def test_tool_calls_go_through_the_same_gate_as_chat(self):
        core = read(KT, "FazeCore.kt")
        self.assertIn("gatedTools = tools", core)
        self.assertRegex(core, r"LiveSession\(\s*OkHttpLiveConnector\(\)[^)]*\n[^\n]*\n\s*liveTools,")
        self.assertIn("tools.execute(c.name, c.args)", read(KT, "LiveSession.kt"))

    def test_wake_word_listener_is_paused_while_live(self):
        core = read(KT, "FazeCore.kt")
        live = core[core.index("fun startLive()"):]
        live = live[:live.index("\n    fun ", 10)]
        self.assertIn("listenControl?.pause()", live)
        self.assertIn("listenControl?.resume()", core[core.index("private fun liveEnded"):][:600])

    def test_no_echo_loop_without_headphones(self):
        s = read(KT, "LiveSession.kt")
        self.assertIn("speaking && halfDuplex()", s)
        self.assertIn("{ !headphonesConnected(app) }", read(KT, "FazeCore.kt"))
        a = read(KT, "LiveAndroid.kt")
        self.assertIn("AudioSource.VOICE_COMMUNICATION", a)
        self.assertIn("AcousticEchoCanceler", a)

    def test_audio_formats(self):
        a = read(KT, "LiveAndroid.kt")
        self.assertIn("LiveProtocol.INPUT_RATE", a)
        self.assertIn("LiveProtocol.OUTPUT_RATE", a)
        self.assertIn("ENCODING_PCM_16BIT", a)
        self.assertIn("Android.RECORD_AUDIO".replace("Android.", "Manifest.permission."), a)
        self.assertIn("android.permission.RECORD_AUDIO", read(APP, "src", "main", "AndroidManifest.xml"))

    def test_websocket_dependency_and_ipv4_first(self):
        self.assertIn('com.squareup.okhttp3:okhttp:4.12.0', read(APP, "build.gradle.kts"))
        self.assertIn("sortedBy { it is Inet6Address }", read(KT, "LiveAndroid.kt"))

    def test_the_live_model_can_be_picked_and_the_default_has_a_free_tier(self):
        k = read(KT, "LiveProtocol.kt")
        self.assertIn('DEFAULT_MODEL to "2.5 Audio"', k)
        self.assertIn('"gemini-3.1-flash-live-preview" to "3.1 Live"', k)
        self.assertIn('"gemini-3.8-live" to "3.8 Live"', k)
        s = read(KT, "SettingsScreen.kt")
        self.assertIn("Seg(LiveProtocol.MODELS, liveModel", s)
        self.assertIn("core.settings.liveModel = it", s)

    def test_settings_card_and_voices(self):
        s = read(KT, "SettingsScreen.kt")
        self.assertIn('SettingsCard("Live voice")', s)
        self.assertIn("core.startLive()", read(KT, "ChatScreen.kt"))  # started from the chat, not from Settings
        self.assertNotIn("core.startLive()", s)
        st = read(KT, "Settings.kt")
        self.assertIn('text("live_model").ifEmpty { LiveProtocol.DEFAULT_MODEL }', st)
        self.assertIn("LiveProtocol.VOICES", st)

    def test_the_gemini_wire_shares_one_declarations_function(self):
        g = read(KT, "Gemini.kt")
        self.assertEqual(1, g.count("fun declarations("))
        self.assertIn("declarations(tools)", g)

    def test_kotlin_tests_exist(self):
        t = read(TESTS, "LiveTest.kt")
        for name in ["triesTheFlatShapeWhenTheFirstIsRefusedByName", "toolCallsRunThroughTheGateAndAllAnswersGoBackTogether",
                     "withoutHeadphonesTheMicIsMutedWhileTheModelSpeaks", "hangingUpYourselfIsNotAnErrorEvent", "aMicFailureClosesTheConversation"]:
            self.assertIn("fun %s" % name, t)


if __name__ == "__main__":
    unittest.main()
