"""What Faze can do on this PC. Each tool has a tier: SAFE runs at once, ASK shows an Allow / Deny card first, and the shell has a
short list of things it never runs. Every call is written to the activity log."""
import json
import os
import re
import shlex
import shutil
import subprocess
import threading
import time
import urllib.request
import uuid

from . import config as cfg

SAFE, ASK = "safe", "ask"
MAX_RESULT = 4000

# Things the shell tool refuses even if you press Allow: wiping disks, forking bombs, privilege escalation.
BLOCKED = [
    r"\brm\s+(-[a-zA-Z]*\s+)*(/|~|\$HOME|/\*)(\s|$)", r"\bmkfs", r"\bdd\s+.*\bof=/dev/", r":\(\)\s*\{", r">\s*/dev/sd", r"\bsudo\b",
    r"\bsu\s+-?\w*\s*$", r"\bchmod\s+-R\s+\d+\s+/(\s|$)", r"\bshutdown\b", r"\breboot\b", r"\bpoweroff\b", r"curl[^|]*\|\s*(ba)?sh", r"wget[^|]*\|\s*(ba)?sh",
]

TOOLS = {
    "get_time": (SAFE, "The current date and time on this PC.", {}),
    "web_search": (SAFE, "Search the web for current information.", {"query": "what to search for"}),
    "remember": (SAFE, "Remember a fact about the user for later chats.", {"text": "the fact, in a short sentence"}),
    "notify": (SAFE, "Show a desktop notification.", {"title": "title", "body": "text"}),
    "open_app": (SAFE, "Open an application by name, e.g. Firefox or Dolphin.", {"name": "the app's name"}),
    "open_path": (ASK, "Open a file, folder or web address with its default app.", {"path": "path or URL"}),
    "take_screenshot": (ASK, "Take a screenshot of the whole screen and save it. Returns the file path.", {}),
    "read_clipboard": (ASK, "Read the text on the clipboard.", {}),
    "set_clipboard": (ASK, "Put text on the clipboard.", {"text": "the text"}),
    "read_file": (ASK, "Read a text file.", {"path": "path of the file"}),
    "list_dir": (ASK, "List the files in a folder.", {"path": "path of the folder"}),
    "run_command": (ASK, "Run a shell command and return its output.", {"command": "the command"}),
}


def specs():
    """The tool list in the OpenAI function format."""
    out = []
    for name, (_, desc, params) in TOOLS.items():
        out.append({"type": "function", "function": {
            "name": name, "description": desc,
            "parameters": {"type": "object", "properties": {k: {"type": "string", "description": v} for k, v in params.items()}, "required": list(params)},
        }})
    return out


def tier(name):
    return TOOLS[name][0] if name in TOOLS else None


def blocked(command):
    return any(re.search(p, command) for p in BLOCKED)


def summary(name, args):
    """One line for the approval card and the log. Typed text and clipboard contents are shown in full on the card, shortened in the log."""
    if name == "run_command":
        return "Run: " + str(args.get("command", ""))
    if name in ("open_path", "read_file", "list_dir"):
        return "%s: %s" % (name.replace("_", " ").capitalize(), args.get("path", ""))
    if name == "set_clipboard":
        return "Put on the clipboard: " + str(args.get("text", ""))[:200]
    if name == "read_clipboard":
        return "Read the clipboard"
    if name == "take_screenshot":
        return "Take a screenshot of your screen"
    return "%s %s" % (name, json.dumps(args, ensure_ascii=False)[:160])


class Approver:
    """Holds a tool call until the person answers the card (or two minutes pass, which counts as no)."""

    def __init__(self, timeout=120):
        self.timeout = timeout
        self.pending = {}
        self.lock = threading.Lock()

    def ask(self, name, text, emit):
        rid = uuid.uuid4().hex[:10]
        slot = {"ev": threading.Event(), "ok": False}
        with self.lock:
            self.pending[rid] = slot
        emit({"t": "approve", "id": rid, "tool": name, "summary": text})
        slot["ev"].wait(self.timeout)
        with self.lock:
            self.pending.pop(rid, None)
        return slot["ok"]

    def answer(self, rid, ok):
        with self.lock:
            slot = self.pending.get(rid)
        if slot:
            slot["ok"] = bool(ok)
            slot["ev"].set()

    def deny_all(self):
        with self.lock:
            slots = list(self.pending.values())
        for s in slots:
            s["ok"] = False
            s["ev"].set()


