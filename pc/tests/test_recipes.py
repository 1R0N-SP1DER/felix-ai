import os
import tempfile
import unittest

from faze_pc import recipes as rb
from faze_pc.config import Config
from faze_pc.engine import Engine
from faze_pc.store import Store


def tmp():
    return tempfile.mkdtemp()


class FakeTools:
    def __init__(self, results):
        self.results = results
        self.ran = []

    def execute(self, name, args):
        self.ran.append(name)
        return self.results.get(name, (True, "ok"))


class YesApprover:
    def ask(self, name, text, emit):
        return True

    def deny_all(self):
        pass


def streamer_that_must_not_run(*a, **k):
    raise AssertionError("the model was called")


def make(online=False, provider="ollama", results=None, streamer=streamer_that_must_not_run):
    c = Config(os.path.join(tmp(), "c.json"))
    c.data["provider"] = provider
    tools = FakeTools(results or {})
    e = Engine(c, Store(tmp()), online=lambda: online, streamer=streamer, tools=tools, approver=YesApprover())
    return c, e, tools


OPEN = [{"name": "open_app", "args": {"name": "Firefox"}, "ok": True}]


class BookTest(unittest.TestCase):
    def book(self):
        return rb.RecipeBook(os.path.join(tmp(), "r.json"))

    def test_word_order_and_filler_do_not_change_the_key(self):
        self.assertEqual(rb.key_of("open firefox please"), rb.key_of("please open the firefox"))

    def test_only_repeatable_successful_short_chains_are_learned(self):
        self.assertTrue(rb.can_learn(OPEN))
        self.assertFalse(rb.can_learn([]))
        self.assertFalse(rb.can_learn([{"name": "open_app", "args": {}, "ok": False}]))
        self.assertFalse(rb.can_learn([{"name": "run_command", "args": {}, "ok": True}]))
        self.assertFalse(rb.can_learn(OPEN * 6))

    def test_save_find_hit_replace_fail_remove(self):
        b = self.book()
        self.assertTrue(b.save("open firefox", OPEN, "Opened."))
        r = b.find("firefox open", {"open_app"})
        self.assertTrue(r["silent"])
        self.assertIsNone(b.find("open firefox", {"get_time"}))  # a tool that no longer exists
        b.hit(r["key"])
        self.assertEqual(b.find("open firefox", {"open_app"})["hits"], 1)
        b.save("open firefox", OPEN, "Done.")
        self.assertEqual(b.size(), 1)
        b.fail(r["key"])
        b.fail(r["key"])
        self.assertEqual(b.size(), 0)
        b.save("open firefox", OPEN, "x")
        b.remove("firefox open")
        self.assertEqual(b.size(), 0)


class RateTest(unittest.TestCase):
    def test_up_saves_down_forgets_and_same_tap_undoes(self):
        b = rb.RecipeBook(os.path.join(tmp(), "r.json"))
        msg = {"content": "Opened.", "chain": OPEN, "via": "model"}
        self.assertEqual(rb.rate(msg, "open firefox", 1, b)[0], 1)
        self.assertEqual(b.size(), 1)
        self.assertEqual(rb.rate(dict(msg, feedback=1), "open firefox", 1, b), (0, ""))
        self.assertEqual(rb.rate(dict(msg, via="recipe"), "open firefox", -1, b)[0], -1)
        self.assertEqual(b.size(), 0)

    def test_plain_answers_and_unsafe_chains_save_nothing(self):
        b = rb.RecipeBook(os.path.join(tmp(), "r.json"))
        self.assertIn("nothing to learn", rb.rate({"content": "hi"}, "hi", 1, b)[1])
        risky = {"content": "x", "chain": [{"name": "run_command", "args": {}, "ok": True}], "via": "model"}
        self.assertIn("didn't save", rb.rate(risky, "do it", 1, b)[1])
        self.assertEqual(b.size(), 0)


class EngineRecipeTest(unittest.TestCase):
    def teach(self, e, text="open firefox", chain=OPEN, say="Opened Firefox."):
        e.recipes.save(text, chain, say)

    def test_offline_replay_never_calls_the_model(self):
        c, e, tools = make()
        self.teach(e)
        cid = e.store.new_chat()
        ev = []
        e.chat(cid, "firefox open", ev.append)
        self.assertEqual(tools.ran, ["open_app"])
        self.assertEqual(ev[-1]["text"], "Opened Firefox.")
        self.assertEqual(e.store.history(cid)[-1]["via"], "recipe")
        self.assertEqual(e.recipes.list()[0]["hits"], 1)

    def test_non_silent_steps_return_the_tools_text(self):
        c, e, tools = make(results={"get_time": (True, "10:00")})
        self.teach(e, "what time", [{"name": "get_time", "args": {}, "ok": True}], "old")
        ev = []
        e.chat(e.store.new_chat(), "what time", ev.append)
        self.assertEqual(ev[-1]["text"], "10:00")

    def test_online_ignores_recipes(self):
        calls = []

        def stream(base, key, model, msgs, on_token, abort, **extra):
            calls.append(model)
            on_token("model answer")
            return "model answer"

        c, e, tools = make(online=True, provider="auto", streamer=stream)
        c.data["ollama_chat_model"] = "m"
        self.teach(e)
        ev = []
        e.chat(e.store.new_chat(), "open firefox", ev.append)
        self.assertEqual(tools.ran, [])
        self.assertEqual(calls, ["m"])

    def test_a_later_failure_is_reported_without_the_model(self):
        c, e, tools = make(results={"set_clipboard": (False, "no clipboard")})
        self.teach(e, "do both", OPEN + [{"name": "set_clipboard", "args": {"text": "x"}, "ok": True}], "x")
        ev = []
        e.chat(e.store.new_chat(), "do both", ev.append)
        self.assertIn("set clipboard failed", ev[-1]["text"])

    def test_rating_and_retry_hide_the_old_reply(self):
        calls = []

        def stream(base, key, model, msgs, on_token, abort, **extra):
            calls.append(model)
            on_token("a")
            return "a"

        c, e, tools = make(online=True, provider="auto", streamer=stream)
        c.data["ollama_chat_model"] = "m"
        cid = e.store.new_chat()
        e.chat(cid, "hello there", [].append)
        reply = e.store.history(cid)[-1]
        self.assertEqual(e.rate(cid, reply["id"], -1)[0], -1)
        self.assertEqual(e.store.history(cid)[-1]["feedback"], -1)
        e.chat(cid, "", [].append, retry=True)
        texts = [m["content"] for m in e.store.history(cid)]
        self.assertEqual(texts, ["hello there", "a"])
        self.assertEqual(len(calls), 2)


class StoreTest(unittest.TestCase):
    def test_chain_via_and_rating_survive(self):
        s = Store(tmp())
        cid = s.new_chat()
        s.add(cid, "user", "hi")
        mid = s.add(cid, "assistant", "yo", chain=OPEN, via="model", model="groq")
        s.rate(cid, mid, 1)
        m = s.history(cid)[-1]
        self.assertEqual((m["chain"], m["via"], m["model"], m["feedback"]), (OPEN, "model", "groq", 1))
        s.rate(cid, mid, 0)
        self.assertEqual(s.history(cid)[-1]["feedback"], 0)
        s.drop(cid, mid)
        self.assertEqual(len(s.history(cid)), 1)


if __name__ == "__main__":
    unittest.main()
