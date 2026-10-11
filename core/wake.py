"""
felix-ai Core -- wake.py
Always-listening wake word, fully offline.

How it works: a mic command streams raw 16 kHz audio; a small voice-activity
detector cuts it into utterances; ONLY those utterances are transcribed by
whisper.cpp (stt.py) and checked against your wake phrase. Audio is never
saved or sent anywhere: it lives in memory and is dropped after the check.

Run  python3 wake.py --check  to test that the phone's mic actually delivers sound.
"""

import array
import math
import re
import shlex
import struct
import subprocess
import sys
import threading
import time
from collections import deque
from difflib import SequenceMatcher

import stt
from action_log import log_action

RATE = 16000
FRAME_SAMPLES = 480                      # 30 ms
FRAME_BYTES = FRAME_SAMPLES * 2
FRAME_S = FRAME_SAMPLES / RATE
SILENT_FRAMES_WARN = 300                 # ~9 s of exact zeros


# ---------------------------------------------------------------- phrase matching

def normalize(text: str) -> list:
    return re.sub(r"[^a-z0-9 ]+", " ", text.lower()).split()


def skeleton(word: str) -> str:
    """Rough sound-alike key: 'faze', 'phase' and 'fays' all become 'fs'."""
    w = word.lower()
    w = w.replace("ph", "f").replace("ck", "k")
    w = re.sub(r"c(?=[aou])", "k", w).replace("c", "s")
    w = w.replace("z", "s").replace("q", "k").replace("x", "ks")
    w = re.sub(r"(.)\1+", r"\1", w)
    return w[:1] + re.sub(r"[aeiouyhw]", "", w[1:])


def token_match(heard: str, wanted: str) -> bool:
    if heard == wanted:
        return True
    ratio = SequenceMatcher(None, heard, wanted).ratio()
    return ratio >= 0.8 or (skeleton(heard) == skeleton(wanted) and ratio >= 0.4)


def matches(text: str, phrase: str) -> bool:
    """True if `text` contains the wake phrase, allowing the usual
    speech-to-text mishearings. A short utterance that hits the last
    word (the name) also counts, since 'hey' is often dropped."""
    words, want = normalize(text), normalize(phrase)
    if not words or not want:
        return False
    for i in range(len(words) - len(want) + 1):
        if all(token_match(words[i + j], want[j]) for j in range(len(want))):
            return True
    return len(want) > 1 and len(words) <= 4 and any(token_match(w, want[-1]) for w in words)


def phrase_for(config: dict) -> str:
    custom = str(config["wake"].get("phrase", "")).strip()
    return custom or f"hey {config['assistant']['name']}"


# ---------------------------------------------------------------- audio

