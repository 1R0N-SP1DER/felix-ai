"""Push the mouse against the left screen edge and hold, and the phone's screen appears in its own window.
It listens to the mouse's raw movement (evdev) because a desktop will not say where the pointer is while it is stuck at an edge: a steady push to the left,
for a good moment, with no pull back, is what only an edge push looks like. Needs access to the input devices (the same `bash install.sh --input` step) and is off until switched on."""
import glob
import os
import re
import select
import struct
import threading
import time

EV_REL, REL_X = 2, 0
EVENT = struct.Struct("llHHi")


class EdgeDetector:
    """Feed it (time, dx) for every horizontal mouse movement. fire() is true once per push."""

    def __init__(self, hold=0.7, distance=2200, quiet=2.0):
        self.hold, self.distance, self.quiet = hold, distance, quiet
        self.start = None
        self.total = 0
        self.last = 0.0
        self.fired_at = -1e9

    def feed(self, t, dx):
        if dx == 0:
            return False
        if t - self.fired_at < self.quiet:
            return False
        if dx > 0 or (self.start is not None and t - self.last > 0.25):  # a pull back, or a pause: not a push
            self.start, self.total = None, 0
            if dx > 0:
                return False
        if self.start is None:
            self.start, self.total = t, 0
        self.total += -dx
        self.last = t
        if t - self.start >= self.hold and self.total >= self.distance:
            self.start, self.total, self.fired_at = None, 0, t
            return True
        return False


def mouse_devices(text):
    """The /dev/input/event* files of real mice, from the contents of /proc/bus/input/devices."""
    out = []
    for block in text.split("\n\n"):
        name = re.search(r'N: Name="([^"]*)"', block)
        handlers = re.search(r"H: Handlers=(.*)", block)
        if not name or not handlers or "Faze virtual" in name.group(1):
            continue
        h = handlers.group(1).split()
        ev = next((x for x in h if x.startswith("event")), None)
        if ev and any(x.startswith("mouse") for x in h):
            out.append("/dev/input/" + ev)
    return out


class EdgeWatcher:
    def __init__(self, enabled, on_push, note=lambda m: None, devices_text=None, opener=os.open, clock=time.monotonic):
        self.enabled, self.on_push, self.note = enabled, on_push, note
        self.devices_text = devices_text or (lambda: open("/proc/bus/input/devices").read())
        self.opener, self.clock = opener, clock
        self.stopping = threading.Event()
        self.detector = EdgeDetector()
        self.warned = False

    def start(self):
        threading.Thread(target=self._loop, daemon=True, name="edge").start()

    def stop(self):
        self.stopping.set()

    def _loop(self):
        fds = {}
        last_scan = 0.0
        while not self.stopping.is_set():
            if not self.enabled():
                for fd in fds.values():
                    try:
                        os.close(fd)
                    except OSError:
                        pass
                fds = {}
                self.stopping.wait(2.0)
                continue
            if self.clock() - last_scan > 10.0 or not fds:
                last_scan = self.clock()
                try:
                    for path in mouse_devices(self.devices_text()):
                        if path not in fds:
                            try:
                                fds[path] = self.opener(path, os.O_RDONLY | os.O_NONBLOCK)
                            except OSError as e:
                                if not self.warned:
                                    self.warned = True
                                    self.note("The edge push needs access to the mouse. Run: bash install.sh --input, then log out and in. (%s)" % e.strerror)
                except OSError:
                    pass
            if not fds:
                self.stopping.wait(3.0)
                continue
            try:
                ready, _, _ = select.select(list(fds.values()), [], [], 1.0)
            except (OSError, ValueError):
                fds = {}
                continue
            for fd in ready:
                try:
                    data = os.read(fd, EVENT.size * 64)
                except OSError:
                    fds = {p: f for p, f in fds.items() if f != fd}
                    continue
                self.feed_bytes(data)

    def feed_bytes(self, data):
        for i in range(0, len(data) - EVENT.size + 1, EVENT.size):
            _, _, t, code, value = EVENT.unpack(data[i:i + EVENT.size])
            if t == EV_REL and code == REL_X and self.detector.feed(self.clock(), value):
                try:
                    self.on_push()
                except Exception:
                    pass
