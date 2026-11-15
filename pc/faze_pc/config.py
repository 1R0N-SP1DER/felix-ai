"""Settings and where things live. Keys are kept in the user's own config file (mode 0600), never in the repo."""
import json
import os
import tempfile

PROVIDERS = ("groq", "gemini", "openrouter", "nvidia", "custom", "ollama")
KEYED = ("groq", "gemini", "openrouter", "nvidia", "custom")

DEFAULTS = {
    "provider": "auto",
    "user_name": "there",
    "keys": {},
    "models": {
        "groq": "openai/gpt-oss-120b",
        "gemini": "gemini-3.1-flash-lite",
        "openrouter": "meta-llama/llama-3.3-70b-instruct:free",
        "nvidia": "meta/llama-3.3-70b-instruct",
        "custom": "",
    },
    "custom_url": "",
    "ollama_url": "http://127.0.0.1:11434",
    "ollama_chat_model": "qwen2.5:1.5b",
    "ollama_deep_model": "qwen2.5:1.5b",
    "effort": "medium",
}


def _dir(env, fallback, name="faze"):
    base = os.environ.get(env) or os.path.expanduser(fallback)
    return os.path.join(base, name)


def config_dir():
    return _dir("XDG_CONFIG_HOME", "~/.config")


def data_dir():
    return _dir("XDG_DATA_HOME", "~/.local/share")


def runtime_dir():
    base = os.environ.get("XDG_RUNTIME_DIR")
    if base and os.path.isdir(base):
        return os.path.join(base, "faze")
    return os.path.join(tempfile.gettempdir(), "faze-%d" % os.getuid())


class Config:
    def __init__(self, path=None):
        self.path = path or os.path.join(config_dir(), "config.json")
        self.data = json.loads(json.dumps(DEFAULTS))
        self.load()

    def load(self):
        try:
            with open(self.path, encoding="utf-8") as f:
                saved = json.load(f)
        except (OSError, ValueError):
            return
        for k, v in saved.items():
            if isinstance(v, dict) and isinstance(self.data.get(k), dict):
                self.data[k].update(v)
            else:
                self.data[k] = v

    def save(self):
        os.makedirs(os.path.dirname(self.path), exist_ok=True)
        tmp = self.path + ".tmp"
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(self.data, f, indent=2)
        os.replace(tmp, self.path)

    def key(self, provider):
        return os.environ.get("FAZE_%s_KEY" % provider.upper()) or self.data["keys"].get(provider, "")

    def model(self, provider):
        return self.data["models"].get(provider, "")

    def has_key(self, provider):
        if provider == "ollama":
            return True
        if provider == "custom":
            return bool(self.data.get("custom_url"))
        return bool(self.key(provider))

    def keyed(self):
        return {p for p in KEYED if self.has_key(p) and (p != "custom" or self.model("custom"))}

    def set(self, name, value):
        """One setting by dotted name, e.g. "provider", "keys.groq", "models.gemini"."""
        if "." in name:
            group, item = name.split(".", 1)
            if group not in ("keys", "models") or not isinstance(self.data.get(group), dict):
                raise KeyError(name)
            self.data[group][item] = str(value).strip()
        elif name in DEFAULTS and not isinstance(DEFAULTS[name], dict):
            self.data[name] = value.strip() if isinstance(value, str) else value
        else:
            raise KeyError(name)
        self.save()

    def public(self):
        """The settings with the keys hidden, safe to show in a window."""
        d = json.loads(json.dumps(self.data))
        d["keys"] = {p: bool(self.key(p)) for p in KEYED}
        return d
