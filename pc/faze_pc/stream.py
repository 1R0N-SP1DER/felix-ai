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


def kbps(width, fps, quality):
    """A sensible H.264 bitrate in kilobits a second for a 16:9 picture: more pixels, more frames and higher quality all want more."""
    height = width * 9 // 16
    bpp = 0.03 + max(20, min(95, quality)) / 100.0 * 0.07
    return max(300, int(width * height * fps * bpp / 1000))


class H264Splitter:
    """Cuts a byte stream of H.264 (Annex B: 00 00 01 start codes) into access units, one per picture, each with whether it is a keyframe.
    A unit ends when the next one begins (an access-unit delimiter, SPS/PPS/SEI after picture data, or a slice that starts at macroblock 0), or on flush()
    when the encoder has gone quiet, which is what keeps the delay down: the newest picture is not held back waiting for the one after it."""

    def __init__(self):
        self.buf = bytearray()
        self.cur = bytearray()
        self.has_vcl = False
        self.key = False

    @staticmethod
    def nals(buf):
        """[(start_of_start_code, payload_start)] for every start code in buf."""
        out, i, n = [], 0, len(buf)
        while True:
            i = buf.find(b"\x00\x00\x01", i)
            if i < 0:
                return out
            s = i - 1 if i > 0 and buf[i - 1] == 0 else i
            out.append((s, i + 3))
            i += 3

    def _begins_new(self, typ, first):
        if not self.has_vcl:
            return False
        if typ in (6, 7, 8, 9):
            return True
        return typ in (1, 5) and first is not None and first & 0x80 != 0  # first_mb_in_slice == 0

    def _take(self, out):
        if self.has_vcl and self.cur:
            out.append((bytes(self.cur), self.key))
        self.cur.clear()
        self.has_vcl = self.key = False

    def _consume(self, nal_bytes, out):
        if len(nal_bytes) < 4:
            return
        head = nal_bytes.find(b"\x01") + 1
        if head <= 0 or head >= len(nal_bytes):
            return
        typ = nal_bytes[head] & 0x1F
        first = nal_bytes[head + 1] if head + 1 < len(nal_bytes) else None
        if self._begins_new(typ, first):
            self._take(out)
        self.cur += nal_bytes
        if typ in (1, 5):
            self.has_vcl = True
            if typ == 5:
                self.key = True

    def feed(self, data):
        self.buf += data
        out = []
        marks = self.nals(self.buf)
        if len(marks) >= 2:
            for k in range(len(marks) - 1):
                self._consume(bytes(self.buf[marks[k][0]:marks[k + 1][0]]), out)
            del self.buf[:marks[-1][0]]
        if len(self.buf) > MAX_BUF:
            self.buf.clear()
            self.cur.clear()
            self.has_vcl = self.key = False
        return out

    def flush(self):
        """The encoder has been quiet: what is waiting is a whole picture."""
        out = []
        if self.buf:
            self._consume(bytes(self.buf), out)
            self.buf.clear()
        self._take(out)
        return out


