import os
import time
import unittest

from faze_pc.config import Config
from faze_pc.daemon import Daemon
from faze_pc.store import Store
from faze_pc import link
from tests.test_tasks import FakeEngine, tmp


class ScreenLinks:
    def __init__(self, frames=3, state="connected"):
        self.frames, self.state, self.sent = frames, state, []

    def list(self):
        return [{"id": "p1", "name": "Pixel", "address": "x", "state": self.state, "error": ""}]

    def request(self, pid, msg, timeout=15):
        self.sent.append(msg)
        if msg["t"] == "screen_get":
            if self.frames <= 0:
                return {"t": "error", "message": "Screen sharing is off on the phone."}
            self.frames -= 1
            return {"t": "screen_ok", "jpeg": "AAAA"}
        return {"t": "screen_done", "note": "ok"}


class MirrorTest(unittest.TestCase):
    def daemon(self, links):
        store = Store(tmp())
        d = Daemon(config=Config(os.path.join(tmp(), "c.json")), store=store, engine=FakeEngine(store), links=links)
        d.mirror_gap = 0.0
        d.events = []
        d.listeners.add(d.events.append)
        return d

    def wait(self, f, secs=3):
        end = time.time() + secs
        while time.time() < end:
            if f():
                return True
            time.sleep(0.02)
        return False

    def test_frames_flow_until_the_phone_says_no(self):
        d = self.daemon(ScreenLinks(frames=3))
        d.handle({"op": "mirror_start"}, lambda e: None)
        self.assertTrue(self.wait(lambda: any(e["t"] == "mirror_error" for e in d.events)))
        self.assertEqual([e["t"] for e in d.events if e["t"] == "frame"], ["frame"] * 3)
        self.assertIn("off on the phone", [e for e in d.events if e["t"] == "mirror_error"][0]["message"])
        self.assertTrue(self.wait(lambda: not d.mirroring))

    def test_stop_ends_the_loop(self):
        d = self.daemon(ScreenLinks(frames=10**6))
        d.mirror_gap = 0.01
        d.handle({"op": "mirror_start"}, lambda e: None)
        self.assertTrue(self.wait(lambda: len(d.events) > 2))
        d.handle({"op": "mirror_stop"}, lambda e: None)
        self.assertTrue(self.wait(lambda: not d.mirroring))

    def test_no_phone_is_an_error_not_a_hang(self):
        d = self.daemon(ScreenLinks(state="offline"))
        d.handle({"op": "mirror_start"}, lambda e: None)
        self.assertTrue(self.wait(lambda: any(e["t"] == "mirror_error" for e in d.events)))

    def test_input_is_forwarded_as_the_phones_messages(self):
        links = ScreenLinks()
        d = self.daemon(links)
        out = []
        d.handle({"op": "mirror_input", "kind": "tap", "x": 0.5, "y": 0.25}, out.append)
        d.handle({"op": "mirror_input", "kind": "swipe", "x1": 0.5, "y1": 0.8, "x2": 0.5, "y2": 0.2}, out.append)
        d.handle({"op": "mirror_input", "kind": "key", "key": "back"}, out.append)
        d.handle({"op": "mirror_input", "kind": "text", "text": "hi"}, out.append)
        self.assertEqual([m["t"] for m in links.sent], ["screen_tap", "screen_swipe", "screen_key", "screen_text"])
        self.assertEqual(links.sent[0]["x"], 0.5)
        self.assertEqual(out, [])

    def test_a_refused_input_and_unknown_input_are_reported(self):
        links = ScreenLinks()
        links.request = lambda pid, msg, timeout=15: {"t": "error", "message": "The phone is locked."}
        d = self.daemon(links)
        out = []
        d.handle({"op": "mirror_input", "kind": "tap", "x": 0.1, "y": 0.1}, out.append)
        d.handle({"op": "mirror_input", "kind": "format"}, out.append)
        self.assertEqual(out[0], {"t": "mirror_error", "message": "The phone is locked."})
        self.assertEqual(out[1]["t"], "mirror_error")


if __name__ == "__main__":
    unittest.main()
