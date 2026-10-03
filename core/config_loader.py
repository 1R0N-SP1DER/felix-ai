"""
felix-ai Core — config_loader.py
Loads config/profile.json into a plain dict.
"""

import json
from pathlib import Path

CONFIG_PATH = Path(__file__).parent / "config" / "profile.json"


def load_config() -> dict:
    with open(CONFIG_PATH, "r") as f:
        return json.load(f)


def get_llm_url(config: dict) -> str:
    llm = config["llm"]
    return f"http://{llm['host']}:{llm['port']}{llm['endpoint']}"
