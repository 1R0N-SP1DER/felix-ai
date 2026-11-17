"""The link between this PC and the phone (Faze Link v1). The phone is the hub and listens; the PC connects. It works over any IP network:
Wi-Fi, Bluetooth tethering or USB tethering. Nothing travels in the clear after pairing. See docs/link.md for the exact byte layout."""
import hashlib
import hmac
import json
import os
import platform
import secrets
import socket
import struct
import subprocess
import time

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from . import config as cfg

PORT = 8765
DISCOVERY_PORT = 8766
VERSION = 1
MAX_FRAME = 1 << 20
PBKDF2_ROUNDS = 100_000
CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"  # no 0/O/1/I, easy to read off a screen
CODE_LENGTH = 10


class LinkError(Exception):
    pass


def new_code():
    return "".join(secrets.choice(CODE_ALPHABET) for _ in range(CODE_LENGTH))


def clean_code(text):
    return "".join(c for c in text.upper() if c in CODE_ALPHABET)


def code_key(code):
    return hashlib.pbkdf2_hmac("sha256", clean_code(code).encode(), b"faze-pair-v1", PBKDF2_ROUNDS, 32)


def proof(key, label, a, b):
    return hmac.new(key, label.encode() + a + b, hashlib.sha256).digest()


def hkdf(secret, salt, info, length=32):
    prk = hmac.new(salt, secret, hashlib.sha256).digest()
    out, block, i = b"", b"", 1
    while len(out) < length:
        block = hmac.new(prk, block + info + bytes([i]), hashlib.sha256).digest()
        out += block
        i += 1
    return out[:length]


def seal_once(key, nonce, plaintext):
    return AESGCM(key).encrypt(nonce, plaintext, b"")


def open_once(key, nonce, data):
    try:
        return AESGCM(key).decrypt(nonce, data, b"")
    except InvalidTag:
        raise LinkError("a message failed its check (wrong key or tampered)")


# --- framing: 4 bytes big-endian length, then that many bytes
def send_frame(sock, data):
    if len(data) > MAX_FRAME:
        raise LinkError("message too large")
    sock.sendall(struct.pack(">I", len(data)) + data)


def _read_exact(sock, n):
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise LinkError("the other side closed the connection")
        buf += chunk
    return buf


def recv_frame(sock):
    (n,) = struct.unpack(">I", _read_exact(sock, 4))
    if n > MAX_FRAME:
        raise LinkError("message too large")
    return _read_exact(sock, n)


def send_json(sock, obj):
    send_frame(sock, json.dumps(obj, separators=(",", ":")).encode())


def recv_json(sock):
    try:
        return json.loads(recv_frame(sock))
    except ValueError:
        raise LinkError("not a valid message")


class Session:
    """An encrypted connection. Each direction has its own 12-byte nonce: a 4-byte direction tag then an 8-byte counter, and a
    counter that is not exactly the next one is refused, so a captured message cannot be replayed."""

    def __init__(self, sock, key, role):
        self.sock = sock
        self.key = key
        self.send_tag = b"PCPC" if role == "pc" else b"PHON"
        self.recv_tag = b"PHON" if role == "pc" else b"PCPC"
        self.sent = 0
        self.got = 0

    def send(self, obj):
        nonce = self.send_tag + struct.pack(">Q", self.sent)
        self.sent += 1
        send_frame(self.sock, seal_once(self.key, nonce, json.dumps(obj, separators=(",", ":")).encode()))

    def recv(self):
        nonce = self.recv_tag + struct.pack(">Q", self.got)
        data = open_once(self.key, nonce, recv_frame(self.sock))
        self.got += 1
        return json.loads(data)

    def close(self):
        try:
            self.sock.shutdown(socket.SHUT_RDWR)  # wakes a thread that is waiting to read
        except OSError:
            pass
        try:
            self.sock.close()
        except OSError:
            pass


def session_key(secret, pc_nonce, phone_nonce):
    return hkdf(secret, pc_nonce + phone_nonce, b"faze-session-v1")


