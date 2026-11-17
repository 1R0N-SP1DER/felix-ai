"""Live mode on the PC: a spoken conversation with Gemini Live, the same one the phone has.
The microphone streams up as raw 16 kHz audio; the model's voice comes back as 24 kHz audio and plays as it arrives.
The mic is muted while Faze is talking, so speakers do not feed it back to itself (use headphones and set live_barge_in for interruptions)."""
import base64
import json
import re
import shutil
import subprocess
import threading
import time

from . import voice as vc
from . import wsclient

URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key={key}"
OUTPUT_RATE = 24000
SETUP_TIMEOUT = 12.0
DEFAULT_MODEL = "gemini-2.5-flash-native-audio-preview-12-2025"
RETRYABLE = re.compile(r"unknown name|invalid json|invalid argument|cannot find field|unsupported|not supported|tools?\b", re.IGNORECASE)
PLAYERS = [
    ["pacat", "--playback", "--format=s16le", "--rate=%d" % OUTPUT_RATE, "--channels=1", "--latency-msec=120"],
    ["pw-cat", "-p", "--raw", "--rate", str(OUTPUT_RATE), "--format", "s16", "--channels", "1", "-"],
    ["ffplay", "-nodisp", "-autoexit", "-loglevel", "quiet", "-f", "s16le", "-ar", str(OUTPUT_RATE), "-ac", "1", "-i", "pipe:0"],
]


class LiveError(Exception):
    pass


def scrub(text):
    return re.sub(r"key=[^&\s'\"]+", "key=...", str(text))


def build_setup(model, instruction, voice, shape, tools=None):
    speech = {"voiceConfig": {"prebuiltVoiceConfig": {"voiceName": voice}}}
    setup = {"model": model if model.startswith("models/") else "models/" + model,
             "systemInstruction": {"parts": [{"text": instruction}]},
             "inputAudioTranscription": {}, "outputAudioTranscription": {}}
    if shape == "generation":
        setup["generationConfig"] = {"responseModalities": ["AUDIO"], "speechConfig": speech}
    else:
        setup["responseModalities"] = ["AUDIO"]
        setup["speechConfig"] = speech
    if tools:
        setup["tools"] = [{"functionDeclarations": tools}]
    return {"setup": setup}


def declarations(specs):
    """OpenAI-style tool specs (tools.specs()) as Gemini function declarations."""
    out = []
    for sp in specs:
        fn = sp["function"]
        params = fn.get("parameters") or {}
        d = {"name": fn["name"], "description": fn["description"]}
        if params.get("properties"):
            d["parameters"] = {"type": "OBJECT", "properties": {k: {"type": "STRING", "description": v.get("description", "")} for k, v in params["properties"].items()},
                               "required": params.get("required", [])}
        out.append(d)
    return out


def parse_message(msg):
    events = []
    content = msg.get("serverContent") or {}
    for part in (content.get("modelTurn") or {}).get("parts") or []:
        inline = part.get("inlineData")
        if inline and inline.get("data"):
            events.append(("audio", base64.b64decode(inline["data"])))
    if (content.get("inputTranscription") or {}).get("text"):
        events.append(("heard", content["inputTranscription"]["text"]))
    if (content.get("outputTranscription") or {}).get("text"):
        events.append(("said", content["outputTranscription"]["text"]))
    if content.get("interrupted"):
        events.append(("interrupted",))
    if content.get("turnComplete"):
        events.append(("turn_complete",))
    if (msg.get("toolCall") or {}).get("functionCalls"):
        events.append(("tool_call", msg["toolCall"]["functionCalls"]))
    if msg.get("goAway") is not None:
        events.append(("go_away",))
    return events


class Player:
    def __init__(self, which=shutil.which, popen=subprocess.Popen):
        self.which, self.popen, self.proc = which, popen, None

    def command(self):
        for cmd in PLAYERS:
            if self.which(cmd[0]):
                return list(cmd)
        return None

    def write(self, pcm):
        if self.proc is None or self.proc.poll() is not None:
            cmd = self.command()
            if not cmd:
                raise LiveError("no audio player. Install pulseaudio-utils, pipewire or ffmpeg.")
            self.proc = self.popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        try:
            self.proc.stdin.write(pcm)
            self.proc.stdin.flush()
        except (BrokenPipeError, OSError, ValueError):
            self.proc = None

    def finish(self):
        if self.proc is not None:
            try:
                self.proc.stdin.close()
            except (OSError, ValueError):
                pass

    def busy(self):
        return self.proc is not None and self.proc.poll() is None

    def stop(self):
        proc, self.proc = self.proc, None
        if proc is not None:
            for fn in (proc.stdin.close, proc.terminate):
                try:
                    fn()
                except (OSError, ValueError, AttributeError):
                    pass


