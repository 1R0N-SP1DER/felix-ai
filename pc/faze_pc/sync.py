"""Sync with a paired phone. The phone is the hub and the source of truth; this PC asks what changed, takes what it lacks, and sends the
phone what the phone lacks. Chats merge message by message (same role and text is the same message), notes by their text. Nothing is
deleted by a sync, and only text travels (not attachments or tool steps)."""
import json
import os

from . import config as cfg

MAX_MESSAGES = 200


def norm(text):
    return " ".join(text.lower().split())


class Syncer:
    def __init__(self, store, state_path=None):
        self.store = store
        self.state_path = state_path or os.path.join(cfg.config_dir(), "sync.json")
        try:
            with open(self.state_path, encoding="utf-8") as f:
                self.state = json.load(f)
        except (OSError, ValueError):
            self.state = {}

    def _save(self):
        os.makedirs(os.path.dirname(self.state_path), exist_ok=True)
        with open(self.state_path, "w", encoding="utf-8") as f:
            json.dump(self.state, f)

    def run(self, pid, request):
        """One round with phone `pid`. `request(msg)` sends a message to it and returns the reply. Returns a summary dict."""
        start = self.store.now()
        st = self.state.get(pid, {"phone": 0, "pc": 0})
        listing = request({"t": "sync_list", "since": st["phone"]})
        got = {"facts_in": 0, "facts_out": 0, "chats_in": 0, "chats_out": 0}

        theirs = {norm(f["text"]) for f in listing.get("facts", [])}
        mine = {norm(f["text"]) for f in self.store.facts()}
        for f in listing.get("facts", []):
            if norm(f["text"]) not in mine:
                self.store.add_fact(f["text"])
                mine.add(norm(f["text"]))
                got["facts_in"] += 1
        send = [f["text"] for f in self.store.facts() if norm(f["text"]) not in theirs]
        if send:
            request({"t": "sync_put_facts", "facts": send})
            got["facts_out"] = len(send)

        for c in listing.get("chats", []):
            reply = request({"t": "sync_get", "id": c["id"]})
            chat = reply.get("chat")
            if chat and chat.get("id") and chat["id"].isalnum():
                n = self.store.import_chat(chat["id"], [(m["role"], m["text"]) for m in chat["messages"]], c["updated"] / 1000.0)
                if n:
                    got["chats_in"] += 1

        for cid in self.store.changed_chats(st["pc"]):
            lines = self.store.chat_lines(cid)[-MAX_MESSAGES:]
            if not lines:
                continue
            try:
                request({"t": "sync_put_chat", "chat": {"id": cid, "messages": [{"role": r, "text": t} for r, t in lines]}})
                got["chats_out"] += 1
            except ValueError:
                continue  # too large for one message: skipped, the rest still sync

        self.state[pid] = {"phone": listing.get("now", st["phone"]), "pc": start}
        self._save()
        return got
