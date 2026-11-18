"""What the model picker shows, in the phone's order and words: Auto, this PC's own models (Ollama), then the online ones. Pure logic plus one short Ollama lookup."""
import json
import urllib.error
import urllib.request

ORDER = (("auto", "Auto"), ("ollama", "This PC (Ollama)"), ("gemini", "Gemini"), ("groq", "Groq"), ("openrouter", "OpenRouter"), ("nvidia", "NVIDIA"), ("custom", "Custom"))
EFFORTS = (("low", "Low"), ("medium", "Medium"), ("high", "High"))


def ollama_models(config, opener=urllib.request.urlopen, timeout=1.5):
    """(running, [{"name", "gb"}]) from the local Ollama. Not running is a normal answer, not an error."""
    url = config.data["ollama_url"].rstrip("/") + "/api/tags"
    try:
        with opener(url, timeout=timeout) as r:
            data = json.loads(r.read().decode("utf-8", "replace"))
    except (OSError, ValueError, urllib.error.URLError):
        return False, []
    out = []
    for m in data.get("models", []) if isinstance(data, dict) else []:
        if isinstance(m, dict) and m.get("name"):
            out.append({"name": str(m["name"]), "gb": round((m.get("size") or 0) / 1e9, 1)})
    return True, sorted(out, key=lambda m: m["name"])


def rows(config, running, local):
    chosen = config.data.get("provider", "auto")
    online = config.keyed()
    out = []
    for pid, name in ORDER:
        if pid == "auto":
            ok = bool(online) or bool(local)
            row = {"sub": "picks the best model for each request", "dot": "ok" if ok else "warn"}
        elif pid == "ollama":
            if not running:
                row = {"sub": "Ollama is not running on this PC", "dot": "warn"}
            elif not local:
                row = {"sub": "no models installed yet", "dot": "warn"}
            else:
                row = {"sub": "%d installed · chat: %s" % (len(local), config.data.get("ollama_chat_model") or "?"), "dot": "ok"}
        elif pid == "custom":
            has = config.has_key("custom") and bool(config.model("custom"))
            row = {"sub": (config.model("custom") or "set an address and model in Settings") if has else "set an address and model in Settings", "dot": "ok" if has else "bad"}
        else:
            has = config.has_key(pid)
            row = {"sub": (config.model(pid) or "free model, picked for you") if has else "no key yet (Settings)", "dot": "ok" if has else "bad"}
        row.update({"id": pid, "name": name, "chosen": pid == chosen})
        out.append(row)
    return out


def pick(config, pid, model=""):
    """Choose a provider, and for Ollama optionally the exact local model. Raises ValueError for anything unknown."""
    if pid not in [p for p, _ in ORDER]:
        raise ValueError("unknown model: %s" % pid)
    config.set("provider", pid)
    if pid == "ollama" and model:
        config.set("ollama_chat_model", model)
        config.set("ollama_deep_model", model)


def set_effort(config, level):
    if level not in [e for e, _ in EFFORTS]:
        raise ValueError("unknown thinking level: %s" % level)
    config.set("effort", level)
