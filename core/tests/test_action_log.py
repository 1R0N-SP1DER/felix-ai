from action_log import log_action
import os

print("Running PASS check for Stage 1 (Action Log)...")
log_action("qwen", "battery", "{}", "19%", 50)
log_action("gemini", "search_web", "{query: 'test'}", "success", 1200)

LOG_FILE = os.path.expanduser("~/felix-ai/logs/actions.log")
print(f"\n--- Tail of {LOG_FILE} ---")
with open(LOG_FILE, 'r') as f:
    lines = f.readlines()
    for line in lines[-2:]:
        print(line.strip())
