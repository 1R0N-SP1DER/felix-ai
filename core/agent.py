"""
felix-ai Core -- agent.py
Gemini-written tools, saved tasks, and the approval modes that govern them.

  - A tool is a small Python file defining run(args) -> str. Gemini writes it with
    create_tool; it is checked, shown to you (or auto-approved in yolo mode with the
    same description), saved under ~/.felix/agent/tools and registered so every model
    can call it by name.
  - A task is a record of something Gemini worked out with tools (the request, the
    tools it used, the outcome). When a similar request comes in later it is handed
    to the model as a worked example, so Qwen can repeat it offline.

Safety, honestly: tools run in a separate python process with a scrubbed environment,
a scratch working directory, a CPU/memory limit and a timeout. Code that imports
anything outside a short list of pure-computation modules (or reaches for open, eval,
exec, dunder attributes...) is classed "high" and only runs while agent.high_tools is
on. That limits accidents; it is not a boundary against deliberately hostile code, so
read what you approve, and treat yolo mode as trust.
"""

import ast
import functools
import json
import os
import re
import subprocess
import sys
import threading
import time
import uuid
from pathlib import Path

import gemini_tools
from permission_policy import PERMISSION_REGISTRY
from tool_executor import TOOL_EXECUTORS, ToolExecutionError
from tool_schema import TOOL_REGISTRY

MODES = ("off", "ask", "yolo")
MODE_HELP = {
    "off": "Gemini cannot create tools",
    "ask": "Gemini shows each new tool and waits for your approval",
    "yolo": "new tools are added without asking; you still get a description of each",
}
BUILTIN_TOOLS = frozenset(TOOL_REGISTRY) | {"create_tool"}
MAX_TOOLS, MAX_TASKS, MAX_CODE = 25, 60, 8000
TOOL_TIMEOUT = 20.0
OUTPUT_CAP = 4000
PY_TYPES = {"string": str, "integer": int, "number": float, "boolean": bool}
NAME_RE = re.compile(r"[a-z][a-z0-9_]{2,30}")
PARAM_RE = re.compile(r"[a-z][a-z0-9_]{0,24}")

# Modules that only compute. Anything else makes a tool "high".
SAFE_MODULES = {
    "json", "math", "re", "datetime", "time", "statistics", "itertools", "functools", "collections", "textwrap",
    "string", "random", "hashlib", "base64", "decimal", "fractions", "calendar", "unicodedata", "urllib.parse",
    "html", "uuid", "operator", "typing", "dataclasses", "enum", "heapq", "bisect", "zlib", "colorsys", "numbers",
}
RISKY_CALLS = {"open", "exec", "eval", "compile", "__import__", "input", "breakpoint", "globals", "locals",
               "vars", "getattr", "setattr", "delattr"}
SAFE_DUNDERS = {"__name__", "__doc__", "__len__", "__str__", "__repr__", "__init__"}

POLICY = {"high_tools": False}


def set_policy(high_tools=None):
    if high_tools is not None:
        POLICY["high_tools"] = bool(high_tools)


class ToolCreateError(Exception):
    """A create_tool request that cannot be accepted; the message goes back to Gemini."""


# ---------------------------------------------------------------- code checks

