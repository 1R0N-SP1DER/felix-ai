"""A virtual mouse and keyboard made directly through /dev/uinput. One write per movement, no program started per event, so it is fast enough for games.
It needs access to /dev/uinput (the installer's --input step sets that up). Without it, Faze falls back to ydotool or xdotool."""
import fcntl
import os
import struct
import threading
import time

UI_SET_EVBIT, UI_SET_KEYBIT, UI_SET_RELBIT = 0x40045564, 0x40045565, 0x40045566
UI_SET_ABSBIT, UI_ABS_SETUP = 0x40045567, 0x401C5504
UI_DEV_SETUP, UI_DEV_CREATE, UI_DEV_DESTROY = 0x405C5503, 0x5501, 0x5502
EV_SYN, EV_KEY, EV_REL, EV_ABS = 0, 1, 2, 3
REL_X, REL_Y, REL_HWHEEL, REL_WHEEL = 0, 1, 6, 8
BTN = {"left": 0x110, "right": 0x111, "middle": 0x112}
KEY_LEFTSHIFT = 42

NAMED = {"enter": 28, "backspace": 14, "delete": 111, "tab": 15, "esc": 1, "space": 57, "up": 103, "down": 108, "left": 105, "right": 106,
         "home": 102, "end": 107, "pageup": 104, "pagedown": 109, "super": 125, "volup": 115, "voldown": 114, "mute": 113, "playpause": 164,
         "shift": 42, "ctrl": 29, "alt": 56, "f1": 59, "f2": 60, "f3": 61, "f4": 62, "f5": 63, "f6": 64, "f7": 65, "f8": 66, "f9": 67, "f10": 68, "f11": 87, "f12": 88}


# A virtual Xbox-style controller (made to look like an Xbox 360 pad so games and Steam pick it up).
PAD_BUTTONS = {"A": 0x130, "B": 0x131, "X": 0x133, "Y": 0x134, "LB": 0x136, "RB": 0x137, "BACK": 0x13A, "START": 0x13B, "GUIDE": 0x13C, "LS": 0x13D, "RS": 0x13E}
PAD_AXES = {"lx": 0x00, "ly": 0x01, "rx": 0x03, "ry": 0x04}  # sticks, -32768..32767
PAD_TRIGGERS = {"LT": 0x02, "RT": 0x05}  # 0..255
PAD_HATS = {"LEFT": (0x10, -1), "RIGHT": (0x10, 1), "UP": (0x11, -1), "DOWN": (0x11, 1)}


def _layout():
    """A US keyboard: character -> (key code, needs shift)."""
    m = {}
    for row, start in (("qwertyuiop", 16), ("asdfghjkl", 30), ("zxcvbnm", 44)):
        for i, c in enumerate(row):
            m[c] = (start + i, False)
            m[c.upper()] = (start + i, True)
    for i, c in enumerate("1234567890"):
        m[c] = (2 + i, False)
    for c, k in zip("!@#$%^&*()", range(2, 12)):
        m[c] = (k, True)
    for c, k in (("-", 12), ("=", 13), ("[", 26), ("]", 27), (";", 39), ("'", 40), ("`", 41), ("\\", 43), (",", 51), (".", 52), ("/", 53)):
        m[c] = (k, False)
    for c, k in (("_", 12), ("+", 13), ("{", 26), ("}", 27), (":", 39), ('"', 40), ("~", 41), ("|", 43), ("<", 51), (">", 52), ("?", 53)):
        m[c] = (k, True)
    m[" "] = (57, False)
    m["\n"] = (28, False)
    m["\t"] = (15, False)
    return m


LAYOUT = _layout()


def code_for(name):
    """The key code for a name: a named key (shift, ctrl, f5...) or a single letter, digit or symbol. None when unknown."""
    name = str(name).lower()
    if name in NAMED:
        return NAMED[name]
    if len(name) == 1 and name in LAYOUT:
        return LAYOUT[name][0]
    return None


def can_type(text):
    return all(c in LAYOUT for c in text)


