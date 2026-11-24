import os
import tempfile
import time
import unittest

from faze_pc import link, providers
from faze_pc.config import Config
from faze_pc.linkmanager import LinkManager
from faze_pc.peer import PeerRequests, clean
from tests.phone_sim import PhoneSim
from tests.test_linkmanager import wait


class PeerTest(unittest.TestCase):
    def cfg(self):
        return Config(os.path.join(tempfile.mkdtemp(prefix="faze-test-"), "c.json"))

    def test_clean_makes_a_plain_conversation(self):
        got = clean([{"role": "system", "content": "be kind"}, {"role": "user", "content": [{"type": "text", "text": "hi"}]},
                     {"role": "assistant", "content": None, "tool_calls": [{"id": "1"}]}, {"role": "tool", "content": "42"}])
        self.assertEqual(got, [{"role": "system", "content": "be kind"}, {"role": "user", "content": "hi"}, {"role": "user", "content": "Tool result: 42"}])

    def test_chat_uses_the_right_ollama_model(self):
        seen = []
        c = self.cfg()
        c.data["ollama_chat_model"], c.data["ollama_deep_model"] = "small", "big"
        p = PeerRequests(c, lambda base, key, model, messages, on_token, **kw: (seen.append((base, key, model)), "answer")[1])
        r = p.handle("x", {"t": "pc_chat", "messages": [{"role": "user", "content": "hi"}], "deep": False})
        self.assertEqual((r["t"], r["content"]), ("pc_chat_ok", "answer"))
        p.handle("x", {"t": "pc_chat", "messages": [{"role": "user", "content": "hi"}], "deep": True})
        self.assertEqual([m for _, _, m in seen], ["small", "big"])
        self.assertTrue(seen[0][0].endswith("/v1"))
        self.assertEqual(seen[0][1], "")

    def test_ollama_down_is_an_error_reply_not_a_crash(self):
        def boom(*a, **k):
            raise providers.ProviderError("network", "refused")
        r = PeerRequests(self.cfg(), boom).handle("x", {"t": "pc_chat", "messages": [{"role": "user", "content": "hi"}]})
        self.assertEqual(r["t"], "error")
        self.assertIn("Ollama", r["message"])

    def test_unknown_requests_and_empty_chats_are_refused(self):
        p = PeerRequests(self.cfg(), lambda *a, **k: "x")
        self.assertEqual(p.handle("x", {"t": "nope"})["t"], "error")
        self.assertEqual(p.handle("x", {"t": "pc_chat", "messages": []})["t"], "error")


class DuplexTest(unittest.TestCase):
    def setUp(self):
        self.phone = PhoneSim("Pixel")
        self.devs = link.Devices(os.path.join(tempfile.mkdtemp(prefix="faze-test-"), "d.json"))
        self.asked = []
        self.m = LinkManager(self.devs, interval=0.2, keepalive=30, discover=lambda **k: [], gateway=lambda: None,
                             on_request=lambda pid, msg: (self.asked.append(msg), {"t": "pc_chat_ok", "content": "from the pc"})[1])
        self.m._connect = lambda a, s, pc, port=link.PORT, timeout=8: link.connect("127.0.0.1", s, pc, self.phone.port, timeout)
        self.m._pair = lambda a, code, pc, name, port=link.PORT, timeout=8: link.pair("127.0.0.1", code, pc, name, self.phone.port, timeout)
        self.pid = self.m.pair("127.0.0.1", self.phone.open_pairing())["id"]
        self.m.start()
        self.assertTrue(wait(lambda: self.m.list()[0]["state"] == "connected" and self.phone.sessions))

    def tearDown(self):
        self.m.stop()
        self.phone.close()

    def test_the_phone_can_ask_the_pc_and_gets_an_answer_with_its_number(self):
        self.phone.sessions[0].send({"t": "pc_chat", "rid": 7, "messages": []})
        self.assertTrue(wait(lambda: 7 in self.phone.replies))
        self.assertEqual(self.phone.replies[7]["content"], "from the pc")
        self.assertEqual(self.asked[0]["t"], "pc_chat")

    def test_two_requests_at_once_each_get_their_own_answer(self):
        import threading
        out = {}
        def go(n):
            out[n] = self.m.request(self.pid, {"t": "ping", "n": n})
        ts = [threading.Thread(target=go, args=(n,)) for n in range(5)]
        [t.start() for t in ts]
        [t.join(5) for t in ts]
        self.assertEqual(len(out), 5)
        self.assertTrue(all(r["t"] == "pong" for r in out.values()))

    def test_a_dropped_connection_fails_waiting_requests_quickly(self):
        self.phone.handlers["slow"] = lambda m: time.sleep(5)
        import threading
        err = []
        def go():
            try:
                self.m.request(self.pid, {"t": "slow"}, timeout=10)
            except link.LinkError as e:
                err.append(str(e))
        t = threading.Thread(target=go)
        t.start()
        time.sleep(0.3)
        for s in list(self.phone.sessions):
            s.close()
        t.join(4)
        self.assertTrue(err and "lost" in err[0])


if __name__ == "__main__":
    unittest.main()


class PhoneUseTest(unittest.TestCase):
    def peer(self, allowed=True, hook=True):
        from faze_pc.config import Config
        cfg = Config(os.path.join(tempfile.mkdtemp(), "c.json"))
        cfg.data["allow_remote"] = allowed
        calls = []

        class R:
            def enabled(self): return allowed

        p = PeerRequests(cfg, remote=R(), phone_use=(lambda pid, what, on: (calls.append((pid, what, on)), {"mic": on, "cam": False, "sound": False})[1]) if hook else None)
        return p, calls

    def test_the_phone_can_ask_for_its_mic(self):
        p, calls = self.peer()
        r = p.handle("ph", {"t": "pc_use_phone", "what": "mic", "on": True})
        self.assertEqual(r, {"t": "pc_use_phone_ok", "mic": True, "cam": False, "sound": False})
        self.assertEqual(calls, [("ph", "mic", True)])

    def test_refused_when_remote_control_is_off(self):
        p, calls = self.peer(allowed=False)
        self.assertEqual(p.handle("ph", {"t": "pc_use_phone", "what": "mic", "on": True})["t"], "error")
        self.assertEqual(calls, [])

    def test_unknown_device_is_an_error(self):
        p, _ = self.peer()
        self.assertEqual(p.handle("ph", {"t": "pc_use_phone", "what": "kettle"})["t"], "error")
