"""Controls an Android phone through adb (Android's own debugging bridge), with no root and without the Faze app on the phone.

Turn on Developer options > Wireless debugging (or plug in USB debugging), pair once (adb pair HOST:PORT, adb connect HOST:PORT), and this can show the phone's
screen as live H.264 video (adb exec-out screenrecord), tap, swipe and press any key (adb shell input). It is the way scrcpy works. adb is only used when it is
installed and a device is authorised; everything runs as the phone's shell user, so no root is needed.
"""
import shlex
import shutil
import subprocess
import threading

KEYS = {
    "back": "KEYCODE_BACK", "home": "KEYCODE_HOME", "recents": "KEYCODE_APP_SWITCH", "enter": "KEYCODE_ENTER", "return": "KEYCODE_ENTER",
    "delete": "KEYCODE_DEL", "backspace": "KEYCODE_DEL", "tab": "KEYCODE_TAB", "space": "KEYCODE_SPACE", "escape": "KEYCODE_ESCAPE", "esc": "KEYCODE_ESCAPE",
    "up": "KEYCODE_DPAD_UP", "down": "KEYCODE_DPAD_DOWN", "left": "KEYCODE_DPAD_LEFT", "right": "KEYCODE_DPAD_RIGHT",
    "volume_up": "KEYCODE_VOLUME_UP", "volume_down": "KEYCODE_VOLUME_DOWN", "play_pause": "KEYCODE_MEDIA_PLAY_PAUSE", "next": "KEYCODE_MEDIA_NEXT",
    "previous": "KEYCODE_MEDIA_PREVIOUS", "notifications": "KEYCODE_NOTIFICATION", "wake": "KEYCODE_WAKEUP",
}


class AdbError(Exception):
    pass


def parse_devices(output):
    """Serials of devices that are ready (state "device"). Unauthorised or offline ones are left out."""
    out = []
    for line in output.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            out.append(parts[0])
    return out


def parse_size(output):
    """(width, height) from "Physical size: 1080x2400" (an override line wins when there is one)."""
    size = None
    for line in output.splitlines():
        if "size:" in line and "x" in line:
            try:
                w, h = line.split(":")[1].strip().split("x")
                size = (int(w), int(h))
            except ValueError:
                pass
    return size


def text_arg(text):
    """Text for `input text`: spaces become %s, and the whole thing is quoted for the phone's shell. Only plain ASCII goes through adb."""
    if any(ord(c) > 126 or ord(c) < 32 for c in text):
        raise AdbError("adb can only type plain letters, numbers and punctuation (not emoji or accents)")
    return shlex.quote(text.replace("%", "%25").replace(" ", "%s"))


class Adb:
    def __init__(self, run=subprocess.run, which=shutil.which, popen=subprocess.Popen):
        self._run, self._which, self._popen = run, which, popen
        self.sizes = {}

    def available(self):
        return self._which("adb") is not None

    def _adb(self, serial, *args, timeout=15, binary=False):
        cmd = ["adb"] + (["-s", serial] if serial else []) + list(args)
        try:
            r = self._run(cmd, capture_output=True, timeout=timeout)
        except (OSError, subprocess.SubprocessError) as e:
            raise AdbError("adb failed: %s" % e)
        if r.returncode != 0:
            err = (r.stderr or b"").decode("utf-8", "replace").strip()
            raise AdbError(err.splitlines()[-1] if err else "adb returned an error")
        return r.stdout if binary else r.stdout.decode("utf-8", "replace")

    def devices(self):
        if not self.available():
            return []
        try:
            return parse_devices(self._adb(None, "devices"))
        except AdbError:
            return []

    def size(self, serial):
        if serial not in self.sizes:
            s = parse_size(self._adb(serial, "shell", "wm", "size"))
            if not s:
                raise AdbError("could not read the phone's screen size")
            self.sizes[serial] = s
        return self.sizes[serial]

    def _px(self, serial, x, y):
        w, h = self.size(serial)
        return int(round(min(1.0, max(0.0, float(x))) * (w - 1))), int(round(min(1.0, max(0.0, float(y))) * (h - 1)))

    def tap(self, serial, x, y):
        px, py = self._px(serial, x, y)
        self._adb(serial, "shell", "input", "tap", str(px), str(py))

    def long_press(self, serial, x, y):
        px, py = self._px(serial, x, y)
        self._adb(serial, "shell", "input", "swipe", str(px), str(py), str(px), str(py), "700")

    def swipe(self, serial, x1, y1, x2, y2):
        a, b = self._px(serial, x1, y1), self._px(serial, x2, y2)
        self._adb(serial, "shell", "input", "swipe", str(a[0]), str(a[1]), str(b[0]), str(b[1]), "300")

    def key(self, serial, name):
        code = KEYS.get(str(name).strip().lower())
        if not code:
            raise AdbError("unknown key '%s' (keys: %s)" % (name, ", ".join(sorted(KEYS))))
        self._adb(serial, "shell", "input", "keyevent", code)

    def text(self, serial, text):
        self._adb(serial, "shell", "input", "text", text_arg(text[:300]))

    def screenshot(self, serial):
        """One picture of the screen as PNG bytes."""
        return self._adb(serial, "exec-out", "screencap", "-p", timeout=20, binary=True)

    def record_args(self, serial, width=720, kbps=6000):
        w, h = self.size(serial)
        long_edge = max(w, h)
        scale = min(1.0, float(max(width, 240)) / min(w, h)) if long_edge else 1.0
        sw, sh = max(2, int(w * scale) // 2 * 2), max(2, int(h * scale) // 2 * 2)
        return ["adb", "-s", serial, "exec-out", "screenrecord", "--output-format=h264", "--size", "%dx%d" % (sw, sh), "--bit-rate", str(kbps * 1000), "-"]

    def stream(self, serial, on_data, stop, width=720, kbps=6000):
        """Feeds live H.264 to on_data until stop is set. screenrecord ends by itself after three minutes, so it is started again."""
        while not stop.is_set():
            proc = self._popen(self.record_args(serial, width, kbps), stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
            began = threading.Event()
            try:
                while not stop.is_set():
                    chunk = proc.stdout.read(16384)
                    if not chunk:
                        break
                    began.set()
                    on_data(chunk)
            finally:
                try:
                    proc.kill()
                except OSError:
                    pass
            if not began.is_set() and not stop.is_set():
                raise AdbError("screenrecord sent nothing (is the phone unlocked and authorised?)")
