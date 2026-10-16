"""
felix-ai Core -- netfix.py
Name lookups that ask for "any address family" waited about five seconds on the phone before answering (the AAAA
query for an IPv6 address the phone cannot use times out), while an IPv4-only lookup answered at once. Every request
Faze makes (Gemini, search, speech, Live) paid that wait now and then, and the Live connection sometimes gave up with
"[Errno 7] No address associated with hostname".

install() makes lookups try IPv4 first and fall back to the normal lookup only if that finds nothing, so a network that
is IPv6-only still works. `net.ipv4_first = false` turns it off.
"""

import socket

_original = socket.getaddrinfo
_installed = False


def _ipv4_first(host, port, family=0, type=0, proto=0, flags=0):
    if family == socket.AF_UNSPEC and host:
        try:
            found = _original(host, port, socket.AF_INET, type, proto, flags)
            if found:
                return found
        except socket.gaierror:
            pass
    return _original(host, port, family, type, proto, flags)


def install(config: dict = None) -> bool:
    """Returns True when lookups are now IPv4-first."""
    global _installed
    if config is not None and not config.get("net", {}).get("ipv4_first", True):
        uninstall()
        return False
    socket.getaddrinfo = _ipv4_first
    _installed = True
    return True


def uninstall() -> None:
    global _installed
    socket.getaddrinfo = _original
    _installed = False


def installed() -> bool:
    return _installed and socket.getaddrinfo is _ipv4_first
