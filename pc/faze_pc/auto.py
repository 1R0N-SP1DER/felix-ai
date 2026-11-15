"""Auto mode, as on the phone: chat goes to Groq or OpenRouter, code and deep questions to Gemini, a provider out of allowance is
skipped for a while, and Ollama on this PC is the fallback (and the answer when offline)."""
import re
import socket
import time

CASUAL, CODING, DEEP = "casual", "coding", "deep"

_CODING = re.compile(
    r"```|\b(code|coding|function|bug|debug|error|exception|stack ?trace|compile|compiler|python|kotlin|java|javascript|typescript|rust|c\+\+|sql|regex|script|api|json|refactor|implement|algorithm|class|git|android|gradle|html|css|syntax|unit test|variable)\b",
    re.I,
)
_DEEP = re.compile(
    r"\b(analy[sz]e|analysis|compare|explain in detail|in depth|step by step|essay|research|strategy|prove|derive|pros and cons|think carefully|break down|design a|plan for)\b",
    re.I,
)

CASUAL_ORDER = ("groq", "openrouter", "gemini", "nvidia", "custom")
HEAVY_ORDER = ("gemini", "groq", "openrouter", "nvidia", "custom")


def kind_of(text, effort="medium"):
    if _CODING.search(text):
        return CODING
    if len(text) > 500 or _DEEP.search(text) or effort in ("high", "extra", "max"):
        return DEEP
    return CASUAL


def plan(task, online, keyed, resting, model_for, ollama_models):
    """A list of (provider, model) to try in order. ollama_models is (chat model, deep model)."""
    chat_m, deep_m = ollama_models
    first, second = (chat_m, deep_m) if task == CASUAL else (deep_m, chat_m)
    local = []
    for m in (first, second):
        if m and ("ollama", m) not in local:
            local.append(("ollama", m))
    order = CASUAL_ORDER if task == CASUAL else HEAVY_ORDER
    usable = [p for p in order if p in keyed]
    ready = [(p, model_for(p)) for p in usable if not resting(p)]
    tired = [(p, model_for(p)) for p in usable if resting(p)]
    return ready + local + tired if online else local + ready + tired


class Limits:
    """Which providers hit their limit, and until when."""

    def __init__(self, now=time.time):
        self.now = now
        self.until = {}

    def rest(self, provider, seconds):
        self.until[provider] = max(self.until.get(provider, 0), self.now() + seconds)

    def resting(self, provider):
        return self.until.get(provider, 0) > self.now()

    def note(self, provider, err):
        text = str(err).lower()
        if err.kind == "rate_limit":
            given = err.retry_after
            if "quota" in text or "daily" in text or given > 600:
                self.rest(provider, max(given, 3600))
            else:
                self.rest(provider, max(given, 60))
        elif err.kind == "auth":
            self.rest(provider, 600)


_online = {"at": 0.0, "value": True}


def online(now=time.time, probe=None):
    """Whether the internet is reachable, checked at most every ten seconds."""
    t = now()
    if t - _online["at"] < 10:
        return _online["value"]
    try:
        (probe or (lambda: socket.create_connection(("1.1.1.1", 53), timeout=1.0).close()))()
        _online["value"] = True
    except OSError:
        _online["value"] = False
    _online["at"] = t
    return _online["value"]
