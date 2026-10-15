"""
felix-ai Core -- live_client.py
A small client for the Gemini Live API (bidirectional audio over a WebSocket), used only by the dedicated live voice
mode (`faze start --live`). Needs the `websockets` package (pip install websockets); everything else is standard library.

    conn = LiveConnection(key, "gemini-3.8-live")
    conn.open(instruction, declarations, search=True, voice="Kore")   # raises LiveError if it cannot be set up
    conn.send_audio(pcm16_16k)                                        # as often as you like
    for event in conn.poll(0.2): ...                                  # ("audio", bytes) ("in_text", s) ("out_text", s)
                                                                       # ("tool_call", [{id,name,args}]) ("turn_complete",)
                                                                       # ("interrupted",) ("go_away", s) ("closed", why)

The message layout follows Google's Live API docs. Those docs show the output settings in two places (inside
generationConfig, and flat on the setup message), so setup is tried in both shapes, and without Google Search as
a last resort, when the server rejects a field by name. Every rejection is kept for the error message.
The API key never appears in an error or a log line.
"""

import base64
import json
import re
import shutil
import socket
import subprocess
import threading
import time
import urllib.parse
import warnings

try:
    from websockets.exceptions import ConnectionClosed
    from websockets.sync.client import connect as _ws_connect
except ImportError:  # the live mode reports this; nothing else needs the package
    _ws_connect, ConnectionClosed = None, None

URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key={key}"
INPUT_RATE = 16000
OUTPUT_RATE = 24000
SETUP_TIMEOUT = 12.0
_RETRYABLE = re.compile(r"unknown name|invalid json|invalid argument|cannot find field|unsupported|not supported|tools?\b", re.IGNORECASE)


class LiveError(Exception):
    pass


def _open_socket(host: str, port: int, timeout: float):
    """A connected TCP socket to host:port. Resolved here, the way `requests` does it (which works in Termux), with an
    IPv4-only retry: the resolver the websockets package uses itself failed on some phones with
    "[Errno 7] No address associated with hostname" while every other request to the same host worked."""
    try:
        return socket.create_connection((host, port), timeout=timeout)
    except socket.gaierror:
        last = None
        for family, kind, proto, _, addr in socket.getaddrinfo(host, port, socket.AF_INET, socket.SOCK_STREAM):
            try:
                sock = socket.socket(family, kind, proto)
                sock.settimeout(timeout)
                sock.connect(addr)
                return sock
            except OSError as e:
                last = e
        raise last or socket.gaierror(7, "no IPv4 address found for " + host)


def _default_connect(url: str, open_timeout: float = SETUP_TIMEOUT, **kw):
    """websockets' connect(), handed a socket of our own so it never resolves names or looks for a proxy itself."""
    parts = urllib.parse.urlparse(url)
    sock = _open_socket(parts.hostname, parts.port or (443 if parts.scheme == "wss" else 80), open_timeout)
    try:
        for extra in ({"proxy": None}, {}):  # `proxy` exists only in newer websockets releases
            try:
                return _ws_connect(url, sock=sock, open_timeout=open_timeout, **kw, **extra)
            except TypeError:
                continue
        raise LiveError("this websockets version cannot connect through a socket (pip install -U websockets)")
    except BaseException:
        sock.close()
        raise


def available() -> bool:
    return _ws_connect is not None


def scrub(text: str) -> str:
    return re.sub(r"key=[^&\s'\"]+", "key=...", str(text))


def build_setup(model: str, instruction: str, declarations: list, search: bool, voice: str, shape: str) -> dict:
    """The first message. shape "generation": output settings inside generationConfig; "flat": on the setup itself."""
    speech = {"voiceConfig": {"prebuiltVoiceConfig": {"voiceName": voice}}}
    setup = {
        "model": model if model.startswith("models/") else "models/" + model,
        "systemInstruction": {"parts": [{"text": instruction}]},
        "inputAudioTranscription": {},
        "outputAudioTranscription": {},
    }
    if shape == "generation":
        setup["generationConfig"] = {"responseModalities": ["AUDIO"], "speechConfig": speech}
    else:
        setup["responseModalities"] = ["AUDIO"]
        setup["speechConfig"] = speech
    tools = []
    if search:
        tools.append({"googleSearch": {}})
    if declarations:
        tools.append({"functionDeclarations": declarations})
    if tools:
        setup["tools"] = tools
    return {"setup": setup}


