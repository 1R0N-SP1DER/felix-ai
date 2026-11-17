"""Live view of this PC's screen for the phone: a continuous stream of JPEG pictures, as fast as the link takes them.
Capture is chosen by what the desktop offers: the screen-cast portal on Wayland (best, one approval the first time), ffmpeg x11grab on X11, or ffmpeg kmsgrab
(needs one setcap command). Pictures are never queued: when the phone or the link is slower than the capture, old pictures are dropped and the newest is sent."""
import base64
import os
import shutil
import subprocess
import sys
import threading
import time

SOI, EOI = b"\xff\xd8", b"\xff\xd9"
MAX_BUF = 8 * 1024 * 1024
IDLE = 10.0


class JpegSplitter:
    """Cuts a byte stream of back-to-back JPEG pictures into pictures. Start-of-image to end-of-image; 0xFF inside picture data is always stuffed, so FFD9 only ends a picture."""

    def __init__(self):
        self.buf = bytearray()

    def feed(self, data):
        self.buf += data
        out = []
        while True:
            s = self.buf.find(SOI)
            if s < 0:
                last = self.buf[-1:] if self.buf.endswith(b"\xff") else b""  # half of a start marker may be waiting
                self.buf.clear()
                self.buf += last
                break
            e = self.buf.find(EOI, s + 2)
            if e < 0:
                if s > 0:
                    del self.buf[:s]
                if len(self.buf) > MAX_BUF:
                    self.buf.clear()
                break
            out.append(bytes(self.buf[s:e + 2]))
            del self.buf[:e + 2]
        return out


def x11_args(width, fps, quality, display=":0"):
    return ["ffmpeg", "-loglevel", "error", "-f", "x11grab", "-framerate", str(fps), "-i", display, "-vf", "scale=%d:-2" % width,
            "-q:v", str(max(2, min(31, (100 - quality) // 4 + 2))), "-f", "mjpeg", "-"]


def kms_args(width, fps, quality):
    return ["ffmpeg", "-loglevel", "error", "-f", "kmsgrab", "-framerate", str(fps), "-i", "-", "-vf",
            "hwmap=derive_device=vaapi,scale_vaapi=w=%d:h=-2:format=nv12,hwdownload,format=nv12" % width,
            "-q:v", str(max(2, min(31, (100 - quality) // 4 + 2))), "-f", "mjpeg", "-"]


class ScreenStream:
    def __init__(self, on_frame, on_error, env=None, which=shutil.which, popen=subprocess.Popen, clock=time.time, python="/usr/bin/python3"):
        self.on_frame, self.on_error = on_frame, on_error
        self.env = os.environ if env is None else env
        self.which, self.popen, self.clock, self.python = which, popen, clock, python
        self.proc = None
        self.engine = ""
        self.running = False
        self.last_ping = 0.0
        self.latest = None
        self.seq = 0
        self.cond = threading.Condition()
        self.sent = 0

    def wayland(self):
        return self.env.get("XDG_SESSION_TYPE", "").lower() == "wayland" or bool(self.env.get("WAYLAND_DISPLAY"))

    def engines(self, width, fps, quality):
        """Ways to capture, best first: [(name, argv, missing-hint)]."""
        out = []
        if self.wayland():
            out.append(("portal", [self.python, "-m", "faze_pc.portal", str(width), str(fps), str(quality)],
                        None if os.path.exists(self.python) else "system Python is missing"))
            out.append(("kmsgrab", kms_args(width, fps, quality), None if self.which("ffmpeg") else "ffmpeg is missing"))
        else:
            out.append(("x11grab", x11_args(width, fps, quality, self.env.get("DISPLAY") or ":0"), None if self.which("ffmpeg") else "ffmpeg is missing"))
        return out

    def start(self, width=960, fps=30, quality=60):
        """Starts the capture. Returns the engine's name, or raises RuntimeError with what to do."""
        self.stop()
        width = max(320, min(int(width), 1920))
        fps = max(5, min(int(fps), 60))
        quality = max(20, min(int(quality), 95))
        problems = []
        for name, argv, missing in self.engines(width, fps, quality):
            if missing:
                problems.append("%s: %s" % (name, missing))
                continue
            try:
                proc = self.popen(argv, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=dict(self.env, PYTHONPATH=os.pathsep.join(filter(None, [os.path.dirname(os.path.dirname(os.path.abspath(__file__))), self.env.get("PYTHONPATH", "")]))))
            except OSError as e:
                problems.append("%s: %s" % (name, e))
                continue
            self.proc, self.engine, self.running, self.last_ping = proc, name, True, self.clock()
            threading.Thread(target=self._read, args=(proc, name), daemon=True, name="stream-read").start()
            threading.Thread(target=self._send, daemon=True, name="stream-send").start()
            threading.Thread(target=self._watch, daemon=True, name="stream-watch").start()
            return name
        raise RuntimeError(self.explain(problems))

    def explain(self, problems):
        if self.wayland():
            return ("Could not capture the screen. On KDE Wayland install: sudo apt install python3-gi gstreamer1.0-tools gstreamer1.0-pipewire gstreamer1.0-plugins-good xdg-desktop-portal-kde"
                    + (" (" + "; ".join(problems) + ")" if problems else ""))
        return "Could not capture the screen. Install ffmpeg: sudo apt install ffmpeg" + (" (" + "; ".join(problems) + ")" if problems else "")

    def ping(self):
        self.last_ping = self.clock()

    def _read(self, proc, name):
        split = JpegSplitter()
        try:
            while self.running and self.proc is proc:
                chunk = proc.stdout.read(65536)
                if not chunk:
                    break
                for jpeg in split.feed(chunk):
                    with self.cond:
                        self.latest, self.seq = jpeg, self.seq + 1  # overwrites an unsent picture: that is the dropping
                        self.cond.notify()
        finally:
            if self.proc is proc and self.running:
                err = b""
                try:
                    err = proc.stderr.read() or b""
                except (OSError, ValueError):
                    pass
                self.running = False
                with self.cond:
                    self.cond.notify_all()
                msg = err.decode("utf-8", "replace").strip().splitlines()
                self.on_error(msg[-1] if msg else "%s stopped" % name)

    def _send(self):
        sent_seq = 0
        while self.running:
            with self.cond:
                while self.running and self.seq == sent_seq:
                    self.cond.wait(0.5)
                if not self.running:
                    return
                jpeg, sent_seq = self.latest, self.seq
            try:
                self.on_frame(base64.b64encode(jpeg).decode(), sent_seq)
                self.sent += 1
            except Exception:
                self.stop()
                return

    def _watch(self):
        while self.running:
            time.sleep(1.0)
            if self.clock() - self.last_ping > IDLE:
                self.stop()
                return

    def stop(self):
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
