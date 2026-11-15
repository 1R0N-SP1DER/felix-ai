import os
import tempfile
import time
import unittest

from faze_pc.config import Config
from faze_pc.daemon import Daemon
from faze_pc.store import Store
from faze_pc.voice import VoiceError
from tests.test_tasks import FakeEngine, FakeLinks, tmp


class FakeVoice:
    def __init__(self, heard="open firefox", fail=None):
        self.heard, self.fail, self.said, self.calls = heard, fail, [], []

    def start_listening(self):
        self.calls.append("start")
        if self.fail == "start":
            raise VoiceError("no recorder found")

    def stop_listening(self):
        self.calls.append("stop")
        return self.heard

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

    def test_listen_then_stop_returns_what_was_said(self):
        v = FakeVoice()
        d = self.daemon(v)
        d.handle({"op": "listen"}, self.out.append)
        d.handle({"op": "listen_stop"}, self.out.append)
        self.assertEqual([e["t"] for e in self.out], ["listening", "heard"])
        self.assertEqual(self.out[1]["text"], "open firefox")
        self.assertEqual(v.calls, ["quiet", "start", "stop"])  # talking over a spoken reply cuts it off

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
