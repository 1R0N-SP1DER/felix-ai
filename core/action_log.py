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

def log_action(layer, tool, args, result, ms, extra=None, echo=True):
    result = str(result).replace("\n", " ")
    if len(result) > 200:
        result = result[:200] + "..."
    log_line = f"[{layer} -> {tool}] {args} => {result} ({ms}ms)"
    if extra:
        log_line += f" | {extra}"
    logger.info(log_line)
    if echo:
        print(log_line)
