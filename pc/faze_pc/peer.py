"""What this PC does when the phone asks. v1: answer a chat with this PC's own Ollama model (the phone uses it as a provider in Auto mode)."""
import json
import urllib.request

from . import providers
from .fs import FsError, Shares


def clean(messages):
    """Makes a phone conversation safe for a plain Ollama chat: tool results become user text and tool-call fields are dropped."""
    out = []
    for m in messages:
        role, content = m.get("role"), m.get("content")
        if isinstance(content, list):  # parts: keep only the text
            content = " ".join(p.get("text", "") for p in content if isinstance(p, dict))
        if role == "tool":
            out.append({"role": "user", "content": "Tool result: %s" % (content or "")})
        elif role in ("system", "user", "assistant") and content:
            out.append({"role": role, "content": content})
    return out


class PeerRequests:
    def __init__(self, config, streamer=providers.stream_chat, runner=None, lister=None, log=None):
        self.shares = Shares(config)
        self.log = log or (lambda kind, text: None)
        self.lister = lister or self._ollama_models
        self.config = config
        self.streamer = streamer
        self.runner = runner  # runner(task) -> (ok, text): runs a task the phone handed over, as a chat on this PC

    def handle(self, pid, msg):
        t = msg.get("t")
        if t == "pc_chat":
            return self.chat(msg)
        if t in ("fs_roots", "fs_list", "fs_read"):
            return self.files(msg)
        if t == "pc_models":
            try:
                return {"t": "pc_models_ok", "models": self.lister()}
            except Exception as e:
                return {"t": "error", "message": "Ollama on the PC did not answer (is it running?): %s" % str(e)[:100]}
        if t == "pc_task":
            task = str(msg.get("task", "")).strip()
            if not task or not self.runner:
                return {"t": "error", "message": "no task given" if self.runner else "this PC cannot run tasks"}
            ok, text = self.runner(task)
            return {"t": "pc_task_ok" if ok else "error", "text": text, "message": text}
        return {"t": "error", "message": "this PC does not know %r" % t}

    def files(self, msg):
        t = msg["t"]
        try:
            if t == "fs_roots":
                return {"t": "fs_roots_ok", "roots": [{"id": rid, "name": name} for rid, name, _ in self.shares.roots()]}
            if t == "fs_list":
                return {"t": "fs_list_ok", "entries": self.shares.list(str(msg.get("root", "")), msg.get("path", ""))}
            offset = int(msg.get("offset", 0))
            if offset == 0:
                self.log("share", "phone reads %s/%s" % (msg.get("root", ""), msg.get("path", "")))
            return dict(self.shares.read(str(msg.get("root", "")), msg.get("path", ""), offset, msg.get("len", 0) or 524288), t="fs_read_ok")
        except (FsError, ValueError, TypeError) as e:
            return {"t": "error", "message": str(e)}

    def _ollama_models(self):
        url = providers.base_for(self.config, "ollama").rsplit("/v1", 1)[0] + "/api/tags"
        with urllib.request.urlopen(url, timeout=5) as r:
            return sorted(m["name"] for m in json.loads(r.read().decode("utf-8")).get("models", []))

    def chat(self, msg):
        c = self.config
        model = str(msg.get("model") or "").strip() or (c.data["ollama_deep_model"] if msg.get("deep") else c.data["ollama_chat_model"])
        messages = clean(msg.get("messages") or [])
        if not messages:
            return {"t": "error", "message": "nothing to answer"}
        try:
            text = self.streamer(providers.base_for(c, "ollama"), "", model, messages, lambda piece: None, timeout=120)
        except providers.ProviderError as e:
            return {"t": "error", "message": "Ollama on the PC: %s" % e}
        return {"t": "pc_chat_ok", "content": text, "model": model}
