"""Decodes the phone's live H.264 screen video into pictures for the mirror window. The phone pushes video pieces as it records; they are fed to one
ffmpeg process that turns them into JPEG pictures as fast as they arrive. Only the newest picture is kept for the window, so a slow window skips ahead."""
import base64
import shutil
import subprocess
import threading
import time

from .stream import JpegSplitter

ARGS = ["ffmpeg", "-loglevel", "error", "-fflags", "nobuffer", "-flags", "low_delay", "-probesize", "32768", "-analyzeduration", "0",
        "-f", "h264", "-i", "-", "-q:v", "4", "-f", "mjpeg", "-"]


class H264Decoder:
    def __init__(self, on_frame, on_error, which=shutil.which, popen=subprocess.Popen):
        self.on_frame, self.on_error = on_frame, on_error
        self.which, self.popen = which, popen
        self.proc = None
        self.running = False
        self.latest, self.seq = None, 0
        self.bytes_in = 0
        self.frames = 0
        self.tail = ""  # the last thing ffmpeg complained about
        self.cond = threading.Condition()
        self.lock = threading.Lock()

    def feed(self, data):
        """Video bytes from the phone. Starts the decoder on the first piece."""
        with self.lock:
            if not self.running and not self._start():
                return
            proc = self.proc
        self.bytes_in += len(data)
        try:
            proc.stdin.write(data)
            proc.stdin.flush()
        except (OSError, ValueError):
            self.stop()

    def _start(self):
        if not self.which("ffmpeg"):
            self.on_error("Showing the phone live needs ffmpeg on the PC: sudo apt install ffmpeg")
            return False
        try:
            self.proc = self.popen(ARGS, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        except OSError as e:
            self.on_error("Could not start ffmpeg: %s" % e)
            return False
        self.running = True
        threading.Thread(target=self._read, args=(self.proc,), daemon=True, name="h264-read").start()
        threading.Thread(target=self._drain, args=(self.proc,), daemon=True, name="h264-err").start()
        threading.Thread(target=self._send, daemon=True, name="h264-send").start()
        return True

    def _drain(self, proc):
        """Keeps ffmpeg's messages flowing (a full error pipe would stall it) and remembers the last one."""
        try:
            for line in iter(proc.stderr.readline, b""):
                text = line.decode("utf-8", "replace").strip()
                if text:
                    self.tail = text[-200:]
        except (OSError, ValueError):
            pass

    def _read(self, proc):
        split = JpegSplitter()
        while self.running and self.proc is proc:
            chunk = proc.stdout.read(65536)
            if not chunk:
                break
            for jpeg in split.feed(chunk):
                with self.cond:
                    self.latest, self.seq = jpeg, self.seq + 1
                    self.frames += 1
                    self.cond.notify()
        if self.running and self.proc is proc:
            time.sleep(0.1)  # let the error reader see ffmpeg's last words
            self.stop()
            self.on_error(self.tail or "the video decoder stopped")

    def _send(self):
        sent = 0
        while self.running:
            with self.cond:
                while self.running and self.seq == sent:
                    self.cond.wait(0.5)
                if not self.running:
                    return
                jpeg, sent = self.latest, self.seq
            try:
                self.on_frame(base64.b64encode(jpeg).decode())
            except Exception:
                pass

    def stop(self):
        with self.lock:
            self.running = False
            proc, self.proc = self.proc, None
        with self.cond:
            self.cond.notify_all()
        if proc is not None:
            for fn in (lambda: proc.stdin.close(), proc.terminate):
                try:
                    fn()
                except (OSError, ValueError, AttributeError):
                    pass
