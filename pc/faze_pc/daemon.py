"""The background daemon: owns settings, memory and the chat engine, and talks to windows over a Unix socket (one JSON object per line)."""
import json
import os
import socket
import socketserver
import sys
import threading

from . import config as cfg
from . import link
from .linkmanager import LinkManager
from .engine import Engine
from .store import Store
from . import tools as toolbox
from .peer import PeerRequests
from .sync import Syncer


def socket_path():
    return os.path.join(cfg.runtime_dir(), "faze.sock")


class Daemon:
    def __init__(self, config=None, store=None, engine=None, links=None):
        self.config = config or cfg.Config()
        self.store = store or Store(cfg.data_dir())
        self.engine = engine or Engine(self.config, self.store, tools=toolbox.Tools(self.config, self.store, phone=self.ask_phone))
        self.in_task = False
        self.chat_lock = threading.Lock()  # one conversation at a time on the engine
        self.listeners = set()
        self.peer = PeerRequests(self.config, runner=self.run_task)
        self.links = links or LinkManager(link.Devices(), syncer=Syncer(self.store), on_request=lambda pid, msg: self.peer.handle(pid, msg), on_synced=lambda pid, got: self.broadcast({"t": "state", "config": self.config.public(), "chats": self.store.chats()}))

    def ask_phone(self, task):
        """The ask_phone tool: hands a task to the first connected phone. The phone has its own approvals."""
        if self.in_task:
            return False, "this task came from the phone, so do it on this PC instead of handing it back"
        for d in self.links.list():
            if d["state"] == "connected":
                try:
                    r = self.links.request(d["id"], {"t": "phone_task", "task": task}, timeout=30)
                except link.LinkError as e:
                    return False, str(e)
                return r.get("t") == "phone_task_ok", r.get("note") or r.get("message") or "no answer"
        return False, "no phone is connected"

    def run_task(self, task):
        """A task the phone handed over: runs as a chat on this PC. Approval cards show in the open windows, like any chat."""
        if not self.chat_lock.acquire(blocking=False):
            return False, "this PC is busy with another chat"
        try:
            self.in_task = True
            chat_id = self.store.new_chat()
            result = {}

            def emit(ev):
                if ev.get("t") in ("approve", "approval"):
                    self.broadcast(ev)
                elif ev.get("t") in ("done", "error"):
                    result.update(ev)

            self.engine.chat(chat_id, "(From your phone) " + task, emit)
            self.broadcast({"t": "state", "config": self.config.public(), "chats": self.store.chats()})
            if result.get("t") == "done":
                return True, result.get("text", "")
            return False, result.get("message", "no answer")
        finally:
            self.in_task = False
            self.chat_lock.release()

    def broadcast(self, ev):
        for emit in list(self.listeners):
            emit(ev)

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
            with self.chat_lock:
                self.engine.chat(chat_id, req.get("text", ""), emit)
        elif op == "stop":
            self.engine.stop()
            emit({"t": "ok"})
        elif op == "approve":
            self.engine.approver.answer(req.get("id"), bool(req.get("ok")))
            emit({"t": "ok"})
        elif op == "activity":
            emit({"t": "activity", "rows": self.store.activity()})
        elif op == "devices":
            emit({"t": "devices", "devices": self.links.list(), "pc": self.links.devices.pc_name})
        elif op == "link_find":
            try:
                emit({"t": "found", "phones": self.links.find()})
            except Exception as e:
                emit({"t": "error", "message": "could not search: %s" % e})
        elif op == "link_pair":
            try:
                phone = self.links.pair(req["address"].strip(), req["code"])
                self.store.log("link", "paired with %s" % phone["name"])
                emit({"t": "devices", "devices": self.links.list(), "pc": self.links.devices.pc_name})
            except (link.LinkError, OSError, KeyError, ValueError) as e:
                emit({"t": "error", "message": "pairing failed: %s" % e})
        elif op == "link_forget":
            self.links.forget(req.get("id", ""))
            self.store.log("link", "forgot a phone")
            emit({"t": "devices", "devices": self.links.list(), "pc": self.links.devices.pc_name})
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
        self.links.start()

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

                daemon.listeners.add(emit)
                try:
                    for raw in self.rfile:
                        try:
                            req = json.loads(raw)
                        except ValueError:
                            continue
                        if req.get("op") == "stop":
                            daemon.handle(req, emit)  # answered at once, even while a chat is streaming
                        else:
                            threading.Thread(target=daemon.handle, args=(req, emit), daemon=True).start()
                finally:
                    daemon.listeners.discard(emit)

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
