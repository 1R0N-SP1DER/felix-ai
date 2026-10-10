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

SYSTEM_COMMANDS = {"am", "pm", "su", "settings", "screencap", "screenrecord", "input", "cmd", "dumpsys", "wm", "svc",
                   "getprop", "setprop", "monkey", "service", "content", "appops", "ime", "logcat"}
_LINKER = re.compile(r"\bCFI:|linker64|CANNOT LINK")
OUTPUT_CAP = 4000


def _clean_env() -> dict:
    return {k: v for k, v in os.environ.items() if k not in ("LD_PRELOAD", "LD_LIBRARY_PATH")}


def _is_system(cmd0: str) -> bool:
    return cmd0.startswith(("/system/", "/apex/", "/vendor/")) or os.path.basename(cmd0) in SYSTEM_COMMANDS


def run(cmd, root: bool = False, timeout: float = 15.0, input_text: str = None) -> str:
    """Run a command (a list of arguments, or a string split like a shell would) and return its output.
    Raises RuntimeError with the real error text if it fails."""
    args = shlex.split(cmd) if isinstance(cmd, str) else [str(a) for a in cmd]
    if not args:
        raise RuntimeError("faze_run needs a command")
    if root:
        args = ["su", "-c", "unset LD_PRELOAD LD_LIBRARY_PATH; " + " ".join(shlex.quote(a) for a in args)]
    attempts = [_clean_env()] if _is_system(args[0]) else [None, _clean_env()]
    proc = None
    for env in attempts:
        try:
            proc = subprocess.run(args, capture_output=True, text=True, timeout=timeout, input=input_text, env=env)
        except subprocess.TimeoutExpired:
            raise RuntimeError(f"{args[0]} timed out after {timeout:.0f}s")
        except OSError as e:
            raise RuntimeError(f"could not run {args[0]}: {e.strerror or e}")
        if not _LINKER.search((proc.stdout or "") + (proc.stderr or "")):
            break
    if proc.returncode != 0:
        tail = ((proc.stderr or proc.stdout or "").strip().splitlines() or ["failed"])[-1]
        raise RuntimeError(f"{args[0]} exited {proc.returncode}: {tail[:300]}")
    return (proc.stdout or "").strip()[:OUTPUT_CAP]