def _trim(text):
    text = text if isinstance(text, str) else str(text)
    return text if len(text) <= MAX_RESULT else text[:MAX_RESULT] + "\n[cut: %d more characters]" % (len(text) - MAX_RESULT)


def _desktop_dirs():
    dirs = [os.path.expanduser("~/.local/share/applications")]
    for d in (os.environ.get("XDG_DATA_DIRS") or "/usr/local/share:/usr/share").split(":"):
        dirs.append(os.path.join(d, "applications"))
    dirs.append("/var/lib/flatpak/exports/share/applications")
    dirs.append(os.path.expanduser("~/.local/share/flatpak/exports/share/applications"))
    return dirs


def find_app(name, dirs=None):
    """The desktop entry that best matches a name: (file id, Exec line) or None. An exact name beats a partial one."""
    want = name.strip().lower()
    best = None
    for d in dirs or _desktop_dirs():
        try:
            files = sorted(os.listdir(d))
        except OSError:
            continue
        for f in files:
            if not f.endswith(".desktop"):
                continue
            try:
                text = open(os.path.join(d, f), encoding="utf-8", errors="ignore").read()
            except OSError:
                continue
            sect = text.split("[Desktop Action", 1)[0]
            if re.search(r"^(NoDisplay|Hidden)\s*=\s*true", sect, re.M):
                continue
            n = re.search(r"^Name\s*=\s*(.+)$", sect, re.M)
            e = re.search(r"^Exec\s*=\s*(.+)$", sect, re.M)
            if not n or not e:
                continue
            label = n.group(1).strip().lower()
            score = 3 if label == want else 2 if f[:-8].lower() == want else 1 if want in label else 0
            if score and (best is None or score > best[0]):
                best = (score, f[:-8], e.group(1).strip())
        if best and best[0] == 3:
            break
    return (best[1], best[2]) if best else None


