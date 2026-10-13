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


_NOT_TODAY = re.compile(r"\b(tomorrow|yesterday|next|last|week|weekend|month|forecast for|monday|tuesday|wednesday|thursday|friday|"
                        r"saturday|sunday|why|climate|average|usually|history|app|widget|change|and|then|also|after)\b", re.IGNORECASE)
_CITY = re.compile(r"\b(?:in|for|at)\s+(?!today\b|tonight\b|the\b|my\b|now\b|me\b|this\b)([A-Za-z][A-Za-z .'-]{1,40}?)"
                   r"(?=\s*(?:today|tonight|right now|now|currently|outside|this\b|\?|!|\.|,|$))", re.IGNORECASE)


def weather_route(message: str):
    """"what's the weather (like) (in Lagos) (today)": today's weather only; anything about other days, history or
    the app itself goes to the model. An empty city means the weather.location setting."""
    text = message.strip()
    if len(text) > 90 or not re.search(r"\bweather\b", text, re.IGNORECASE) or _NOT_TODAY.search(text):
        return None
    city = _CITY.search(text)
    return {"tool": "get_weather", "parameters": {"city": city.group(1).strip() if city else ""}}


def try_direct_route(user_message: str):
    weather = weather_route(user_message)
    if weather:
        return weather

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
