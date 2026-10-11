"""
felix-ai Core -- voice_session.py
One spoken exchange run entirely on the server, so `faze start --voice` never has to open the web app:

    listening -> transcribing -> thinking (reply streams) -> speaking -> done

State is published to every subscriber as a "voice" SSE event (a full snapshot each time, so a dropped event
costs nothing) and readable at GET /api/voice/state. The /overlay page renders it; a Tasker Scene shows that page
as a floating overlay. Audio comes from the wake listener's stream (hold() keeps the wake word from reacting to
it); if the wake listener is off, a short direct recording is used instead.
"""

import re
import shutil
import subprocess
import threading
import time

import action_log
import stt
import wake

LISTEN_SECONDS_MAX = 15.0
FALLBACK_SECONDS = 6
SPEAK_TIMEOUT = 180.0
SPEAK_CHARS = 1500
IDLE, LISTENING, TRANSCRIBING, THINKING, SPEAKING, DONE, ERROR = (
    "idle", "listening", "transcribing", "thinking", "speaking", "done", "error")
ACTIVE = (LISTENING, TRANSCRIBING, THINKING, SPEAKING)
TASKER_ACTION = "net.dinglisch.android.tasker.ACTION_TASK"


def speakable(text: str) -> str:
    """Reply text without markdown, code blocks and links, so the phone does not read out symbols."""
    text = re.sub(r"```.*?```", " ", text or "", flags=re.S)
    text = re.sub(r"`([^`]*)`", r"\1", text)
    text = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", text)
    text = re.sub(r"https?://\S+", " link ", text)
    text = re.sub(r"[*_#>~|]+", " ", text)
    return re.sub(r"\s+", " ", text).strip()[:SPEAK_CHARS]


