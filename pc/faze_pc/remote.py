"""Lets the phone see this PC's screen and drive its mouse and keyboard, like a remote receiver app. Off unless "Let my phone see and control this PC" is on.
Screenshots come from spectacle (KDE) or grim, scaled with ImageMagick when it is there. Input goes through ydotool (Wayland, needs ydotoold running)
or xdotool (X11). Whatever is missing is reported to the phone in plain words."""
import base64
import os
import shutil
import subprocess
import tempfile

from .stream import ScreenStream
from .uinput import UInput, can_type, code_for

MAX_JPEG = 800 * 1024
BUTTONS = {"left": "0xC0", "right": "0xC1", "middle": "0xC2"}
XBUTTON = {"left": "1", "middle": "2", "right": "3"}
KEYS = {  # name -> (ydotool evdev codes, xdotool name)
    "enter": ("28:1 28:0", "Return"), "backspace": ("14:1 14:0", "BackSpace"), "delete": ("111:1 111:0", "Delete"),
    "tab": ("15:1 15:0", "Tab"), "esc": ("1:1 1:0", "Escape"), "space": ("57:1 57:0", "space"),
    "up": ("103:1 103:0", "Up"), "down": ("108:1 108:0", "Down"), "left": ("105:1 105:0", "Left"), "right": ("106:1 106:0", "Right"),
    "home": ("102:1 102:0", "Home"), "end": ("107:1 107:0", "End"), "pageup": ("104:1 104:0", "Prior"), "pagedown": ("109:1 109:0", "Next"),
    "super": ("125:1 125:0", "Super_L"), "volup": ("115:1 115:0", "XF86AudioRaiseVolume"), "voldown": ("114:1 114:0", "XF86AudioLowerVolume"),
    "mute": ("113:1 113:0", "XF86AudioMute"), "playpause": ("164:1 164:0", "XF86AudioPlay"),
}