def assess_code(code: str) -> dict:
    """{'ok', 'error', 'level': 'normal'|'high', 'reasons': [...]} for a tool's source."""
    if not isinstance(code, str) or not code.strip():
        return {"ok": False, "error": "no code given", "level": "normal", "reasons": []}
    if len(code) > MAX_CODE:
        return {"ok": False, "error": f"code is over {MAX_CODE} characters", "level": "normal", "reasons": []}
    if "GEMINI_API_KEY" in code:
        return {"ok": False, "error": "tools may not touch the API key", "level": "normal", "reasons": []}
    try:
        tree = ast.parse(code)
    except SyntaxError as e:
        return {"ok": False, "error": f"syntax error on line {e.lineno}: {e.msg}", "level": "normal", "reasons": []}
    run = next((n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name == "run"), None)
    if run is None or len(run.args.args) != 1:
        return {"ok": False, "error": "define exactly one entry point: def run(args): ... return a string",
                "level": "normal", "reasons": []}
    reasons = []

    def note(text):
        if text not in reasons:
            reasons.append(text)

    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            for alias in node.names:
                if alias.name not in SAFE_MODULES:
                    note(f"imports {alias.name}")
        elif isinstance(node, ast.ImportFrom):
            module = node.module or ""
            if node.level or module not in SAFE_MODULES:
                note(f"imports from {'.' * node.level}{module}")
        elif isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id in RISKY_CALLS:
            note(f"calls {node.func.id}()")
        elif isinstance(node, ast.Name) and node.id == "faze_run":
            note("runs system commands (faze_run)")
        elif isinstance(node, ast.Attribute) and node.attr.startswith("__") and node.attr.endswith("__") \
                and node.attr not in SAFE_DUNDERS:
            note(f"uses {node.attr}")
    return {"ok": True, "error": "", "level": "high" if reasons else "normal", "reasons": reasons}


def normalize_params(raw) -> dict:
    """params_json -> {name: {'type', 'description'}}. Accepts {"n": "string"} or {"n": {"type":..., "description":...}}."""
    if raw in (None, "", {}):
        return {}
    try:
        data = json.loads(raw) if isinstance(raw, str) else raw
    except ValueError:
        raise ToolCreateError("params_json is not valid JSON")
    if not isinstance(data, dict) or len(data) > 6:
        raise ToolCreateError("params_json must be an object with at most 6 parameters")
    out = {}
    for name, spec in data.items():
        if not PARAM_RE.fullmatch(str(name)):
            raise ToolCreateError(f"bad parameter name '{name}' (lowercase letters, digits, underscore)")
        kind, desc = (spec, str(name)) if isinstance(spec, str) else (
            (spec or {}).get("type", "string"), str((spec or {}).get("description") or name))
        if kind not in PY_TYPES:
            raise ToolCreateError(f"parameter '{name}' has type '{kind}'; use string, integer, number or boolean")
        out[str(name)] = {"type": kind, "description": desc[:120]}
    return out


def prepare_tool(args: dict, high_allowed: bool):
    """Validate a create_tool request. Returns (info, meta, code); raises ToolCreateError."""
    name = str(args.get("name", "")).strip()
    if not NAME_RE.fullmatch(name):
        raise ToolCreateError("name must be 3-31 characters: lowercase letters, digits and underscores, starting with a letter")
    if name in BUILTIN_TOOLS:
        raise ToolCreateError(f"'{name}' is a built-in tool; pick another name")
    description = str(args.get("description", "")).strip()[:200]
    how = str(args.get("how_it_works", "")).strip()[:600]
    if not description or not how:
        raise ToolCreateError("description and how_it_works are both required (the user reads them before approving)")
    params = normalize_params(args.get("params_json"))
    code = str(args.get("code", ""))
    check = assess_code(code)
    if not check["ok"]:
        raise ToolCreateError(check["error"])
    declared = str(args.get("level", "normal")).strip().lower()
    level = "high" if declared == "high" or check["level"] == "high" else "normal"
    if level == "high" and not high_allowed:
        why = "; ".join(check["reasons"]) or "you declared it high-level"
        raise ToolCreateError(f"Higher-level tools are turned off, so '{name}' was not created ({why}). "
                              "Rewrite it with only pure computation, or ask the user to run /elevated on.")
    store = STORE
    if name not in store.tools and len(store.tools) >= MAX_TOOLS:
        raise ToolCreateError(f"already {MAX_TOOLS} custom tools; ask the user to remove some (/tools)")
    meta = {"name": name, "description": description, "how_it_works": how, "level": level, "params": params,
            "created": time.time(), "uses": 0}
    info = {"kind": "tool", "name": name, "description": description, "how_it_works": how, "level": level,
            "reasons": check["reasons"], "params": {k: v["type"] for k, v in params.items()},
            "code": code, "replaces": name in store.tools}
    return info, meta, code


