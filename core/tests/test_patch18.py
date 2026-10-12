"""
Patch 18: connectivity-aware speech recognition (Gemini when online, whisper when offline), speech that starts while
the reply is still being written, and a latency readout for voice exchanges.

    PYTHONPATH=. python3 -m unittest tests.test_patch18 -v
"""

import json
import os
import subprocess
import sys
import threading
import time
import unittest
from unittest import mock

CORE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, CORE)

from tests.test_patch1 import Base  # noqa: E402
from tests.test_patch6 import wav  # noqa: E402
from tests.test_patch16 import VoiceBase, HAVE_JSDOM, run_node  # noqa: E402

import config_loader  # noqa: E402
import net_status  # noqa: E402
import stt  # noqa: E402
import wake  # noqa: E402
from gemini_client import GeminiError  # noqa: E402
from voice_session import SentenceStream, split_speakable  # noqa: E402

CLIP = wake.wav_from_pcm(b"\x01\x02" * 1600)


class Clock:
    def __init__(self):
        self.t = 100.0

    def __call__(self):
        return self.t


# ---------------------------------------------------------------- net_status

class TestNetStatus(unittest.TestCase):
    def setUp(self):
        net_status.reset()
        self.addCleanup(net_status.reset)

    def test_unknown_until_checked(self):
        self.assertIsNone(net_status.last())

    def test_online_answer_is_cached_for_a_while(self):
        clock, calls = Clock(), []

        def probe():
            calls.append(1)
            return True
        self.assertTrue(net_status.online(now=clock, probe_fn=probe))
        clock.t += net_status.ONLINE_TTL - 1
        self.assertTrue(net_status.online(now=clock, probe_fn=probe))
        self.assertEqual(len(calls), 1)
        clock.t += 2
        net_status.online(now=clock, probe_fn=probe)
        self.assertEqual(len(calls), 2)

    def test_offline_is_rechecked_sooner(self):
        clock, calls = Clock(), []
        results = iter([False, True])

        def probe():
            calls.append(1)
            return next(results)
        self.assertFalse(net_status.online(now=clock, probe_fn=probe))
        clock.t += net_status.OFFLINE_TTL - 0.5
        self.assertFalse(net_status.online(now=clock, probe_fn=probe))
        self.assertEqual(len(calls), 1)
        clock.t += 1
        self.assertTrue(net_status.online(now=clock, probe_fn=probe))
        self.assertEqual(net_status.last(), True)

    def test_a_real_failure_is_believed_immediately(self):
        clock = Clock()
        net_status.online(now=clock, probe_fn=lambda: True)
        net_status.mark_offline(now=clock)
        self.assertFalse(net_status.online(now=clock, probe_fn=lambda: self.fail("should not probe while cached")))
        net_status.mark_online(now=clock)
        self.assertTrue(net_status.online(now=clock, probe_fn=lambda: self.fail("should not probe while cached")))

    def test_check_can_be_turned_off(self):
        self.assertTrue(net_status.online({"net": {"check": False}}, probe_fn=lambda: self.fail("must not probe")))

    def test_probe_tries_ip_literals_before_dns(self):
        tried = []

        def connect(addr, timeout):
            tried.append(addr)
            return addr == net_status.PROBES[1]
        self.assertTrue(net_status.probe(connect=connect, resolve=lambda *a: self.fail("no DNS needed")))
        self.assertEqual(tried, list(net_status.PROBES))

    def test_probe_falls_back_to_the_gemini_host_and_can_say_offline(self):
        seen = []
        self.assertTrue(net_status.probe(connect=lambda a, t: False, resolve=lambda h, p, t: seen.append(h) or True))
        self.assertEqual(seen, [net_status.HOST[0]])
        self.assertFalse(net_status.probe(connect=lambda a, t: False, resolve=lambda h, p, t: False))

    def test_settings_exist(self):
        self.assertEqual(config_loader.SETTINGS["stt.engine"], (str, ("auto", "gemini", "whisper")))
        self.assertIn("net.check", config_loader.SETTINGS)
        cfg = config_loader.load_config()
        self.assertEqual((cfg["stt"]["engine"], cfg["net"]["check"]), ("auto", True))


# ---------------------------------------------------------------- transcribe_best

