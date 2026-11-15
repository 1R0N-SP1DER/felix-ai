"""The background daemon: owns settings, memory and the chat engine, and talks to windows over a Unix socket (one JSON object per line)."""
import json
import os
import socket
import socketserver
import sys
import threading

from . import config as cfg
from .engine import Engine
from .store import Store


def socket_path():
    return os.path.join(cfg.runtime_dir(), "faze.sock")


class Daemon:
    def __init__(self, config=None, store=None, engine=None):
        self.config = config or cfg.Config()
        self.store = store or Store(cfg.data_dir())
        self.engine = engine or Engine(self.config, self.store)

    def handle(self, req, emit):
        op = req.get("op")
        if op == "state":
            emit({"t": "state", "config": self.config.public(), "chats": self.store.chats()})
        elif op == "new_chat":
            emit({"t": "chat", "id": self.store.new_chat()})
        elif op == "history":
            emit({"t": "history", "id": req["chat"], "messages": self.store.history(req["chat"])})
        elif op == "set":
            try:
                self.config.set(req["name"], req["value"])
                emit({"t": "state", "config": self.config.public(), "chats": self.store.chats()})
            except KeyError:
                emit({"t": "error", "message": "unknown setting: %s" % req.get("name")})
        elif op == "chat":
            chat_id = req.get("chat") or self.store.new_chat()
            emit({"t": "chat", "id": chat_id})
            self.engine.chat(chat_id, req.get("text", ""), emit)
        elif op == "stop":
            self.engine.stop()
            emit({"t": "ok"})
        elif op == "approve":
            self.engine.approver.answer(req.get("id"), bool(req.get("ok")))
            emit({"t": "ok"})
        elif op == "activity":
            emit({"t": "activity", "rows": self.store.activity()})
        elif op == "ping":
            emit({"t": "pong"})
        else:
            emit({"t": "error", "message": "unknown request"})

    def serve(self, path=None):
        path = path or socket_path()
        os.makedirs(os.path.dirname(path), mode=0o700, exist_ok=True)
        if os.path.exists(path):
            try:
                socket.socket(socket.AF_UNIX).connect(path)
                raise SystemExit("Faze is already running")
            except (ConnectionRefusedError, FileNotFoundError):
                os.unlink(path)
        daemon = self

        class Handler(socketserver.StreamRequestHandler):
            def handle(self):
                lock = threading.Lock()

                def emit(ev):
                    with lock:
                        try:
                            self.wfile.write((json.dumps(ev, ensure_ascii=False) + "\n").encode())
                            self.wfile.flush()
                        except OSError:
                            pass

                for raw in self.rfile:
                    try:
                        req = json.loads(raw)
                    except ValueError:
                        continue
                    if req.get("op") == "stop":
                        daemon.handle(req, emit)  # answered at once, even while a chat is streaming
                    else:
                        threading.Thread(target=daemon.handle, args=(req, emit), daemon=True).start()

        class Server(socketserver.ThreadingMixIn, socketserver.UnixStreamServer):
            daemon_threads = True

        srv = Server(path, Handler)
        os.chmod(path, 0o600)
        self.server = srv
        srv.serve_forever()


def main():
    Daemon().serve()


if __name__ == "__main__":
    sys.exit(main())