def format_info(info: dict) -> str:
    """Plain-text version of an approval request (terminal, logs, yolo notices)."""
    lines = [f"{'Updated' if info.get('replaces') else 'New'} tool: {info['name']}  [{info['level']}]",
             f"What it does: {info['description']}", f"How it works: {info['how_it_works']}"]
    if info.get("params"):
        lines.append("Takes: " + ", ".join(f"{k} ({v})" for k, v in info["params"].items()))
    if info.get("reasons"):
        lines.append("Higher-level because it " + "; ".join(info["reasons"]))
    return "\n".join(lines)


# ---------------------------------------------------------------- running tools

_RUNNER = (
    "import sys, json, importlib.util\n"
    "sys.path.insert(0, sys.argv[2])\n"
    "import tool_helpers\n"
    "spec = importlib.util.spec_from_file_location('faze_tool', sys.argv[1])\n"
    "mod = importlib.util.module_from_spec(spec)\n"
    "mod.faze_run = tool_helpers.run\n"
    "spec.loader.exec_module(mod)\n"
    "out = mod.run(json.loads(sys.stdin.read() or '{}'))\n"
    "sys.stdout.write(out if isinstance(out, str) else json.dumps(out, default=str))\n"
)


def _limits(timeout):
    def apply():
        try:
            import resource
            resource.setrlimit(resource.RLIMIT_CPU, (int(timeout) + 2, int(timeout) + 2))
            # Soft limit only: faze_run lifts it for the commands it starts. A hard cap here is inherited by
            # Android's own binaries, whose CFI shadow mapping then fails ("MapShadow CHECK p != MAP_FAILED").
            resource.setrlimit(resource.RLIMIT_AS, (768 * 1024 * 1024, resource.RLIM_INFINITY))
            resource.setrlimit(resource.RLIMIT_FSIZE, (8 * 1024 * 1024, 8 * 1024 * 1024))
        except (ImportError, ValueError, OSError):
            pass
    return apply


_SECRET = re.compile(r"KEY|TOKEN|SECRET|PASSWORD|PASSWD|CREDENTIAL|AUTH", re.IGNORECASE)


def _clean_env(work: Path) -> dict:
    """Everything the shell had (Android's variables, Termux's library paths: stripping those makes system
    binaries such as am and su crash in the linker) minus anything that looks like a secret."""
    env = {k: v for k, v in os.environ.items() if not _SECRET.search(k)}
    env.update({"HOME": str(work), "TMPDIR": str(work), "PYTHONIOENCODING": "utf-8", "PYTHONDONTWRITEBYTECODE": "1"})
    return env


# ---------------------------------------------------------------- store

def keywords(text: str) -> set:
    stop = {"the", "a", "an", "to", "of", "for", "in", "on", "and", "or", "is", "it", "my", "me", "i", "you", "please",
            "can", "could", "do", "what", "how", "with", "that", "this", "be", "are", "was", "at", "by", "from", "as",
            "your", "we", "us", "so", "if", "then", "just", "some", "want", "need", "would", "like", "make", "get"}
    return {w for w in re.findall(r"[a-z][a-z0-9']+", str(text).lower()) if w not in stop and len(w) > 1}


