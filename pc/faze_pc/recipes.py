"""Phrases the person approved with a thumbs up. A request (its words, in any order, without the little ones) leads to the tool steps that worked.
Used only offline: the saved steps run again and the offline model is never loaded, not even to word the answer."""
import json
import os
import re
import time

MAX = 100
MAX_STEPS = 5
MAX_FAILS = 2

# Tools that do the same thing every time they are asked the same way. Tools that ask first still ask first when they are replayed.
REPEATABLE = {"get_time", "web_search", "notify", "open_app", "open_path", "take_screenshot", "read_clipboard", "set_clipboard", "list_dir"}
# Tools that only do something and have nothing to report, so the saved reply is as good as a new one.
SILENT = {"notify", "open_app", "open_path", "set_clipboard"}

LITTLE = set("a an the please can could would you me my i to for of on in at and or is it that this do does just now hey faze".split())


def tokens(text):
    return [w for w in re.sub(r"[^a-z0-9]+", " ", (text or "").lower()).split() if w not in LITTLE]


def key_of(request):
    return " ".join(sorted(tokens(request)))


def can_learn(chain):
    return 0 < len(chain) <= MAX_STEPS and all(s.get("ok") and s.get("name") in REPEATABLE for s in chain)


class RecipeBook:
    def __init__(self, path, now=time.time):
        self.path = path
        self.now = now

    def list(self):
        try:
            with open(self.path, encoding="utf-8") as f:
                data = json.load(f)
            return [r for r in data if isinstance(r, dict) and r.get("key") and isinstance(r.get("steps"), list)]
        except (OSError, ValueError):
            return []

    def _write(self, items):
        os.makedirs(os.path.dirname(self.path) or ".", exist_ok=True)
        tmp = self.path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(items, f, ensure_ascii=False)
        os.replace(tmp, self.path)

    def find(self, request, have):
        key = key_of(request)
        if not key:
            return None
        for r in self.list():
            if r["key"] == key and all(s.get("name") in have for s in r["steps"]):
                return r
        return None

    def save(self, request, chain, say):
        """True if saved. A newer success for the same request replaces the older one."""
        key = key_of(request)
        if not key or not can_learn(chain):
            return False
        items = [r for r in self.list() if r["key"] != key]
        if len(items) >= MAX:
            items = sorted(items, key=lambda r: (r.get("hits", 0), r.get("created", 0)))[1:]
        steps = [{"name": s["name"], "args": s.get("args") or {}} for s in chain]
        items.append({"key": key, "phrase": request.strip()[:120], "steps": steps, "say": (say or "")[:600],
                      "silent": all(s["name"] in SILENT for s in steps), "hits": 0, "fails": 0, "created": self.now()})
        self._write(items)
        return True

    def hit(self, key):
        self._write([dict(r, hits=r.get("hits", 0) + 1, fails=0) if r["key"] == key else r for r in self.list()])

    def fail(self, key):
        """A recipe that keeps failing is forgotten."""
        items = [dict(r, fails=r.get("fails", 0) + 1) if r["key"] == key else r for r in self.list()]
        self._write([r for r in items if r["fails"] < MAX_FAILS])

    def remove(self, request):
        key = key_of(request)
        self._write([r for r in self.list() if r["key"] != key])

    def clear(self):
        try:
            os.remove(self.path)
        except OSError:
            pass

    def size(self):
        return len(self.list())


def rate(message, request, tap, book):
    """What a thumbs up or down does. Returns (new rating, a short line to show). The same tap again takes it back."""
    if message.get("feedback", 0) == tap:
        return 0, ""
    chain = message.get("chain") or []
    via = message.get("via", "")
    if tap > 0:
        if via == "recipe":
            book.hit(key_of(request))
            return 1, "Got it. I'll keep doing it this way."
        if not chain:
            return 1, "Thanks. There was nothing to learn from a plain answer."
        if book.save(request, chain, message.get("content", "")):
            return 1, "Learned. Offline, I'll do this without thinking."
        return 1, "Thanks. Those steps can't be repeated safely from words, so I didn't save them."
    if via == "recipe":
        book.remove(request)
    return -1, "Noted. Tap retry and I'll try another way."
