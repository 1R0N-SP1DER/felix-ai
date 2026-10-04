import os
from pathlib import Path
from logging.handlers import RotatingFileHandler
import logging

LOG_DIR = Path(os.environ.get("FELIX_LOG_DIR") or (Path.home() / "felix-ai" / "logs"))
LOG_FILE = LOG_DIR / "actions.log"

LOG_DIR.mkdir(parents=True, exist_ok=True)

handler = RotatingFileHandler(LOG_FILE, maxBytes=1024*1024, backupCount=3)
formatter = logging.Formatter('%(message)s')
handler.setFormatter(formatter)

logger = logging.getLogger("action_logger")
logger.setLevel(logging.INFO)
logger.addHandler(handler)

_console = True
_inline_open = False  # a "Faze [tag]: " prefix is waiting on the current line


def set_console(enabled: bool) -> None:
    global _console
    _console = bool(enabled)


def set_inline_open(is_open: bool) -> None:
    global _inline_open
    _inline_open = bool(is_open)


def log_action(layer, tool, args, result, ms, extra=None, echo=True):
    result = str(result).replace("\n", " ")
    if len(result) > 200:
        result = result[:200] + "..."
    log_line = f"[{layer} -> {tool}] {args} => {result} ({ms}ms)"
    if extra:
        log_line += f" | {extra}"
    logger.info(log_line)
    if echo and _console:
        global _inline_open
        if _inline_open:
            print()
            _inline_open = False
        print(log_line)
