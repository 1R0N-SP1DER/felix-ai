import os
import tempfile
import threading
import time
import unittest

from faze_pc.config import Config
from faze_pc.daemon import Daemon
from faze_pc.store import Store
from faze_pc.voice import VoiceError
from tests.test_tasks import FakeEngine, FakeLinks, tmp


class FakeVoice:
    """A voice that "hears" each line of `lines` in turn, then silence."""

    def __init__(self, lines=("open firefox",), fail=None):
        self.lines, self.fail, self.said, self.calls = list(lines), fail, [], []
        self.on_result = None

    def start_listening(self, on_level=None, auto=False, on_result=None):
        self.calls.append("start")
        if self.fail == "start":
            raise VoiceError("no recorder found")
        if on_level:
            on_level(0.5)
        text = self.lines.pop(0) if self.lines else ""
        if text == "":
            time.sleep(0.05)
        threading.Thread(target=lambda: on_result(text, None), daemon=True).start()

    def stop_listening(self):
        self.calls.append("stop")

    def cancel_listening(self):
        self.calls.append("cancel")

    def stop_speaking(self):
        self.calls.append("quiet")

    def speak(self, text):
        self.said.append(text)
        if self.fail == "speak":
            raise VoiceError("could not speak")


class VoiceDaemonTest(unittest.TestCase):
    def daemon(self, voice):
        store = Store(tmp())
        d = Daemon(config=Config(os.path.join(tmp(), "c.json")), store=store, engine=FakeEngine(store), links=FakeLinks(), voice=voice)
        self.out = []
        return d

    def test_listening_reports_levels_then_what_was_said(self):
        v = FakeVoice()
        d = self.daemon(v)
        d.handle({"op": "listen", "auto": True}, self.out.append)
        for _ in range(50):
            if any(e["t"] == "heard" for e in self.out):
                break
            time.sleep(0.02)
        kinds = [e["t"] for e in self.out]
        self.assertIn("level", kinds)
        self.assertIn("listening", kinds)
        self.assertEqual([e for e in self.out if e["t"] == "heard"][0]["text"], "open firefox")
        self.assertEqual(v.calls[:2], ["quiet", "start"])  # talking over a spoken reply cuts it off

    def test_voice_chat_listens_answers_speaks_and_listens_again(self):
        v = FakeVoice(lines=("hello", "", "second one"))
        d = self.daemon(v)
        d.listeners.add(self.out.append)
        d.handle({"op": "voice_start"}, lambda e: None)
        for _ in range(100):
            if len(v.said) >= 2:
                break
            time.sleep(0.05)
        d.handle({"op": "voice_stop"}, lambda e: None)
        self.assertEqual(v.said, ["done it", "done it"])
        users = [e["text"] for e in self.out if e["t"] == "voice_user"]
        self.assertEqual(users, ["hello", "second one"])  # silence in between is skipped, not sent
        states = [e["state"] for e in self.out if e["t"] == "voice_state"]
        self.assertEqual(states[:3], ["listening", "thinking", "speaking"])
        self.assertEqual(states[-1], "off")
        self.assertFalse(d.voicing)

    def test_ending_voice_chat_stops_the_mic_and_the_voice(self):
        v = FakeVoice(lines=("",) * 50)
        d = self.daemon(v)
        d.handle({"op": "voice_start"}, lambda e: None)
        time.sleep(0.2)
        d.handle({"op": "voice_stop"}, lambda e: None)
        self.assertIn("cancel", v.calls)
        self.assertIn("quiet", v.calls)

    def test_a_voice_problem_ends_voice_chat_with_a_message(self):
        d = self.daemon(FakeVoice(fail="start"))
        d.listeners.add(self.out.append)
        d.handle({"op": "voice_start"}, lambda e: None)
        for _ in range(50):
            if any(e["t"] == "voice_state" and e["state"] == "off" for e in self.out):
                break
            time.sleep(0.05)
        self.assertTrue(any(e.get("message") == "no recorder found" for e in self.out))

    def test_a_missing_recorder_is_an_error_event(self):
        d = self.daemon(FakeVoice(fail="start"))
        d.handle({"op": "listen"}, self.out.append)
        self.assertEqual(self.out[0], {"t": "error", "message": "no recorder found"})

    def test_replies_are_spoken_only_when_asked_for(self):
        v = FakeVoice()
        d = self.daemon(v)
        d.handle({"op": "chat", "text": "hi"}, self.out.append)
        time.sleep(0.1)
        self.assertEqual(v.said, [])
        d.config.set("speak", "true")
        d.handle({"op": "chat", "text": "hi"}, self.out.append)
        for _ in range(50):
            if v.said:
                break
            time.sleep(0.05)
        self.assertEqual(v.said, ["done it"])

    def test_a_voice_problem_does_not_break_the_chat(self):
        v = FakeVoice(fail="speak")
        d = self.daemon(v)
        d.config.set("speak", "true")
        d.handle({"op": "chat", "text": "hi"}, self.out.append)
        for _ in range(50):
            if any(e.get("message") == "could not speak" for e in self.out):
                break
            time.sleep(0.05)
        self.assertTrue(any(e["t"] == "done" for e in self.out))
        self.assertTrue(any(e.get("message") == "could not speak" for e in self.out))

    def test_stop_also_quietens_the_voice(self):
        v = FakeVoice()
        d = self.daemon(v)
        d.handle({"op": "stop"}, self.out.append)
        self.assertIn("quiet", v.calls)

    def test_speak_setting_accepts_text_from_the_window(self):
        c = Config(os.path.join(tmp(), "c.json"))
        c.set("speak", "true")
        self.assertIs(c.data["speak"], True)
        c.set("speak", "false")
        self.assertIs(c.data["speak"], False)


if __name__ == "__main__":
    unittest.main()