class AgentStore:
    def __init__(self, root):
        self.root = Path(root).expanduser()
        self.tools = {}
        self.tasks = []
        self.rev = 0  # bumps on every change so open apps know to refresh their lists
        self._lock = threading.RLock()

    # -- persistence
    def _read(self, name, default):
        try:
            return json.loads((self.root / name).read_text())
        except (OSError, ValueError):
            return default

    def _write(self, name, data):
        self.root.mkdir(parents=True, exist_ok=True)
        tmp = self.root / (name + ".tmp")
        tmp.write_text(json.dumps(data, indent=1))
        os.replace(tmp, self.root / name)
        self.rev += 1

    def load(self):
        with self._lock:
            for name in list(self.tools):
                self.unregister(name)
            self.tools = {}
            for name, meta in (self._read("tools.json", {}) or {}).items():
                if NAME_RE.fullmatch(name) and name not in BUILTIN_TOOLS and (self.root / "tools" / f"{name}.py").exists():
                    self.tools[name] = meta
                    self.register(meta)
            tasks = self._read("tasks.json", [])
            self.tasks = tasks if isinstance(tasks, list) else []

    # -- registration into the live tool tables
    def register(self, meta):
        name = meta["name"]
        TOOL_REGISTRY[name] = {p: PY_TYPES[s["type"]] for p, s in meta["params"].items()}
        PERMISSION_REGISTRY[name] = "safe"
        TOOL_EXECUTORS[name] = functools.partial(_run_registered, name)
        gemini_tools.TOOL_DESCRIPTIONS[name] = f"Custom tool. Call this when the user asks: {meta['description']}"
        gemini_tools.TOOL_PARAM_DESCRIPTIONS[name] = {p: s["description"] for p, s in meta["params"].items()}

    def unregister(self, name):
        for table in (TOOL_REGISTRY, PERMISSION_REGISTRY, TOOL_EXECUTORS, gemini_tools.TOOL_DESCRIPTIONS,
                      gemini_tools.TOOL_PARAM_DESCRIPTIONS):
            table.pop(name, None)

    # -- tools
    def add_tool(self, meta, code):
        with self._lock:
            (self.root / "tools").mkdir(parents=True, exist_ok=True)
            (self.root / "work").mkdir(parents=True, exist_ok=True)
            path = self.root / "tools" / f"{meta['name']}.py"
            path.write_text(code)
            os.chmod(path, 0o600)
            old = self.tools.get(meta["name"])
            if old:
                meta["uses"], meta["created"] = old.get("uses", 0), old.get("created", meta["created"])
            self.tools[meta["name"]] = meta
            self._write("tools.json", self.tools)
            self.register(meta)
        return meta

    def remove_tool(self, name) -> bool:
        with self._lock:
            if name not in self.tools:
                return False
            self.unregister(name)
            del self.tools[name]
            try:
                (self.root / "tools" / f"{name}.py").unlink()
            except OSError:
                pass
            self._write("tools.json", self.tools)
            return True

    def clear_tools(self) -> int:
        with self._lock:
            n = len(self.tools)
            for name in list(self.tools):
                self.remove_tool(name)
            return n

    def run_tool(self, name, parameters, timeout=None):
        timeout = TOOL_TIMEOUT if timeout is None else timeout
        meta = self.tools.get(name)
        if not meta:
            raise ToolExecutionError(f"custom tool '{name}' no longer exists")
        if meta["level"] == "high" and not POLICY["high_tools"]:
            raise ToolExecutionError(f"'{name}' is a higher-level tool and those are off (use /elevated on)")
        path = self.root / "tools" / f"{name}.py"
        work = self.root / "work"
        work.mkdir(parents=True, exist_ok=True)
        try:
            proc = subprocess.run([sys.executable, "-I", "-B", "-c", _RUNNER, str(path), str(Path(__file__).resolve().parent)], input=json.dumps(parameters),
                                  capture_output=True, text=True, timeout=timeout, cwd=str(work),
                                  env=_clean_env(work), preexec_fn=_limits(timeout) if os.name == "posix" else None)
        except subprocess.TimeoutExpired:
            raise ToolExecutionError(f"'{name}' timed out after {timeout:.0f}s")
        except OSError as e:
            raise ToolExecutionError(f"could not start '{name}': {e.strerror or e}")
        if proc.returncode != 0:
            tail = (proc.stderr.strip().splitlines() or ["failed"])[-1]
            raise ToolExecutionError(f"'{name}' failed: {tail[:300]}")
        with self._lock:
            meta["uses"] = meta.get("uses", 0) + 1
            self._write("tools.json", self.tools)
        out = proc.stdout.strip()
        return out[:OUTPUT_CAP] if out else "(no output)"

    # -- tasks
    def save_task(self, prompt, steps, reply, created=()):
        words = keywords(prompt)
        if not words:
            return None
        clean = [{"tool": s["tool"], "args": s.get("args", {}), "ok": bool(s.get("ok", True))} for s in steps][:8]
        with self._lock:
            for t in self.tasks:
                if _jaccard(words, set(t["keywords"])) >= 0.8:
                    t.update(steps=clean, reply=str(reply)[:300], runs=t.get("runs", 1) + 1, ts=time.time(),
                             created_tools=sorted(set(t.get("created_tools", [])) | set(created)))
                    self._write("tasks.json", self.tasks)
                    return t
            task = {"id": uuid.uuid4().hex[:8], "prompt": str(prompt)[:300], "keywords": sorted(words), "steps": clean,
                    "reply": str(reply)[:300], "created_tools": sorted(set(created)), "uses": 0, "runs": 1, "ts": time.time()}
            self.tasks.append(task)
            while len(self.tasks) > MAX_TASKS:
                self.tasks.remove(min(self.tasks, key=lambda t: (t.get("uses", 0), t.get("ts", 0))))
            self._write("tasks.json", self.tasks)
            return task

    def match_tasks(self, prompt, k=2, min_score=0.5):
        words = keywords(prompt)
        scored = [(_jaccard(words, set(t["keywords"])), t) for t in self.tasks] if words else []
        return [t for score, t in sorted(scored, key=lambda p: -p[0]) if score >= min_score][:k]

    def task_hint(self, prompt) -> str:
        found = self.match_tasks(prompt)
        if not found:
            return ""
        lines = ["A similar request was solved before. Repeat the same approach with the new details:"]
        for t in found:
            steps = "; ".join(f"{i}. {s['tool']}({', '.join(f'{k}={json.dumps(v)[:40]}' for k, v in s['args'].items())})"
                              for i, s in enumerate(t["steps"], 1)) or "answered directly"
            lines.append(f"- Request: \"{t['prompt'][:120]}\"\n  Steps: {steps}\n  Outcome: {t['reply'][:160]}")
        with self._lock:
            for t in found:
                t["uses"] = t.get("uses", 0) + 1
            self._write("tasks.json", self.tasks)
        return "\n".join(lines)[:900]

    def remove_task(self, task_id) -> bool:
        with self._lock:
            keep = [t for t in self.tasks if t["id"] != task_id]
            if len(keep) == len(self.tasks):
                return False
            self.tasks = keep
            self._write("tasks.json", self.tasks)
            return True

    def clear_tasks(self) -> int:
        with self._lock:
            n = len(self.tasks)
            self.tasks = []
            self._write("tasks.json", self.tasks)
            return n

    # -- views
    def code(self, name):
        try:
            return (self.root / "tools" / f"{name}.py").read_text() if name in self.tools else None
        except OSError:
            return None

    def snapshot(self) -> dict:
        with self._lock:
            return {
                "rev": self.rev,
                "tools": [{**{k: m[k] for k in ("name", "description", "how_it_works", "level", "uses", "created")},
                           "params": {p: {"type": s["type"], "description": s["description"]} for p, s in m["params"].items()}}
                          for m in sorted(self.tools.values(), key=lambda m: m["name"])],
                "tasks": [{"id": t["id"], "prompt": t["prompt"], "tools": sorted({s["tool"] for s in t["steps"]}),
                           "uses": t.get("uses", 0), "runs": t.get("runs", 1), "ts": t.get("ts", 0)}
                          for t in sorted(self.tasks, key=lambda t: -t.get("ts", 0))],
            }


