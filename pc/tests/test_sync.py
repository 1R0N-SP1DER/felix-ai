import os
import tempfile
import unittest

from faze_pc import link
from faze_pc.linkmanager import LinkManager
from faze_pc.store import Store
from faze_pc.sync import Syncer
from tests.phone_sim import PhoneSim
from tests.test_linkmanager import wait


def tmp():
    return tempfile.mkdtemp(prefix="faze-test-")


class FakePhoneHub:
    """The phone's sync answers, written to match LinkSync.kt: merge by (role, text), notes by text, nothing deleted."""

    def __init__(self):
        self.chats = {}   # id -> {"updated": ms, "messages": [(role, text)]}
        self.facts = []
        self.clock = 10_000
        self.saves = 0

    def handlers(self):
        return {"sync_list": self.list, "sync_get": self.get, "sync_put_chat": self.put_chat, "sync_put_facts": self.put_facts}

    def list(self, m):
        return {"t": "sync_list_ok", "now": self.clock, "facts": [{"text": t, "category": "me"} for t in self.facts],
                "chats": [{"id": i, "title": "x", "updated": c["updated"]} for i, c in self.chats.items() if c["updated"] > m["since"]]}

    def get(self, m):
        c = self.chats.get(m["id"])
        if not c:
            return {"t": "sync_chat", "chat": None}
        return {"t": "sync_chat", "chat": {"id": m["id"], "updated": c["updated"], "messages": [{"role": r, "text": t} for r, t in c["messages"]]}}

    def put_chat(self, m):
        c = self.chats.setdefault(m["chat"]["id"], {"updated": 0, "messages": []})
        have = set(c["messages"])
        added = [(x["role"], x["text"]) for x in m["chat"]["messages"] if (x["role"], x["text"]) not in have]
        if added:
            c["messages"] += added
            self.clock += 10
            c["updated"] = self.clock
            self.saves += 1
        return {"t": "ok", "added": len(added)}

    def put_facts(self, m):
        new = [f for f in m["facts"] if f.lower() not in [x.lower() for x in self.facts]]
        self.facts += new
        return {"t": "ok", "added": len(new)}


class SyncTest(unittest.TestCase):
    def setUp(self):
        self.store = Store(tmp())
        self.hub = FakePhoneHub()
        self.sync = Syncer(self.store, os.path.join(tmp(), "sync.json"))
        self.h = self.hub.handlers()

    def run_sync(self):
        return self.sync.run("p1", lambda m: self.h[m["t"]](m))

    def test_both_sides_get_what_the_other_has(self):
        self.hub.chats["c1"] = {"updated": 20_000, "messages": [("user", "from phone"), ("assistant", "ok")]}
        self.hub.facts.append("likes tea")
        cid = self.store.new_chat()
        self.store.add(cid, "user", "from pc")
        self.store.add_fact("works at night")
        got = self.run_sync()
        self.assertEqual((got["chats_in"], got["chats_out"], got["facts_in"], got["facts_out"]), (1, 1, 1, 1))
        self.assertEqual(self.store.chat_lines("c1"), [("user", "from phone"), ("assistant", "ok")])
        self.assertEqual(self.hub.chats[cid]["messages"], [("user", "from pc")])
        self.assertIn("works at night", self.hub.facts)
        self.assertEqual(sorted(f["text"] for f in self.store.facts()), ["likes tea", "works at night"])

    def test_a_second_round_changes_nothing(self):
        self.hub.chats["c1"] = {"updated": 20_000, "messages": [("user", "a")]}
        cid = self.store.new_chat()
        self.store.add(cid, "user", "b")
        self.run_sync()
        saves, lines = self.hub.saves, self.store.chat_lines("c1")
        got = self.run_sync()
        self.assertEqual(got, {"facts_in": 0, "facts_out": 0, "chats_in": 0, "chats_out": 0})
        self.assertEqual((self.hub.saves, self.store.chat_lines("c1")), (saves, lines))

    def test_new_messages_on_both_sides_of_one_chat_merge(self):
        cid = self.store.new_chat()
        self.store.add(cid, "user", "one")
        self.hub.chats[cid] = {"updated": 20_000, "messages": [("user", "one"), ("assistant", "from phone")]}
        self.store.add(cid, "assistant", "from pc")
        self.run_sync()
        self.assertEqual(set(self.store.chat_lines(cid)), {("user", "one"), ("assistant", "from phone"), ("assistant", "from pc")})
        self.assertEqual(set(self.hub.chats[cid]["messages"]), set(self.store.chat_lines(cid)))

    def test_notes_match_ignoring_case_and_spacing(self):
        self.hub.facts.append("Likes   Tea")
        self.store.add_fact("likes tea")
        got = self.run_sync()
        self.assertEqual((got["facts_in"], got["facts_out"]), (0, 0))

    def test_only_new_phone_chats_are_asked_for_next_time(self):
        self.hub.clock = 30_000
        self.hub.chats["c1"] = {"updated": 20_000, "messages": [("user", "a")]}
        self.run_sync()
        asked = []
        orig = self.h["sync_get"]
        self.h["sync_get"] = lambda m: (asked.append(m["id"]), orig(m))[1]
        self.hub.chats["c2"] = {"updated": 40_000, "messages": [("user", "b")]}
        self.hub.clock = 50_000
        self.run_sync()
        self.assertEqual(asked, ["c2"])

    def test_a_chat_id_that_is_not_plain_letters_is_refused(self):
        self.h["sync_get"] = lambda m: {"t": "sync_chat", "chat": {"id": "../x", "messages": [{"role": "user", "text": "hi"}]}}
        self.hub.chats["c1"] = {"updated": 20_000, "messages": []}
        self.run_sync()
        self.assertEqual(os.listdir(os.path.join(self.store.root, "chats")), [])

    def test_the_manager_syncs_after_connecting(self):
        phone = PhoneSim("Pixel")
        hub = FakePhoneHub()
        hub.facts.append("from phone")
        phone.handlers.update({k: v for k, v in hub.handlers().items()})
        devs = link.Devices(os.path.join(tmp(), "d.json"))
        seen = []
        m = LinkManager(devs, interval=0.2, keepalive=5, discover=lambda **k: [], gateway=lambda: None, syncer=self.sync, sync_every=0.3, on_synced=lambda pid, got: seen.append(got))
        m._connect = lambda a, s, pc, port=link.PORT, timeout=8: link.connect("127.0.0.1", s, pc, phone.port, timeout)
        m._pair = lambda a, code, pc, name, port=link.PORT, timeout=8: link.pair("127.0.0.1", code, pc, name, phone.port, timeout)
        try:
            m.pair("127.0.0.1", phone.open_pairing())
            m.start()
            self.assertTrue(wait(lambda: self.store.facts()))
            self.assertEqual(self.store.facts()[0]["text"], "from phone")
            self.assertTrue(wait(lambda: m.list()[0]["synced"].startswith("synced")))
            self.assertTrue(seen)
        finally:
            m.stop()
            phone.close()


if __name__ == "__main__":
    unittest.main()
