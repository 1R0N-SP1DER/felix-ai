"""
felix-ai Core -- voice_session.py
One spoken exchange run entirely on the server, so `faze start --voice` never has to open the web app:

    listening -> transcribing -> thinking (reply streams) -> speaking -> done

Speech starts while the reply is still being written: finished sentences go to the phone's voice as they appear
(if several pile up while one is spoken they are said together, so slow generation does not leave gaps).

State is published to every subscriber as a "voice" SSE event (a full snapshot each time, so a dropped event
costs nothing) and readable at GET /api/voice/state. The /overlay page renders it; a Tasker Scene shows that page
as a floating overlay. Audio comes from the wake listener's stream (hold() keeps the wake word from reacting to
it); if the wake listener is off, a short direct recording is used instead.
"""

import queue
import re
import shutil
import subprocess
import threading
import time

import action_log
import net_status
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


_CODE = re.compile(r"```.*?```", re.S)
_END = re.compile(r"[.!?\u2026][\"')\]]*\s+|\n+")


def split_speakable(buffer: str, final: bool = False):
    """Finished sentences in text that is still arriving: (sentences, rest). Code blocks are said once as
    "Code omitted", fragments under 20 characters wait for the next sentence, and a sentence end needs a space
    after it, so "3.5" or "v2." mid-word is not a break."""
    text = _CODE.sub(" Code omitted. ", buffer)
    hold = ""
    i = text.find("```")
    if i >= 0:
        hold, text = text[i:], text[:i]
    sentences, start = [], 0
    for m in _END.finditer(text):
        piece = re.sub(r"[ \t]+", " ", text[start:m.end()]).strip()
        if len(piece) >= 20 or ("\n" in m.group(0) and piece):
            sentences.append(piece)
            start = m.end()
    if final:
        tail = re.sub(r"[ \t]+", " ", text[start:]).strip()
        if tail:
            sentences.append(tail)
        return sentences, ""
    return sentences, text[start:] + hold


class SentenceStream:
    """Feed pieces as they stream in; get back the sentences that just became complete."""

    def __init__(self):
        self.reset()

    def reset(self):
        self.text, self.done = "", 0

    def feed(self, piece: str) -> list:
        self.text += piece
        found, _ = split_speakable(self.text)
        new, self.done = found[self.done:], len(found)
        return new

    def finish(self) -> list:
        found, _ = split_speakable(self.text, final=True)
        new, self.done = found[self.done:], len(found)
        return new


