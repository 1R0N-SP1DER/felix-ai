"""
felix-ai Core -- remote_models.py
Online models that speak the OpenAI chat format (Groq, OpenRouter): streaming replies, real tool calling, and the
bookkeeping a free tier needs. A provider that rate-limits or rejects the key is put on a cooldown, so the next
message goes to another backend at once instead of waiting on a failure.

    chat("groq", model, messages, tools=schema, on_token=hook)   -> {"content", "tool_calls", "streamed"}
    available("groq")      key present and not on cooldown
    note("groq")           why not: "no key", "rate limited, retry in 42s", ...
    list_models("groq")    [{"id", "free", "tools", "context"}]
    model_for("groq", config)   the model setting, else a sensible pick from the provider's own list

Keys come from the environment (~/.felix/env): GROQ_API_KEY, OPENROUTER_API_KEY. One key per provider: free-tier
limits belong to the account, so extra keys from the same account add nothing.
"""

import json
import os
import re
import time

import requests

PROVIDERS = {
    "groq": {
        "label": "Groq", "env": "GROQ_API_KEY", "base": "https://api.groq.com/openai/v1", "headers": {},
        # a model id containing the first pattern wins, then the second, ...
        "prefer": ("gpt-oss-120b", "llama-3.3-70b", "qwen3-32b", "gpt-oss-20b", "llama"),
        "skip": ("whisper", "tts", "guard", "embed", "orpheus", "playai", "distil"),
    },
    "openrouter": {
        "label": "OpenRouter", "env": "OPENROUTER_API_KEY", "base": "https://openrouter.ai/api/v1",
        "headers": {"HTTP-Referer": "https://github.com/1R0N-SP1DER/felix-ai", "X-Title": "Faze"},
        "prefer": ("gpt-oss-120b", "llama-3.3-70b", "qwen3", "deepseek", "mistral", "llama"),
        "skip": ("whisper", "tts", "embed"),
    },
}
COOLDOWN = {"rate_limit": 60.0, "auth": 3600.0, "server": 30.0, "model": 600.0}
MAX_COOLDOWN = 900.0

_blocked = {}   # provider -> (until, reason)
_models = {}    # provider -> [model dicts]
_picked = {}    # provider -> chosen model id


class RemoteError(Exception):
    """kind: auth | rate_limit | model | tool_failed | bad_request | server | network | empty"""

    def __init__(self, kind: str, message: str, retry_after: float = 0.0):
        super().__init__(message)
        self.kind, self.retry_after = kind, retry_after


class Cancelled(Exception):
    pass


def key_for(provider: str) -> str:
    return os.environ.get(PROVIDERS[provider]["env"], "").strip()


def has_key(provider: str) -> bool:
    return provider in PROVIDERS and bool(key_for(provider))


def available(provider: str, now=time.monotonic) -> bool:
    if not has_key(provider):
        return False
    until = _blocked.get(provider, (0.0, ""))[0]
    return now() >= until


def note(provider: str, now=time.monotonic) -> str:
    if provider not in PROVIDERS:
        return "unknown provider"
    if not has_key(provider):
        return f"no key (put {PROVIDERS[provider]['env']} in ~/.felix/env)"
    until, reason = _blocked.get(provider, (0.0, ""))
    if now() < until:
        return f"{reason}, retry in {int(until - now())}s"
    return "ready"


def block(provider: str, kind: str, retry_after: float = 0.0, reason: str = "", now=time.monotonic) -> None:
    seconds = min(MAX_COOLDOWN if kind != "auth" else COOLDOWN["auth"], retry_after or COOLDOWN.get(kind, 30.0))
    names = {"rate_limit": "rate limited", "auth": "key refused", "server": "provider error", "model": "model problem"}
    _blocked[provider] = (now() + seconds, reason or names.get(kind, kind.replace("_", " ")))


def reset(provider: str = None) -> None:
    for table in (_blocked, _models, _picked):
        if provider:
            table.pop(provider, None)
        else:
            table.clear()


