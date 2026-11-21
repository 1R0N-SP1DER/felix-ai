"""Chats and memory as append-only files, so two devices can be merged later without losing either side's lines."""
import json
import os
import time
import uuid


def _id():
    return uuid.uuid4().hex[:12]


class Store:
    def __init__(self, root, device="pc", now=time.time):
        self.root = root
        self.device = device
        self.now = now
        os.makedirs(os.path.join(root, "chats"), exist_ok=True)

    def _chat_path(self, chat_id):
        if not chat_id or not all(c.isalnum() for c in chat_id):
            raise ValueError("bad chat id")
        return os.path.join(self.root, "chats", chat_id + ".jsonl")

    def _append(self, path, obj):
        with open(path, "a", encoding="utf-8") as f:
            f.write(json.dumps(obj, ensure_ascii=False) + "\n")

    def _read(self, path):
        try:
            with open(path, encoding="utf-8") as f:
                lines = f.read().splitlines()
        except OSError:
            return []
        out = []
        for line in lines:
            try:
                out.append(json.loads(line))
            except ValueError:
                pass  # a half-written line is skipped, never fatal
        return out

    def new_chat(self):
        cid = _id()
        self._append(self._chat_path(cid), {"id": _id(), "t": self.now(), "dev": self.device, "role": "meta", "content": "chat started"})
        return cid

    def add(self, chat_id, role, content, **extra):
        """Appends a message and returns its id. `extra` (chain, via, model for a reply) is stored with it."""
        mid = _id()
        self._append(self._chat_path(chat_id), dict(extra, id=mid, t=self.now(), dev=self.device, role=role, content=content))
        return mid

    def rate(self, chat_id, message_id, value):
        """A thumbs up (1), thumbs down (-1) or nothing (0) on a message. Stored as its own line, so nothing is rewritten."""
        self._append(self._chat_path(chat_id), {"id": _id(), "t": self.now(), "dev": self.device, "role": "rating", "for": message_id, "v": int(value)})

    def drop(self, chat_id, message_id):
        """Hides a message (retry replaces a reply)."""
        self._append(self._chat_path(chat_id), {"id": _id(), "t": self.now(), "dev": self.device, "role": "drop", "for": message_id})

    @staticmethod
    def _live(lines):
        """The user and assistant lines that were not dropped, each with its latest rating as `feedback`."""
        dropped = {m.get("for") for m in lines if m.get("role") == "drop"}
        rated = {}
        for m in lines:
            if m.get("role") == "rating":
                rated[m.get("for")] = m.get("v", 0)
        return [dict(m, feedback=rated.get(m.get("id"), 0)) for m in lines if m.get("role") in ("user", "assistant") and m.get("id") not in dropped]

    def history(self, chat_id, limit=None):
        msgs = self._live(self._read(self._chat_path(chat_id)))
        return msgs[-limit:] if limit else msgs

    def chats(self):
        """Newest first: (id, title, last time)."""
        out = []
        d = os.path.join(self.root, "chats")
        for name in os.listdir(d):
            if not name.endswith(".jsonl"):
                continue
            cid = name[:-6]
            msgs = self._read(os.path.join(d, name))
            real = self._live(msgs)
            if not real:
                continue
            first = next((m["content"] for m in real if m["role"] == "user"), real[0]["content"])
            out.append({"id": cid, "title": first.strip().replace("\n", " ")[:48], "t": real[-1]["t"]})
        return sorted(out, key=lambda c: c["t"], reverse=True)

    # --- sync helpers (see sync.py)
    def chat_lines(self, chat_id):
        return [(m["role"], m["content"]) for m in self.history(chat_id) if m.get("content")]

    def changed_chats(self, since):
        """Ids of chats with a message of our own newer than `since`."""
        out = []
        d = os.path.join(self.root, "chats")
        for name in os.listdir(d):
            if name.endswith(".jsonl"):
                msgs = [m for m in self._live(self._read(os.path.join(d, name))) if m.get("dev") != "sync"]  # lines that came from a sync are already on the phone
                if msgs and max(m.get("t", 0) for m in msgs) > since:
                    out.append(name[:-6])
        return out

    def import_chat(self, chat_id, messages, t):
        """Adds the (role, text) pairs this chat does not have yet, after the ones it has. Returns how many were added."""
        path = self._chat_path(chat_id)
        if not os.path.exists(path):
            self._append(path, {"id": _id(), "t": t, "dev": "sync", "role": "meta", "content": "chat started"})
        have = set(self.chat_lines(chat_id))
        added = 0
        for role, text in messages:
            if role in ("user", "assistant") and text and (role, text) not in have:
                have.add((role, text))
                self._append(path, {"id": _id(), "t": t, "dev": "sync", "role": role, "content": text})
                added += 1
        return added

    def log(self, kind, text):
        self._append(os.path.join(self.root, "activity.jsonl"), {"t": self.now(), "kind": kind, "text": text[:300]})

    def activity(self, limit=60):
        rows = self._read(os.path.join(self.root, "activity.jsonl"))
        return rows[-limit:][::-1]

    # Memory: facts the person asked to be remembered.
    def _facts_path(self):
        return os.path.join(self.root, "facts.jsonl")

    def add_fact(self, text):
        fid = _id()
        self._append(self._facts_path(), {"id": fid, "t": self.now(), "dev": self.device, "text": text.strip()})
        return fid

    def forget(self, fact_id):
        if not any(f["id"] == fact_id for f in self.facts()):
            return False
        self._append(self._facts_path(), {"id": fact_id, "t": self.now(), "dev": self.device, "deleted": True})
        return True

    def facts(self):
        live = {}
        for r in self._read(self._facts_path()):
            if r.get("deleted"):
                live.pop(r["id"], None)
            elif r.get("text"):
                live[r["id"]] = r
        return sorted(live.values(), key=lambda r: r["t"])
