import json
import os
import socket
import tempfile
import threading
import time
import unittest

from faze_pc import auto
from faze_pc.client import Client
from faze_pc.config import Config
from faze_pc.daemon import Daemon
from faze_pc.engine import Engine
from faze_pc.providers import Abort, ProviderError, stream_chat
from faze_pc.store import Store
from tests.fakes import FakeProvider


def tmp():
    return tempfile.mkdtemp(prefix="faze-test-")


class ConfigTest(unittest.TestCase):
    def test_keys_are_saved_privately_and_hidden_in_public(self):
        p = os.path.join(tmp(), "c.json")
        c = Config(p)
        c.set("keys.groq", " gsk_secret ")
        self.assertEqual(oct(os.stat(p).st_mode & 0o777), "0o600")
        again = Config(p)
        self.assertEqual(again.key("groq"), "gsk_secret")
        self.assertEqual(again.public()["keys"]["groq"], True)
        self.assertNotIn("gsk_secret", json.dumps(again.public()))

    def test_unknown_settings_are_refused(self):
        c = Config(os.path.join(tmp(), "c.json"))
        for bad in ("nope", "keys2.x", "models"):
            with self.assertRaises(KeyError):
                c.set(bad, "x")

    def test_keyed_needs_a_key_and_custom_needs_an_address_and_model(self):
        c = Config(os.path.join(tmp(), "c.json"))
        self.assertEqual(c.keyed(), set())
        c.set("keys.gemini", "k")
        c.set("custom_url", "http://x/v1")
        self.assertEqual(c.keyed(), {"gemini"})
        c.set("models.custom", "m")
        c.set("keys.custom", "k")
        self.assertEqual(c.keyed(), {"gemini", "custom"})


class AutoTest(unittest.TestCase):
    def test_kinds(self):
        self.assertEqual(auto.kind_of("hey how's it going"), auto.CASUAL)
        self.assertEqual(auto.kind_of("why does this python function crash"), auto.CODING)
        self.assertEqual(auto.kind_of("compare these two"), auto.DEEP)
        self.assertEqual(auto.kind_of("hi", "max"), auto.DEEP)
        self.assertEqual(auto.kind_of("a" * 600), auto.DEEP)

    def test_online_chat_prefers_groq_code_prefers_gemini_and_resting_goes_last(self):
        keyed = {"groq", "gemini", "openrouter"}
        m = lambda p: "m-" + p
        chat = [p for p, _ in auto.plan(auto.CASUAL, True, keyed, lambda p: False, m, ("q1", "q2"))]
        self.assertEqual(chat, ["groq", "openrouter", "gemini", "ollama", "ollama"])
        code = [p for p, _ in auto.plan(auto.CODING, True, keyed, lambda p: False, m, ("q1", "q2"))]
        self.assertEqual(code[0], "gemini")
        rest = [p for p, _ in auto.plan(auto.CASUAL, True, keyed, lambda p: p == "groq", m, ("q1", "q1"))]
        self.assertEqual(rest, ["openrouter", "gemini", "ollama", "groq"])

    def test_offline_uses_the_local_model_first_and_picks_by_job(self):
        p = auto.plan(auto.CASUAL, False, {"groq"}, lambda p: False, lambda p: "m", ("small", "big"))
        self.assertEqual(p[0], ("ollama", "small"))
        p = auto.plan(auto.DEEP, False, {"groq"}, lambda p: False, lambda p: "m", ("small", "big"))
        self.assertEqual(p[0], ("ollama", "big"))

    def test_limits(self):
        t = [0]
        l = auto.Limits(lambda: t[0])
        l.note("groq", ProviderError("rate_limit", "slow", 5))
        self.assertTrue(l.resting("groq"))
        t[0] = 61
        self.assertFalse(l.resting("groq"))
        l.note("gemini", ProviderError("rate_limit", "You exceeded your current quota"))
        t[0] = 61 + 3000
        self.assertTrue(l.resting("gemini"))
        l.note("nvidia", ProviderError("server", "busy"))
        self.assertFalse(l.resting("nvidia"))