def _retry_after(response) -> float:
    value = (getattr(response, "headers", None) or {}).get("retry-after") or (getattr(response, "headers", None) or {}).get("Retry-After")
    try:
        return max(1.0, float(value))
    except (TypeError, ValueError):
        pass
    for key in ("x-ratelimit-reset-requests", "x-ratelimit-reset-tokens"):  # Groq: "7m59.56s" or "2.5s"
        raw = (getattr(response, "headers", None) or {}).get(key, "")
        m = re.fullmatch(r"(?:(\d+)h)?(?:(\d+)m(?!s))?(?:([\d.]+)s)?", raw.strip())
        if raw and m and any(m.groups()):
            return max(1.0, int(m.group(1) or 0) * 3600 + int(m.group(2) or 0) * 60 + float(m.group(3) or 0))
    return 0.0


def classify(status: int, body: str, response=None) -> RemoteError:
    text = (body or "")[:300]
    try:
        err = json.loads(body).get("error", {})
        message = (err.get("message") if isinstance(err, dict) else str(err)) or text
        code = str(err.get("code", "")) if isinstance(err, dict) else ""
    except (ValueError, AttributeError):
        message, code = text, ""
    message = re.sub(r"\s+", " ", message).strip()[:200]
    if status in (401, 403):
        return RemoteError("auth", f"the key was refused ({status}): {message}")
    if status == 429:
        return RemoteError("rate_limit", f"rate limited: {message}", _retry_after(response))
    if status == 404 or (status == 400 and re.search(r"model", message, re.IGNORECASE) and not re.search(r"tool", message, re.IGNORECASE)):
        return RemoteError("model", f"model problem: {message}")
    if status == 400 and (code == "tool_use_failed" or re.search(r"tool|function", message, re.IGNORECASE)):
        return RemoteError("tool_failed", f"the model's tool call was malformed: {message}")
    if status >= 500:
        return RemoteError("server", f"the provider had an error ({status}): {message}")
    return RemoteError("bad_request", f"request refused ({status}): {message}")


def chat(provider: str, model: str, messages: list, tools: list = None, on_token=None, cancelled=lambda: False,
         timeout: float = 60.0, post=requests.post, now=time.monotonic, max_tokens: int = 1000, temperature: float = 0.3) -> dict:
    """One streamed request. Raises RemoteError (and puts the provider on a cooldown when that helps) or Cancelled."""
    cfg = PROVIDERS[provider]
    label = cfg["label"]
    key = key_for(provider)
    if not key:
        raise RemoteError("auth", f"{label} has no key (put {cfg['env']} in ~/.felix/env)")
    payload = {"model": model, "messages": messages, "stream": True, "temperature": temperature, "max_tokens": max_tokens}
    if tools:
        payload["tools"] = tools
        payload["tool_choice"] = "auto"
    headers = {"Authorization": f"Bearer {key}", "Content-Type": "application/json", **cfg["headers"]}
    try:
        response = post(f"{cfg['base']}/chat/completions", headers=headers, json=payload, timeout=timeout, stream=True)
    except requests.RequestException as e:
        try:
            import net_status
            net_status.mark_offline()
        except Exception:
            pass
        raise RemoteError("network", f"{label}: network error ({type(e).__name__})")
    if response.status_code != 200:
        try:
            body = response.text
        except Exception:
            body = ""
        err = classify(response.status_code, body, response)
        if err.kind in COOLDOWN:
            block(provider, err.kind, err.retry_after, now=now)
        response.close()
        raise err
    response.encoding = "utf-8"

    content, calls, streamed = [], {}, False
    try:
        for line in response.iter_lines(decode_unicode=True):
            if cancelled():
                response.close()
                raise Cancelled()
            if not line or not line.startswith("data:"):
                continue
            raw = line[5:].strip()
            if raw == "[DONE]":
                break
            try:
                chunk = json.loads(raw)
            except ValueError:
                continue
            if chunk.get("error"):
                e = chunk["error"]
                raise RemoteError("server", f"{label} stopped mid-reply: {(e.get('message') if isinstance(e, dict) else e)!s}"[:200])
            delta = ((chunk.get("choices") or [{}])[0]).get("delta") or {}
            piece = delta.get("content")
            if piece:
                content.append(piece)
                streamed = True
                if on_token:
                    on_token("answer", piece)
            for tc in delta.get("tool_calls") or []:
                slot = calls.setdefault(tc.get("index", 0), {"id": "", "name": "", "arguments": ""})
                if tc.get("id"):
                    slot["id"] = tc["id"]
                fn = tc.get("function") or {}
                if fn.get("name"):
                    slot["name"] += fn["name"]
                if fn.get("arguments"):
                    slot["arguments"] += fn["arguments"]
    except (requests.RequestException, OSError) as e:
        if cancelled():
            raise Cancelled()
        raise RemoteError("network", f"{label}: the reply was cut off ({type(e).__name__})")
    ordered = [calls[i] for i in sorted(calls) if calls[i]["name"]]
    return {"content": "".join(content), "tool_calls": ordered or None, "streamed": streamed}


