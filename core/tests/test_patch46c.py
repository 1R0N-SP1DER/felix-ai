"""Patch 46c: our own voice chat. You talk, Groq Whisper listens, the chat answers aloud; a small fast model covers slow answers."""
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


class VoiceChat(unittest.TestCase):
    def test_the_loop_reuses_the_existing_parts(self):
        core = read(KT, "FazeCore.kt")
        self.assertIn("stt.transcribe(wav)", core)          # Groq Whisper, the same Stt as the mic button
        self.assertIn("engine.stop()", core[core.index("override fun interrupt"):core.index("override fun engineBusy")])
        self.assertIn("speaker.isSpeaking()", core)          # Android's own text to speech
        self.assertIn("AndroidLiveMic(app)", core[core.index("fun startVoiceChat"):])
        loop = read(KT, "VoiceLoop.kt")
        self.assertIn("Endpointer(endSilenceMs = 800)", loop)
        self.assertIn("Wav.fromPcm(pcm, Recorder.RATE)", loop)

    def test_the_loop_is_pure_and_runs_on_frames_alone(self):
        loop = read(KT, "VoiceLoop.kt")
        self.assertNotIn("import android.", loop)
        self.assertNotIn("System.currentTimeMillis", loop)
        self.assertNotIn("Thread.sleep", loop)
        self.assertIn("nowMs += pcm.size / 2 * 1000L / Recorder.RATE", loop)

    def test_replies_are_spoken_while_voice_chat_is_on(self):
        core = read(KT, "FazeCore.kt")
        self.assertIn("override fun speak(): Boolean = settings.speakReplies || voiceActive", core)

    def test_the_wake_word_listener_is_paused_while_voice_chat_is_on(self):
        core = read(KT, "FazeCore.kt")
        self.assertIn("listenControl?.pause()", core[core.index("fun startVoiceChat"):core.index("fun stopVoiceChat")])
        self.assertIn("listenControl?.resume()", core[core.index("private fun voiceEnded"):core.index("private fun sendWhenFree")])

    def test_filler_model_is_groqs_small_production_model_with_a_canned_fallback(self):
        loop = read(KT, "VoiceLoop.kt")
        self.assertIn('DEFAULT_MODEL = "llama-3.1-8b-instant"', loop)
        self.assertIn("?: Fillers.canned(text, 0)", loop)
        self.assertIn("timeoutMs: Long = 1500", loop)
        self.assertIn("Fillers.canned(userText, counter++)", loop)
        st = read(KT, "Settings.kt")
        self.assertIn('prefs.getBoolean("voice_fillers", true)', st)
        self.assertIn('text("filler_model").ifEmpty { ModelFiller.DEFAULT_MODEL }', st)

    def test_the_filler_never_talks_over_a_started_answer(self):
        loop = read(KT, "VoiceLoop.kt")
        self.assertIn("if (!fillersOn() || host.replyStarted() || said >= MAX_FILLERS) return", loop)
        core = read(KT, "FazeCore.kt")
        self.assertIn('it.role == "assistant" && it.text.isNotEmpty()', core)

    def test_no_echo_loop_without_headphones(self):
        loop = read(KT, "VoiceLoop.kt")
        self.assertIn("if (host.bargeIn()) capture(pcm, true)", loop)
        self.assertIn("override fun bargeIn(): Boolean = headphonesConnected(app)", read(KT, "FazeCore.kt"))

    def test_settings_card(self):
        s = read(KT, "SettingsScreen.kt")
        self.assertIn('SettingsCard("Voice chat")', s)
        self.assertIn("core.startVoiceChat()", s)
        self.assertIn("core.stopVoiceChat()", s)
        self.assertIn("core.settings.voiceFillers = it", s)
        self.assertLess(s.index('SettingsCard("Voice chat")'), s.index('SettingsCard("Live voice")'))

    def test_a_message_that_arrives_while_the_chat_is_busy_is_not_lost(self):
        core = read(KT, "FazeCore.kt")
        self.assertIn("if (!engine.isBusy()) send(text) else if (tries > 0) main.postDelayed({ sendWhenFree(text, tries - 1) }, 100)", core)

    def test_kotlin_tests_exist(self):
        t = read(TESTS, "VoiceLoopTest.kt")
        for name in ["aTurnIsHeardTranscribedAndAsked", "roomNoiseAloneIsNeverTranscribed", "aSlowAnswerGetsAFillerThenAnOccasionalNudge",
                     "withHeadphonesTalkingOverTheAnswerInterruptsAndStartsANewTurn", "withoutHeadphonesTheMicIsIgnoredWhileItAnswers",
                     "stoppingWhileTranscribingDropsTheResult", "anyProblemWithTheModelFallsBackToACannedPhrase"]:
            self.assertIn("fun %s" % name, t)

    def test_no_keys_in_the_new_files(self):
        self.assertNotRegex(read(KT, "VoiceLoop.kt"), r"gsk_[A-Za-z0-9]{10,}|AIza[A-Za-z0-9_\-]{20,}")


if __name__ == "__main__":
    unittest.main()
