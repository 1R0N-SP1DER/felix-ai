"""The background daemon: owns settings, memory and the chat engine, and talks to windows over a Unix socket (one JSON object per line)."""
import base64
import json
import os
import socket
import socketserver
import subprocess
import sys
import threading
import time

from . import config as cfg
from . import link
from .linkmanager import LinkManager
from .engine import Engine
from .store import Store
from . import tools as toolbox
from .peer import PeerRequests
from .sync import Syncer
from .clip import ClipSync
from .h264 import H264Decoder
from .media import Media
from .remote import RemoteControl
from .live import LiveSession
from .voice import Voice, VoiceError


def socket_path():
    return os.path.join(cfg.runtime_dir(), "faze.sock")


class Daemon:
    def __init__(self, config=None, store=None, engine=None, links=None, voice=None, spawn=None):
        self.config = config or cfg.Config()
        self.store = store or Store(cfg.data_dir())
        self.engine = engine or Engine(self.config, self.store, tools=toolbox.Tools(self.config, self.store, phone=self.ask_phone))
        self.in_task = False
        self.voicing = False
        self.live = None
        self.mirroring = False
        self.mirror_gap = 0.2  # seconds between pictures at most: the phone makes a few a second
        self.chat_lock = threading.Lock()  # one conversation at a time on the engine
        self.listeners = set()
        self.voice = voice or Voice(self.config)
        self.mirror_proc = None
        self.h264 = None
        self.live_video = False
        self.ping_gap = 3.0
        self.spawn = spawn or subprocess.Popen
        self.peer = PeerRequests(self.config, runner=self.run_task, log=self.store.log, mirror_opener=self.open_mirror_window, clip_in=lambda m: self.clip.receive(m), remote=RemoteControl(self.config, log=self.store.log, notify=lambda m: self.links.notify(m)), media=Media(self.config), screen_in=lambda m: self.screen_in(m))
        self.links = links or LinkManager(link.Devices(), syncer=Syncer(self.store), on_request=lambda pid, msg: self.peer.handle(pid, msg), on_synced=lambda pid, got: self.broadcast({"t": "state", "config": self.config.public(), "chats": self.store.chats()}))

        self.clip = ClipSync(self.config, lambda m: self.links.notify(m))

    def _phone(self):
        for d in self.links.list():
            if d["state"] == "connected":
                return d["id"]
        return None

    def screen_in(self, msg):
        """Notices from the phone while it streams its screen: video pieces, or the stream giving up (then we fall back to screenshots)."""
        if msg.get("t") == "screen_h264":
            if self.h264 is not None:
                try:
                    self.h264.feed(base64.b64decode(msg.get("data", "")))
                except ValueError:
                    pass
        else:
            self.live_video = False
            self.broadcast({"t": "mirror_error", "message": msg.get("message", "the phone's live video stopped") + " Showing slower pictures."})
        return None

    def start_live_video(self, pid):
        """Asks the phone for live H.264 video. True when it agreed; the window then fills from screen_in."""
        try:
            r = self.links.request(pid, {"t": "screen_stream_start"}, timeout=15)
        except link.LinkError:
            return False
        if r.get("t") != "screen_stream_ok":
            return False  # an old phone, or a refusal: the screenshot path below says why in its own words
        self.h264 = H264Decoder(lambda jpeg: self.broadcast({"t": "frame", "jpeg": jpeg}), lambda m: self.broadcast({"t": "mirror_error", "message": m}))
        self.live_video = True
        return True

    def stop_live_video(self):
        pid = self._phone()
        if self.h264 is not None:
            self.h264.stop()
            self.h264 = None
        if self.live_video and pid:
            try:
                self.links.request(pid, {"t": "screen_stream_stop"}, timeout=5)
            except link.LinkError:
                pass
        self.live_video = False

    def mirror_loop(self):
        """Shows the phone's screen in the open windows until stopped. Live H.264 video when the phone offers it; otherwise it asks for one picture after another."""
        pid = self._phone()
        if pid and self.start_live_video(pid):
            while self.mirroring and self.live_video:
                time.sleep(self.ping_gap)
                try:
                    self.links.request(pid, {"t": "screen_stream_ping"}, timeout=5)
                except link.LinkError:
                    break
            if self.live_video:  # stopped by the window or the link
                self.stop_live_video()
                self.mirroring = False
                return
            if self.h264 is not None:  # the phone gave up on video: carry on with screenshots
                self.h264.stop()
                self.h264 = None
        try:
            while self.mirroring:
                pid = self._phone()
                if not pid:
                    self.broadcast({"t": "mirror_error", "message": "no phone is connected"})
                    return
                t0 = time.time()
                try:
                    r = self.links.request(pid, {"t": "screen_get"}, timeout=20)
                except link.LinkError as e:
                    self.broadcast({"t": "mirror_error", "message": str(e)})
                    return
                if r.get("t") != "screen_ok":
                    self.broadcast({"t": "mirror_error", "message": r.get("message", "the phone did not send its screen")})
                    return
                self.broadcast({"t": "frame", "jpeg": r["jpeg"]})
                time.sleep(max(0.0, self.mirror_gap - (time.time() - t0)))
        finally:
            self.mirroring = False

    def mirror_input(self, req):
        """One tap, swipe, key or piece of text from the window, forwarded to the phone."""
        kind = req.get("kind")
        msg = {"tap": {"t": "screen_tap", "x": req.get("x"), "y": req.get("y")},
               "long": {"t": "screen_long", "x": req.get("x"), "y": req.get("y")},
               "swipe": {"t": "screen_swipe", "x1": req.get("x1"), "y1": req.get("y1"), "x2": req.get("x2"), "y2": req.get("y2")},
               "key": {"t": "screen_key", "key": str(req.get("key", ""))},
               "text": {"t": "screen_text", "text": str(req.get("text", ""))[:300]}}.get(kind)
        pid = self._phone()
        if not msg or not pid:
            return {"t": "mirror_error", "message": "no phone is connected" if not pid else "unknown input"}
        try:
            r = self.links.request(pid, msg, timeout=15)
        except link.LinkError as e:
            return {"t": "mirror_error", "message": str(e)}
        return {"t": "mirror_error", "message": r.get("message", "the phone refused")} if r.get("t") == "error" else None

    def start_listen(self, emit, auto):
        """One recording. Level events draw the waveform; the text arrives as a "heard" event when it ends."""
        try:
            self.voice.stop_speaking()

            def result(text, err):
                emit({"t": "error", "message": str(err)} if err else {"t": "heard", "text": text})

            self.voice.start_listening(lambda v: emit({"t": "level", "v": v}), auto, result)
            emit({"t": "listening"})
        except VoiceError as e:
            emit({"t": "error", "message": str(e)})

    def open_mirror_window(self):
        """The phone-screen window is its own program. Opens it unless it is already open."""
        if self.mirror_proc is not None and self.mirror_proc.poll() is None:
            return
        self.mirror_proc = self.spawn([sys.executable, "-m", "faze_pc.cli", "--mirror"], stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)

    def start_live(self):
        """Gemini Live, a continuous spoken conversation. Runs in its own thread until stopped."""
        if self.live is not None:
            return
        session = LiveSession(self.config, self.broadcast)
        self.live = session

        def go():
            try:
                session.run()
            finally:
                self.live = None

        threading.Thread(target=go, daemon=True, name="live").start()

    def stop_voice_chat(self):
        self.voicing = False
        if self.live is not None:
            self.live.stop()
        self.voice.cancel_listening()
        self.voice.stop_speaking()
        self.broadcast({"t": "voice_state", "state": "off"})

    def voice_chat(self, chat_id):
        """Hands-free conversation: listen, answer, speak, and listen again, until stopped."""
        try:
            while self.voicing:
                self.broadcast({"t": "voice_state", "state": "listening"})
                got = {}
                ready = threading.Event()

                def result(text, err):
                    got["text"], got["err"] = text, err
                    ready.set()

                try:
                    self.voice.stop_speaking()
                    self.voice.start_listening(lambda v: self.broadcast({"t": "level", "v": v}), True, result)
                except VoiceError as e:
                    self.broadcast({"t": "error", "message": str(e)})
                    return
                while not ready.wait(0.2):
                    if not self.voicing:
                        return
                if not self.voicing:
                    return
                if got.get("err"):
                    self.broadcast({"t": "error", "message": str(got["err"])})
                    return
                text = (got.get("text") or "").strip()
                if not text:
                    continue
                if not chat_id:
                    chat_id = self.store.new_chat()
                    self.broadcast({"t": "chat", "id": chat_id})
                self.broadcast({"t": "voice_user", "text": text})
                self.broadcast({"t": "voice_state", "state": "thinking"})
                said = []

                def watch(ev):
                    if ev.get("t") == "done" and not ev.get("stopped"):
                        said.append(ev.get("text", ""))
                    self.broadcast(ev)

                with self.chat_lock:
                    self.engine.chat(chat_id, text, watch)
                if said and said[-1].strip() and self.voicing:
                    self.broadcast({"t": "voice_state", "state": "speaking"})
                    try:
                        self.voice.speak(said[-1])
                    except VoiceError as e:
                        self.broadcast({"t": "error", "message": str(e)})
                        return
        finally:
            self.voicing = False
            self.broadcast({"t": "voice_state", "state": "off"})

    def say(self, text, emit):
        """Speaks a reply when "Speak replies" is on. A voice problem is shown once, never stops the chat."""
        try:
            self.voice.speak(text)
        except VoiceError as e:
            emit({"t": "error", "message": str(e)})

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
            said = []

            def watch(ev):
                if ev.get("t") == "done" and not ev.get("stopped"):
                    said.append(ev.get("text", ""))
                emit(ev)

            with self.chat_lock:
                self.engine.chat(chat_id, req.get("text", ""), watch)
            if said and self.config.data.get("speak"):
                threading.Thread(target=self.say, args=(said[-1], emit), daemon=True).start()
        elif op == "mirror_start":
            if not self.mirroring:
                self.mirroring = True
                threading.Thread(target=self.mirror_loop, daemon=True, name="mirror").start()
            emit({"t": "ok"})
        elif op == "mirror_open":
            try:
                self.open_mirror_window()
                emit({"t": "ok"})
            except OSError as e:
                emit({"t": "mirror_error", "message": "could not open the phone window: %s" % e})
        elif op == "mirror_stop":
            self.mirroring = False
            self.stop_live_video()
            emit({"t": "ok"})
        elif op == "mirror_input":
            reply = self.mirror_input(req)
            if reply:
                emit(reply)
        elif op == "listen":
            self.start_listen(emit, bool(req.get("auto", True)))
        elif op == "listen_stop":
            self.voice.stop_listening()
        elif op == "voice_start":
            if not self.voicing:
                self.voicing = True
                threading.Thread(target=self.voice_chat, args=(req.get("chat") or None,), daemon=True, name="voice-chat").start()
            emit({"t": "ok"})
        elif op == "live_start":
            if not self.voicing:
                self.broadcast({"t": "voice_state", "state": "listening"})
                self.start_live()
            emit({"t": "ok"})
        elif op == "voice_stop":
            self.stop_voice_chat()
            emit({"t": "ok"})
        elif op == "listen_cancel":
            self.voice.cancel_listening()
            emit({"t": "ok"})
        elif op == "stop":
            if self.live is not None:
                self.live.player.stop()
            self.voice.stop_speaking()
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
        self.clip.start()

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
