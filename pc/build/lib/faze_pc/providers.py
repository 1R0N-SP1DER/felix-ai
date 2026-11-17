"""One small streaming client for every OpenAI-style provider (Groq, Gemini, OpenRouter, NVIDIA, a custom address, and Ollama)."""
import json
import socket
import urllib.error
import urllib.request

BASES = {
    "groq": "https://api.groq.com/openai/v1",
    "gemini": "https://generativelanguage.googleapis.com/v1beta/openai",
    "openrouter": "https://openrouter.ai/api/v1",
    "nvidia": "https://integrate.api.nvidia.com/v1",
}

LABELS = {"groq": "Groq", "gemini": "Gemini", "openrouter": "OpenRouter", "nvidia": "NVIDIA", "custom": "Custom", "ollama": "Ollama (this PC)"}


class ProviderError(Exception):
    """kind: rate_limit, auth, server, network, bad_request, cancelled."""

    def __init__(self, kind, message, retry_after=0.0):
        super().__init__(message)
        self.kind = kind
        self.retry_after = retry_after


class Abort:
    """Lets another thread cut a request that is waiting on the network (the stop button)."""

    def __init__(self):
        self.cancelled = False
        self._resp = None
        self._sock = None

    def attach(self, resp):
        self._resp = resp
        # closing the response does not wake a read that is already waiting; shutting the socket down does
        self._sock = getattr(getattr(resp.fp, "raw", None), "_sock", None)
        if self.cancelled:
            self.cancel()

    def cancel(self):
        self.cancelled = True
        sock = self._sock
        if sock is not None:
            try:
                sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
        r = self._resp
        if r is not None:
            try:
                r.close()
            except Exception:
                pass


def base_for(config, provider):
    if provider == "ollama":
        return config.data["ollama_url"].rstrip("/") + "/v1"
    if provider == "custom":
        return config.data["custom_url"].rstrip("/")
    return BASES[provider]


def _problem(body):
    try:
        j = json.loads(body)
        e = j.get("error", j)
        return (e.get("message") if isinstance(e, dict) else str(e)) or body[:200]
    except Exception:
        return body[:200]


def _retry_after(headers):
    try:
        return float(headers.get("retry-after", 0) or 0)
    except ValueError:
        return 0.0


def classify(status, body, headers):
    msg = _problem(body)
    label = "the provider answered %d: %s" % (status, msg)
    if status == 429:
        return ProviderError("rate_limit", label, _retry_after(headers))
    if status in (401, 402, 403):
        return ProviderError("auth", label)
    if status >= 500 or status == 408:
        return ProviderError("server", label)
    return ProviderError("bad_request", label)


def stream_chat(base, key, model, messages, on_token, abort=None, timeout=60, max_tokens=None, tools=None, calls=None):
    """Sends the conversation and calls on_token for each piece of the answer. Returns the whole answer."""
    body = {"model": model, "messages": messages, "stream": True}
    if max_tokens:
        body["max_tokens"] = max_tokens
    if tools:
        body["tools"] = tools
    partial = {}
    headers = {"Content-Type": "application/json", "Accept": "text/event-stream"}
    if key:
        headers["Authorization"] = "Bearer " + key
    req = urllib.request.Request(base + "/chat/completions", data=json.dumps(body).encode(), headers=headers, method="POST")
    abort = abort or Abort()
    out = []
    try:
        resp = urllib.request.urlopen(req, timeout=timeout)
    except urllib.error.HTTPError as e:
        raise classify(e.code, e.read().decode("utf-8", "replace"), e.headers)
    except (urllib.error.URLError, socket.timeout, ConnectionError, OSError) as e:
        raise ProviderError("network", "could not reach the provider: %s" % getattr(e, "reason", e))
    abort.attach(resp)
    try:
        for raw in resp:
            if abort.cancelled:
                raise ProviderError("cancelled", "stopped")
            line = raw.decode("utf-8", "replace").strip()
            if not line.startswith("data:"):
                continue
            data = line[5:].strip()
            if data == "[DONE]":
                break
            try:
                j = json.loads(data)
            except ValueError:
                continue
            if "error" in j:
                raise ProviderError("server", _problem(data))
            for choice in j.get("choices", []):
                delta = choice.get("delta") or {}
                piece = delta.get("content")
                if piece:
                    out.append(piece)
                    on_token(piece)
                for tc in delta.get("tool_calls") or []:
                    slot = partial.setdefault(tc.get("index", 0), {"id": "", "name": "", "args": ""})
                    if tc.get("id"):
                        slot["id"] = tc["id"]
                    fn = tc.get("function") or {}
                    if fn.get("name"):
                        slot["name"] += fn["name"]
                    if fn.get("arguments"):
                        slot["args"] += fn["arguments"]
    except ProviderError:
        raise
    except Exception as e:
        if abort.cancelled:
            raise ProviderError("cancelled", "stopped")
        raise ProviderError("network", "the connection dropped: %s" % e)
    finally:
        try:
            resp.close()
        except Exception:
            pass
    if calls is not None:
        for i in sorted(partial):
            s = partial[i]
            if s["name"]:
                calls.append({"id": s["id"] or "call_%d" % i, "type": "function", "function": {"name": s["name"], "arguments": s["args"] or "{}"}})
    return "".join(out)
