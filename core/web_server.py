"""
felix-ai Core -- web_server.py
Serves the installable Faze web app and its API, from the same process
as the orchestrator:   ./faze.sh --serve   then open the printed link
in Chrome and choose "Install app".

Security (anything on this phone's browser can reach localhost):
  - /api/* needs a secret token (~/.felix/token, created on first run).
    The token travels in an Authorization header, never a cookie, so a
    web page from another site cannot use it.
  - POSTs must be application/json and, if they carry an Origin, it must
    be this server's own. That forces browsers to preflight cross-site
    requests, which this server never approves.
  - While bound to loopback, the Host header must be localhost or
    127.0.0.1 (blocks DNS rebinding).
"""

import hmac
import json
import mimetypes
import os
import queue
import secrets
import shutil
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import action_log
import stt
import tts
import wake
from icons import make_icon
from tool_replies import summarize_error

WEB_DIR = Path(__file__).parent / "web"
STATIC = {
    "/": ("index.html", "text/html; charset=utf-8"),
    "/index.html": ("index.html", "text/html; charset=utf-8"),
    "/app.js": ("app.js", "text/javascript; charset=utf-8"),
    "/app.css": ("app.css", "text/css; charset=utf-8"),
    "/manifest.webmanifest": ("manifest.webmanifest", "application/manifest+json"),
    "/sw.js": ("sw.js", "text/javascript; charset=utf-8"),
}
ICONS = {
    "/icon-192.png": (192, False),
    "/icon-512.png": (512, False),
    "/icon-maskable-512.png": (512, True),
}
CSP = ("default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
       "connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'")
MAX_BODY = 64 * 1024
MAX_MESSAGE = 4000


def token_path() -> Path:
    return Path(os.environ.get("FAZE_TOKEN_FILE") or (Path.home() / ".felix" / "token"))


def get_or_create_token() -> str:
    path = token_path()
    if path.exists():
        token = path.read_text().strip()
        if len(token) >= 20:
            return token
    path.parent.mkdir(parents=True, exist_ok=True)
    token = secrets.token_urlsafe(32)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        f.write(token + "\n")
    os.chmod(path, 0o600)
    return token


def _loopback(host: str) -> bool:
    return host in ("127.0.0.1", "localhost", "::1")


class FazeServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address, convo, config, token):
        super().__init__(address, Handler)
        self.convo = convo
        self.config = config
        self.token = token
        self.chat_lock = threading.Lock()
        self.stt_lock = threading.Lock()
        self.tts_lock = threading.Lock()
        self.bound_host = address[0]
        self.port = self.server_address[1]
        self._subs = []
        self._subs_lock = threading.Lock()
        self.wake_seq = 0
        self._wake_last = 0.0
        self.wake = None
        self._wake_cmd = None

    # ---- push events to open apps (Server-Sent Events) ------------------

    def subscribe(self):
        q = queue.Queue(maxsize=50)
        with self._subs_lock:
            self._subs.append(q)
        return q

    def unsubscribe(self, q):
        with self._subs_lock:
            if q in self._subs:
                self._subs.remove(q)

    def publish(self, event: str, payload: dict):
        with self._subs_lock:
            subs = list(self._subs)
        for q in subs:
            try:
                q.put_nowait((event, payload))
            except queue.Full:
                pass

    # ---- wake word --------------------------------------------------------

    def trigger_wake(self, source: str, phrase: str = "") -> bool:
        """Tell open apps to start listening. Returns False if it fired a moment ago."""
        now = time.monotonic()
        if now - self._wake_last < 1.0:
            return False
        self._wake_last = now
        self.wake_seq += 1
        action_log.log_action("wake", "trigger", {"source": source}, f"#{self.wake_seq}", 0, echo=False)
        app_open = bool(self._subs)
        self.publish("wake", {"seq": self.wake_seq, "source": source, "phrase": phrase})
        wake_cfg = self.config["wake"]
        if not app_open and wake_cfg.get("open_app", True) and shutil.which("termux-open-url"):
            subprocess.Popen(["termux-open-url", f"http://localhost:{self.port}/#voice"],
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        return True

    def sync_wake(self):
        """Start, stop or restart the listener so it matches the settings."""
        cfg = self.config["wake"]
        want, cmd = bool(cfg.get("enabled")), cfg.get("mic_cmd")
        if self.wake and (not want or cmd != self._wake_cmd):
            self.wake.stop()
            self.wake = None
        if want and not self.wake:
            self._wake_cmd = cmd
            self.wake = wake.WakeListener(
                self.config, on_wake=lambda phrase, text: self.trigger_wake("voice", phrase),
                is_busy=lambda: self.chat_lock.locked())
            self.wake.start()

    def stop_wake(self):
        if self.wake:
            self.wake.stop()
            self.wake = None

    def wake_status(self) -> dict:
        if self.wake:
            return {"enabled": True, "seq": self.wake_seq, **self.wake.status()}
        return {"enabled": False, "seq": self.wake_seq, "state": "off", "detail": "",
                "phrase": wake.phrase_for(self.config)}

    def allowed_hosts(self):
        if not _loopback(self.bound_host):
            return None  # token auth is the protection off-loopback
        return {f"localhost:{self.port}", f"127.0.0.1:{self.port}", f"[::1]:{self.port}"}


class Handler(BaseHTTPRequestHandler):
    server_version = "Faze"
    sys_version = ""

    def log_message(self, fmt, *args):  # keep the terminal quiet
        pass

    # ---- helpers -------------------------------------------------------

    def _send(self, code, body: bytes, ctype: str, extra=None):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("X-Content-Type-Options", "nosniff")
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def _json(self, code, data):
        self._send(code, json.dumps(data).encode(), "application/json", {"Cache-Control": "no-store"})

    def _error(self, code, message):
        self._json(code, {"error": message})

    def _host_ok(self) -> bool:
        allowed = self.server.allowed_hosts()
        return allowed is None or self.headers.get("Host", "") in allowed

    def _authorized(self) -> bool:
        header = self.headers.get("Authorization", "")
        if not header.startswith("Bearer "):
            return False
        return hmac.compare_digest(header[len("Bearer "):].strip(), self.server.token)

    def _read_json(self):
        """Parsed JSON body, or None after sending the error response."""
        if self.headers.get("Content-Type", "").split(";")[0].strip() != "application/json":
            self._error(415, "Content-Type must be application/json")
            return None
        origin = self.headers.get("Origin")
        if origin and origin != f"http://{self.headers.get('Host', '')}":
            self._error(403, "cross-origin request refused")
            return None
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            length = -1
        if length < 0 or length > MAX_BODY:
            self._error(413, "body too large")
            return None
        try:
            data = json.loads(self.rfile.read(length) or b"{}")
        except json.JSONDecodeError:
            self._error(400, "invalid JSON")
            return None
        if not isinstance(data, dict):
            self._error(400, "JSON object expected")
            return None
        return data

    def _read_audio(self):
        """Raw WAV body, or None after sending the error response."""
        if self.headers.get("Content-Type", "").split(";")[0].strip() != "audio/wav":
            self._error(415, "Content-Type must be audio/wav")
            return None
        origin = self.headers.get("Origin")
        if origin and origin != f"http://{self.headers.get('Host', '')}":
            self._error(403, "cross-origin request refused")
            return None
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            length = -1
        if length <= 0 or length > stt.MAX_WAV_BYTES:
            self._error(413, "recording is empty or too long")
            return None
        return self.rfile.read(length)

    # ---- routing ---------------------------------------------------------

    def do_GET(self):
        if not self._host_ok():
            return self._error(421, "unexpected Host header")
        path = self.path.split("?", 1)[0]
        if path in STATIC:
            name, ctype = STATIC[path]
            file = WEB_DIR / name
            if not file.is_file():
                return self._error(404, "missing web asset")
            extra = {"Cache-Control": "no-cache"}
            if ctype.startswith("text/html"):
                extra["Content-Security-Policy"] = CSP
                extra["X-Frame-Options"] = "DENY"
            if path == "/sw.js":
                extra["Service-Worker-Allowed"] = "/"
            return self._send(200, file.read_bytes(), ctype, extra)
        if path in ICONS:
            size, maskable = ICONS[path]
            return self._send(200, make_icon(size, maskable), "image/png", {"Cache-Control": "public, max-age=86400"})
        if path.startswith("/api/"):
            if not self._authorized():
                return self._error(401, "missing or wrong token")
            if path == "/api/status":
                return self._json(200, self._status())
            if path == "/api/settings":
                return self._json(200, {"settings": self.server.convo.settings_list()})
            if path == "/api/commands":
                return self._json(200, {"commands": self.server.convo.commands()})
            if path == "/api/events":
                return self._events()
            if path == "/api/voices":
                return self._json(200, {"gemini": [{"name": n.lower(), "label": n, "style": st} for n, st in tts.VOICES]})
            return self._error(404, "unknown endpoint")
        return self._error(404, "not found")

    def do_POST(self):
        if not self._host_ok():
            return self._error(421, "unexpected Host header")
        path = self.path.split("?", 1)[0]
        if not path.startswith("/api/"):
            return self._error(404, "not found")
        if not self._authorized():
            return self._error(401, "missing or wrong token")
        if path == "/api/transcribe":
            return self._transcribe()
        data = self._read_json()
        if data is None:
            return
        if path == "/api/speak":
            return self._speak(data)
        if path == "/api/wake":
            fired = self.server.trigger_wake("api")
            return self._json(200, {"ok": True, "fired": fired})
        if path == "/api/cancel":
            self.server.convo.cancel()
            return self._json(200, {"ok": True})
        if path == "/api/chat":
            return self._chat(data)
        if path == "/api/command":
            return self._command(data)
        if path == "/api/settings":
            return self._settings(data)
        return self._error(404, "unknown endpoint")

    # ---- endpoints -------------------------------------------------------

    def _status(self) -> dict:
        convo = self.server.convo
        local = convo.local
        lfm = ("running" if convo.lfm_process and convo.lfm_process.poll() is None
               else "idle" if convo._lfm_configured() else "not configured")
        return {
            "name": convo._names()[0],
            "qwen": {"state": local.state, "detail": local.detail, "note": local.status_note()},
            "lfm": lfm,
            "gemini": convo._gemini_on(),
            "mode": convo.forced_model or "auto",
            "busy": self.server.chat_lock.locked(),
            "notices": convo.drain_notices(),
            "show_tag": bool(self.server.config["ui"].get("show_model_tag", True)),
            "wake": self.server.wake_status(),
            "tts": dict(convo.config["tts"]),
            "user_name": convo._names()[1],
            "forced_turns": convo.forced_remaining,
            "think": convo.config["reasoning"]["mode"],
            "stt": {"server": stt.available(convo.config), "pref": convo.config["voice"]["stt"],
                    "reason": stt.missing_reason(convo.config)},
        }

    def _events(self):
        server = self.server
        q = server.subscribe()
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Accel-Buffering", "no")
        self.end_headers()
        try:
            self.wfile.write(b"event: hello\ndata: {}\n\n")
            self.wfile.flush()
            while True:
                try:
                    event, payload = q.get(timeout=15)
                    self.wfile.write(f"event: {event}\ndata: {json.dumps(payload)}\n\n".encode())
                except queue.Empty:
                    self.wfile.write(b": keepalive\n\n")
                self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError, OSError):
            pass
        finally:
            server.unsubscribe(q)

    def _speak(self, data):
        cfg = self.server.convo.config["tts"]
        if not self.server.tts_lock.acquire(blocking=False):
            return self._error(409, "still speaking the last reply")
        try:
            wav = tts.synthesize(str(data.get("text", "")), str(data.get("voice") or cfg["voice"]), cfg["model"])
            self._send(200, wav, "audio/wav", {"Cache-Control": "no-store"})
        except tts.TtsError as e:
            self._error(422, str(e))
        finally:
            self.server.tts_lock.release()

    def _transcribe(self):
        data = self._read_audio()
        if data is None:
            return
        if not self.server.stt_lock.acquire(blocking=False):
            return self._error(409, "still transcribing the last clip")
        try:
            text = stt.transcribe_wav(data, self.server.convo.config)
            action_log.log_action("stt", "transcribe", {"bytes": len(data)}, f"{len(text)} chars", 0, echo=False)
            self._json(200, {"text": text})
        except stt.TranscribeError as e:
            self._error(422, str(e))
        finally:
            self.server.stt_lock.release()

    def _command(self, data):
        line = str(data.get("line", "")).strip()
        text = self.server.convo.handle_command(line) if line.startswith("/") else None
        if text is None:
            return self._error(400, "not a slash command")
        self.server.sync_wake()
        self._json(200, {"text": text})

    def _settings(self, data):
        convo = self.server.convo
        key = str(data.get("key", ""))
        try:
            if data.get("unset"):
                removed = convo.reset_setting(key)
                self.server.sync_wake()
                return self._json(200, {"ok": True, "removed": removed, "settings": convo.settings_list()})
            value, restart = convo.change_setting(key, str(data.get("value", "")))
            self.server.sync_wake()
            return self._json(200, {"ok": True, "value": value, "restart": restart,
                                    "settings": convo.settings_list()})
        except ValueError as e:
            return self._error(400, str(e))

    def _chat(self, data):
        message = str(data.get("message", "")).strip()
        if not message:
            return self._error(400, "empty message")
        if len(message) > MAX_MESSAGE:
            return self._error(413, f"message over {MAX_MESSAGE} characters")
        if message.startswith("/"):
            return self._error(400, "send slash commands to /api/command")
        server = self.server
        if not server.chat_lock.acquire(blocking=False):
            return self._error(409, "Faze is still answering the last message")

        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Accel-Buffering", "no")
        self.end_headers()
        alive = True

        def emit(event: str, payload: dict):
            nonlocal alive
            if not alive:
                return
            try:
                self.wfile.write(f"event: {event}\ndata: {json.dumps(payload)}\n\n".encode())
                self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError, OSError):
                alive = False  # client left: stop generating, nobody is reading
                server.convo.cancel()

        convo = server.convo
        tracer = lambda line: emit("trace", {"line": line})  # noqa: E731
        convo.on_route = lambda tag, note: emit("route", {"tag": tag, "note": note})
        convo.on_token = lambda kind, text: emit("token", {"kind": kind, "text": text})
        action_log.add_listener(tracer)
        try:
            for note in convo.drain_notices():
                emit("notice", {"text": note})
            reply = convo.send(message)
            emit("reply", {"text": reply, "route": convo.last_route})
        except Exception as e:  # never let a turn take the server down
            emit("error", {"message": summarize_error(e)})
        finally:
            convo.on_route = None
            convo.on_token = None
            action_log.remove_listener(tracer)
            emit("done", {})
            server.chat_lock.release()


def serve(convo, config):
    """Run the web app until Ctrl+C."""
    server_cfg = config["server"]
    host, port = server_cfg["host"], int(server_cfg["port"])
    token = get_or_create_token()
    try:
        httpd = FazeServer((host, port), convo, config, token)
    except OSError as e:
        print(f"[web] can't listen on {host}:{port} ({e.strerror or e}). "
              f"Change it with: /settings set server.port <n>")
        return
    shown = "localhost" if _loopback(host) else host
    url = f"http://{shown}:{httpd.port}/#t={token}"
    print(f"\nFaze web app running on {host}:{httpd.port}")
    print("Open this link once in Chrome, then menu > Install app:")
    print(f"  {url}\n")
    print("Ctrl+C to stop.")
    httpd.sync_wake()
    if httpd.wake:
        print(f"Wake word listening for: \"{wake.phrase_for(config)}\"")
    if server_cfg.get("open_on_start") and shutil.which("termux-open-url"):
        subprocess.Popen(["termux-open-url", url], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print()
    finally:
        httpd.stop_wake()
        httpd.server_close()