class UInput:
    def __init__(self, path="/dev/uinput", opener=os.open, ioctl=fcntl.ioctl, writer=os.write, closer=os.close, sleep=time.sleep):
        self.path, self.opener, self.ioctl, self.writer, self.closer, self.sleep = path, opener, ioctl, writer, closer, sleep
        self.mouse = self.keyboard = None
        self.gamepad = None
        self.pad_last = {}
        self.lock = threading.Lock()

    def open(self):
        """Creates the two devices. Raises OSError with the reason when /dev/uinput cannot be used."""
        if self.mouse is not None:
            return
        m = self._device("Faze virtual mouse", rel=(REL_X, REL_Y, REL_WHEEL, REL_HWHEEL), keys=list(BTN.values()))
        try:
            k = self._device("Faze virtual keyboard", rel=(), keys=list(range(1, 249)))
        except OSError:
            self.closer(m)
            raise
        self.mouse, self.keyboard = m, k
        self.sleep(0.15)  # the desktop needs a moment to notice new devices

    def open_pad(self):
        """Creates the virtual controller the first time a game pad message arrives."""
        if self.gamepad is not None:
            return
        fd = self.opener(self.path, os.O_WRONLY | os.O_NONBLOCK)
        try:
            self.ioctl(fd, UI_SET_EVBIT, EV_KEY)
            for k in PAD_BUTTONS.values():
                self.ioctl(fd, UI_SET_KEYBIT, k)
            self.ioctl(fd, UI_SET_EVBIT, EV_ABS)
            for code, lo, hi in [(c, -32768, 32767) for c in PAD_AXES.values()] + [(c, 0, 255) for c in PAD_TRIGGERS.values()] + [(c, -1, 1) for c in (0x10, 0x11)]:
                self.ioctl(fd, UI_SET_ABSBIT, code)
                self.ioctl(fd, UI_ABS_SETUP, struct.pack("<HxxiiiiII", code, 0, lo, hi, 16 if hi > 255 else 0, 0, 0)[:28])
            self.ioctl(fd, UI_DEV_SETUP, struct.pack("<HHHH80sI", 0x03, 0x045E, 0x028E, 0x0110, b"Faze virtual controller", 0))
            self.ioctl(fd, UI_DEV_CREATE)
        except OSError:
            self.closer(fd)
            raise
        self.gamepad = fd
        self.sleep(0.15)

    def pad(self, state):
        """Applies a whole controller state from the phone; only what changed is written."""
        self.open_pad()
        held = {str(b).upper() for b in state.get("buttons", [])}
        want = {}
        for name, code in PAD_BUTTONS.items():
            want[(EV_KEY, code)] = 1 if name in held else 0
        for name, code in PAD_AXES.items():
            v = max(-1.0, min(1.0, float(state.get(name, 0))))
            want[(EV_ABS, code)] = int(round(v * 32767))
        for name, code in PAD_TRIGGERS.items():
            want[(EV_ABS, code)] = 255 if name in held else 0
        for axis in (0x10, 0x11):
            want[(EV_ABS, axis)] = 0
        for name, (axis, sign) in PAD_HATS.items():
            if name in held:
                want[(EV_ABS, axis)] = sign
        ev = [(t, c, v) for (t, c), v in want.items() if self.pad_last.get((t, c), 0) != v]
        if ev:
            self.pad_last.update({(t, c): v for t, c, v in ev})
            self._emit(self.gamepad, ev)

    def _device(self, name, rel, keys):
        fd = self.opener(self.path, os.O_WRONLY | os.O_NONBLOCK)
        try:
            if keys:
                self.ioctl(fd, UI_SET_EVBIT, EV_KEY)
                for k in keys:
                    self.ioctl(fd, UI_SET_KEYBIT, k)
            if rel:
                self.ioctl(fd, UI_SET_EVBIT, EV_REL)
                for r in rel:
                    self.ioctl(fd, UI_SET_RELBIT, r)
            setup = struct.pack("<HHHH80sI", 0x03, 0x4641, 0x5A45, 1, name.encode(), 0)  # bus USB, vendor "FA", product "ZE"
            self.ioctl(fd, UI_DEV_SETUP, setup)
            self.ioctl(fd, UI_DEV_CREATE)
        except OSError:
            self.closer(fd)
            raise
        return fd

    def _emit(self, fd, events):
        data = b"".join(struct.pack("llHHi", 0, 0, t, c, v) for t, c, v in events) + struct.pack("llHHi", 0, 0, EV_SYN, 0, 0)
        with self.lock:
            self.writer(fd, data)

    def move(self, dx, dy):
        ev = []
        if dx:
            ev.append((EV_REL, REL_X, int(dx)))
        if dy:
            ev.append((EV_REL, REL_Y, int(dy)))
        if ev:
            self._emit(self.mouse, ev)

    def button(self, name, down):
        self._emit(self.mouse, [(EV_KEY, BTN[name], 1 if down else 0)])

    def click(self, name, count=1):
        for i in range(count):
            self.button(name, True)
            self.button(name, False)
            if i + 1 < count:
                self.sleep(0.04)

    def scroll(self, lines):
        """Positive lines scroll up, like the wheel."""
        self._emit(self.mouse, [(EV_REL, REL_WHEEL, int(lines))])

    def key(self, code, shift=False):
        ev = []
        if shift:
            ev.append((EV_KEY, KEY_LEFTSHIFT, 1))
        ev += [(EV_KEY, code, 1), (EV_KEY, code, 0)]
        if shift:
            ev.append((EV_KEY, KEY_LEFTSHIFT, 0))
        self._emit(self.keyboard, ev)

    def named_key(self, name):
        code = NAMED.get(name)
        if code is None:
            raise KeyError(name)
        self.key(code)

    def hold(self, name, down):
        """Presses or releases one key and leaves it so (a modifier held while the next thing is tapped)."""
        code = code_for(name)
        if code is None:
            raise KeyError(name)
        self._emit(self.keyboard, [(EV_KEY, code, 1 if down else 0)])

    def combo(self, names):
        """Presses the keys in order and releases them in reverse, like Ctrl+Shift+T."""
        codes = [code_for(n) for n in names]
        if not codes or None in codes:
            raise KeyError(",".join(map(str, names)))
        self._emit(self.keyboard, [(EV_KEY, c, 1) for c in codes] + [(EV_KEY, c, 0) for c in reversed(codes)])

    def type_text(self, text):
        for c in text:
            code, shift = LAYOUT[c]
            self.key(code, shift)
            self.sleep(0.004)

    def close(self):
        for fd in (self.mouse, self.keyboard, self.gamepad):
            if fd is not None:
                try:
                    self.ioctl(fd, UI_DEV_DESTROY)
                    self.closer(fd)
                except OSError:
                    pass
        self.gamepad = None
        self.pad_last = {}
        self.mouse = self.keyboard = None
