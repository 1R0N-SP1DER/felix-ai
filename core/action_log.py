from pathlib import Path
from logging.handlers import RotatingFileHandler
import logging

LOG_DIR = Path.home() / "felix-ai" / "logs"
LOG_FILE = LOG_DIR / "actions.log"

if not LOG_DIR.exists():
    LOG_DIR.mkdir(parents=True)

handler = RotatingFileHandler(LOG_FILE, maxBytes=1024*1024, backupCount=3)
formatter = logging.Formatter('%(message)s')
handler.setFormatter(formatter)

logger = logging.getLogger("action_logger")
logger.setLevel(logging.INFO)
logger.addHandler(handler)

def log_action(layer, tool, args, result, ms, extra=None):
    log_line = f"[{layer} -> {tool}] {args} => {result} ({ms}ms)"
    if extra:
        log_line += f" | {extra}"
    logger.info(log_line)
    print(log_line)