class TestTranscribeBest(unittest.TestCase):
    def setUp(self):
        net_status.reset()
        stt._ONLINE["blocked_until"] = 0.0
        self.addCleanup(net_status.reset)
        self.addCleanup(lambda: stt._ONLINE.update(blocked_until=0.0))
        env = mock.patch.dict(os.environ, {"GEMINI_API_KEY": "AQ.test"})
        env.start()
        self.addCleanup(env.stop)
        os.environ.pop("FAZE_GEMINI_DISABLED", None)
        w = mock.patch("stt.transcribe_wav", return_value="from whisper")
        self.whisper = w.start()
        self.addCleanup(w.stop)
        self.cfg = config_loader.load_config()

    def best(self, **kw):
        return stt.transcribe_best(CLIP, self.cfg, **kw)

    def test_online_uses_gemini_and_skips_whisper(self):
        net_status.mark_online()
        online = mock.Mock(return_value=" [BLANK_AUDIO] hello there ")
        self.assertEqual(self.best(online=online), "hello there")
        online.assert_called_once_with(CLIP)
        self.whisper.assert_not_called()

    def test_offline_goes_straight_to_whisper_without_trying_gemini(self):
        net_status.mark_offline()
        with mock.patch("gemini_client.transcribe_audio", side_effect=AssertionError("must not call Gemini")):
            self.assertEqual(self.best(), "from whisper")
        self.whisper.assert_called_once()

    def test_a_network_error_falls_back_and_remembers_it(self):
        net_status.mark_online()
        online = mock.Mock(side_effect=GeminiError("Network error calling Gemini: timed out"))
        self.assertEqual(self.best(online=online), "from whisper")
        self.assertIs(net_status.last(), False)
        with mock.patch("gemini_client.transcribe_audio", side_effect=AssertionError("cooling down")):
            self.assertEqual(self.best(), "from whisper")  # the next clip does not wait on a dead connection

    def test_quota_pauses_online_for_five_minutes(self):
        net_status.mark_online()
        clock = Clock()
        online = mock.Mock(side_effect=GeminiError("Gemini returned 429: quota"))
        self.assertEqual(self.best(online=online, now=clock), "from whisper")
        online.reset_mock()
        clock.t += 120
        self.assertEqual(self.best(online=online, now=clock), "from whisper")
        online.assert_not_called()
        clock.t += 400
        online.side_effect = None
        online.return_value = "back online"
        self.assertEqual(self.best(online=online, now=clock), "back online")

    def test_engine_whisper_never_goes_online(self):
        self.cfg["stt"]["engine"] = "whisper"
        net_status.mark_online()
        self.assertEqual(self.best(online=mock.Mock(side_effect=AssertionError("no"))), "from whisper")

    def test_engine_gemini_never_falls_back(self):
        self.cfg["stt"]["engine"] = "gemini"
        net_status.mark_offline()
        with self.assertRaises(stt.TranscribeError) as cm:
            self.best()
        self.assertIn("unavailable", str(cm.exception))
        net_status.mark_online()
        with self.assertRaises(stt.TranscribeError) as cm:
            self.best(online=mock.Mock(side_effect=GeminiError("Gemini returned 500")))
        self.assertIn("500", str(cm.exception))
        self.whisper.assert_not_called()

    def test_no_key_means_whisper(self):
        os.environ.pop("GEMINI_API_KEY")
        net_status.mark_online()
        self.assertEqual(self.best(online=mock.Mock(side_effect=AssertionError("no"))), "from whisper")

    def test_bad_audio_is_rejected_before_anything_runs(self):
        with self.assertRaises(stt.TranscribeError):
            stt.transcribe_best(b"not a wav", self.cfg)
        self.whisper.assert_not_called()

    def test_wake_word_also_skips_gemini_when_offline(self):
        net_status.mark_offline()
        with mock.patch("stt.available", return_value=True), \
                mock.patch("gemini_client.transcribe_audio", side_effect=AssertionError("must not call Gemini")):
            self.assertEqual(stt.transcribe_wake(CLIP, self.cfg), "from whisper")

    def test_wake_word_network_error_marks_offline(self):
        net_status.mark_online()
        with mock.patch("stt.available", return_value=True):
            stt.transcribe_wake(CLIP, self.cfg, online=mock.Mock(side_effect=GeminiError("Network error calling Gemini: x")))
        self.assertIs(net_status.last(), False)