class RemoteControl:
    def __init__(self, config, which=shutil.which, runner=None, log=None, notify=None, stream_factory=None, uinput=None, draw=None):
        self.draw = draw
        self.config, self.which = config, which
        self.uinput = uinput if uinput is not None else UInput()
        self.uinput_failed = False
        self.notify = notify or (lambda m: 0)
        self.stream = (stream_factory or ScreenStream)(lambda jpeg, n: self.notify({"t": "pc_frame", "jpeg": jpeg, "n": n}), lambda m: self.notify({"t": "pc_stream_error", "message": m}))
        self.run = runner or self._run
        self.log = log or (lambda kind, text: None)
        self.announced = False

    def _run(self, argv, timeout=10):
        try:
            r = subprocess.run(argv, capture_output=True, timeout=timeout)
            return r.returncode, r.stdout, r.stderr.decode("utf-8", "replace")
        except (OSError, subprocess.SubprocessError) as e:
            return 1, b"", str(e)

    def enabled(self):
        return self.config.data.get("allow_remote") is True

    def handle(self, msg):
        t = msg.get("t")
        if t not in ("pc_screen_get", "pc_input", "pc_stream_start", "pc_stream_ping", "pc_stream_stop"):
            return None
        if not self.enabled():
            return {"t": "error", "message": "Turn on \"Let my phone see and control this PC\" in Faze's settings on the PC."}
        if not self.announced:
            self.announced = True
            self.log("link", "The phone is viewing and controlling this PC")
        if t == "pc_stream_start":
            try:
                engine = self.stream.start(int(msg.get("w", 960) or 960), int(msg.get("fps", 30) or 30), int(msg.get("q", 60) or 60))
            except RuntimeError as e:
                return {"t": "error", "message": str(e)}
            return {"t": "pc_stream_ok", "engine": engine}
        if t == "pc_stream_ping":
            self.stream.ping()
            return {"t": "ok"}
        if t == "pc_stream_stop":
            self.stream.stop()
            return {"t": "ok"}
        return self.screen(msg) if t == "pc_screen_get" else self.input(msg)

    # --- seeing
    def screen(self, msg):
        width = max(320, min(int(msg.get("w", 960) or 960), 1920))
        d = tempfile.mkdtemp(prefix="faze-rc-")
        raw, out = os.path.join(d, "s.jpg"), os.path.join(d, "o.jpg")
        try:
            for argv in (["spectacle", "-b", "-n", "-f", "-o", raw], ["grim", "-t", "jpeg", "-q", "70", raw]):
                if self.which(argv[0]):
                    rc, _, err = self.run(argv, 20)
                    if rc == 0 and os.path.exists(raw) and os.path.getsize(raw) > 0:
                        break
            else:
                return {"t": "error", "message": "no screenshot tool worked on the PC (tried spectacle and grim)"}
            path = raw
            im = self.which("magick") or self.which("convert")
            if im:
                argv = [im, raw, "-resize", "%dx>" % width, "-quality", "60", out]
                if self.run(argv, 20)[0] == 0 and os.path.exists(out):
                    path = out
            data = open(path, "rb").read()
            if len(data) > MAX_JPEG:
                return {"t": "error", "message": "the PC screenshot is too big to send. Install imagemagick so it can be scaled down."}
            return {"t": "pc_screen_ok", "jpeg": base64.b64encode(data).decode()}
        finally:
            shutil.rmtree(d, ignore_errors=True)

    # --- doing
    def backend(self):
        if self.which("ydotool"):
            return "ydotool"
        if self.which("xdotool"):
            return "xdotool"
        return None

    def direct(self, msg):
        """The fast path: a virtual mouse and keyboard through /dev/uinput. Returns the reply, or None when it cannot be used and the slower tools should try."""
        if self.uinput_failed:
            return None
        kind = msg.get("kind")
        try:
            self.uinput.open()
            u = self.uinput
            if kind == "move":
                u.move(max(-3000, min(3000, float(msg.get("dx", 0)))), max(-3000, min(3000, float(msg.get("dy", 0)))))
            elif kind == "click":
                b = msg.get("button", "left")
                if b not in BUTTONS:
                    return {"t": "error", "message": "unknown button"}
                u.click(b, 2 if msg.get("double") else 1)
            elif kind == "down" or kind == "up":
                b = msg.get("button", "left")
                if b not in BUTTONS:
                    return {"t": "error", "message": "unknown button"}
                u.button(b, kind == "down")
            elif kind == "scroll":
                u.scroll(max(-50, min(50, int(float(msg.get("dy", 0))))))
            elif kind == "key":
                try:
                    u.named_key(str(msg.get("key", "")).lower())
                except KeyError:
                    return {"t": "error", "message": "unknown key"}
            elif kind == "keydown" or kind == "keyup":
                try:
                    u.hold(str(msg.get("key", "")), kind == "keydown")
                except KeyError:
                    return {"t": "error", "message": "unknown key"}
            elif kind == "pad":
                state = msg.get("state")
                if not isinstance(state, dict):
                    return {"t": "error", "message": "no controller state"}
                u.pad(state)
            elif kind == "combo":
                try:
                    u.combo(self.combo_names(msg))
                except KeyError:
                    return {"t": "error", "message": "unknown key"}
            elif kind == "text":
                text = str(msg.get("text", ""))[:500]
                if not can_type(text):
                    return None  # letters outside a US keyboard: the other tools can type those
                u.type_text(text)
            else:
                return None
            return {"t": "pc_input_ok"}
        except OSError:
            self.uinput_failed = True
            try:
                self.uinput.close()
            except Exception:
                pass
            return None

    @staticmethod
    def combo_names(msg):
        keys = msg.get("keys", [])
        return [str(k).lower() for k in keys[:5]] if isinstance(keys, list) else []

    def input(self, msg):
        if msg.get("kind") == "draw":
            return self.draw.handle(msg) if self.draw is not None else {"t": "ok"}
        fast = self.direct(msg)
        if fast is not None:
            return fast
        be = self.backend()
        if be is None:
            return {"t": "error", "message": "no way to move the PC's mouse. Run: bash install.sh --input (in the pc folder, then log out and in), or install ydotool or xdotool."}
        kind = msg.get("kind")
        argv = None
        if kind == "move":
            dx, dy = int(max(-3000, min(3000, float(msg.get("dx", 0))))), int(max(-3000, min(3000, float(msg.get("dy", 0)))))
            argv = ["ydotool", "mousemove", "-x", str(dx), "-y", str(dy)] if be == "ydotool" else ["xdotool", "mousemove_relative", "--", str(dx), str(dy)]
        elif kind == "click":
            b = msg.get("button", "left")
            if b not in BUTTONS:
                return {"t": "error", "message": "unknown button"}
            n = 2 if msg.get("double") else 1
            argv = ["ydotool", "click"] + [BUTTONS[b]] * n if be == "ydotool" else ["xdotool", "click", "--repeat", str(n), XBUTTON[b]]
        elif kind == "scroll":
            dy = int(max(-50, min(50, float(msg.get("dy", 0)))))
            if dy == 0:
                return {"t": "pc_input_ok"}
            argv = ["ydotool", "mousemove", "-w", "-x", "0", "-y", str(-dy)] if be == "ydotool" else ["xdotool", "click", "--repeat", str(abs(dy)), "4" if dy > 0 else "5"]
        elif kind == "key":
            k = KEYS.get(str(msg.get("key", "")).lower())
            if not k:
                return {"t": "error", "message": "unknown key"}
            argv = ["ydotool", "key"] + k[0].split() if be == "ydotool" else ["xdotool", "key", k[1]]
        elif kind in ("keydown", "keyup", "combo"):
            names = [str(msg.get("key", "")).lower()] if kind != "combo" else self.combo_names(msg)
            codes = [code_for(n) for n in names]
            if not names or None in codes:
                return {"t": "error", "message": "unknown key"}
            if be == "ydotool":
                if kind == "combo":
                    seq = ["%d:1" % c for c in codes] + ["%d:0" % c for c in reversed(codes)]
                else:
                    seq = ["%d:%d" % (codes[0], 1 if kind == "keydown" else 0)]
                argv = ["ydotool", "key"] + seq
            else:
                xnames = {"shift": "shift", "ctrl": "ctrl", "alt": "alt", "super": "super", "enter": "Return", "esc": "Escape", "backspace": "BackSpace", "tab": "Tab", "space": "space"}
                x = [xnames.get(n, n) for n in names]
                argv = ["xdotool", {"keydown": "keydown", "keyup": "keyup"}.get(kind, "key"), "+".join(x) if kind == "combo" else x[0]]
        elif kind == "text":
            text = str(msg.get("text", ""))[:500]
            if not text:
                return {"t": "pc_input_ok"}
            argv = ["ydotool", "type", "--", text] if be == "ydotool" else ["xdotool", "type", "--", text]
        else:
            return {"t": "error", "message": "unknown input"}
        rc, _, err = self.run(argv, 10)
        if rc != 0:
            hint = " Is ydotoold running? Try: systemctl --user enable --now ydotool" if be == "ydotool" else ""
            return {"t": "error", "message": ("the PC input tool failed: " + err.strip()[:120] + hint).strip()}
        return {"t": "pc_input_ok"}
