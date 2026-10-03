"""
felix-ai Core — direct_tool_router.py
Bypasses unreliable LLM tool-calling for unambiguous direct requests.
"""

import re

DIRECT_PATTERNS = [
    (re.compile(r"\bvibrate\b", re.IGNORECASE), "vibrate", None),
    (re.compile(r"\bbattery\b", re.IGNORECASE), "get_battery_status", None),
    (re.compile(r"\b(what.?s the time|current time|what time is it|date and time|today.?s date)\b", re.IGNORECASE),
     "get_current_datetime", None),
    (re.compile(r"\bwhat.?s (on|in) my clipboard\b", re.IGNORECASE), "read_clipboard", None),
]

WRITE_CLIPBOARD_PATTERN = re.compile(
    r'(?:copy|put|save|write)\s+["\u201c](.+?)["\u201d]\s+(?:to|in|into)\s+(?:my |the )?clipboard',
    re.IGNORECASE
)

SET_VOLUME_PATTERN = re.compile(
    r"(?:set |turn )?volume (?:to )?(\d+)", re.IGNORECASE
)


def try_direct_route(user_message: str):
    write_match = WRITE_CLIPBOARD_PATTERN.search(user_message)
    if write_match:
        return {"tool": "write_clipboard", "parameters": {"text": write_match.group(1)}}

    volume_match = SET_VOLUME_PATTERN.search(user_message)
    if volume_match:
        return {"tool": "set_volume", "parameters": {"level": int(volume_match.group(1))}}

    for pattern, tool_name, _ in DIRECT_PATTERNS:
        if pattern.search(user_message):
            return {"tool": tool_name, "parameters": {}}

    return None
