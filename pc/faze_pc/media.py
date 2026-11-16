"""What the phone can pull from this PC besides the screen: the sound it is playing, its microphone, its webcam, and files sent to it.
All of it sits behind the same switch as remote control ("Let my phone see and control this PC").
Audio is captured as raw 16-bit mono at 32 kHz and handed over in chunks the phone asks for; capture stops by itself when the phone stops asking."""
import base64
import os
import re
import shutil
import subprocess
import threading
import time

RATE = 32000
IDLE = 6.0
MAX_CHUNK = 96 * 1024
MAX_FILE = 2 * 1024 ** 3
MONITOR = [
    ["parec", "-d", "@DEFAULT_MONITOR@", "--format=s16le", "--rate=%d" % RATE, "--channels=1", "--latency-msec=60"],
    ["pw-record", "-P", "stream.capture.sink=true", "--rate", str(RATE), "--channels", "1", "--format", "s16", "-"],
]
MIC = [
    ["parec", "--format=s16le", "--rate=%d" % RATE, "--channels=1", "--latency-msec=60"],
    ["pw-record", "--rate", str(RATE), "--channels", "1", "--format", "s16", "-"],
    ["arecord", "-q", "-f", "S16_LE", "-r", str(RATE), "-c", "1", "-t", "raw"],
]
CAMERA = [
    ["ffmpeg", "-loglevel", "error", "-f", "v4l2", "-i", "{dev}", "-frames:v", "1", "-vf", "scale=640:-2", "-q:v", "6", "-f", "mjpeg", "-"],
    ["fswebcam", "-q", "-r", "640x480", "--jpeg", "60", "--no-banner", "-"],
]


class Capture:
    """One running recorder whose output piles up until the phone takes it."""

    def __init__(self, argv, popen=subprocess.Popen, clock=time.time):
        self.clock = clock
        self.buf = bytearray()
        self.lock = threading.Lock()
        self.last_ask = clock()
        self.proc = popen(argv, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        self.dead = False
        threading.Thread(target=self._pump, daemon=True, name="media-pump").start()

    def _pump(self):
        while True:
            chunk = self.proc.stdout.read(4096)
            if not chunk:
                break
            with self.lock:
                self.buf += chunk
                if len(self.buf) > 4 * MAX_CHUNK:
                    del self.buf[: len(self.buf) - 2 * MAX_CHUNK]  # a stalled phone loses old sound, it does not make us hoard it
        self.dead = True

    def take(self):
        self.last_ask = self.clock()
        with self.lock:
            out = bytes(self.buf[:MAX_CHUNK])
            del self.buf[:MAX_CHUNK]
        return out

    def stop(self):
        try:
            self.proc.terminate()
        except OSError:
            pass


class Media:
    def __init__(self, config, which=shutil.which, popen=subprocess.Popen, runner=None, clock=time.time):
        self.config, self.which, self.popen = config, which, popen
        self.run = runner or self._run
        self.clock = clock
        self.caps = {}
        self.lock = threading.Lock()

    def _run(self, argv, timeout=15):
        try:
            r = subprocess.run(argv, capture_output=True, timeout=timeout)
            return r.returncode, r.stdout
        except (OSError, subprocess.SubprocessError):
            return 1, b""

    def enabled(self):
        return self.config.data.get("allow_remote") is True

    def handle(self, msg):
        t = msg.get("t")
        if t not in ("pc_audio_get", "pc_audio_stop", "pc_cam_get", "pc_put"):
            return None
        if not self.enabled():
            return {"t": "error", "message": "Turn on \"Let my phone see and control this PC\" in Faze's settings on the PC."}
        if t == "pc_audio_get":
            return self.audio(msg.get("source", "monitor"))
        if t == "pc_audio_stop":
            self.stop(msg.get("source"))
            return {"t": "ok"}
        if t == "pc_cam_get":
            return self.camera()
        return self.put(msg)

    # --- sound
    def audio(self, source):
        if source not in ("monitor", "mic"):
            return {"t": "error", "message": "unknown sound source"}
        self.reap()
        with self.lock:
            cap = self.caps.get(source)
            if cap is not None and cap.dead:
                self.caps.pop(source)
                cap = None
            if cap is None:
                argv = next((a for a in (MONITOR if source == "monitor" else MIC) if self.which(a[0])), None)
                if argv is None:
                    return {"t": "error", "message": "no recorder on the PC. Install pulseaudio-utils or pipewire."}
                try:
                    cap = self.caps[source] = Capture(argv, self.popen, self.clock)
                except OSError as e:
                    return {"t": "error", "message": "could not start the recorder: %s" % str(e)[:100]}
        data = cap.take()
        return {"t": "pc_audio_ok", "rate": RATE, "data": base64.b64encode(data).decode()}

    def reap(self):
        with self.lock:
            for k, c in list(self.caps.items()):
                if self.clock() - c.last_ask > IDLE:
                    c.stop()
                    self.caps.pop(k)

    def stop(self, source=None):
        with self.lock:
            for k in list(self.caps):
                if source in (None, k):
                    self.caps.pop(k).stop()

    # --- webcam
    def camera(self):
        dev = next((os.path.join("/dev", n) for n in sorted(os.listdir("/dev")) if re.fullmatch(r"video\d+", n)), None) if os.path.isdir("/dev") else None
        if dev is None:
            return {"t": "error", "message": "the PC has no webcam (no /dev/video device)"}
        for argv in CAMERA:
            if self.which(argv[0]):
                rc, out = self.run([a.replace("{dev}", dev) for a in argv], 15)
                if rc == 0 and out[:2] == b"\xff\xd8":
                    return {"t": "pc_cam_ok", "jpeg": base64.b64encode(out).decode()}
                return {"t": "error", "message": "the webcam did not give a picture (is another app using it?)"}
        return {"t": "error", "message": "no webcam tool on the PC. Install ffmpeg or fswebcam."}

    # --- files sent from the phone
    def inbox(self):
        return os.path.realpath(os.path.expanduser(self.config.data.get("inbox") or "~/Downloads/Faze"))

    @staticmethod
    def clean_name(name):
        name = os.path.basename(str(name).replace("\\", "/")).strip().lstrip(".")
        name = re.sub(r"[\x00-\x1f/]", "_", name)[:120]
        return name or "file"

    def put(self, msg):
        """A file arrives in pieces: {name, offset, data (base64), eof}. It is written under a temporary name and renamed when whole; it never replaces an existing file."""
        folder = self.inbox()
        name = self.clean_name(msg.get("name", ""))
        offset = int(msg.get("offset", 0))
        try:
            data = base64.b64decode(msg.get("data", ""), validate=True)
        except ValueError:
            return {"t": "error", "message": "the file piece was damaged"}
        if offset + len(data) > MAX_FILE:
            return {"t": "error", "message": "that file is too big"}
        os.makedirs(folder, exist_ok=True)
        part = os.path.join(folder, "." + name + ".faze-part")
        if offset == 0:
            open(part, "wb").close()
        elif not os.path.exists(part) or os.path.getsize(part) != offset:
            return {"t": "error", "message": "the file pieces arrived out of order"}
        with open(part, "ab") as f:
            f.write(data)
        if not msg.get("eof"):
            return {"t": "pc_put_ok"}
        final, n = os.path.join(folder, name), 0
        base, ext = os.path.splitext(name)
        while os.path.exists(final):
            n += 1
            final = os.path.join(folder, "%s (%d)%s" % (base, n, ext))
        os.replace(part, final)
        return {"t": "pc_put_ok", "saved": final}