def _jaccard(a: set, b: set) -> float:
    return len(a & b) / len(a | b) if a and b else 0.0


def _run_registered(name, parameters):
    return STORE.run_tool(name, parameters)


def default_dir() -> str:
    return os.environ.get("FAZE_AGENT_DIR") or "~/.felix/agent"


STORE = AgentStore(default_dir())


def use_store(root):
    """Swap the global store (tests); returns it after loading."""
    global STORE
    for name in list(STORE.tools):
        STORE.unregister(name)
    STORE = AgentStore(root)
    STORE.load()
    return STORE


def init(config: dict):
    use_store(default_dir())
    set_policy(high_tools=config.get("agent", {}).get("high_tools", False))


# ---------------------------------------------------------------- what the models are told

CREATE_TOOL_DESCRIPTION = (
    "Create a new reusable tool when none of the existing tools can do something the user wants done. Write a "
    "small python function `def run(args): ...` that takes a dict of the declared parameters and returns a string. "
    "Prefer pure computation with the standard library. Describe it honestly: the user reads description and "
    "how_it_works before approving. Set level to 'high' if it needs the network, shell, files or anything beyond pure computation."
)


def create_tool_declaration() -> dict:
    def s(desc):
        return {"type": "string", "description": desc}
    return {"type": "function", "function": {
        "name": "create_tool", "description": CREATE_TOOL_DESCRIPTION,
        "parameters": {"type": "object", "properties": {
            "name": s("snake_case tool name, 3-31 characters"),
            "description": s("one sentence: what the tool does and when to use it"),
            "how_it_works": s("two or three sentences explaining the code's logic in plain language"),
            "params_json": s('JSON object of parameters, e.g. {"miles": {"type": "number", "description": "distance in miles"}}. Types: string, integer, number, boolean. Use "{}" for none.'),
            "code": s("python source defining def run(args) that returns a string"),
            "level": s("normal or high"),
        }, "required": ["name", "description", "how_it_works", "params_json", "code", "level"]}}}


