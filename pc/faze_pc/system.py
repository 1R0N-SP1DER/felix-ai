"""Task manager and device list for the phone's remote: what is running on this PC (and a way to end it), and what is plugged in.
Only read from /proc and /sys, no extra programs. Behind the same "Let my phone see and control this PC" switch as the rest of the remote."""
import glob
import os
import signal
import time

CLK = os.sysconf("SC_CLK_TCK") if hasattr(os, "sysconf") else 100
PAGE = os.sysconf("SC_PAGE_SIZE") if hasattr(os, "sysconf") else 4096


class SystemInfo:
    def __init__(self, config, proc="/proc", sys="/sys", kill=os.kill, clock=time.monotonic, me=None):
        self.config, self.proc, self.sys, self.kill, self.clock = config, proc, sys, kill, clock
        self.me = me if me is not None else os.getpid()
        self.last = {}   # pid -> cpu ticks at the last look
        self.last_at = None

    def enabled(self):
        return self.config.data.get("allow_remote") is True

    def handle(self, msg):
        t = msg.get("t")
        if t not in ("pc_procs", "pc_kill", "pc_devices"):
            return None
        if not self.enabled():
            return {"t": "error", "message": "Turn on \"Let my phone see and control this PC\" in Faze's settings on the PC."}
        if t == "pc_procs":
            return {"t": "pc_procs_ok", "procs": self.procs()}
        if t == "pc_kill":
            return self.end(msg.get("pid"), bool(msg.get("force")))
        return {"t": "pc_devices_ok", "devices": self.devices()}

    def _read(self, path):
        try:
            with open(path, "rb") as f:
                return f.read().decode("utf-8", "replace")
        except OSError:
            return ""

    def procs(self, limit=40):
        now = self.clock()
        span = (now - self.last_at) if self.last_at is not None else None
        seen, out = {}, []
        for d in glob.glob(os.path.join(self.proc, "[0-9]*")):
            pid = os.path.basename(d)
            stat = self._read(os.path.join(d, "stat"))
            if not stat:
                continue
            name = stat[stat.find("(") + 1:stat.rfind(")")]
            rest = stat[stat.rfind(")") + 2:].split()
            try:
                ticks = int(rest[11]) + int(rest[12])  # utime + stime
            except (IndexError, ValueError):
                continue
            statm = self._read(os.path.join(d, "statm")).split()
            mem = int(statm[1]) * PAGE / 1048576 if len(statm) > 1 and statm[1].isdigit() else 0.0
            seen[pid] = ticks
            cpu = 0.0
            if span and pid in self.last and span > 0:
                cpu = max(0.0, (ticks - self.last[pid]) / CLK / span * 100)
            out.append({"pid": int(pid), "name": name[:40], "cpu": round(cpu, 1), "mem": round(mem, 1)})
        self.last, self.last_at = seen, now
        out.sort(key=lambda p: (-p["cpu"], -p["mem"]))
        return out[:limit]

    def end(self, pid, force=False):
        try:
            pid = int(pid)
        except (TypeError, ValueError):
            return {"t": "error", "message": "no such program"}
        if pid <= 1 or pid == self.me or pid == os.getppid():
            return {"t": "error", "message": "Faze will not end that one"}
        try:
            self.kill(pid, signal.SIGKILL if force else signal.SIGTERM)
        except ProcessLookupError:
            return {"t": "error", "message": "it has already closed"}
        except PermissionError:
            return {"t": "error", "message": "that program belongs to another user"}
        return {"t": "ok"}

    def devices(self):
        out = []
        for d in sorted(glob.glob(os.path.join(self.sys, "bus", "usb", "devices", "*"))):
            name = self._read(os.path.join(d, "product")).strip()
            if name:
                make = self._read(os.path.join(d, "manufacturer")).strip()
                out.append({"kind": "usb", "name": (make + " " + name).strip() if make and make not in name else name})
        for line in self._read(os.path.join(self.proc, "bus", "input", "devices")).splitlines():
            if line.startswith("N: Name="):
                name = line.split("=", 1)[1].strip().strip('"')
                if name and not name.startswith("Faze virtual"):
                    out.append({"kind": "input", "name": name})
        for d in sorted(glob.glob(os.path.join(self.sys, "class", "power_supply", "*"))):
            cap = self._read(os.path.join(d, "capacity")).strip()
            if cap:
                out.append({"kind": "battery", "name": "%s %s%%" % (os.path.basename(d), cap)})
        return out
