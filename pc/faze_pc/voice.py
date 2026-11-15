"""Voice on the PC: record from the microphone, turn it into text, and speak replies.
Recording uses whatever the system has (pw-record, parecord or arecord). Speech to text goes to Groq's Whisper when there is a Groq key, else to Gemini.
The reply voice is Gemini's natural voice (same call as the phone) when there is a Gemini key; without one, or when the call fails, a system voice (spd-say or espeak-ng) if installed."""
import base64
import json
import os
import shutil
import signal
import struct
import subprocess
import tempfile
import threading
import urllib.error
import urllib.request
import uuid

GEMINI_TTS_URL = "https://generativelanguage.googleapis.com/v1beta/interactions"
GEMINI_STT_URL = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent"
GROQ_STT_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
RECORDERS = [
    ("pw-record", ["pw-record", "--rate", "16000", "--channels", "1", "{file}"]),
    ("parecord", ["parecord", "--rate=16000", "--channels=1", "--file-format=wav", "{file}"]),
    ("arecord", ["arecord", "-q", "-f", "S16_LE", "-r", "16000", "-c", "1", "-t", "wav", "{file}"]),
]
PLAYERS = [
    ("pw-play", ["pw-play", "{file}"]),
    ("paplay", ["paplay", "{file}"]),
    ("aplay", ["aplay", "-q", "{file}"]),
    ("ffplay", ["ffplay", "-nodisp", "-autoexit", "-loglevel", "quiet", "{file}"]),
]
SYSTEM_VOICES = [("spd-say", ["spd-say", "-w", "{text}"]), ("espeak-ng", ["espeak-ng", "{text}"])]


class VoiceError(Exception):
    pass


def wav_from_pcm(pcm, rate=24000):
    """16-bit mono PCM with a WAV header, so any player can play it."""
    return b"RIFF" + struct.pack("<I", 36 + len(pcm)) + b"WAVEfmt " + struct.pack("<IHHIIHH", 16, 1, 1, rate, rate * 2, 2, 16) + b"data" + struct.pack("<I", len(pcm)) + pcm


def multipart(fields, file_field, filename, content, mime):
    boundary = "----faze" + uuid.uuid4().hex
    out = b""
    for k, v in fields.items():
        out += ("--%s\r\nContent-Disposition: form-data; name=\"%s\"\r\n\r\n%s\r\n" % (boundary, k, v)).encode()
    out += ("--%s\r\nContent-Disposition: form-data; name=\"%s\"; filename=\"%s\"\r\nContent-Type: %s\r\n\r\n" % (boundary, file_field, filename, mime)).encode() + content + b"\r\n"
    out += ("--%s--\r\n" % boundary).encode()
    return out, "multipart/form-data; boundary=" + boundary


