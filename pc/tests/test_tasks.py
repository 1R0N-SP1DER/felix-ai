import os
import tempfile
import unittest

from faze_pc.config import Config
from faze_pc.daemon import Daemon
from faze_pc.peer import PeerRequests
from faze_pc.store import Store
from faze_pc import tools as tb


def tmp():
    return tempfile.mkdtemp(prefix="faze-test-")


class FakeEngine:
    def __init__(self, store, reply="done it", fail=False):
        self.store, self.reply, self.fail, self.seen = store, reply, fail, []

    def stop(self):
        pass

    def chat(self, chat_id, text, emit):
        self.seen.append(text)
        self.store.add(chat_id, "user", text)
        emit({"t": "approve", "id": "a1", "tool": "run_command", "summary": "Run: ls"})
        emit({"t": "error", "message": "no provider"} if self.fail else {"t": "done", "text": self.reply})


class FakeLinks:
    def __init__(self, state="connected", answer=None):
        self.state, self.answer, self.sent = state, answer or {"t": "phone_task_ok", "note": "started on the phone"}, []

    def list(self):
        return [{"id": "p1", "name": "Pixel", "address": "x", "state": self.state, "error": ""}]

    def request(self, pid, msg, timeout=15):
        self.sent.append(msg)
        return self.answer


class TaskTest(unittest.TestCase):
    def daemon(self, **kw):
        store = Store(tmp())
        d = Daemon(config=Config(os.path.join(tmp(), "c.json")), store=store, engine=FakeEngine(store, **kw), links=FakeLinks())
        d.events = []
        d.listeners.add(d.events.append)
        return d

    def test_a_task_from_the_phone_runs_as_a_chat_and_returns_the_answer(self):
        d = self.daemon()
        r = d.peer.handle("p1", {"t": "pc_task", "task": "open firefox"})
        self.assertEqual((r["t"], r["text"]), ("pc_task_ok", "done it"))
        self.assertEqual(d.engine.seen, ["(From your phone) open firefox"])
        self.assertTrue(any(e["t"] == "approve" for e in d.events))  # the card reaches the open window
        self.assertTrue(any(e["t"] == "state" for e in d.events))

    def test_a_failed_task_comes_back_as_an_error(self):
        r = self.daemon(fail=True).peer.handle("p1", {"t": "pc_task", "task": "x"})
        self.assertEqual((r["t"], r["message"]), ("error", "no provider"))

    def test_the_pc_refuses_a_second_task_while_busy(self):
        d = self.daemon()
        d.chat_lock.acquire()
        r = d.peer.handle("p1", {"t": "pc_task", "task": "x"})
        self.assertEqual(r["t"], "error")
        self.assertIn("busy", r["message"])

    def test_a_task_from_the_phone_cannot_be_handed_straight_back(self):
        d = self.daemon()
        d.in_task = True
        ok, text = d.ask_phone("again")
        self.assertFalse(ok)
        self.assertEqual(d.links.sent, [])

    def test_ask_phone_sends_the_task_to_a_connected_phone(self):
        d = self.daemon()
        self.assertEqual(d.ask_phone("check the battery"), (True, "started on the phone"))
        self.assertEqual(d.links.sent[0], {"t": "phone_task", "task": "check the battery"})
        d.links.state = "offline"
        self.assertEqual(d.ask_phone("x"), (False, "no phone is connected"))

    def test_the_tool_needs_a_task_and_a_phone(self):
        t = tb.Tools(Config(os.path.join(tmp(), "c.json")), Store(tmp()))
        self.assertEqual(t.execute("ask_phone", {"task": "x"})[0], False)
        t.phone = lambda task: (True, "ok " + task)
        self.assertEqual(t.execute("ask_phone", {"task": "x"}), (True, "ok x"))
        self.assertEqual(t.execute("ask_phone", {"task": " "})[0], False)
        self.assertEqual(tb.tier("ask_phone"), tb.ASK)
        self.assertIn("Ask your phone", tb.summary("ask_phone", {"task": "call mum"}))

    def test_unknown_tasks_without_a_runner_are_refused(self):
        self.assertEqual(PeerRequests(Config(os.path.join(tmp(), "c.json"))).handle("p", {"t": "pc_task", "task": "x"})["t"], "error")


if __name__ == "__main__":
    unittest.main()
