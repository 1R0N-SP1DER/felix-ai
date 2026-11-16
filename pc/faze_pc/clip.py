"""Clipboard sync with the paired phone, both ways, automatic. Text only, up to 64 KB, over the encrypted link.
Copy on the PC and it lands on the phone; a copy on the phone arrives here (Android only lets an app read the clipboard while it is in front,
so that direction works when Faze is open on the phone). A copy that just arrived from the other side is never sent back."""
import shutil
import subprocess
import threading

MAX = 64 * 1024
KLIPPER = ["qdbus6", "org.kde.klipper", "/klipper"]
READERS = [KLIPPER + ["getClipboardContents"], ["wl-paste", "-n"], ["xclip", "-o", "-selection", "clipboard"]]


def run(argv, input=None, timeout=3):
    try:
        r = subprocess.run(argv, capture_output=True, timeout=timeout, text=True)
        return r.returncode, r.stdout
    except (OSError, subprocess.SubprocessError):
        return 1, ""


class ClipSync:
    def __init__(self, config, send, which=shutil.which, runner=run, interval=1.0):
        self.config, self.send = config, send  # send(msg) -> number of phones it reached
        self.which, self.run, self.interval = which, runner, interval
        self.last = None
        self.stopping = threading.Event()

    def enabled(self):
        return self.config.data.get("clip_sync", True) is not False

    def read(self):
        for argv in READERS:
            if self.which(argv[0]):
                rc, out = self.run(argv)
                if rc == 0:
                    return out.rstrip("\n") if argv[0] == "qdbus6" else out
        return None

    def write(self, text):
        for argv in (KLIPPER + ["setClipboardContents", text], ["wl-copy", text]):
            if self.which(argv[0]):
                rc, _ = self.run(argv)
                if rc == 0:
                    return True
        return False

    def poll(self):
        """One look at the clipboard. Sends it to the phone if it is new text the phone has not just given us."""
        if not self.enabled():
            return False
        text = self.read()
        if self.last is None and text is not None:
            self.last = text  # what was already there at start is not news
            return False
        if not text or text == self.last or len(text.encode()) > MAX:
            return False
        self.last = text
        return bool(self.send({"t": "clip", "text": text}))

    def receive(self, msg):
        """A copy from the phone. Never answered: it was a notice, not a question."""
        text = msg.get("text")
        if not self.enabled() or not isinstance(text, str) or not text or len(text.encode()) > MAX or text == self.last:
            return None
        self.last = text  # set first: reading it back must not echo it to the phone
        self.write(text)
        return None

    def start(self):
        def loop():
            while not self.stopping.wait(self.interval):
                try:
                    self.poll()
                except Exception:
                    pass
        threading.Thread(target=loop, daemon=True, name="clip").start()

    def stop(self):
        self.stopping.set()