def _post(url, headers, body, timeout=40):
    req = urllib.request.Request(url, data=body, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.read()
    except urllib.error.HTTPError as e:
        raise VoiceError("%s answered %d" % (url.split("/")[2], e.code))
    except (urllib.error.URLError, OSError) as e:
        raise VoiceError("could not reach %s: %s" % (url.split("/")[2], getattr(e, "reason", e)))


def gemini_audio(body):
    """The base64 audio of the last model_output step of an Interactions answer."""
    try:
        steps = json.loads(body).get("steps") or []
    except ValueError:
        return None
    found = None
    for s in steps:
        if s.get("type") == "model_output":
            for c in s.get("content") or []:
                if c.get("type") == "audio" and c.get("data"):
                    found = c["data"]
    return found


class Voice:
    def __init__(self, config, which=shutil.which, popen=subprocess.Popen, post=_post):
        self.config = config
        self.which = which
        self.popen = popen
        self.post = post
        self._rec = None
        self._rec_file = None
        self._play = None
        self._lock = threading.Lock()

    # --- microphone
    def recorder(self):
        for name, argv in RECORDERS:
            if self.which(name):
                return argv
        return None

    def start_listening(self):
        argv = self.recorder()
        if not argv:
            raise VoiceError("no recorder found. Install pipewire, pulseaudio-utils or alsa-utils.")
        with self._lock:
            if self._rec:
                return
            fd, path = tempfile.mkstemp(prefix="faze-mic-", suffix=".wav")
            os.close(fd)
            self._rec_file = path
            self._rec = self.popen([a.replace("{file}", path) for a in argv], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    def cancel_listening(self):
        with self._lock:
            rec, path = self._rec, self._rec_file
            self._rec = self._rec_file = None
        if rec:
            self._end(rec)
        if path and os.path.exists(path):
            os.remove(path)

    def stop_listening(self):
        """Stops recording and returns the text that was said ('' when nothing was heard)."""
        with self._lock:
            rec, path = self._rec, self._rec_file
            self._rec = self._rec_file = None
        if not rec:
            raise VoiceError("not listening")
        self._end(rec)
        try:
            with open(path, "rb") as f:
                audio = f.read()
        finally:
            if os.path.exists(path):
                os.remove(path)
        if len(audio) < 2000:
            return ""
        return self.transcribe(audio)

    def _end(self, proc):
        try:
            proc.send_signal(signal.SIGINT)  # lets the recorder finish the file's header
            proc.wait(timeout=3)
        except Exception:
            try:
                proc.kill()
            except Exception:
                pass

    def transcribe(self, wav):
        groq = self.config.key("groq")
        if groq:
            body, ctype = multipart({"model": "whisper-large-v3-turbo", "response_format": "json"}, "file", "audio.wav", wav, "audio/wav")
            return json.loads(self.post(GROQ_STT_URL, {"Authorization": "Bearer " + groq, "Content-Type": ctype}, body)).get("text", "").strip()
        gem = self.config.key("gemini")
        if gem:
            model = self.config.data["models"].get("gemini") or "gemini-2.5-flash"
            payload = {"contents": [{"parts": [{"text": "Transcribe this speech exactly. Reply with only the words that were said, or nothing if there is no speech."},
                                              {"inline_data": {"mime_type": "audio/wav", "data": base64.b64encode(wav).decode("ascii")}}]}]}
            r = json.loads(self.post(GEMINI_STT_URL % model, {"x-goog-api-key": gem, "Content-Type": "application/json"}, json.dumps(payload).encode()))
            try:
                return "".join(p.get("text", "") for p in r["candidates"][0]["content"]["parts"]).strip()
            except (KeyError, IndexError, TypeError):
                return ""
        raise VoiceError("add a Groq or Gemini key in settings to turn speech into text")

    # --- speaking
    def synth_gemini(self, text):
        key = self.config.key("gemini")
        if not key:
            raise VoiceError("no Gemini key")
        body = {"model": self.config.data.get("tts_model") or "gemini-3.8-flash-tts",
                "input": [{"type": "user_input", "content": [{"type": "text", "text": text}]}],
                "response_format": {"type": "audio"},
                "generation_config": {"speech_config": [{"voice": self.config.data.get("voice") or "Kore"}]}}
        raw = self.post(GEMINI_TTS_URL, {"x-goog-api-key": key, "Content-Type": "application/json"}, json.dumps(body).encode(), 40)
        b64 = gemini_audio(raw)
        if not b64:
            raise VoiceError("Gemini voice sent no sound")
        data = base64.b64decode(b64)
        return data if data[:4] == b"RIFF" else wav_from_pcm(data)

    def player(self):
        for name, argv in PLAYERS:
            if self.which(name):
                return argv
        return None

    def speak(self, text):
        """Speaks text: Gemini's voice if possible, else a system voice. Returns how it was spoken ('gemini', 'system') or raises VoiceError."""
        text = (text or "").strip()[:1500]
        if not text:
            return None
        self.stop_speaking()
        tried = ""
        try:
            wav = self.synth_gemini(text)
            argv = self.player()
            if not argv:
                raise VoiceError("no audio player found")
            fd, path = tempfile.mkstemp(prefix="faze-say-", suffix=".wav")
            with os.fdopen(fd, "wb") as f:
                f.write(wav)
            try:
                self._run(argv, file=path)
            finally:
                os.remove(path)
            return "gemini"
        except VoiceError as e:
            tried = str(e)
        for name, argv in SYSTEM_VOICES:
            if self.which(name):
                self._run(argv, text=text)
                return "system"
        raise VoiceError("could not speak: %s" % tried)

    def _run(self, argv, file="", text=""):
        proc = self.popen([a.replace("{file}", file).replace("{text}", text) for a in argv], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        with self._lock:
            self._play = proc
        try:
            proc.wait()
        finally:
            with self._lock:
                if self._play is proc:
                    self._play = None

    def stop_speaking(self):
        with self._lock:
            p = self._play
        if p:
            try:
                p.terminate()
            except Exception:
                pass
