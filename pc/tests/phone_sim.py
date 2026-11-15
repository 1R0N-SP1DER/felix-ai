"""A stand-in phone for tests: the phone's half of Faze Link v1, written independently of the PC half's code paths where it matters."""
import json
import secrets
import socket
import threading

from faze_pc import link


class PhoneSim:
    def __init__(self, name="Test phone"):
        self.id = secrets.token_hex(8)
        self.name = name
        self.paired = {}  # pc id -> secret
        self.code = None
        self.attempts = 0
        self.sock = socket.socket()
        self.sock.bind(("127.0.0.1", 0))
        self.sock.listen(5)
        self.port = self.sock.getsockname()[1]
        self.sessions = []
        self.handlers = {"ping": lambda m: {"t": "pong", "name": name}}
        threading.Thread(target=self.loop, daemon=True).start()

    def open_pairing(self):
        self.code = link.new_code()
        self.attempts = 0
        return self.code

    def loop(self):
        while True:
            try:
                c, _ = self.sock.accept()
            except OSError:
                return
            threading.Thread(target=self.handle, args=(c,), daemon=True).start()

    def handle(self, c):
        try:
            first = link.recv_json(c)
            if first["t"] == "pair":
                self.do_pair(c, first)
            elif first["t"] == "hello":
                self.do_hello(c, first)
        except Exception:
            pass
        finally:
            try:
                c.close()
            except OSError:
                pass

    def do_pair(self, c, first):
        if not self.code or self.attempts >= 5:
            link.send_json(c, {"t": "error", "message": "pairing is not open"})
            return
        self.attempts += 1
        k = link.code_key(self.code)
        nc = bytes.fromhex(first["nonce"])
        np = secrets.token_bytes(16)
        link.send_json(c, {"t": "pair_ok", "nonce": np.hex(), "proof": link.proof(k, "phone", nc, np).hex()})
        conf = link.recv_json(c)
        import hmac
        if conf.get("t") != "pair_confirm" or not hmac.compare_digest(bytes.fromhex(conf["proof"]), link.proof(k, "pc", np, nc)):
            link.send_json(c, {"t": "error", "message": "wrong code"})
            return
        secret = secrets.token_bytes(32)
        self.paired[first["id"]] = secret
        self.code = None
        link.send_json(c, {"t": "secret", "id": self.id, "name": self.name, "ct": link.seal_once(k, b"SECRET" + b"\0" * 6, secret).hex()})

    def do_hello(self, c, first):
        secret = self.paired.get(first["id"])
        if not secret:
            link.send_json(c, {"t": "error", "message": "not paired"})
            return
        nc = bytes.fromhex(first["nonce"])
        np = secrets.token_bytes(16)
        link.send_json(c, {"t": "hello_ok", "nonce": np.hex(), "proof": link.proof(secret, "phone", nc, np).hex()})
        auth = link.recv_json(c)
        import hmac
        if not hmac.compare_digest(bytes.fromhex(auth["proof"]), link.proof(secret, "pc", np, nc)):
            link.send_json(c, {"t": "error", "message": "bad proof"})
            return
        s = link.Session(c, link.session_key(secret, nc, np), "phone")
        self.sessions.append(s)
        s.send({"t": "ready", "id": self.id, "name": self.name})
        while True:
            msg = s.recv()
            h = self.handlers.get(msg.get("t"))
            if h:
                s.send(h(msg))

    def close(self):
        try:
            self.sock.shutdown(socket.SHUT_RDWR)  # wakes the thread waiting in accept(), or the port would stay open
        except OSError:
            pass
        self.sock.close()
