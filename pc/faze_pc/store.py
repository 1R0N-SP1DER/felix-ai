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

    def add(self, chat_id, role, content):
        self._append(self._chat_path(chat_id), {"id": _id(), "t": self.now(), "dev": self.device, "role": role, "content": content})

    def history(self, chat_id, limit=None):
        msgs = [m for m in self._read(self._chat_path(chat_id)) if m.get("role") in ("user", "assistant")]
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
            real = [m for m in msgs if m.get("role") in ("user", "assistant")]
            if not real:
                continue
            first = next((m["content"] for m in real if m["role"] == "user"), real[0]["content"])
            out.append({"id": cid, "title": first.strip().replace("\n", " ")[:48], "t": real[-1]["t"]})
        return sorted(out, key=lambda c: c["t"], reverse=True)

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