class _Speaker:
    """Says sentences one after another on a worker thread, while the reply is still being generated."""

    def __init__(self, session, cancel, on_first):
        self.session, self.cancel, self.on_first = session, cancel, on_first
        self.q = queue.Queue()
        self.thread = None
        self.spoke = False

    def say(self, sentence: str) -> None:
        text = speakable(sentence)
        if not text:
            return
        if self.thread is None:
            self.thread = threading.Thread(target=self._loop, daemon=True)
            self.thread.start()
        self.q.put(text)

    def finish(self) -> None:
        """Wait until everything queued has been said (or the exchange was cancelled)."""
        self.q.put(None)
        if self.thread is not None:
            self.thread.join(SPEAK_TIMEOUT)

    def abort(self) -> None:
        while True:
            try:
                self.q.get_nowait()
            except queue.Empty:
                break
        self.q.put(None)

    def _loop(self) -> None:
        ended = False
        while not ended and not self.cancel.is_set():
            item = self.q.get()
            if item is None:
                break
            batch = [item]
            while True:  # whatever piled up while the last one was spoken goes out as one utterance
                try:
                    nxt = self.q.get_nowait()
                except queue.Empty:
                    break
                if nxt is None:
                    ended = True
                    break
                batch.append(nxt)
            if self.cancel.is_set():
                break
            self._say(" ".join(batch)[:SPEAK_CHARS])

    def _say(self, text: str) -> None:
        session = self.session
        try:
            proc = session._popen(["termux-tts-speak", text], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        except (OSError, ValueError) as e:
            action_log.log_action("voice", "speak", {}, f"ERROR {getattr(e, 'strerror', None) or e}", 0, echo=False)
            return
        session._tts = proc
        if not self.spoke:
            self.spoke = True
            self.on_first()
        try:
            deadline = time.monotonic() + SPEAK_TIMEOUT
            while proc.poll() is None:
                if self.cancel.is_set() or time.monotonic() > deadline:
                    try:
                        proc.terminate()
                    except OSError:
                        pass
                    break
                time.sleep(0.05)
        finally:
            session._tts = None


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
        self._snap = {"state": IDLE, "transcript": "", "reply": "", "error": "", "note": "", "timings": {}, "seq": 0, "active": False}

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
            self._snap.update(state=LISTENING, transcript="", reply="", error="", note="", timings={}, active=True)
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
        speaker = None
        marks = {"start": time.monotonic()}

        def ms(a, b):
            return int((marks[b] - marks[a]) * 1000) if a in marks and b in marks else None

        def timings():
            t = {"listen_ms": ms("start", "heard"), "stt_ms": ms("heard", "text"), "first_token_ms": ms("text", "token"),
                 "first_speech_ms": ms("text", "speech"), "total_ms": ms("start", "end")}
            return {k: v for k, v in t.items() if v is not None}
        try:
            if listener is not None:
                listener.hold(True)
                held = True
            pcm = self._hear(listener)
            marks["heard"] = time.monotonic()
            if cancel.is_set():
                return
            if not pcm:
                self._set(gen, state=IDLE, note="I did not hear anything")
                return
            self._set(gen, state=TRANSCRIBING)
            text = self._transcribe(pcm)
            marks["text"] = time.monotonic()
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
            streamed, sentences = [], SentenceStream()

            def first_speech():
                marks.setdefault("speech", time.monotonic())
                if self.state()["state"] == THINKING:
                    self._set(gen, state=SPEAKING)

            speaker = _Speaker(self, cancel, first_speech) if server.config["voice"].get("speak", True) else None

            def on_token(kind, piece):
                if cancel.is_set():
                    return
                if kind == "reset":  # a tool call followed text: what was shown is dropped; unspoken text too
                    streamed.clear()
                    sentences.reset()
                    if speaker is not None:
                        speaker.abort()
                        speaker.q = queue.Queue()
                        speaker.thread = None
                elif kind == "text":
                    marks.setdefault("token", time.monotonic())
                    streamed.append(piece)
                    self._set(gen, reply="".join(streamed))
                    if speaker is not None:
                        for sentence in sentences.feed(piece):
                            speaker.say(sentence)

            convo.on_token = on_token
            try:
                reply = convo.send(text)
            finally:
                convo.on_token = None
                server.chat_lock.release()
                locked = False
            if cancel.is_set():
                return
            marks.setdefault("token", time.monotonic())
            shown = "".join(streamed)
            if speaker is not None:
                if not shown:  # commands and direct tools answer without streaming
                    sentences.reset()
                    sentences.feed(reply)
                elif reply.startswith(shown) and len(reply) > len(shown):
                    sentences.feed(reply[len(shown):])
                for sentence in sentences.finish():
                    speaker.say(sentence)
                if speaker.thread is not None and self.state()["state"] == THINKING:
                    self._set(gen, reply=reply, state=SPEAKING)
            self._set(gen, reply=reply)
            if speaker is not None:
                speaker.finish()
            if not cancel.is_set():
                marks["end"] = time.monotonic()
                t = timings()
                action_log.log_action("voice", "timings", t, "done", 0, echo=False)
                self._set(gen, state=DONE, timings=t)
        except Exception as e:  # nothing here may take the server down
            from tool_replies import summarize_error
            self._set(gen, state=ERROR, error=summarize_error(e))
        finally:
            if speaker is not None:
                speaker.abort()
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
            text = stt.transcribe_best(wake.wav_from_pcm(pcm), cfg)
        finally:
            self.server.stt_lock.release()
        return (text or "").strip()