def x264_args(src, width, fps, quality):
    """ffmpeg output side for low-latency H.264 on stdout; src is the input part."""
    return src + ["-c:v", "libx264", "-preset", "ultrafast", "-tune", "zerolatency", "-pix_fmt", "yuv420p", "-bf", "0", "-g", str(max(10, fps)),
                  "-b:v", "%dk" % kbps(width, fps, quality), "-maxrate", "%dk" % int(kbps(width, fps, quality) * 1.5), "-bufsize", "%dk" % max(200, kbps(width, fps, quality) // 4),
                  "-x264-params", "aud=1:repeat-headers=1", "-f", "h264", "-"]


def x11_args(width, fps, quality, display=":0", codec="jpeg"):
    if codec == "h264":
        return x264_args(["ffmpeg", "-loglevel", "error", "-f", "x11grab", "-framerate", str(fps), "-i", display, "-vf", "scale=%d:-2" % (width // 2 * 2)], width, fps, quality)
    return ["ffmpeg", "-loglevel", "error", "-f", "x11grab", "-framerate", str(fps), "-i", display, "-vf", "scale=%d:-2" % width,
            "-q:v", str(max(2, min(31, (100 - quality) // 4 + 2))), "-f", "mjpeg", "-"]


def kms_args(width, fps, quality, codec="jpeg"):
    if codec == "h264":
        return x264_args(["ffmpeg", "-loglevel", "error", "-f", "kmsgrab", "-framerate", str(fps), "-i", "-", "-vf",
                          "hwmap=derive_device=vaapi,scale_vaapi=w=%d:h=-2:format=nv12,hwdownload,format=nv12" % (width // 2 * 2)], width, fps, quality)
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
        self.tail = ""
        self.frames_made = 0
        self.first_wait = 8.0
        self.codec = "jpeg"
        self.queue = []          # h264 only: pictures waiting to be sent, oldest first
        self.need_key = False

    def wayland(self):
        return self.env.get("XDG_SESSION_TYPE", "").lower() == "wayland" or bool(self.env.get("WAYLAND_DISPLAY"))

    def engines(self, width, fps, quality, codec="jpeg"):
        """Ways to capture, best first: [(name, argv, missing-hint)]."""
        out = []
        if self.wayland():
            out.append(("portal", [self.python, "-m", "faze_pc.portal", str(width), str(fps), str(quality)] + (["h264"] if codec == "h264" else []),
                        None if os.path.exists(self.python) else "system Python is missing"))
            out.append(("kmsgrab", kms_args(width, fps, quality, codec), None if self.which("ffmpeg") else "ffmpeg is missing"))
        else:
            out.append(("x11grab", x11_args(width, fps, quality, self.env.get("DISPLAY") or ":0", codec), None if self.which("ffmpeg") else "ffmpeg is missing"))
        return out

    def start(self, width=960, fps=30, quality=60, codec="jpeg"):
        """Starts the capture. Returns the engine's name, or raises RuntimeError with what to do. codec is "jpeg" (a picture each, tolerant) or "h264"
        (much smaller, needs the phone's hardware decoder). With h264, self.codec says what is really being sent."""
        self.stop()
        self.codec = "h264" if codec == "h264" else "jpeg"
        width = max(320, min(int(width), 1920))
        fps = max(5, min(int(fps), 60))
        quality = max(20, min(int(quality), 95))
        problems = []
        for name, argv, missing in self.engines(width, fps, quality, self.codec):
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
            threading.Thread(target=self._drain, args=(proc,), daemon=True, name="stream-err").start()
            self.queue.clear()
            self.need_key = False
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

    def _drain(self, proc):
        try:
            for line in iter(proc.stderr.readline, b""):
                text = line.decode("utf-8", "replace").strip()
                if text:
                    self.tail = text[-200:]
        except (OSError, ValueError):
            pass

    def _read_h264(self, proc, name):
        import select
        split = H264Splitter()
        try:
            fd = proc.stdout.fileno()
        except (OSError, ValueError, AttributeError):
            fd = None
        try:
            while self.running and self.proc is proc:
                if fd is not None:
                    ready, _, _ = select.select([fd], [], [], 0.004)
                    if not ready:
                        units = split.flush()
                        chunk = None
                    else:
                        chunk = os.read(fd, 65536)
                        if not chunk:
                            break
                        units = split.feed(chunk)
                else:
                    chunk = proc.stdout.read(65536)
                    if not chunk:
                        break
                    units = split.feed(chunk)
                for au, key in units:
                    with self.cond:
                        self.frames_made += 1
                        if self.need_key and not key:
                            continue
                        if key:
                            self.need_key = False
                        self.queue.append((au, key))
                        if len(self.queue) > 8:  # the phone or the link is behind: throw the backlog away and wait for the next keyframe
                            self.queue.clear()
                            self.need_key = True
                        self.seq += 1
                        self.cond.notify()
        finally:
            if self.proc is proc and self.running:
                time.sleep(0.1)
                self.running = False
                with self.cond:
                    self.cond.notify_all()
                self.on_error(self.tail or "%s stopped" % name)

    def _read(self, proc, name):
        if self.codec == "h264":
            return self._read_h264(proc, name)
        split = JpegSplitter()
        try:
            while self.running and self.proc is proc:
                chunk = proc.stdout.read(65536)
                if not chunk:
                    break
                for jpeg in split.feed(chunk):
                    with self.cond:
                        self.frames_made += 1
                        self.latest, self.seq = jpeg, self.seq + 1  # overwrites an unsent picture: that is the dropping
                        self.cond.notify()
        finally:
            if self.proc is proc and self.running:
                time.sleep(0.1)  # let the error reader see the last words
                self.running = False
                with self.cond:
                    self.cond.notify_all()
                self.on_error(self.tail or "%s stopped" % name)

    def _send_h264(self):
        while self.running:
            with self.cond:
                while self.running and not self.queue:
                    self.cond.wait(0.5)
                if not self.running:
                    return
                au, key = self.queue.pop(0)
                n = self.seq
            try:
                self.on_frame(base64.b64encode(au).decode(), n, key)
                self.sent += 1
            except Exception:
                self.stop()
                return

    def _send(self):
        if self.codec == "h264":
            return self._send_h264()
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
        began, warned = self.clock(), False
        while self.running:
            time.sleep(1.0)
            if not warned and self.frames_made == 0 and self.clock() - began > self.first_wait:
                warned = True
                where = " Look at the PC screen: a box may be asking to share it." if self.engine == "portal" else ""
                self.on_error("The PC has not produced a picture yet." + where + (" (" + self.tail + ")" if self.tail else ""))
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


def check(seconds=8):
    """`python3 -m faze_pc.stream`: tries the PC screen capture for a few seconds and says what happened. For finding out why the phone shows nothing."""
    got = []
    errors = []
    st = ScreenStream(lambda jpeg, n: got.append(len(jpeg)), errors.append)
    try:
        engine = st.start()
    except RuntimeError as e:
        print("Could not start:", e)
        return 1
    print("Capture method:", engine)
    if engine == "portal":
        print("If a box asks to share the screen, approve it now.")
    end = time.time() + seconds
    while time.time() < end and not errors:
        st.ping()
        time.sleep(0.5)
    st.stop()
    print("Pictures received:", len(got), ("(about %d KB each)" % (sum(got) // len(got) // 1024)) if got else "")
    for e in errors:
        print("Stopped with:", e)
    if not got and not errors:
        print("Nothing arrived. Last message from the capture program:", st.tail or "(none)")
    return 0 if got else 1


if __name__ == "__main__":
    sys.exit(check())
