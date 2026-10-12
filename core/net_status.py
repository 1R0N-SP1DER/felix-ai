"""
felix-ai Core -- net_status.py
Is the internet reachable right now? One cheap cached answer, so callers can choose the fast online service or the
offline fallback without waiting for a request timeout.

  online(config)   probe if the cached answer is stale (online: 20 s, offline: 4 s), else the cached answer
  last()           the cached answer without probing: True, False, or None (never checked)
  mark_offline()   a real request just failed with a network error: believe it immediately
  mark_online()    a real request just worked

The probe connects to IP literals first (no DNS to hang on), then to the Gemini host itself. `net.check = false`
turns probing off and online() always says True (a network that blocks the probes but reaches Gemini).
"""

import socket
import threading
import time

PROBES = (("1.1.1.1", 443), ("8.8.8.8", 53))
HOST = ("generativelanguage.googleapis.com", 443)
ONLINE_TTL = 20.0
OFFLINE_TTL = 4.0
PROBE_TIMEOUT = 1.2

_state = {"online": None, "at": 0.0}
_lock = threading.Lock()


def _connect(addr, timeout: float) -> bool:
    try:
        socket.create_connection(addr, timeout=timeout).close()
        return True
    except OSError:
        return False


def _resolve_and_connect(host: str, port: int, timeout: float) -> bool:
    """getaddrinfo ignores socket timeouts, so it runs in a thread we are willing to abandon."""
    found = []

    def work():
        try:
            found.append(socket.getaddrinfo(host, port, type=socket.SOCK_STREAM)[0][4])
        except OSError:
            pass
    t = threading.Thread(target=work, daemon=True)
    t.start()
    t.join(timeout)
    if not found:
        return False
    return _connect(found[0][:2], timeout)


def probe(connect=_connect, resolve=_resolve_and_connect) -> bool:
    for addr in PROBES:
        if connect(addr, PROBE_TIMEOUT):
            return True
    return resolve(HOST[0], HOST[1], PROBE_TIMEOUT * 2)


def online(config: dict = None, now=time.monotonic, probe_fn=None) -> bool:
    if config is not None and not config.get("net", {}).get("check", True):
        return True
    with _lock:
        known, at = _state["online"], _state["at"]
        if known is not None and now() - at < (ONLINE_TTL if known else OFFLINE_TTL):
            return known
    result = (probe_fn or probe)()
    with _lock:
        _state["online"], _state["at"] = result, now()
    return result


def last():
    return _state["online"]


def mark_offline(now=time.monotonic) -> None:
    with _lock:
        _state["online"], _state["at"] = False, now()


def mark_online(now=time.monotonic) -> None:
    with _lock:
        _state["online"], _state["at"] = True, now()


def reset() -> None:
    with _lock:
        _state["online"], _state["at"] = None, 0.0