class StoreTest(unittest.TestCase):
    def test_chats_and_memory(self):
        s = Store(tmp())
        cid = s.new_chat()
        self.assertEqual(s.chats(), [])  # an empty chat is not listed
        s.add(cid, "user", "hello there")
        s.add(cid, "assistant", "hi")
        self.assertEqual([m["content"] for m in s.history(cid)], ["hello there", "hi"])
        self.assertEqual(s.chats()[0]["title"], "hello there")
        f = s.add_fact("likes tea")
        self.assertEqual([x["text"] for x in s.facts()], ["likes tea"])
        self.assertTrue(s.forget(f))
        self.assertEqual(s.facts(), [])
        self.assertFalse(s.forget("nope"))

    def test_a_torn_line_is_skipped_and_bad_ids_are_refused(self):
        s = Store(tmp())
        cid = s.new_chat()
        s.add(cid, "user", "ok")
        with open(s._chat_path(cid), "a") as f:
            f.write('{"broken')
        self.assertEqual(len(s.history(cid)), 1)
        with self.assertRaises(ValueError):
            s.history("../etc")


class ProviderTest(unittest.TestCase):
    def test_streams_pieces_and_sends_the_key(self):
        srv = FakeProvider([(200, ["Hel", "lo"])])
        got = []
        out = stream_chat(srv.url + "/v1", "k1", "m", [{"role": "user", "content": "x"}], got.append)
        srv.close()
        self.assertEqual(out, "Hello")
        self.assertEqual(got, ["Hel", "lo"])
        self.assertEqual(srv.requests[0][0], "/v1/chat/completions")
        self.assertEqual(srv.requests[0][1], "Bearer k1")
        self.assertTrue(srv.requests[0][2]["stream"])

    def test_errors_are_classified(self):
        for status, kind in ((429, "rate_limit"), (401, "auth"), (402, "auth"), (503, "server"), (400, "bad_request")):
            srv = FakeProvider([(status, "nope", {"retry-after": "7"})])
            with self.assertRaises(ProviderError) as cm:
                stream_chat(srv.url + "/v1", "k", "m", [], lambda p: None)
            srv.close()
            self.assertEqual(cm.exception.kind, kind)
            if kind == "rate_limit":
                self.assertEqual(cm.exception.retry_after, 7.0)

    def test_unreachable_is_a_network_error(self):
        s = socket.socket()
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]
        s.close()
        with self.assertRaises(ProviderError) as cm:
            stream_chat("http://127.0.0.1:%d/v1" % port, "k", "m", [], lambda p: None)
        self.assertEqual(cm.exception.kind, "network")

    def test_abort_ends_a_request_that_is_waiting(self):
        srv = FakeProvider([(200, ["a", "__HANG__"])])
        abort = Abort()
        res = {}

        def run():
            try:
                stream_chat(srv.url + "/v1", "k", "m", [], lambda p: None, abort, timeout=60)
            except ProviderError as e:
                res["kind"] = e.kind
            res["done"] = time.time()

        t = threading.Thread(target=run)
        t.start()
        time.sleep(0.5)
        began = time.time()
        abort.cancel()
        t.join(5)
        self.assertEqual(res.get("kind"), "cancelled")
        self.assertLess(res["done"] - began, 3)


def make_engine(providers, **cfg):
    c = Config(os.path.join(tmp(), "c.json"))
    for k, v in cfg.items():
        c.data[k] = v
    return c, Engine(c, Store(tmp()), online=lambda: True)