def parse_message(msg: dict) -> list:
    """One server message as a list of events."""
    events = []
    content = msg.get("serverContent") or {}
    for part in (content.get("modelTurn") or {}).get("parts") or []:
        inline = part.get("inlineData")
        if inline and inline.get("data"):
            events.append(("audio", base64.b64decode(inline["data"])))
        elif part.get("text"):
            events.append(("text", part["text"]))
    if (content.get("inputTranscription") or {}).get("text"):
        events.append(("in_text", content["inputTranscription"]["text"]))
    if (content.get("outputTranscription") or {}).get("text"):
        events.append(("out_text", content["outputTranscription"]["text"]))
    if content.get("interrupted"):
        events.append(("interrupted",))
    if content.get("turnComplete"):
        events.append(("turn_complete",))
    calls = (msg.get("toolCall") or {}).get("functionCalls")
    if calls:
        events.append(("tool_call", [{"id": c.get("id", ""), "name": c.get("name", ""), "args": c.get("args") or {}} for c in calls]))
    if msg.get("goAway") is not None:
        events.append(("go_away", str((msg["goAway"] or {}).get("timeLeft", ""))))
    return events


class LiveConnection:
    def __init__(self, key: str, model: str, connect=None, clock=time.monotonic):
        self.key, self.model = key, model
        self._connect = connect or _default_connect
        self._clock = clock
        self.ws = None
        self.shape = ""
        self.search = False
        self.rejections = []
        self._send_lock = threading.Lock()
        self.closed = ""

    # ---- setup -------------------------------------------------------------

    def open(self, instruction: str, declarations: list, search: bool = True, voice: str = "Kore") -> None:
        if self._connect is _default_connect and _ws_connect is None:
            raise LiveError("the websockets package is not installed (pip install websockets)")
        attempts = [("generation", search), ("flat", search)] + ([("generation", False), ("flat", False)] if search else [])
        for shape, with_search in attempts:
            try:
                with warnings.catch_warnings():  # newer websockets prefer `with connect(...)`; a long-lived socket cannot
                    warnings.simplefilter("ignore", DeprecationWarning)
                    ws = self._connect(URL.format(key=self.key), open_timeout=SETUP_TIMEOUT, max_size=None)
            except Exception as e:
                raise LiveError(f"could not connect to {urllib.parse.urlparse(URL).hostname}: " + scrub(e)[:160])
            try:
                ws.send(json.dumps(build_setup(self.model, instruction, declarations, with_search, voice, shape)))
                first = self._load(ws.recv(timeout=SETUP_TIMEOUT))
            except Exception as e:
                reason = self._reason(e)
                self._shut(ws)
                self.rejections.append(f"{shape}{'' if with_search else ' no-search'}: {reason}")
                if _RETRYABLE.search(reason):
                    continue
                raise LiveError(reason)
            if "setupComplete" in first:
                self.ws, self.shape, self.search = ws, shape, with_search
                return
            self._shut(ws)
            raise LiveError("the server did not confirm the setup: " + scrub(json.dumps(first))[:160])
        raise LiveError("setup was refused: " + " | ".join(self.rejections)[:400])

    @staticmethod
    def _reason(e: Exception) -> str:
        rcvd = getattr(e, "rcvd", None)
        if rcvd is not None and (rcvd.reason or rcvd.code):
            return scrub(f"{rcvd.code}: {rcvd.reason}".strip(": "))[:300]
        return scrub(f"{type(e).__name__}: {e}")[:300]

    def _close_reason(self) -> str:
        """Why the connection is closed, from the protocol state ("" while it is open)."""
        proto = getattr(self.ws, "protocol", None)
        code = getattr(proto, "close_code", None)
        if code is None:
            return ""
        return scrub(f"{code}: {getattr(proto, 'close_reason', '') or ''}".strip(": "))[:300]

    @staticmethod
    def _load(raw) -> dict:
        if isinstance(raw, (bytes, bytearray)):
            raw = raw.decode("utf-8", "replace")
        try:
            return json.loads(raw)
        except ValueError:
            raise LiveError("the server sent something unreadable")

    @staticmethod
    def _shut(ws) -> None:
        try:
            ws.close()
        except Exception:
            pass

    # ---- traffic -----------------------------------------------------------

    def _send(self, payload: dict) -> None:
        try:
            with self._send_lock:
                self.ws.send(json.dumps(payload))
        except Exception as e:
            self.closed = self.closed or self._reason(e)  # a dead socket seen while sending is a close, even if recv has not noticed
            raise

    def send_audio(self, pcm: bytes) -> None:
        self._send({"realtimeInput": {"audio": {"data": base64.b64encode(pcm).decode(), "mimeType": f"audio/pcm;rate={INPUT_RATE}"}}})

    def send_text(self, text: str) -> None:
        self._send({"realtimeInput": {"text": text}})

    def end_audio(self) -> None:
        self._send({"realtimeInput": {"audioStreamEnd": True}})

    def send_tool_responses(self, responses: list) -> None:
        """responses: [{"id", "name", "ok", "text"}]"""
        self._send({"toolResponse": {"functionResponses": [
            {"id": r["id"], "name": r["name"], "response": {"result": r["text"]} if r["ok"] else {"error": r["text"]}}
            for r in responses]}})

    def poll(self, timeout: float = 0.2) -> list:
        """Events that arrived within `timeout` seconds (maybe none). A dropped connection is a ("closed", why) event."""
        if self.closed:
            return [("closed", self.closed)]
        try:
            raw = self.ws.recv(timeout=timeout)
        except TimeoutError:
            why = self._close_reason()
            if why:
                self.closed = why
                return [("closed", why)]
            return []
        except Exception as e:
            self.closed = self._reason(e)
            return [("closed", self.closed)]
        try:
            return parse_message(self._load(raw))
        except LiveError as e:
            return [("closed", str(e))]

    def close(self) -> None:
        if self.ws is not None:
            self._shut(self.ws)
        self.closed = self.closed or "closed"


