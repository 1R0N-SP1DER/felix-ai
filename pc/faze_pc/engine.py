"""One chat turn: build the conversation, pick the models (Auto or the chosen one), stream the answer, fall through on limits."""
import threading

import json

from . import auto
from . import tools as toolbox
from .providers import LABELS, Abort, ProviderError, base_for, stream_chat

PERSONA = (
    "You are Faze, {user}'s casual chat companion, not a customer support agent. "
    "Talk like a close friend: relaxed, natural, a bit of slang, light humor when it fits. Match the user's energy and the length of their message: "
    "a one-line message gets a one- or two-sentence reply. Do not recite lists unless asked. "
    "Never describe yourself as an AI model or say you have no feelings; you are Faze. "
    "You have no body: do not describe physical actions or surroundings. "
    "Use the user's own words and what you know about them; do not make up facts about their life. "
    "You can use tools on this PC (search, open apps, files, clipboard, screenshots, shell). Use one only when it helps with what was asked, "
    "and say briefly what you did. If a tool is refused or fails, say so plainly instead of pretending it worked."
)

HISTORY = 20
MAX_ROUNDS = 6


class Engine:
    def __init__(self, config, store, limits=None, online=auto.online, streamer=stream_chat, tools=None, approver=None):
        self.config = config
        self.store = store
        self.tools = tools or toolbox.Tools(config, store)
        self.approver = approver or toolbox.Approver()
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
        self.approver.deny_all()  # a card nobody answered must not keep the turn waiting
        with self._lock:
            a = self._abort
        if a:
            a.cancel()

    def use_tools(self, provider):
        if provider == "ollama":
            return bool(self.config.data.get("ollama_tools"))  # small local models often handle tools badly, so it is opt-in
        return True

    def run_tool(self, call, emit, ran):
        """Runs one tool call from the model, asking first when the tier says so. Returns the text the model will read."""
        fn = call.get("function", {})
        name = fn.get("name", "")
        try:
            args = json.loads(fn.get("arguments") or "{}")
            if not isinstance(args, dict):
                raise ValueError
        except ValueError:
            return "The arguments were not valid JSON."
        t = toolbox.tier(name)
        if t is None:
            return "There is no tool called %s." % name
        text = toolbox.summary(name, args)
        if name == "run_command" and toolbox.blocked(str(args.get("command", ""))):
            self.store.log("tool", "refused: " + text)
            emit({"t": "tool", "name": name, "summary": text, "ok": False})
            return "Refused: that command looks destructive or needs administrator rights."
        if t == toolbox.ASK and not self.approver.ask(name, text, emit):
            self.store.log("tool", "denied: " + text)
            emit({"t": "tool", "name": name, "summary": text, "ok": False, "denied": True})
            return "The user said no (or did not answer), so this was not done."
        ran.append(name)  # from here on this turn must not be handed to another provider, or the action could run twice
        ok, result = self.tools.execute(name, args)
        self.store.log("tool", ("%s: " % ("ok" if ok else "failed")) + text)
        emit({"t": "tool", "name": name, "summary": text, "ok": ok})
        return result

    def run_model(self, provider, model, messages, emit, abort, sent, ran):
        """One provider, as many rounds as the model needs for its tools. Returns the final answer."""
        specs = toolbox.specs() if self.use_tools(provider) else None
        key = "" if provider == "ollama" else self.config.key(provider)
        base = base_for(self.config, provider)
        msgs = list(messages)
        for _ in range(MAX_ROUNDS):
            calls = []
            extra = {"tools": specs, "calls": calls} if specs else {}
            on_token = lambda piece: (sent.append(piece), emit({"t": "token", "v": piece}))
            try:
                answer = self.streamer(base, key, model, msgs, on_token, abort, **extra)
            except ProviderError as e:
                if specs and e.kind == "bad_request" and "tool" in str(e).lower():
                    specs = None  # this model cannot use tools: carry on as plain chat
                    self.store.log("note", "%s cannot use tools, answering without them" % model)
                    continue
                raise
            if not calls:
                return answer
            msgs.append({"role": "assistant", "content": answer or None, "tool_calls": calls})
            for c in calls:
                if abort.cancelled:
                    raise ProviderError("cancelled", "stopped")
                msgs.append({"role": "tool", "tool_call_id": c["id"], "content": self.run_tool(c, emit, ran)})
        return "I went round in circles with my tools and gave up. Try asking a bit differently."

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
            ran = []
            emit({"t": "route", "provider": provider, "label": LABELS.get(provider, provider), "model": model, "task": task})
            self.store.log("route", "%s request: %s %s" % (task, provider, model))
            try:
                answer = self.run_model(provider, model, messages, emit, abort, sent, ran)
                self.store.add(chat_id, "assistant", answer)
                emit({"t": "done", "text": answer})
                return
            except ProviderError as e:
                if e.kind == "cancelled":
                    self.approver.deny_all()
                    partial = "".join(sent)
                    if partial:
                        self.store.add(chat_id, "assistant", partial)
                    emit({"t": "done", "text": partial, "stopped": True})
                    return
                last = e
                self.limits.note(provider, e)
                if sent or ran:  # half an answer is on screen, or a tool already ran: do not start over under another provider
                    partial = "".join(sent)
                    if partial:
                        self.store.add(chat_id, "assistant", partial)
                    emit({"t": "error", "message": "%s stopped part way: %s" % (LABELS.get(provider, provider), e)})
                    return
                if e.kind == "bad_request" and provider == "ollama":
                    emit({"t": "note", "message": "Ollama said: %s" % e})
        emit({"t": "error", "message": ("Every provider failed. Last error: %s" % last) if last else "Stopped."})
        with self._lock:
            self._abort = None
