"""A small WebSocket client (RFC 6455) on the standard library, for the Gemini Live API. Text frames in and out, ping/pong, close.
Only what Live needs: wss or ws, no compression, no extensions."""
import base64
import hashlib
import os
import select
import socket
import ssl
import struct
import threading
import urllib.parse

GUID = b"258EAFA5-E914-47DA-95CA-C5AB0DC85B11"


class WsError(Exception):
    pass


class WsClosed(WsError):
    def __init__(self, code=1006, reason=""):
        super().__init__("%s: %s" % (code, reason) if reason else str(code))
        self.code, self.reason = code, reason


def accept_key(key):
    return base64.b64encode(hashlib.sha1(key.encode() + GUID).digest()).decode()


def encode_frame(opcode, payload, mask=True, fin=True):
    head = bytes([(0x80 if fin else 0) | opcode])
    n = len(payload)
    bit = 0x80 if mask else 0
    if n < 126:
        head += bytes([bit | n])
    elif n < 65536:
        head += bytes([bit | 126]) + struct.pack(">H", n)
    else:
        head += bytes([bit | 127]) + struct.pack(">Q", n)
    if mask:
        m = os.urandom(4)
        payload = bytes(b ^ m[i % 4] for i, b in enumerate(payload)) if n < 512 else _xor(payload, m)
        return head + m + payload
    return head + payload


def _xor(data, mask):
    big = int.from_bytes(data, "big")
    full = (mask * (len(data) // 4 + 1))[: len(data)]
    return (big ^ int.from_bytes(full, "big")).to_bytes(len(data), "big") if data else b""


class Ws:
    def __init__(self, sock):
        self.sock = sock
        self.buf = b""
        self.lock = threading.Lock()
        self.closed = False

    @classmethod
    def connect(cls, url, timeout=12.0, sock=None):
        u = urllib.parse.urlparse(url)
        secure = u.scheme == "wss"
        host, port = u.hostname, u.port or (443 if secure else 80)
        raw = sock or socket.create_connection((host, port), timeout=timeout)
        if secure:
            raw = ssl.create_default_context().wrap_socket(raw, server_hostname=host)
        raw.settimeout(timeout)
        key = base64.b64encode(os.urandom(16)).decode()
        path = (u.path or "/") + ("?" + u.query if u.query else "")
        raw.sendall(("GET %s HTTP/1.1\r\nHost: %s\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: %s\r\nSec-WebSocket-Version: 13\r\n\r\n" % (path, host, key)).encode())
        data = b""
        while b"\r\n\r\n" not in data:
            chunk = raw.recv(4096)
            if not chunk:
                raise WsError("the server closed during the handshake")
            data += chunk
            if len(data) > 65536:
                raise WsError("handshake too long")
        head, rest = data.split(b"\r\n\r\n", 1)
        lines = head.decode("latin-1").split("\r\n")
        if " 101" not in lines[0]:
            raise WsError("handshake refused: " + lines[0][:80])
        headers = {k.strip().lower(): v.strip() for k, _, v in (l.partition(":") for l in lines[1:])}
        if headers.get("sec-websocket-accept") != accept_key(key):
            raise WsError("bad handshake answer")
        ws = cls(raw)
        ws.buf = rest
        return ws

    def _need(self, n):
        while len(self.buf) < n:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise WsClosed(1006, "connection lost")
            self.buf += chunk
        out, self.buf = self.buf[:n], self.buf[n:]
        return out

    def _frame(self):
        b1, b2 = self._need(2)
        n = b2 & 0x7F
        if n == 126:
            n = struct.unpack(">H", self._need(2))[0]
        elif n == 127:
            n = struct.unpack(">Q", self._need(8))[0]
        m = self._need(4) if b2 & 0x80 else None
        body = self._need(n) if n else b""
        if m:
            body = _xor(body, m)
        return b1 & 0x0F, bool(b1 & 0x80), body

    def send(self, text):
        self._send(1, text.encode() if isinstance(text, str) else text)

    def _send(self, opcode, payload):
        with self.lock:
            try:
                self.sock.sendall(encode_frame(opcode, payload))
            except OSError as e:
                raise WsClosed(1006, str(e))

    def recv(self, timeout=None):
        """The next whole text or binary message. TimeoutError when nothing arrives in time; WsClosed when the connection ends."""
        parts, kind = [], 0
        while True:
            if not parts and not self.buf and not (hasattr(self.sock, "pending") and self.sock.pending()):
                if not select.select([self.sock], [], [], timeout)[0]:
                    raise TimeoutError()
            self.sock.settimeout(30)  # once a frame has started it arrives whole or the link is dead
            try:
                op, fin, body = self._frame()
            except socket.timeout:
                raise WsClosed(1006, "connection stalled")
            except OSError as e:
                raise WsClosed(1006, str(e))
            if op == 9:
                self._send(10, body)
            elif op == 10:
                pass
            elif op == 8:
                code = struct.unpack(">H", body[:2])[0] if len(body) >= 2 else 1005
                reason = body[2:].decode("utf-8", "replace")
                try:
                    self._send(8, body[:2])
                except WsClosed:
                    pass
                self.closed = True
                raise WsClosed(code, reason)
            else:
                if op in (1, 2):
                    kind = op
                parts.append(body)
                if fin:
                    data = b"".join(parts)
                    return data.decode("utf-8", "replace") if kind == 1 else data

    def close(self):
        if not self.closed:
            self.closed = True
            try:
                self._send(8, struct.pack(">H", 1000))
            except WsClosed:
                pass
        try:
            self.sock.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        self.sock.close()