class EngineTest(unittest.TestCase):
    def test_falls_through_a_limit_to_the_next_provider_and_remembers_it(self):
        groq = FakeProvider([(429, "rate limit", {"retry-after": "30"})])
        ollama = FakeProvider([(200, ["from ", "ollama"])])
        c, e = make_engine({})
        c.data["keys"] = {"groq": "gk"}
        c.data["ollama_url"] = ollama.url
        import faze_pc.providers as pv
        pv.BASES["groq"] = groq.url + "/v1"
        events = []
        cid = e.store.new_chat()
        e.chat(cid, "hey there", events.append)
        types = [x["t"] for x in events]
        self.assertEqual([x["provider"] for x in events if x["t"] == "route"], ["groq", "ollama"])
        self.assertEqual(events[-1], {"t": "done", "text": "from ollama"})
        self.assertTrue(e.limits.resting("groq"))
        self.assertEqual([m["content"] for m in e.store.history(cid)], ["hey there", "from ollama"])
        groq.close(); ollama.close()

    def test_memory_commands_and_the_prompt(self):
        c, e = make_engine({})
        cid = e.store.new_chat()
        ev = []
        e.chat(cid, "/remember I take my coffee black", ev.append)
        self.assertEqual(ev[-1]["t"], "done")
        self.assertIn("coffee black", e.system_prompt())
        ev = []
        e.chat(cid, "/facts", ev.append)
        fid = ev[-1]["text"].split()[0]
        e.chat(cid, "/forget " + fid, [].append)
        self.assertNotIn("coffee black", e.system_prompt())

    def test_nothing_set_up_says_so(self):
        c, e = make_engine({})
        c.data["ollama_chat_model"] = ""
        c.data["ollama_deep_model"] = ""
        ev = []
        e.chat(e.store.new_chat(), "hi", ev.append)
        self.assertEqual(ev[-1]["t"], "error")

    def test_a_chosen_provider_goes_first(self):
        c, e = make_engine({})
        c.data["keys"] = {"groq": "g", "gemini": "k"}
        c.data["provider"] = "gemini"
        _, a = e.attempts("hello")
        self.assertEqual(a[0][0], "gemini")
        self.assertEqual([x[0] for x in a].count("gemini"), 1)

    def test_stop_keeps_the_part_that_arrived(self):
        srv = FakeProvider([(200, ["part", "__HANG__"])])
        c, e = make_engine({})
        c.data["ollama_url"] = srv.url
        ev = []
        cid = e.store.new_chat()
        t = threading.Thread(target=e.chat, args=(cid, "hello friend", ev.append))
        t.start()
        time.sleep(0.6)
        e.stop()
        t.join(5)
        self.assertFalse(t.is_alive())
        self.assertEqual(ev[-1], {"t": "done", "text": "part", "stopped": True})
        self.assertEqual(e.store.history(cid)[-1]["content"], "part")
        srv.close()


class DaemonTest(unittest.TestCase):
    def test_chat_over_the_socket_and_stop_while_streaming(self):
        srv = FakeProvider([(200, ["one ", "two"])])
        c = Config(os.path.join(tmp(), "c.json"))
        c.data["ollama_url"] = srv.url
        d = Daemon(c, Store(tmp()), None)
        d.engine = Engine(c, d.store, online=lambda: True)
        path = os.path.join(tmp(), "s.sock")
        threading.Thread(target=d.serve, args=(path,), daemon=True).start()
        for _ in range(50):
            if os.path.exists(path):
                break
            time.sleep(0.05)
        cl = Client(path)
        self.assertEqual(cl.request({"op": "ping"})["t"], "pong")
        cl.send({"op": "chat", "text": "hello friend"})
        text = ""
        for ev in cl.events():
            if ev["t"] == "token":
                text += ev["v"]
            if ev["t"] in ("done", "error"):
                break
        self.assertEqual(text, "one two")
        st = cl.request({"op": "state"})
        self.assertEqual(st["chats"][0]["title"], "hello friend")
        self.assertEqual(st["config"]["keys"]["groq"], False)
        bad = cl.request({"op": "set", "name": "nope", "value": "x"})
        self.assertEqual(bad["t"], "error")
        cl.close()
        d.server.shutdown()
        srv.close()


if __name__ == "__main__":
    unittest.main()
