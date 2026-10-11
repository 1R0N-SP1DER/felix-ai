"""
felix-ai Core -- tool_helpers.py
What a Gemini-written tool can call without importing anything: faze_run(), a safe way to run a command.

Android's own binaries (am, su, pm, settings, screencap...) crash in the linker with a "CFI ... control flow
integrity" error when Termux's LD_PRELOAD / LD_LIBRARY_PATH are in their environment. faze_run clears them for
system commands, retries a crashed command with a clean environment, and wraps root commands so the variables
are unset inside su as well.
"""

import os
import re
import shlex
import subprocess

SYSTEM_COMMANDS = {"uiautomator", "wm", "am", "pm", "su", "settings", "screencap", "screenrecord", "input", "cmd", "dumpsys", "wm", "svc",
                   "getprop", "setprop", "monkey", "service", "content", "appops", "ime", "logcat"}
_LINKER = re.compile(r"\bCFI:|linker64|CANNOT LINK")
OUTPUT_CAP = 4000


def _lift_limits():
    """Runs in the child before exec: undo the tool sandbox's memory cap, which Android's binaries cannot live with."""
    try:
        import resource
        soft, hard = resource.getrlimit(resource.RLIMIT_AS)
        resource.setrlimit(resource.RLIMIT_AS, (hard, hard))
    except (ImportError, ValueError, OSError):
        pass


def _clean_env() -> dict:
    return {k: v for k, v in os.environ.items() if k not in ("LD_PRELOAD", "LD_LIBRARY_PATH")}


def _is_system(cmd0: str) -> bool:
    return cmd0.startswith(("/system/", "/apex/", "/vendor/")) or os.path.basename(cmd0) in SYSTEM_COMMANDS


def _exec(cmd, root: bool, timeout: float, input_text, text: bool):
    args = shlex.split(cmd) if isinstance(cmd, str) else [str(a) for a in cmd]
    if not args:
        raise RuntimeError("faze_run needs a command")
    if root:
        args = ["su", "-c", "unset LD_PRELOAD LD_LIBRARY_PATH; " + " ".join(shlex.quote(a) for a in args)]
    attempts = [_clean_env()] if _is_system(args[0]) else [None, _clean_env()]
    proc = None
    for env in attempts:
        try:
            proc = subprocess.run(args, capture_output=True, text=text, timeout=timeout, input=input_text, env=env,
                                  preexec_fn=_lift_limits if os.name == "posix" else None)
        except subprocess.TimeoutExpired:
            raise RuntimeError(f"{args[0]} timed out after {timeout:.0f}s")
        except OSError as e:
            raise RuntimeError(f"could not run {args[0]}: {e.strerror or e}")
        seen = (proc.stdout or "") + (proc.stderr or "") if text else (proc.stderr or b"").decode("utf-8", "replace")
        if not _LINKER.search(seen):
            break
    if proc.returncode != 0:
        said = proc.stderr if text else (proc.stderr or b"").decode("utf-8", "replace")
        said = said or (proc.stdout if text else "")
        tail = ((said or "").strip().splitlines() or ["failed"])[-1]
        raise RuntimeError(f"{args[0]} exited {proc.returncode}: {tail[:300]}")
    return proc


def run(cmd, root: bool = False, timeout: float = 15.0, input_text: str = None, cap: int = OUTPUT_CAP) -> str:
    """Run a command (a list of arguments, or a string split like a shell would) and return its output.
    Raises RuntimeError with the real error text if it fails."""
    proc = _exec(cmd, root, timeout, input_text, True)
    return (proc.stdout or "").strip()[:cap]


def run_bytes(cmd, root: bool = False, timeout: float = 20.0) -> bytes:
    """Like run, for binary output (a screenshot)."""
    return _exec(cmd, root, timeout, None, False).stdout or b""
