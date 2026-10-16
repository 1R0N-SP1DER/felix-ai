"""
felix-ai Core -- env_loader.py
The service can be started from Tasker, a boot script or a root shell, none of which read ~/.bashrc, so the API key
the interactive shell exports is missing. This finds it:

  1. ~/.felix/env         GEMINI_API_KEY=... (or `export GEMINI_API_KEY=...`), one per line, chmod 600
  2. ~/.bashrc, ~/.bash_profile, ~/.profile    only plain `export GEMINI_API_KEY=value` lines

A value already in the environment always wins; quoted values and plain values work, anything containing `$`
(a reference to another variable) is skipped.
"""

import os
import re

NAMES = ("GEMINI_API_KEY", "GROQ_API_KEY", "OPENROUTER_API_KEY", "TAVILY_API_KEY", "CEREBRAS_API_KEY", "MISTRAL_API_KEY", "ANTHROPIC_API_KEY")
SHELL_FILES = (".bashrc", ".bash_profile", ".profile")
_LINE = re.compile(r"""^\s*(?P<export>export\s+)?(?P<name>[A-Z][A-Z0-9_]*)=(?:"(?P<dq>[^"]*)"|'(?P<sq>[^']*)'|(?P<bare>[^\s#]*))""")


def _scan(path: str, names, need_export: bool) -> dict:
    found = {}
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            for line in f:
                m = _LINE.match(line)
                if not m or m.group("name") not in names or (need_export and not m.group("export")):
                    continue
                value = next((m.group(g) for g in ("dq", "sq", "bare") if m.group(g) is not None), "").strip()
                if value and "$" not in value:
                    found[m.group("name")] = value  # the last assignment in a file wins, like the shell
    except OSError:
        pass
    return found


def load_env(home: str = None, environ=None, names=NAMES) -> dict:
    """Fill in missing variables. Returns {name: file it came from} for what was loaded."""
    environ = os.environ if environ is None else environ
    home = home or os.environ.get("HOME") or os.path.expanduser("~")
    loaded = {}
    sources = [(os.path.join(home, ".felix", "env"), False)] + [(os.path.join(home, f), True) for f in SHELL_FILES]
    for path, need_export in sources:
        missing = [n for n in names if not environ.get(n)]
        if not missing:
            break
        for name, value in _scan(path, missing, need_export).items():
            environ[name] = value
            loaded[name] = path
    return loaded
