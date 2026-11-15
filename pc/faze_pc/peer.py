"""What this PC does when the phone asks. v1: answer a chat with this PC's own Ollama model (the phone uses it as a provider in Auto mode)."""
from . import providers


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
    def __init__(self, config, streamer=providers.stream_chat, runner=None):
        self.config = config
        self.streamer = streamer
        self.runner = runner  # runner(task) -> (ok, text): runs a task the phone handed over, as a chat on this PC

    def handle(self, pid, msg):
        t = msg.get("t")
        if t == "pc_chat":
            return self.chat(msg)
        if t == "pc_task":
            task = str(msg.get("task", "")).strip()
            if not task or not self.runner:
                return {"t": "error", "message": "no task given" if self.runner else "this PC cannot run tasks"}
            ok, text = self.runner(task)
            return {"t": "pc_task_ok" if ok else "error", "text": text, "message": text}
        return {"t": "error", "message": "this PC does not know %r" % t}

    def chat(self, msg):
        c = self.config
        model = c.data["ollama_deep_model"] if msg.get("deep") else c.data["ollama_chat_model"]
        messages = clean(msg.get("messages") or [])
        if not messages:
            return {"t": "error", "message": "nothing to answer"}
        try:
            text = self.streamer(providers.base_for(c, "ollama"), "", model, messages, lambda piece: None, timeout=120)
        except providers.ProviderError as e:
            return {"t": "error", "message": "Ollama on the PC: %s" % e}
        return {"t": "pc_chat_ok", "content": text, "model": model}