class LiveSession:
    def __init__(self, config, emit, which=shutil.which, popen=subprocess.Popen, connect=None, tools=None, runner=None):
        self.config, self.emit = config, emit
        self.tools, self.runner = tools, runner  # tools: declarations; runner(name, args) -> text
        self.which, self.popen = which, popen
        self.connect = connect or (lambda url: wsclient.Ws.connect(url, SETUP_TIMEOUT))
        self.player = Player(which, popen)
        self.ws = None
        self.stopping = threading.Event()
        self.rec = None
        self.speaking = False
        self.rejections = []

    def tool_response(self, call):
        if not self.runner:
            return {"error": "tools are not available here"}
        try:
            return {"output": str(self.runner(call.get("name", ""), call.get("args") or {}))}
        except Exception as e:
            return {"error": scrub(e)[:200]}

    def open(self):
        key = self.config.key("gemini")
        if not key:
            raise LiveError("Live needs a Gemini key. Add one in Settings.")
        model = self.config.data.get("live_model") or DEFAULT_MODEL
        voice = self.config.data.get("voice") or "Kore"
        instruction = "You are Faze, a quick, warm voice assistant for %s. Keep answers short and spoken." % self.config.data.get("user_name", "there")
        for shape in ("generation", "flat"):
            try:
                ws = self.connect(URL.format(key=key))
            except Exception as e:
                raise LiveError("could not connect: " + scrub(e)[:160])
            try:
                ws.send(json.dumps(build_setup(model, instruction, voice, shape, self.tools if self.runner else None)))
                first = json.loads(ws.recv(timeout=SETUP_TIMEOUT))
            except Exception as e:
                reason = scrub(e)[:300]
                try:
                    ws.close()
                except Exception:
                    pass
                self.rejections.append("%s: %s" % (shape, reason))
                if RETRYABLE.search(reason):
                    continue
                raise LiveError(reason)
            if "setupComplete" in first:
                self.ws = ws
                return
            ws.close()
            raise LiveError("the server did not confirm the setup: " + scrub(json.dumps(first))[:160])
        raise LiveError("setup was refused: " + " | ".join(self.rejections)[:400])

    def _mic(self):
        argv = next((a for n, a in vc.RECORDERS if self.which(n)), None)
        if not argv:
            raise LiveError("no recorder found. Install pipewire, pulseaudio-utils or alsa-utils.")
        self.rec = self.popen(list(argv), stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        barge = bool(self.config.data.get("live_barge_in"))
        while not self.stopping.is_set():
            chunk = self.rec.stdout.read(vc.CHUNK_BYTES)
            if not chunk:
                break
            if self.speaking and not barge:
                self.emit({"t": "level", "v": 0.0})
                continue
            self.emit({"t": "level", "v": min(1.0, vc.Voice.rms(chunk) / 4000.0)})
            try:
                self.ws.send(json.dumps({"realtimeInput": {"audio": {"data": base64.b64encode(chunk).decode(), "mimeType": "audio/pcm;rate=16000"}}}))
            except Exception:
                break

    def run(self):
        """Blocks until stopped or the connection ends. Events: voice_state, level, voice_user, voice_said, error."""
        try:
            self.open()
        except LiveError as e:
            self.emit({"t": "error", "message": str(e)})
            return
        mic = threading.Thread(target=self._mic, daemon=True, name="live-mic")
        try:
            mic.start()
            self.emit({"t": "voice_state", "state": "listening"})
            while not self.stopping.is_set():
                try:
                    raw = self.ws.recv(timeout=0.2)
                except TimeoutError:
                    if self.speaking and not self.player.busy() and not self._more:
                        self._listen()
                    continue
                for ev in parse_message(json.loads(raw if isinstance(raw, str) else raw.decode("utf-8", "replace"))):
                    self._on(ev)
        except wsclient.WsClosed as e:
            if not self.stopping.is_set():
                self.emit({"t": "error", "message": "Live ended: " + scrub(e)[:200]})
        except LiveError as e:
            self.emit({"t": "error", "message": str(e)})
        finally:
            self.stop()
            self.emit({"t": "voice_state", "state": "off"})

    _more = False

    def _listen(self):
        self.speaking = False
        self.emit({"t": "voice_state", "state": "listening"})

    def _on(self, ev):
        kind = ev[0]
        if kind == "audio":
            if not self.speaking:
                self.speaking = True
                self.emit({"t": "voice_state", "state": "speaking"})
            self._more = True
            self.emit({"t": "level", "v": min(1.0, vc.Voice.rms(ev[1]) / 6000.0)})
            self.player.write(ev[1])
        elif kind == "heard":
            self.emit({"t": "voice_user_live", "text": ev[1]})
        elif kind == "said":
            self.emit({"t": "voice_said", "text": ev[1]})
        elif kind == "interrupted":
            self.player.stop()
            self._more = False
            self._listen()
        elif kind == "turn_complete":
            self._more = False
            self.player.finish()
        elif kind == "tool_call":
            replies = [{"id": c.get("id", ""), "name": c.get("name", ""), "response": self.tool_response(c)} for c in ev[1]]
            self.ws.send(json.dumps({"toolResponse": {"functionResponses": replies}}))
        elif kind == "go_away":
            raise wsclient.WsClosed(1001, "the server is ending the session")

    def stop(self):
        self.stopping.set()
        self.player.stop()
        if self.rec is not None:
            try:
                self.rec.terminate()
            except OSError:
                pass
        if self.ws is not None:
            try:
                self.ws.close()
            except Exception:
                pass