def rms(frame: bytes) -> float:
    samples = array.array("h")
    samples.frombytes(frame[: len(frame) // 2 * 2])
    if sys.byteorder == "big":
        samples.byteswap()
    if not samples:
        return 0.0
    return math.sqrt(sum(s * s for s in samples) / len(samples)) / 32768.0


def wav_from_pcm(pcm: bytes) -> bytes:
    return (b"RIFF" + struct.pack("<I", 36 + len(pcm)) + b"WAVEfmt "
            + struct.pack("<IHHIIHH", 16, 1, 1, RATE, RATE * 2, 2, 16)
            + b"data" + struct.pack("<I", len(pcm)) + pcm)


class Vad:
    """Cuts a stream of 30 ms frames into utterances. feed() returns the
    PCM of a finished utterance, otherwise None."""

    def __init__(self, preroll=10, end_silence=23, max_frames=133, min_frames=12, start_frames=3):
        self.preroll = deque(maxlen=preroll)
        self.end_silence, self.max_frames = end_silence, max_frames
        self.min_frames, self.start_frames = min_frames, start_frames
        self.floor = 0.005
        self.loud_run = 0
        self.active = None
        self.quiet_run = 0

    def feed(self, frame: bytes):
        level = rms(frame)
        loud = level > max(0.012, self.floor * 3.0)
        if self.active is None:
            if not loud:
                self.floor = 0.97 * self.floor + 0.03 * level
                self.loud_run = 0
                self.preroll.append(frame)
                return None
            self.loud_run += 1
            self.preroll.append(frame)
            if self.loud_run >= self.start_frames:
                self.active, self.quiet_run = list(self.preroll), 0
                self.preroll.clear()
            return None
        self.active.append(frame)
        self.quiet_run = 0 if loud else self.quiet_run + 1
        if self.quiet_run >= self.end_silence or len(self.active) >= self.max_frames:
            frames, self.active, self.loud_run = self.active, None, 0
            if len(frames) - self.quiet_run < self.min_frames:
                return None
            return b"".join(frames)
        return None


# ---------------------------------------------------------------- listener

def _reap(proc):
    """Stop a mic process for good and return its exit code."""
    try:
        proc.terminate()
    except OSError:
        pass
    try:
        proc.stdout.close()
    except (OSError, ValueError):
        pass
    try:
        return proc.wait(timeout=3)
    except subprocess.TimeoutExpired:
        proc.kill()
        return proc.wait()


class WakeListener:
    def __init__(self, config, on_wake, is_busy=lambda: False, transcribe=None,
                 popen=subprocess.Popen, clock=time.monotonic, retry_s=5.0):
        self.config = config
        self.on_wake = on_wake
        self.is_busy = is_busy
        self.transcribe = transcribe or stt.transcribe_wake
        self._popen, self._clock, self.retry_s = popen, clock, retry_s
        self.state, self.detail = "off", ""
        self._stop = threading.Event()
        self._thread = None
        self._proc = None
        self._quiet_until = 0.0
        self._ring = deque(maxlen=1000)  # the last 30 s of 30 ms frames, so the listen tool and voice sessions can share this stream
        self._frames = 0
        self._held = False

    # -- control
    def start(self):
        if self._thread and self._thread.is_alive():
            return
        self._stop.clear()
        self.state, self.detail = "starting", ""
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()

    def stop(self):
        self._stop.set()
        proc = self._proc
        if proc is not None:
            try:
                proc.terminate()
            except OSError:
                pass
        if self._thread:
            self._thread.join(timeout=5)
        self.state, self.detail = "off", ""

    def status(self) -> dict:
        return {"state": self.state, "detail": self.detail, "phrase": phrase_for(self.config),
                "engine": self.config["wake"].get("engine", "auto")}

    def _set(self, state, detail=""):
        self.state, self.detail = state, detail

    # -- loop
    def _run(self):
        while not self._stop.is_set():
            if not stt.wake_ready(self.config):
                self._set("error", stt.missing_reason(self.config) or "no speech engine: add a Gemini key or install whisper")
                self._stop.wait(self.retry_s)
                continue
            cmd = shlex.split(self.config["wake"]["mic_cmd"])
            try:
                self._proc = self._popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
            except (OSError, ValueError) as e:
                self._set("error", f"mic command failed: {getattr(e, 'strerror', None) or e}")
                self._stop.wait(self.retry_s)
                continue
            self._listen(self._proc)
            code = _reap(self._proc)
            self._proc = None
            if not self._stop.is_set():
                self._set("error", f"mic command stopped (code {code}); retrying")
                self._stop.wait(self.retry_s)

    def _listen(self, proc):
        vad, silent = Vad(end_silence=max(6, int(self.config["wake"].get("end_silence_ms", 500)) // 30)), 0
        self._set("listening")
        while not self._stop.is_set():
            frame = proc.stdout.read(FRAME_BYTES)
            if len(frame) < FRAME_BYTES:
                return
            self._ring.append(frame)
            self._frames += 1
            silent = silent + 1 if not any(frame) else 0
            if silent == SILENT_FRAMES_WARN:
                self._set("silent", "the mic is delivering pure silence; Android may block background mic access "
                                    "(grant Termux the microphone permission and keep its notification)")
            elif silent == 0 and self.state == "silent":
                self._set("listening")
            utterance = vad.feed(frame)
            if utterance is not None:
                self._check(utterance)

    def hold(self, on: bool) -> None:
        """While held (a voice session is using the stream) the wake word does not react to what it hears."""
        self._held = bool(on)

    def _live(self) -> bool:
        return self.state in ("listening", "transcribing", "silent") and not self._stop.is_set()

    def capture(self, seconds: float, timeout: float = None):
        """The next `seconds` of audio from the stream the wake word already reads, or None if it is not running.
        (A second recorder on the same microphone source tends to get silence, so tools borrow this one.)"""
        if not self._live():
            return None
        need = max(1, int(seconds / FRAME_S))
        start = self._frames
        deadline = time.monotonic() + (timeout if timeout is not None else seconds + 3)
        while self._frames - start < need:
            if not self._live() or time.monotonic() > deadline:
                return None
            time.sleep(0.03)
        return b"".join(list(self._ring)[-need:])

    def capture_utterance(self, max_seconds: float = 12.0, start_timeout: float = 6.0):
        """One spoken utterance (start to pause) from the shared stream; None if nobody speaks or it is not running."""
        if not self._live():
            return None
        vad = Vad(end_silence=max(6, int(self.config["wake"].get("end_silence_ms", 500)) // 30), max_frames=int(max_seconds / FRAME_S))
        seen = self._frames
        began = time.monotonic()
        while self._live():
            if self._frames == seen:
                if vad.active is None and time.monotonic() - began > start_timeout:
                    return None
                time.sleep(0.02)
                continue
            fresh = min(self._frames - seen, len(self._ring))
            frames = list(self._ring)[-fresh:]
            seen = self._frames
            for frame in frames:
                utterance = vad.feed(frame)
                if utterance is not None:
                    return utterance
            if vad.active is None and time.monotonic() - began > start_timeout:
                return None
        return None

    def _check(self, pcm: bytes):
        if self._held or self.is_busy() or self._clock() < self._quiet_until:
            return
        self._set("transcribing")
        try:
            text = self.transcribe(wav_from_pcm(pcm), self.config)
        except stt.TranscribeError as e:
            log_action("wake", "transcribe", {}, f"ERROR {e}", 0, echo=False)
            self._set("listening")
            return
        finally:
            del pcm
        hit = matches(text, phrase_for(self.config))
        self._set("listening")
        if hit:
            self._quiet_until = self._clock() + float(self.config["wake"].get("cooldown", 6))
            log_action("wake", "heard", {"phrase": phrase_for(self.config)}, "wake word", 0, echo=False)
            self.on_wake(phrase_for(self.config), text)


# ---------------------------------------------------------------- mic check

def check_mic(config: dict, seconds: float = 3.0) -> int:
    cmd = shlex.split(config["wake"]["mic_cmd"])
    print(f"Recording {seconds:.0f}s with: {' '.join(cmd)}\nSay something now...")
    try:
        proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    except OSError as e:
        print(f"FAIL: could not run the mic command ({e.strerror or e}).")
        return 1
    levels, frames = [], int(seconds / FRAME_S)
    for _ in range(frames):
        data = proc.stdout.read(FRAME_BYTES)
        if len(data) < FRAME_BYTES:
            break
        levels.append((rms(data), any(data)))
    _reap(proc)
    if not levels:
        try:
            why = proc.stderr.read().decode(errors="replace").strip().splitlines()
        except (OSError, ValueError):
            why = []
        print("FAIL: the mic command produced no audio." + (f" It said: {why[-1][:160]}" if why else ""))
        return 1
    if not any(nonzero for _, nonzero in levels):
        print("FAIL: audio is pure silence. Grant Termux the Microphone permission in Android settings, "
              "then try again with Termux open on screen.")
        return 1
    peak = max(level for level, _ in levels)
    print(f"OK: peak level {peak:.3f} (speech is usually above 0.05). Wake word can listen.")
    return 0


if __name__ == "__main__":
    from config_loader import load_config
    cfg = load_config()
    if "--check" in sys.argv[1:]:
        raise SystemExit(check_mic(cfg))
    print("usage: python3 wake.py --check")
