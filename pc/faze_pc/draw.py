"""The phone's doodles on the PC screen. The phone sends pc_input kind=draw (op start / move / end / clear, a colour and x, y as 0..1 of the screen).
This relays them to a small click-through overlay window that is its own program (`faze --draw`), started on the first stroke."""
import json
import re
import subprocess
import sys

COLOR = re.compile(r"^#?[0-9a-fA-F]{6}$")


def clean(msg):
    """A safe, small version of a draw message, or None when it makes no sense."""
    op = msg.get("op")
    if op not in ("start", "move", "end", "clear"):
        return None
    out = {"op": op}
    if op in ("start", "move"):
        try:
            out["x"] = min(1.0, max(0.0, float(msg.get("x", 0))))
            out["y"] = min(1.0, max(0.0, float(msg.get("y", 0))))
        except (TypeError, ValueError):
            return None
    if op == "start":
        c = str(msg.get("color", ""))
        out["color"] = ("#" + c.lstrip("#")) if COLOR.match(c) else "#5CC8FF"
    return out


class DrawRelay:
    def __init__(self, spawn=None):
        self.spawn = spawn or subprocess.Popen
        self.proc = None

    def alive(self):
        return self.proc is not None and self.proc.poll() is None

    def handle(self, msg):
        d = clean(msg)
        if d is None:
            return {"t": "error", "message": "bad drawing step"}
        if not self.alive():
            if d["op"] in ("end", "clear"):
                return {"t": "ok"}
            try:
                self.proc = self.spawn([sys.executable, "-m", "faze_pc.cli", "--draw"], stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
            except OSError as e:
                return {"t": "error", "message": "could not open the drawing overlay: %s" % e}
        try:
            self.proc.stdin.write((json.dumps(d) + "\n").encode())
            self.proc.stdin.flush()
        except (OSError, ValueError):
            self.proc = None
        return {"t": "ok"}

    def stop(self):
        if self.alive():
            try:
                self.proc.stdin.close()
            except OSError:
                pass
        self.proc = None