# --- the PC's half of pairing and connecting
def pair(address, code, pc_id, pc_name, port=PORT, timeout=8):
    """Pairs with a phone that is showing a code. Returns {"id", "name", "secret"} for the phone."""
    k = code_key(code)
    s = socket.create_connection((address, port), timeout=timeout)
    try:
        nc = secrets.token_bytes(16)
        send_json(s, {"t": "pair", "v": VERSION, "id": pc_id, "name": pc_name, "os": platform.system().lower(), "nonce": nc.hex()})
        r = recv_json(s)
        if r.get("t") == "error":
            raise LinkError(r.get("message", "the phone refused"))
        if r.get("t") != "pair_ok":
            raise LinkError("unexpected answer from the phone")
        np = bytes.fromhex(r["nonce"])
        if not hmac.compare_digest(bytes.fromhex(r["proof"]), proof(k, "phone", nc, np)):
            raise LinkError("the code is wrong, or this is not your phone")
        send_json(s, {"t": "pair_confirm", "proof": proof(k, "pc", np, nc).hex()})
        r = recv_json(s)
        if r.get("t") == "error":
            raise LinkError(r.get("message", "the phone refused"))
        secret = open_once(k, b"SECRET" + b"\0" * 6, bytes.fromhex(r["ct"]))
        return {"id": r["id"], "name": r.get("name", "Phone"), "secret": secret.hex()}
    finally:
        s.close()


def connect(address, secret_hex, pc_id, port=PORT, timeout=8):
    """Opens an encrypted session to a paired phone."""
    secret = bytes.fromhex(secret_hex)
    s = socket.create_connection((address, port), timeout=timeout)
    try:
        nc = secrets.token_bytes(16)
        send_json(s, {"t": "hello", "v": VERSION, "id": pc_id, "os": platform.system().lower(), "nonce": nc.hex()})
        r = recv_json(s)
        if r.get("t") == "error":
            raise LinkError(r.get("message", "the phone refused"))
        np = bytes.fromhex(r["nonce"])
        if not hmac.compare_digest(bytes.fromhex(r["proof"]), proof(secret, "phone", nc, np)):
            raise LinkError("this is not the phone that was paired")
        send_json(s, {"t": "auth", "proof": proof(secret, "pc", np, nc).hex()})
        sess = Session(s, session_key(secret, nc, np), "pc")
        s.settimeout(timeout)
        ready = sess.recv()
        if ready.get("t") != "ready":
            raise LinkError("the phone did not accept the connection")
        s.settimeout(None)
        return sess, ready
    except Exception:
        s.close()
        raise


# --- finding the phone
def gateway():
    """The default gateway: when the phone is tethered by USB or Bluetooth, this is the phone."""
    try:
        out = subprocess.run(["ip", "-4", "route", "show", "default"], capture_output=True, text=True, timeout=3).stdout
    except (OSError, subprocess.SubprocessError):
        return None
    for line in out.splitlines():
        parts = line.split()
        if "via" in parts:
            return parts[parts.index("via") + 1]
    return None


def discover(timeout=1.5, port=DISCOVERY_PORT, targets=("255.255.255.255",)):
    """Asks the network who is a Faze phone. Returns [{"address", "id", "name", "port"}]."""
    found = {}
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    s.settimeout(0.3)
    try:
        for t in targets:
            try:
                s.sendto(b"FAZE?", (t, port))
            except OSError:
                pass
        end = time.time() + timeout
        while time.time() < end:
            try:
                data, addr = s.recvfrom(1024)
            except socket.timeout:
                continue
            except OSError:
                break
            try:
                j = json.loads(data)
            except ValueError:
                continue
            if j.get("t") == "faze" and j.get("id"):
                found[j["id"]] = {"address": addr[0], "id": j["id"], "name": j.get("name", "Phone"), "port": int(j.get("port", PORT))}
    finally:
        s.close()
    return list(found.values())


# --- what the PC remembers
class Devices:
    """The paired phone(s), in the PC's own config folder (mode 0600). The secret never leaves this file except over the link."""

    def __init__(self, path=None):
        self.path = path or os.path.join(cfg.config_dir(), "devices.json")
        self.data = {"pc_id": "", "pc_name": socket.gethostname(), "phones": {}}
        try:
            with open(self.path, encoding="utf-8") as f:
                self.data.update(json.load(f))
        except (OSError, ValueError):
            pass
        if not self.data["pc_id"]:
            self.data["pc_id"] = secrets.token_hex(8)
            self.save()

    def save(self):
        os.makedirs(os.path.dirname(self.path), exist_ok=True)
        tmp = self.path + ".tmp"
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(self.data, f, indent=2)
        os.replace(tmp, self.path)

    @property
    def pc_id(self):
        return self.data["pc_id"]

    @property
    def pc_name(self):
        return self.data["pc_name"]

    def add(self, phone, address, port=PORT):
        self.data["phones"][phone["id"]] = {"name": phone["name"], "secret": phone["secret"], "address": address, "port": port}
        self.save()

    def forget(self, phone_id):
        if self.data["phones"].pop(phone_id, None) is not None:
            self.save()
            return True
        return False

    def phones(self):
        return dict(self.data["phones"])

    def remember_address(self, phone_id, address, port):
        p = self.data["phones"].get(phone_id)
        if p and (p["address"], p["port"]) != (address, port):
            p["address"], p["port"] = address, port
            self.save()
