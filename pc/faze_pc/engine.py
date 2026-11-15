"""One chat turn: build the conversation, pick the models (Auto or the chosen one), stream the answer, fall through on limits."""
import threading

from . import auto
from .providers import LABELS, Abort, ProviderError, base_for, stream_chat

PERSONA = (
    "You are Faze, {user}'s casual chat companion, not a customer support agent. "
    "Talk like a close friend: relaxed, natural, a bit of slang, light humor when it fits. Match the user's energy and the length of their message: "
    "a one-line message gets a one- or two-sentence reply. Do not recite lists unless asked. "
    "Never describe yourself as an AI model or say you have no feelings; you are Faze. "
    "You have no body: do not describe physical actions or surroundings. "
    "Use the user's own words and what you know about them; do not make up facts about their life."
)

HISTORY = 20


class Engine:
    def __init__(self, config, store, limits=None, online=auto.online, streamer=stream_chat):
        self.config = config
        self.store = store
        self.limits = limits or auto.Limits()
        self.online = online
        self.streamer = streamer
        self._abort = None
        self._lock = threading.Lock()

    def system_prompt(self):
        text = PERSONA.format(user=self.config.data.get("user_name") or "there")
        facts = self.store.facts()
        if facts:
            text += "\nThings the user asked you to remember:\n" + "\n".join("- " + f["text"] for f in facts)
        return text

    def attempts(self, text):
        c = self.config
        task = auto.kind_of(text, c.data.get("effort", "medium"))
        planned = auto.plan(
            task, self.online(), c.keyed(), self.limits.resting, c.model,
            (c.data["ollama_chat_model"], c.data["ollama_deep_model"]),
        )
        chosen = c.data.get("provider", "auto")
        if chosen != "auto":
            model = c.data["ollama_chat_model"] if chosen == "ollama" else c.model(chosen)
            first = [(chosen, model)] if model else []
            return task, first + [a for a in planned if a[0] != chosen]
        return task, planned

    def slash(self, text):
        """Commands typed into the chat: /remember, /forget, /facts. Returns the reply, or None if it is not a command."""
        t = text.strip()
        if t.startswith("/remember "):
            self.store.add_fact(t[10:])
            return "Got it, I'll remember that."
        if t.startswith("/forget "):
            fid = t[8:].strip()
            return "Forgotten." if self.store.forget(fid) else "I don't have a memory with that id."
        if t == "/facts":
            facts = self.store.facts()
            return "\n".join("%s  %s" % (f["id"], f["text"]) for f in facts) or "Nothing remembered yet."
        return None

    def stop(self):
        with self._lock:
            a = self._abort
        if a:
            a.cancel()

    def chat(self, chat_id, text, emit):
        """Runs one turn. emit(event) gets {"t":"route"|"token"|"done"|"error", ...}."""
        text = text.strip()
        if not text:
            return
        self.store.add(chat_id, "user", text)
        reply = self.slash(text)
        if reply is not None:
            self.store.add(chat_id, "assistant", reply)
            emit({"t": "token", "v": reply})
            emit({"t": "done", "text": reply})
            return
        history = self.store.history(chat_id, HISTORY)
        messages = [{"role": "system", "content": self.system_prompt()}] + [{"role": m["role"], "content": m["content"]} for m in history]
        task, attempts = self.attempts(text)
        if not attempts:
            emit({"t": "error", "message": "No provider is set up. Add a key in settings, or start Ollama."})
            return
        abort = Abort()
        with self._lock:
            self._abort = abort
        last = None
        for provider, model in attempts:
            if abort.cancelled:
                break
            sent = []
            emit({"t": "route", "provider": provider, "label": LABELS.get(provider, provider), "model": model, "task": task})
            try:
                key = "" if provider == "ollama" else self.config.key(provider)
                answer = self.streamer(
                    base_for(self.config, provider), key, model, messages,
                    lambda piece: (sent.append(piece), emit({"t": "token", "v": piece})), abort,
                )
                self.store.add(chat_id, "assistant", answer)
                emit({"t": "done", "text": answer})
                return
            except ProviderError as e:
                if e.kind == "cancelled":
                    partial = "".join(sent)
                    if partial:
                        self.store.add(chat_id, "assistant", partial)
                    emit({"t": "done", "text": partial, "stopped": True})
                    return
                last = e
                self.limits.note(provider, e)
                if sent:  # half an answer was already shown: do not start another one under it
                    partial = "".join(sent)
                    self.store.add(chat_id, "assistant", partial)
                    emit({"t": "error", "message": "%s stopped part way: %s" % (LABELS.get(provider, provider), e)})
                    return
                if e.kind == "bad_request" and provider == "ollama":
                    emit({"t": "note", "message": "Ollama said: %s" % e})
        emit({"t": "error", "message": ("Every provider failed. Last error: %s" % last) if last else "Stopped."})
        with self._lock:
            self._abort = None