class PcmPlayer:
    """Plays raw 24 kHz mono PCM as it arrives, through the first player the phone has (pacat from PulseAudio, else ffplay).
    `live.player` can name another command that reads s16le 24 kHz mono from stdin."""

    CANDIDATES = (
        ["pacat", "--playback", "--format=s16le", f"--rate={OUTPUT_RATE}", "--channels=1", "--latency-msec=120"],
        ["ffplay", "-nodisp", "-autoexit", "-loglevel", "quiet", "-f", "s16le", "-ar", str(OUTPUT_RATE), "-ac", "1", "-i", "pipe:0"],
    )

    def __init__(self, override: str = "", popen=subprocess.Popen, which=shutil.which):
        self._popen, self._which, self.override = popen, which, override.strip()
        self.proc = None
        self.command = None

    def command_line(self):
        if self.override:
            import shlex
            return shlex.split(self.override)
        for cmd in self.CANDIDATES:
            if self._which(cmd[0]):
                return list(cmd)
        return None

    def usable(self) -> bool:
        return self.command_line() is not None

    def write(self, pcm: bytes) -> None:
        if self.proc is None or self.proc.poll() is not None:
            cmd = self.command_line()
            if cmd is None:
                raise LiveError("no audio player: install pulseaudio (pacat) or ffmpeg (ffplay), or set live.player")
            self.command = cmd
            self.proc = self._popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        try:
            self.proc.stdin.write(pcm)
            self.proc.stdin.flush()
        except (BrokenPipeError, OSError, ValueError):
            self.proc = None

    def finish(self) -> None:
        """No more audio for this turn: the player plays what it has buffered, then exits."""
        if self.proc is not None:
            try:
                self.proc.stdin.close()
            except (OSError, ValueError):
                pass

    def busy(self) -> bool:
        return self.proc is not None and self.proc.poll() is None

    def stop(self) -> None:
        """Silence now (an interruption or Stop)."""
        proc, self.proc = self.proc, None
        if proc is not None:
            try:
                proc.stdin.close()
            except (OSError, ValueError):
                pass
            try:
                proc.terminate()
            except OSError:
                pass