# ---------------------------------------------------------------- models

def list_models(provider: str, get=requests.get, timeout: float = 15.0, refresh: bool = False) -> list:
    if provider in _models and not refresh:
        return _models[provider]
    cfg = PROVIDERS[provider]
    key = key_for(provider)
    if not key:
        raise RemoteError("auth", f"{cfg['label']} has no key (put {cfg['env']} in ~/.felix/env)")
    try:
        response = get(f"{cfg['base']}/models", headers={"Authorization": f"Bearer {key}", **cfg["headers"]}, timeout=timeout)
    except requests.RequestException as e:
        raise RemoteError("network", f"{cfg['label']}: network error ({type(e).__name__})")
    if response.status_code != 200:
        raise classify(response.status_code, response.text, response)
    try:
        rows = response.json().get("data", [])
    except ValueError:
        raise RemoteError("server", f"{cfg['label']} sent an unreadable model list")
    out = []
    for row in rows:
        mid = str(row.get("id", ""))
        if not mid or row.get("active") is False or any(s in mid.lower() for s in cfg["skip"]):
            continue
        pricing = row.get("pricing") or {}
        free = mid.endswith(":free") or (pricing and str(pricing.get("prompt")) in ("0", "0.0") and str(pricing.get("completion")) in ("0", "0.0"))
        params = row.get("supported_parameters")
        out.append({"id": mid, "free": bool(free) if provider == "openrouter" else True,
                    "tools": ("tools" in params) if isinstance(params, list) else None,
                    "context": row.get("context_length") or row.get("context_window") or 0})
    _models[provider] = out
    return out


def pick_default(provider: str, models: list) -> str:
    """Prefer the provider's tool-capable models, in the order of its `prefer` patterns; OpenRouter only free ones."""
    pool = [m for m in models if m["free"]] if provider == "openrouter" else list(models)
    capable = [m for m in pool if m["tools"] is not False]
    pool = capable or pool
    for pattern in PROVIDERS[provider]["prefer"]:
        matches = sorted((m for m in pool if pattern in m["id"].lower()), key=lambda m: -(m["context"] or 0))
        if matches:
            return matches[0]["id"]
    return sorted(pool, key=lambda m: -(m["context"] or 0))[0]["id"] if pool else ""


def model_for(provider: str, config: dict, get=requests.get) -> str:
    chosen = str((config.get(provider) or {}).get("model", "")).strip()
    if chosen:
        return chosen
    if provider in _picked:
        return _picked[provider]
    picked = pick_default(provider, list_models(provider, get=get))
    if not picked:
        raise RemoteError("model", f"{PROVIDERS[provider]['label']} has no usable model; set {provider}.model")
    _picked[provider] = picked
    return picked
