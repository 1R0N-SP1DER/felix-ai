"""
felix-ai Core -- config_loader.py
Layered settings, like Gemini CLI / Claude Code:

    defaults (this file)  <  config/profile.json (repo, no secrets)
                          <  ~/.felix/settings.json (yours, per machine)

Edit the user file by hand, or at runtime with  /settings set <key> <value>.
Secrets (the Gemini key) never live in any of these files.
"""

import copy
import json
import os
from pathlib import Path

CONFIG_PATH = Path(__file__).parent / "config" / "profile.json"

DEFAULTS = {
    "assistant_name": "Faze",
    "llm": {"host": "127.0.0.1", "port": 8080, "endpoint": "/v1/chat/completions"},
    "boot": {
        "mode": "background",              # background | blocking
        "chat_while_booting": "lfm",       # lfm | gemini | wait
        "warmup": True,
    },
    "local": {
        "model_path": "~/models/Qwen3.5-0.8B-Q4_K_M.gguf",
        "server_bin": "~/llama.cpp/build/bin/llama-server",
        "ctx": 4096,
        "threads": 4,
        "log": "~/felix-ai/llama-server.log",
        "start_timeout": 120,
        "auto_restart": True,
        "stop_on_exit": False,
    },
    "lfm": {
        "model_path": "",
        "server_bin": "~/llama.cpp/build/bin/llama-server",
        "port": 8081,
        "ctx": 2048,
        "threads": 4,
        "log": "~/felix-ai/llama-lfm.log",
        "start_timeout": 60,
        "stop_when_qwen_ready": True,
    },
    "gemini_enabled": True,
    "narrate_tool_results": False,
    "ui": {"show_model_tag": True, "show_trace": True},
}

# Settings editable with /settings: key -> (type, allowed values or None)
SETTINGS = {
    "boot.mode": (str, ("background", "blocking")),
    "boot.chat_while_booting": (str, ("lfm", "gemini", "wait")),
    "boot.warmup": (bool, None),
    "local.model_path": (str, None),
    "local.server_bin": (str, None),
    "local.ctx": (int, None),
    "local.threads": (int, None),
    "local.start_timeout": (int, None),
    "local.auto_restart": (bool, None),
    "local.stop_on_exit": (bool, None),
    "lfm.model_path": (str, None),
    "lfm.server_bin": (str, None),
    "lfm.port": (int, None),
    "lfm.ctx": (int, None),
    "lfm.threads": (int, None),
    "lfm.stop_when_qwen_ready": (bool, None),
    "llm.port": (int, None),
    "gemini_enabled": (bool, None),
    "narrate_tool_results": (bool, None),
    "ui.show_model_tag": (bool, None),
    "ui.show_trace": (bool, None),
}

# Changes to these only apply when Faze restarts.
_RESTART_PREFIXES = ("local.", "lfm.model_path", "lfm.server_bin", "lfm.port", "lfm.ctx",
                     "lfm.threads", "llm.", "boot.mode", "boot.warmup")


def user_settings_path() -> Path:
    return Path(os.environ.get("FAZE_SETTINGS") or (Path.home() / ".felix" / "settings.json"))


def _deep_merge(base: dict, over: dict) -> dict:
    for key, value in over.items():
        if isinstance(value, dict) and isinstance(base.get(key), dict):
            _deep_merge(base[key], value)
        else:
            base[key] = value
    return base


def _read_json(path: Path) -> dict:
    try:
        with open(path, "r") as f:
            data = json.load(f)
    except json.JSONDecodeError as e:
        raise ValueError(f"{path} is not valid JSON ({e}); fix or delete it")
    if not isinstance(data, dict):
        raise ValueError(f"{path} must contain a JSON object")
    return data


def load_user_overrides() -> dict:
    path = user_settings_path()
    return _read_json(path) if path.exists() else {}


def load_config() -> dict:
    cfg = copy.deepcopy(DEFAULTS)
    _deep_merge(cfg, _read_json(CONFIG_PATH))
    _deep_merge(cfg, load_user_overrides())
    return cfg


def get_llm_url(config: dict) -> str:
    llm = config["llm"]
    return f"http://{llm['host']}:{llm['port']}{llm['endpoint']}"


def _dig(data: dict, key: str):
    for part in key.split("."):
        if not isinstance(data, dict) or part not in data:
            return None
        data = data[part]
    return data


def _put(data: dict, key: str, value) -> None:
    parts = key.split(".")
    for part in parts[:-1]:
        data = data.setdefault(part, {})
    data[parts[-1]] = value


def _drop(data: dict, key: str) -> bool:
    parts = key.split(".")
    for part in parts[:-1]:
        data = data.get(part)
        if not isinstance(data, dict):
            return False
    return data.pop(parts[-1], None) is not None


def _coerce(key: str, raw: str):
    kind, allowed = SETTINGS[key]
    text = raw.strip()
    if kind is bool:
        if text.lower() in ("true", "on", "yes", "1"):
            value = True
        elif text.lower() in ("false", "off", "no", "0"):
            value = False
        else:
            raise ValueError(f"{key} needs true or false")
    elif kind is int:
        try:
            value = int(text)
        except ValueError:
            raise ValueError(f"{key} needs a whole number")
        if value < 1:
            raise ValueError(f"{key} must be at least 1")
    else:
        value = text
        if allowed and value.lower() not in allowed:
            raise ValueError(f"{key} must be one of: {', '.join(allowed)}")
        if allowed:
            value = value.lower()
    return value


def _write_user_file(overrides: dict) -> None:
    path = user_settings_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".tmp")
    with open(tmp, "w") as f:
        json.dump(overrides, f, indent=2, sort_keys=True)
    os.replace(tmp, path)


def set_user_setting(key: str, raw_value: str):
    """Validate and persist one setting. Returns (value, needs_restart);
    raises ValueError with a user-facing message on any problem."""
    if key not in SETTINGS:
        raise ValueError(f"unknown setting '{key}'. Run /settings to list them")
    value = _coerce(key, raw_value)
    overrides = load_user_overrides()
    _put(overrides, key, value)
    _write_user_file(overrides)
    return value, key.startswith(_RESTART_PREFIXES)


def unset_user_setting(key: str):
    """Remove a user override. Returns True if there was one."""
    if key not in SETTINGS:
        raise ValueError(f"unknown setting '{key}'. Run /settings to list them")
    overrides = load_user_overrides()
    removed = _drop(overrides, key)
    if removed:
        _write_user_file(overrides)
    return removed


def describe_settings(config: dict) -> str:
    overrides = load_user_overrides()
    lines = [f"Settings (user file: {user_settings_path()})"]
    for key in SETTINGS:
        mark = " (user)" if _dig(overrides, key) is not None else ""
        lines.append(f"  {key} = {_dig(config, key)}{mark}")
    lines.append("Change: /settings set <key> <value>   Undo: /settings unset <key>")
    return "\n".join(lines)
