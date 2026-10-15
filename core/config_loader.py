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
import re
import shlex
import sys
from pathlib import Path

CONFIG_PATH = Path(__file__).parent / "config" / "profile.json"

# Termux's parec fails with "Failed to set media name", so the default reads the mic through
# scripts/pa_record.py (libpulse directly). Any parec-style command still works as an override.
DEFAULT_MIC_CMD = "{} {} \"\" 16000".format(
    shlex.quote(sys.executable or "python3"),
    shlex.quote(os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "scripts", "pa_record.py")))

DEFAULTS = {
    "assistant_name": "Faze",   # kept in sync with assistant.name
    "assistant": {"name": "Faze", "user_name": "Felix"},
    "model": {"forced": "auto"},          # auto | qwen | gemini | lfm  (persisted /model choice)
    "reasoning": {"mode": "auto", "level": "auto"},  # level: auto|off|low|medium|high|max; mode (auto|on|off) is derived from it
    "voice": {"stt": "auto", "speak": True, "listen_timeout": 8,  # stt: auto | server | browser
              "overlay_task": "Faze Overlay", "overlay_close_task": "Faze Overlay Close"},  # Tasker task names (empty: do not ask Tasker)
    "live": {"model": "gemini-3.8-live", "barge_in": False, "search": True, "idle_seconds": 15,
             "max_seconds": 600, "player": ""},   # the dedicated live voice mode (faze start --live); Gemini Live over a WebSocket
    "net": {"check": True},                # probe connectivity so online services are used only when reachable
    "tts": {"engine": "system", "voice": "kore", "model": "gemini-3.1-flash-tts-preview"},
    "wake": {
        "enabled": False,
        "phrase": "",                      # empty = "hey <assistant name>"
        "mic_cmd": DEFAULT_MIC_CMD,
        "engine": "auto",                  # auto: online (Gemini) when possible, else whisper | gemini | whisper
        "end_silence_ms": 500,             # how long a pause ends the phrase
        "online_per_min": 10,              # cap on online recognitions, so room chatter cannot eat the quota
        "cooldown": 6,                     # seconds to ignore the wake word after it fires
        "open_app": True,                  # open the app when it wakes and the app isn't open
    },
    "stt": {
        "engine": "auto",                  # auto: Gemini when online, whisper when offline | gemini | whisper
        "bin": "~/whisper.cpp/build/bin/whisper-cli",
        "model_path": "~/models/ggml-tiny.en.bin",
        "threads": 4,
        "language": "en",
        "timeout": 60,
    },
    "llm": {"host": "127.0.0.1", "port": 8080, "endpoint": "/v1/chat/completions"},
    "boot": {
        "mode": "background",              # background | blocking
        "chat_while_booting": "lfm",       # lfm | gemini | wait
        "warmup": True,
        "qwen": "lazy",                    # lazy: start Qwen only when needed | eager: start it at launch
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
    "server": {"host": "127.0.0.1", "port": 8787, "open_on_start": False},
    "gemini": {"stream": True,  # show Gemini's answer as it is written
               "thinking": "low",  # minimal | low | medium | high | default (the model decides): lower is faster
               "model": "gemini-3.1-flash-lite", "search_model": "gemini-3.1-flash-lite"},
    "fallback": {"order": "lfm,qwen"},     # used when forced Gemini can't be reached: lfm,qwen | qwen,lfm | lfm | qwen | off
    "gemini_enabled": True,
    "narrate_tool_results": False,
    "ui": {"show_model_tag": True, "show_trace": True},
    # Gemini-written tools: ask = approve each new tool, yolo = auto-approve with a description, off = none
    "agent": {"mode": "ask", "high_tools": False, "max_rounds": 6},
    "device": {"control": "off", "max_steps": 30, "cursor": True},  # Gemini operating the phone: off | ask | on
    "weather": {"location": ""},    # the place "what's the weather" means when you do not name one
    "search": {"engine": "auto"},   # auto: Gemini grounding, then DuckDuckGo | gemini | web
    "actions": {"root": "auto"},    # auto: retry blocked launches through su | always | never
}

# Settings editable with /settings: key -> (type, allowed values or None)
SETTINGS = {
    "assistant.name": (str, None),
    "assistant.user_name": (str, None),
    "model.forced": (str, ("auto", "qwen", "gemini", "lfm")),
    "reasoning.mode": (str, ("auto", "on", "off")),
    "reasoning.level": (str, ("auto", "off", "low", "medium", "high", "max")),
    "voice.stt": (str, ("auto", "server", "browser")),
    "voice.speak": (bool, None),
    "voice.listen_timeout": (int, None),
    "voice.overlay_task": (str, None),
    "voice.overlay_close_task": (str, None),
    "tts.engine": (str, ("system", "gemini")),
    "tts.voice": (str, __import__("tts").VOICE_NAMES),
    "tts.model": (str, None),
    "wake.enabled": (bool, None),
    "wake.phrase": (str, None),
    "wake.mic_cmd": (str, None),
    "wake.cooldown": (int, None),
    "wake.open_app": (bool, None),
    "stt.engine": (str, ("auto", "gemini", "whisper")),
    "net.check": (bool, None),
    "live.model": (str, None),
    "live.barge_in": (bool, None),
    "live.search": (bool, None),
    "live.idle_seconds": (int, None),
    "live.max_seconds": (int, None),
    "live.player": (str, None),
    "stt.bin": (str, None),
    "stt.model_path": (str, None),
    "stt.threads": (int, None),
    "stt.language": (str, None),
    "stt.timeout": (int, None),
    "boot.mode": (str, ("background", "blocking")),
    "boot.chat_while_booting": (str, ("lfm", "gemini", "wait")),
    "boot.warmup": (bool, None),
    "boot.qwen": (str, ("lazy", "eager")),
    "gemini.stream": (bool, None),
    "gemini.thinking": (str, ("default", "minimal", "low", "medium", "high")),
    "device.control": (str, ("off", "ask", "on")),
    "device.max_steps": (int, None),
    "device.cursor": (bool, None),
    "search.engine": (str, ("auto", "gemini", "web")),
    "weather.location": (str, None),
    "actions.root": (str, ("auto", "always", "never")),
    "wake.engine": (str, ("auto", "gemini", "whisper")),
    "wake.end_silence_ms": (int, None),
    "wake.online_per_min": (int, None),
    "agent.mode": (str, ("off", "ask", "yolo")),
    "agent.high_tools": (bool, None),
    "agent.max_rounds": (int, None),
    "fallback.order": (str, ("lfm,qwen", "qwen,lfm", "lfm", "qwen", "off")),
    "gemini.model": (str, None),
    "gemini.search_model": (str, None),
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
    "server.host": (str, None),
    "server.port": (int, None),
    "server.open_on_start": (bool, None),
    "gemini_enabled": (bool, None),
    "narrate_tool_results": (bool, None),
    "ui.show_model_tag": (bool, None),
    "ui.show_trace": (bool, None),
}

# Changes to these only apply when Faze restarts.
_RESTART_PREFIXES = ("local.", "lfm.model_path", "lfm.server_bin", "lfm.port", "lfm.ctx",
                     "lfm.threads", "llm.", "boot.mode", "boot.warmup", "server.")


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
    cfg["assistant_name"] = cfg["assistant"]["name"]
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
    """Remove a nested key, and any parent dicts it leaves empty."""
    parts = key.split(".")
    chain = [data]
    for part in parts[:-1]:
        nxt = chain[-1].get(part)
        if not isinstance(nxt, dict):
            return False
        chain.append(nxt)
    removed = chain[-1].pop(parts[-1], None) is not None
    if removed:
        for i in range(len(parts) - 1, 0, -1):
            if not chain[i]:
                chain[i - 1].pop(parts[i - 1], None)
    return removed


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
        if key in ("gemini.model", "gemini.search_model", "tts.model") and not re.fullmatch(r"[\w.\-]{3,60}", value):
            raise ValueError(f"{key} must be a model name like gemini-3.1-flash-lite")
        if key == "wake.phrase" and value and not re.fullmatch(r"[\w .'\-]{2,40}", value):
            raise ValueError("wake.phrase must be 2-40 letters, numbers, spaces, . ' or -")
        if key in ("assistant.name", "assistant.user_name"):
            if not re.fullmatch(r"[\w .'\-]{1,24}", value):
                raise ValueError(f"{key} must be 1-24 letters, numbers, spaces, . ' or -")
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