class TestOrchestratorMarksOffline(Base):
    def setUp(self):
        super().setUp()
        net_status.reset()
        self.addCleanup(net_status.reset)

    def test_gemini_network_failure_marks_the_connection_down(self):
        c = self.convo()
        c.handle_command("/model gemini")
        net_status.mark_online()
        with mock.patch("orchestrator.ask_gemini", side_effect=GeminiError("Network error calling Gemini: no route")):
            c.send("hello")
        self.assertIs(net_status.last(), False)

    def test_other_gemini_errors_do_not(self):
        c = self.convo()
        c.handle_command("/model gemini")
        net_status.mark_online()
        with mock.patch("orchestrator.ask_gemini", side_effect=GeminiError("Gemini returned 429: quota")):
            c.send("hello")
        self.assertIs(net_status.last(), True)


# ---------------------------------------------------------------- the app's mic upload

from tests.test_patch5 import WebBase  # noqa: E402


class TestAppTranscribeIsOnlineFirst(WebBase):
    def setUp(self):
        super().setUp()
        net_status.reset()
        self.addCleanup(net_status.reset)
        env = mock.patch.dict(os.environ, {"GEMINI_API_KEY": "AQ.test"})
        env.start()
        self.addCleanup(env.stop)
        os.environ.pop("FAZE_GEMINI_DISABLED", None)

    def post(self):
        return self.request("POST", "/api/transcribe", body=wav(), headers={"Content-Type": "audio/wav"})

    def test_online_the_upload_goes_to_gemini(self):
        net_status.mark_online()
        with mock.patch("gemini_client.transcribe_audio", return_value="hello from gemini"), \
                mock.patch("stt.transcribe_wav", side_effect=AssertionError("whisper not needed")):
            resp, body = self.post()
        self.assertEqual((resp.status, json.loads(body)), (200, {"text": "hello from gemini"}))

    def test_offline_the_upload_goes_to_whisper(self):
        net_status.mark_offline()
        with mock.patch("gemini_client.transcribe_audio", side_effect=AssertionError("offline")), \
                mock.patch("stt.transcribe_wav", return_value="from whisper"):
            resp, body = self.post()
        self.assertEqual((resp.status, json.loads(body)), (200, {"text": "from whisper"}))

    def test_status_reports_what_is_known_without_probing(self):
        self.assertIsNone(self.jget("/api/status")[1]["online"])
        net_status.mark_online()
        self.assertIs(self.jget("/api/status")[1]["online"], True)
        net_status.mark_offline()
        self.assertIs(self.jget("/api/status")[1]["online"], False)


# ---------------------------------------------------------------- sentence splitting

class TestSentenceStream(unittest.TestCase):
    def feed_all(self, pieces):
        s, out = SentenceStream(), []
        for p in pieces:
            out.append(s.feed(p))
        return s, out

    def test_sentences_come_out_as_they_complete(self):
        s, out = self.feed_all(["The weather today is", " mild and clear. ", "Tomorrow it will rain", " in the afternoon. ", "Bring"])
        self.assertEqual(out, [[], ["The weather today is mild and clear."], [], ["Tomorrow it will rain in the afternoon."], []])
        self.assertEqual(s.finish(), ["Bring"])

    def test_a_sentence_end_needs_a_space_after_it(self):
        s, out = self.feed_all(["Version 3.5 is out", " and works fine."])
        self.assertEqual(out, [[], []])  # no break inside 3.5, none until a space follows the final full stop
        self.assertEqual(s.finish(), ["Version 3.5 is out and works fine."])

    def test_short_fragments_wait_for_the_next_sentence(self):
        s, out = self.feed_all(["Yes. ", "That is correct and complete. "])
        self.assertEqual(out[0], [])
        self.assertEqual(out[1], ["Yes. That is correct and complete."])

    def test_newlines_end_a_line_even_without_punctuation(self):
        s, out = self.feed_all(["- first item\n", "- second item\n"])
        self.assertEqual(out, [["- first item"], ["- second item"]])

    def test_code_blocks_are_said_once_and_held_while_open(self):
        s, out = self.feed_all(["Here is the fix for you. ", "```py\nprint(1)", "\n``` ", "And that is all of it. "])
        self.assertEqual(out[0], ["Here is the fix for you."])
        self.assertEqual(out[1], [])
        self.assertEqual(out[2], [])
        self.assertTrue(any("Code omitted." in x for x in out[3]))
        self.assertEqual(split_speakable("open ```code never closed", final=True)[0], ["open"])

    def test_reset_forgets_everything(self):
        s, _ = self.feed_all(["Something that will be dropped. "])
        s.reset()
        self.assertEqual(s.feed("A fresh start with a full sentence. "), ["A fresh start with a full sentence."])

    def test_finish_never_repeats(self):
        s, _ = self.feed_all(["One whole sentence right here. ", "tail"])
        self.assertEqual(s.finish(), ["tail"])
        self.assertEqual(s.finish(), [])