def gemini_schema(config: dict) -> list:
    import device
    schema = gemini_tools.build_gemini_tools_schema(device=device.SETTINGS["control"] != "off")
    if config.get("agent", {}).get("mode", "ask") != "off":
        schema.append(create_tool_declaration())
    return schema


def local_schema() -> list:
    return gemini_tools.build_local_tools_schema()


CONTROL_NOTE = (
    "\nYou can operate the phone. Open the app with open_app, wait 1 to 2 seconds, then screen_read to see the labelled elements "
    "and their positions; use tap_text with a label, or tap with coordinates. When screen_read does not show what you need (a camera "
    "preview, an unlabelled shutter button), use screen_look and ask where it is. Check with screen_read or screen_look after each "
    "important step. In most camera apps press_key volume_down also takes the photo. listen lets you hear through the microphone. "
    "If something blocks you (a lock screen, a permission dialog, a login), stop and tell the user. Never type passwords or payment "
    "details, and never send messages, buy things or delete things without the user saying so in this request.\n")


def agent_note(config: dict) -> str:
    import device
    cfg = config.get("agent", {})
    if device.SETTINGS["control"] != "off" and cfg.get("mode", "ask") == "off":
        return CONTROL_NOTE
    if cfg.get("mode", "ask") == "off":
        return ""
    high = "Higher-level tools are allowed." if cfg.get("high_tools") else "Higher-level tools are NOT allowed: use pure computation only."
    return ("\nYou can finish tasks in several steps. When the user asks for something none of your tools can do, never say you "
            "can't: build the tool with create_tool, then call it. Check the existing tools first; do not recreate one that exists. "
            "If a tool errors, read the error, fix the code and call create_tool again with the same name (up to two fixes) before "
            "telling the user what went wrong, quoting the exact error. When two steps do not depend on each other, ask for both tools in the same reply. "
            "Your tools run as Python 3 on the user's rooted Android phone inside Termux, not on a desktop. To run a command, call "
            "the built-in faze_run(['am', 'start', '-a', 'android.intent.action.VIEW', '-d', URL]) (add root=True for su). It returns "
            "the output text, raises an error with the real message, and already handles Android's linker (CFI) crash, so do not import "
            "subprocess. Examples: ['settings', 'put', 'system', 'screen_brightness', '120'] and ['screencap', '-p', PATH] need root=True; "
            "['termux-notification', '--title', T]. Using faze_run makes the tool higher-level. " + high + "\n"
            + (CONTROL_NOTE if device.SETTINGS["control"] != "off" else ""))


def custom_tools_text() -> str:
    if not STORE.tools:
        return ""
    lines = ["", "CUSTOM TOOLS (made earlier; call one when the request fits):"]
    for name, meta in sorted(STORE.tools.items()):
        lines.append(f"- {name}({', '.join(meta['params'])}) : {meta['description']}")
    return "\n".join(lines) + "\n"