class VoiceSession:
    def __init__(self, server, popen=subprocess.Popen, run=None):
        self.server = server
        self._popen = popen
        self._run_cmd = run  # run(argv) for am/termux-open-url; defaults to tool_helpers.run (clears LD_PRELOAD)
        self._lock = threading.Lock()
        self._cancel = threading.Event()
        self._gen = 0
        self._thread = None
        self._tts = None
        self._snap = {"state": IDLE, "transcript": "", "reply": "", "error": "", "note": "", "seq": 0, "active": False}

    # ---- state -------------------------------------------------------------

    def state(self) -> dict:
        with self._lock:
            return dict(self._snap)

    def active(self) -> bool:
        return self._snap["state"] in ACTIVE

    def _set(self, gen: int, **fields) -> None:
        with self._lock:
            if gen != self._gen:
                return
            self._snap.update(fields)
            self._snap["active"] = self._snap["state"] in ACTIVE
            self._snap["seq"] += 1
            snap = dict(self._snap)
        self.server.publish("voice", snap)

    # ---- control -----------------------------------------------------------

    def start(self, overlay: bool = False) -> dict:
        """Begin one exchange. Returns {"started": bool, **state}; an exchange already running is left alone."""
        with self._lock:
            if self._snap["state"] in ACTIVE:
                return {"started": False, **self._snap}
            self._gen += 1
            gen = self._gen
            self._cancel = threading.Event()
            self._snap.update(state=LISTENING, transcript="", reply="", error="", note="", active=True)
            self._snap["seq"] += 1
            snap = dict(self._snap)
            cancel = self._cancel
        self.server.publish("voice", snap)
        action_log.log_action("voice", "start", {"overlay": overlay}, "listening", 0, echo=False)
        self._thread = threading.Thread(target=self._exchange, args=(gen, cancel), daemon=True)
        self._thread.start()
        if overlay:
            self.tasker(self.server.config["voice"].get("overlay_task", ""))
        return {"started": True, **snap}

    def stop(self, close_overlay: bool = False) -> dict:
        with self._lock:
            gen, was_active = self._gen, self._snap["state"] in ACTIVE
        self._cancel.set()
        if was_active:
            self.server.convo.cancel()
        proc = self._tts
        if proc is not None:
            try:
                proc.terminate()
            except OSError:
                pass
        if was_active:
            self._set(gen, state=IDLE, note="Stopped", error="")
        if close_overlay:
            self.tasker(self.server.config["voice"].get("overlay_close_task", ""))
        return self.state()

    def open_app(self, close_overlay: bool = False) -> bool:
        """Open the web app (what the overlay's Open Faze button does)."""
        link = f"http://localhost:{self.server.port}/#t={self.server.token}"
        ok = self._launch(["am", "start", "-a", "android.intent.action.VIEW", "-d", link])
        if not ok:
            ok = self._launch(["termux-open-url", link])
        if close_overlay:
            self.tasker(self.server.config["voice"].get("overlay_close_task", ""))
        return ok

    # ---- Android launches --------------------------------------------------

    def _launch(self, argv: list) -> bool:
        if not shutil.which(argv[0]) and self._run_cmd is None:
            return False
        try:
            if self._run_cmd is not None:
                self._run_cmd(argv)
            else:
                import tool_helpers
                tool_helpers.run(argv, timeout=8)
            return True
        except Exception as e:  # a missing launcher must never take the exchange down
            action_log.log_action("voice", "launch", {"cmd": argv[0]}, f"ERROR {e}", 0, echo=False)
            return False

    def tasker(self, task: str) -> bool:
        """Ask Tasker to run a task (it shows or closes the overlay Scene). Needs Tasker's Allow External Access."""
        if not task:
            return False
        return self._launch(["am", "broadcast", "-a", TASKER_ACTION, "--es", "task_name", task])

    # ---- the exchange ------------------------------------------------------

    def _exchange(self, gen: int, cancel: threading.Event) -> None:
        server = self.server
        listener = server.wake
        convo = server.convo
        held = False
        locked = False
        try:
            if listener is not None:
                listener.hold(True)
                held = True
            pcm = self._hear(listener)
            if cancel.is_set():
                return
            if not pcm:
                self._set(gen, state=IDLE, note="I did not hear anything")
                return
            self._set(gen, state=TRANSCRIBING)
            text = self._transcribe(pcm)
            if cancel.is_set():
                return
            if not text:
                self._set(gen, state=IDLE, note="I did not catch that")
                return
            self._set(gen, transcript=text, state=THINKING)
            if not server.chat_lock.acquire(blocking=False):
                self._set(gen, state=ERROR, error="Faze is still answering the last message")
                return
            locked = True
            streamed = []

            def on_token(kind, piece):
                if cancel.is_set():
                    return
                if kind == "reset":
                    streamed.clear()
                elif kind == "text":
                    streamed.append(piece)
                    self._set(gen, reply="".join(streamed))

            convo.on_token = on_token
            try:
                reply = convo.send(text)
            finally:
                convo.on_token = None
                server.chat_lock.release()
                locked = False
            if cancel.is_set():
                return
            self._set(gen, reply=reply, state=SPEAKING)
            self._speak(reply, cancel)
            if not cancel.is_set():
                self._set(gen, state=DONE)
        except Exception as e:  # nothing here may take the server down
            from tool_replies import summarize_error
            self._set(gen, state=ERROR, error=summarize_error(e))
        finally:
            if locked:
                server.chat_lock.release()
            if held and listener is not None:
                listener.hold(False)

    def _hear(self, listener):
        """One utterance of PCM, or None. The wake stream when it runs, else a short direct recording."""
        cfg = self.server.config
        timeout = float(cfg["voice"].get("listen_timeout", 8))
        if listener is not None:
            pcm = listener.capture_utterance(max_seconds=LISTEN_SECONDS_MAX, start_timeout=timeout)
            if pcm is not None:
                return pcm
            if listener._live():
                return None  # the stream is fine, nobody spoke
        import device
        try:
            pcm = device.record_pcm(FALLBACK_SECONDS)
        except device.DeviceError as e:
            raise RuntimeError(f"no microphone: {e}")
        return pcm if any(pcm) else None

    def _transcribe(self, pcm: bytes) -> str:
        cfg = self.server.config
        if not self.server.stt_lock.acquire(timeout=20):
            raise RuntimeError("still transcribing the last clip")
        try:
            text = stt.transcribe_wake(wake.wav_from_pcm(pcm), cfg)
        finally:
            self.server.stt_lock.release()
        return (text or "").strip()

    def _speak(self, reply: str, cancel: threading.Event) -> None:
        if not self.server.config["voice"].get("speak", True):
            return
        text = speakable(reply)
        if not text:
            return
        try:
            proc = self._popen(["termux-tts-speak", text], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        except (OSError, ValueError) as e:
            action_log.log_action("voice", "speak", {}, f"ERROR {getattr(e, 'strerror', None) or e}", 0, echo=False)
            return
        self._tts = proc
        try:
            deadline = time.monotonic() + SPEAK_TIMEOUT
            while proc.poll() is None:
                if cancel.is_set() or time.monotonic() > deadline:
                    try:
                        proc.terminate()
                    except OSError:
                        pass
                    break
                time.sleep(0.1)
        finally:
            self._tts = None
