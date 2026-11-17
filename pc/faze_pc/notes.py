"""The phone's notifications on this PC. The phone sends `note` (an app, a title, some text) and `note_gone` when it is cleared there; both are notices and
are never answered. Each is shown as a normal desktop notification, and a repeat of the same one replaces it instead of piling up. Needs notify-send
(libnotify-bin); closing one uses gdbus. Only shown while the phone is linked, and only if the phone's owner switched it on there."""
import shutil
import subprocess
import threading

TITLE_MAX = 120
TEXT_MAX = 500


def run(argv, timeout=4):
    try:
        r = subprocess.run(argv, capture_output=True, text=True, timeout=timeout)
        return r.returncode, r.stdout
    except (OSError, subprocess.SubprocessError):
        return 1, ""


class PhoneNotes:
    def __init__(self, config, which=shutil.which, runner=run, log=lambda m: None):
        self.config, self.which, self.run, self.log = config, which, runner, log
        self.shown = {}  # the phone's notification key -> the desktop's notification number
        self.lock = threading.Lock()
        self.warned = False

    def enabled(self):
        return self.config.data.get("show_phone_notes", True) is not False

    def handle(self, msg):
        t = msg.get("t")
        if not self.enabled():
            return None
        if t == "note":
            self.show(msg)
        elif t == "note_gone":
            self.close(msg.get("id"))
        return None

    def show(self, msg):
        key = msg.get("id")
        if not isinstance(key, str) or not key:
            return
        if not self.which("notify-send"):
            if not self.warned:
                self.warned = True
                self.log("Phone notifications need notify-send: sudo apt install libnotify-bin")
            return
        app = str(msg.get("app") or "Phone")[:60]
        title = str(msg.get("title") or "")[:TITLE_MAX] or app
        text = str(msg.get("text") or "")[:TEXT_MAX]
        if title != app and title:
            head = "%s · %s" % (app, title)
        else:
            head = app
        with self.lock:
            argv = ["notify-send", "-a", "Faze phone", "-p", "-h", "string:desktop-entry:faze"]
            old = self.shown.get(key)
            if old:
                argv += ["-r", str(old)]
            rc, out = self.run(argv + [head, text])
            if rc == 0 and out.strip().isdigit():
                self.shown[key] = int(out.strip())
                if len(self.shown) > 200:  # forget the oldest
                    self.shown.pop(next(iter(self.shown)))

    def close(self, key):
        with self.lock:
            num = self.shown.pop(key, None)
        if num and self.which("gdbus"):
            self.run(["gdbus", "call", "--session", "--dest", "org.freedesktop.Notifications", "--object-path", "/org/freedesktop/Notifications",
                      "--method", "org.freedesktop.Notifications.CloseNotification", str(num)])