class Tools:
    def __init__(self, config, store, run=None, http=None, which=shutil.which):
        self.config = config
        self.store = store
        self._run = run or self._subprocess
        self._http = http or self._urlopen
        self.which = which

    # --- plumbing the tests can replace
    def _subprocess(self, argv, timeout=30, shell=False):
        try:
            p = subprocess.run(argv, capture_output=True, text=True, timeout=timeout, shell=shell, cwd=os.path.expanduser("~"))
        except subprocess.TimeoutExpired:
            return 124, "", "timed out after %ds" % timeout
        except OSError as e:
            return 127, "", str(e)
        return p.returncode, p.stdout, p.stderr

    def _urlopen(self, url, body=None, headers=None, timeout=20):
        req = urllib.request.Request(url, data=body, headers=headers or {}, method="POST" if body is not None else "GET")
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.read().decode("utf-8", "replace")

    # --- running
    def execute(self, name, args):
        """Returns (ok, text). Never raises: a failure is a result the model can read."""
        try:
            fn = getattr(self, "t_" + name)
        except AttributeError:
            return False, "unknown tool: " + name
        try:
            ok, text = fn(args or {})
        except Exception as e:
            return False, "%s failed: %s" % (name, e)
        return ok, _trim(text)

    def t_get_time(self, a):
        return True, time.strftime("%A %d %B %Y, %H:%M (%Z)")

    def t_remember(self, a):
        text = str(a.get("text", "")).strip()
        if not text:
            return False, "nothing to remember"
        self.store.add_fact(text)
        return True, "Remembered."

    def t_notify(self, a):
        if not self.which("notify-send"):
            return False, "notify-send is not installed"
        rc, _, err = self._run(["notify-send", "-a", "Faze", str(a.get("title", "Faze")), str(a.get("body", ""))])
        return rc == 0, "Shown." if rc == 0 else err.strip()

    def t_web_search(self, a):
        key = self.config.key("tavily")
        if not key:
            return False, "No Tavily key is set (Settings > Search key), so I can't search the web."
        q = str(a.get("query", "")).strip()
        if not q:
            return False, "nothing to search for"
        raw = self._http("https://api.tavily.com/search",
                         json.dumps({"query": q, "max_results": 5, "include_answer": True}).encode(),
                         {"Content-Type": "application/json", "Authorization": "Bearer " + key})
        j = json.loads(raw)
        lines = []
        if j.get("answer"):
            lines.append("Summary: " + j["answer"])
        for r in j.get("results", [])[:5]:
            lines.append("- %s (%s): %s" % (r.get("title", ""), r.get("url", ""), (r.get("content") or "")[:300]))
        return True, "\n".join(lines) or "No results."

    def t_open_app(self, a):
        name = str(a.get("name", "")).strip()
        if not name:
            return False, "which app?"
        hit = find_app(name)
        if hit:
            app_id, _ = hit
            for argv in (["gtk-launch", app_id], ["kioclient", "exec", app_id + ".desktop"]):
                if self.which(argv[0]):
                    rc, _, err = self._run(argv, 10)
                    if rc == 0:
                        return True, "Opened %s." % name
            return self._spawn(hit[1].split("%")[0].strip(), name)
        if self.which(name.lower()):
            return self._spawn(name.lower(), name)
        return False, "I couldn't find an app called %s." % name

    def _spawn(self, cmd, name):
        try:
            subprocess.Popen(shlex.split(cmd), stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
            return True, "Opened %s." % name
        except (OSError, ValueError) as e:
            return False, "could not start %s: %s" % (name, e)

    def t_open_path(self, a):
        p = str(a.get("path", "")).strip()
        if not p:
            return False, "nothing to open"
        if not re.match(r"^[a-z]+://", p):
            p = os.path.expanduser(p)
            if not os.path.exists(p):
                return False, "that path does not exist"
        rc, _, err = self._run(["xdg-open", p], 10)
        return rc == 0, "Opened." if rc == 0 else (err.strip() or "xdg-open failed")

    def t_take_screenshot(self, a):
        d = os.path.join(cfg.data_dir(), "screenshots")
        os.makedirs(d, exist_ok=True)
        path = os.path.join(d, time.strftime("screen-%Y%m%d-%H%M%S.png"))
        for argv in (["spectacle", "-b", "-n", "-f", "-o", path], ["grim", path], ["import", "-window", "root", path]):
            if self.which(argv[0]):
                rc, _, err = self._run(argv, 20)
                if rc == 0 and os.path.exists(path):
                    return True, "Saved to " + path
        return False, "no screenshot tool worked (tried spectacle, grim, import)"

    def t_read_clipboard(self, a):
        for argv in (["wl-paste", "-n"], ["xclip", "-o", "-selection", "clipboard"], ["qdbus6", "org.kde.klipper", "/klipper", "getClipboardContents"]):
            if self.which(argv[0]):
                rc, out, _ = self._run(argv, 5)
                if rc == 0:
                    return True, out or "(the clipboard is empty)"
        return False, "could not read the clipboard (install wl-clipboard or xclip)"

    def t_set_clipboard(self, a):
        text = str(a.get("text", ""))
        for argv in (["wl-copy", text], ["qdbus6", "org.kde.klipper", "/klipper", "setClipboardContents", text]):
            if self.which(argv[0]):
                rc, _, _ = self._run(argv, 5)
                if rc == 0:
                    return True, "Copied."
        return False, "could not set the clipboard (install wl-clipboard)"

    def t_read_file(self, a):
        p = os.path.expanduser(str(a.get("path", "")))
        if not os.path.isfile(p):
            return False, "that file does not exist"
        with open(p, "rb") as f:
            data = f.read(MAX_RESULT * 4)
        if b"\0" in data[:2000]:
            return False, "that looks like a binary file, not text"
        return True, data.decode("utf-8", "replace")

    def t_list_dir(self, a):
        p = os.path.expanduser(str(a.get("path", "~")) or "~")
        if not os.path.isdir(p):
            return False, "that folder does not exist"
        names = sorted(os.listdir(p))
        return True, "\n".join(n + ("/" if os.path.isdir(os.path.join(p, n)) else "") for n in names[:300]) or "(empty)"

    def t_run_command(self, a):
        cmd = str(a.get("command", "")).strip()
        if not cmd:
            return False, "no command"
        if blocked(cmd):
            return False, "I won't run that: it looks destructive or needs administrator rights."
        rc, out, err = self._run(["bash", "-c", cmd], 30)
        text = out + (("\n[stderr] " + err) if err.strip() else "")
        return rc == 0, (text.strip() or "(no output)") + ("" if rc == 0 else "\n[exit code %d]" % rc)