# ---------------------------------------------------------------- speech while generating

class GateProc:
    """A speech process that runs until released."""
    def __init__(self, argv):
        self.argv, self.released, self.terminated = argv, threading.Event(), False

    def poll(self):
        if self.terminated:
            return -15
        return 0 if self.released.is_set() else None

    def terminate(self):
        self.terminated = True


class StreamingVoiceBase(VoiceBase):
    def setUp(self):
        super().setUp()
        self.gates = []

        def popen(argv, **kw):
            self.gates.append(GateProc(argv))
            return self.gates[-1]
        self.voice._popen = popen

    def wait_for(self, cond, what, seconds=5):
        end = time.time() + seconds
        while time.time() < end:
            if cond():
                return
            time.sleep(0.02)
        self.fail(f"timed out waiting for {what}; gates={[g.argv for g in self.gates]}")

    def release_all(self):
        for g in self.gates:
            g.released.set()


class TestSpeechWhileGenerating(StreamingVoiceBase):
    def test_first_sentence_is_spoken_before_the_reply_has_finished(self):
        seen = {}
        proceed = threading.Event()

        def send(text):
            self.convo.on_token("text", "The answer is forty two, as expected. ")
            self.wait_for(lambda: self.gates, "first sentence to be spoken")
            seen["spoken_mid_reply"] = self.gates[0].argv[1]
            seen["state_mid_reply"] = self.voice.state()["state"]
            self.gates[0].released.set()
            self.convo.on_token("text", "Ask me anything else.")
            proceed.set()
            return "The answer is forty two, as expected. Ask me anything else."
        with mock.patch.object(self.convo, "send", side_effect=send):
            self.voice.start()
            self.wait_for(proceed.is_set, "reply to continue")
            self.wait_for(lambda: len(self.gates) == 2, "tail to be spoken")
            self.gates[1].released.set()
            done = self.wait_state("done")
        self.assertEqual(seen["spoken_mid_reply"], "The answer is forty two, as expected.")
        self.assertEqual(seen["state_mid_reply"], "speaking")  # while the model is still writing
        self.assertEqual(self.gates[1].argv[1], "Ask me anything else.")
        self.assertEqual(done["reply"], "The answer is forty two, as expected. Ask me anything else.")

    def test_sentences_that_pile_up_are_said_together(self):
        proceed = threading.Event()

        def send(text):
            self.convo.on_token("text", "First sentence is long enough. ")
            self.wait_for(lambda: self.gates, "first utterance")
            self.convo.on_token("text", "Second sentence is also long. Third sentence follows it closely. ")
            time.sleep(0.15)  # the first is still being spoken, so these two wait in the queue
            self.assertEqual(len(self.gates), 1)
            self.gates[0].released.set()
            proceed.set()
            return "First sentence is long enough. Second sentence is also long. Third sentence follows it closely."
        with mock.patch.object(self.convo, "send", side_effect=send):
            self.voice.start()
            self.wait_for(proceed.is_set, "reply to finish")
            self.wait_for(lambda: len(self.gates) == 2, "batched utterance")
            self.gates[1].released.set()
            self.wait_state("done")
        self.assertEqual(self.gates[1].argv[1], "Second sentence is also long. Third sentence follows it closely.")
        self.assertEqual(len(self.gates), 2)

    def test_a_reply_that_never_streamed_is_still_spoken(self):
        with mock.patch.object(self.convo, "send", return_value="Done. The timer is set for five minutes."):
            self.voice.start()
            self.wait_for(lambda: self.gates, "speech")
            self.release_all()
            self.wait_state("done")
        self.assertEqual(self.gates[0].argv[1], "Done. The timer is set for five minutes.")

    def test_text_dropped_by_a_tool_call_is_not_spoken(self):
        def send(text):
            self.convo.on_token("text", "Let me check that for you now. ")
            self.convo.on_token("reset", "")
            self.convo.on_token("text", "It is sunny and warm outside.")
            return "It is sunny and warm outside."
        with mock.patch.object(self.convo, "send", side_effect=send):
            self.voice.start()
            self.wait_for(lambda: self.gates, "speech")
            self.release_all()
            self.wait_state("done")
        spoken = " ".join(g.argv[1] for g in self.gates)
        self.assertIn("sunny", spoken)
        self.assertNotIn("check that", spoken)

    def test_stop_mid_reply_silences_speech_and_ignores_the_rest(self):
        gate = threading.Event()

        def send(text):
            self.convo.on_token("text", "This sentence is being spoken right now. ")
            self.wait_for(lambda: self.gates, "speech")
            gate.set()
            time.sleep(0.4)
            self.convo.on_token("text", "This one arrives after the stop button. ")
            return "late"
        with mock.patch.object(self.convo, "send", side_effect=send), mock.patch.object(self.convo, "cancel"):
            self.voice.start()
            self.wait_for(gate.is_set, "speech to start")
            self.voice.stop()
            time.sleep(0.6)
        self.assertTrue(self.gates[0].terminated)
        self.assertEqual(len(self.gates), 1)  # nothing said after the stop
        self.assertEqual(self.voice.state()["state"], "idle")
        self.assertEqual(self.listener.holds, [True, False])

    def test_speaking_off_never_starts_a_speaker(self):
        self.convo.config["voice"]["speak"] = False
        with mock.patch.object(self.convo, "send", side_effect=lambda t: (self.convo.on_token("text", "Quiet reply that is plain. "), "Quiet reply that is plain.")[1]):
            self.voice.start()
            self.wait_state("done")
        self.assertEqual(self.gates, [])

    def test_timings_are_recorded_and_logged(self):
        with mock.patch.object(self.convo, "send", side_effect=lambda t: (self.convo.on_token("text", "Four thirty."), "Four thirty.")[1]):
            self.voice.start()
            self.wait_for(lambda: self.gates, "speech")
            self.release_all()
            done = self.wait_state("done")
        t = done["timings"]
        for key in ("listen_ms", "stt_ms", "first_token_ms", "first_speech_ms", "total_ms"):
            self.assertIsInstance(t[key], int, key)
            self.assertGreaterEqual(t[key], 0, key)
        self.assertGreaterEqual(t["total_ms"], t["first_speech_ms"])

    def test_a_new_exchange_starts_with_empty_timings(self):
        with mock.patch.object(self.convo, "send", return_value="Quick reply that is plain."):
            self.voice.start()
            self.wait_for(lambda: self.gates, "speech")
            self.release_all()
            self.wait_state("done")
        self.listener.pcm = None
        self.voice.start()
        self.assertEqual(self.voice.state()["timings"], {})


class TestVoiceUsesOnlineFirstStt(StreamingVoiceBase):
    def test_voice_exchange_transcribes_with_transcribe_best(self):
        with mock.patch("stt.transcribe_best", return_value="what time is it") as best, \
                mock.patch.object(self.convo, "send", return_value="Half past four, if you must know.") as send:
            self.voice.start()
            self.wait_for(lambda: self.gates, "speech")
            self.release_all()
            self.wait_state("done")
        best.assert_called_once()
        send.assert_called_once_with("what time is it")


@unittest.skipUnless(HAVE_JSDOM, "jsdom not installed")
class TestOverlayShowsTimings(unittest.TestCase):
    def test_done_note_lists_the_timings(self):
        r = run_node("overlay_smoke.js")
        self.assertNotIn("crash", r, r.get("crash"))
        self.assertEqual(r["errors"], [])
        self.assertEqual(r["doneTimings"]["note"], "heard 1.2s | transcribed 0.8s | first word 1.5s | total 4.0s")


if __name__ == "__main__":
    unittest.main()
