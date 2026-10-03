"""
felix-ai Core -- tool_replies.py
Deterministic, code-built replies for tool results, so a small model
never has to re-narrate (and mangle) real data. Also turns raw
exceptions into short, user-safe error lines (no response bodies, no
keys).
"""

import re
from datetime import datetime

# Tools whose result IS the final answer text (already well-formed prose
# from Gemini) and tools that only signal the orchestrator.
PASSTHROUGH_TOOLS = {"delegate_to_gemini", "search_web", "get_wifi_status"}
SIGNAL_TOOLS = {"enable_thinking_mode"}


def _battery(raw: str, params: dict) -> str:
    pct = re.search(r"(\d+)\s*%", raw)
    status = re.search(r"status:\s*([A-Za-z_]+)", raw)
    if not pct:
        return raw
    state = (status.group(1).upper() if status else "")
    if state == "CHARGING":
        tail = "charging"
    elif state == "FULL":
        tail = "full"
    elif state in ("DISCHARGING", "NOT_CHARGING"):
        tail = "not charging"
    else:
        tail = state.lower().replace("_", " ") or "status unknown"
    return f"Battery is at {pct.group(1)}%, {tail}."


def _datetime(raw: str, params: dict) -> str:
    try:
        dt = datetime.strptime(raw.strip()[:19], "%Y-%m-%d %H:%M:%S")
    except ValueError:
        return raw
    hour12 = dt.hour % 12 or 12
    ampm = "AM" if dt.hour < 12 else "PM"
    return (f"It's {hour12}:{dt.minute:02d} {ampm} on {dt.strftime('%A')}, "
            f"{dt.strftime('%B')} {dt.day}, {dt.year}.")


def _volume(raw: str, params: dict) -> str:
    level = params.get("level")
    return f"Volume set to {level}." if level is not None else raw


TEMPLATES = {
    "get_battery_status": _battery,
    "get_current_datetime": _datetime,
    "set_volume": _volume,
    "vibrate": lambda raw, params: "Done, phone vibrated.",
    "write_clipboard": lambda raw, params: "Copied to clipboard.",
    "read_clipboard": lambda raw, params: f"Clipboard: {raw}",
}


def format_reply(tool_name: str, raw_result, params: dict = None):
    """Final reply text for a successful tool result, or None if the
    tool is a signal tool with no user-facing reply."""
    params = params or {}
    raw = "" if raw_result is None else str(raw_result).strip()
    if tool_name in SIGNAL_TOOLS:
        return None
    if not raw:
        return f"{tool_name}: no result returned."
    fn = TEMPLATES.get(tool_name)
    if fn:
        return fn(raw, params)
    return raw  # passthrough tools and anything unknown: verbatim


def summarize_error(err) -> str:
    """One short, safe line describing an exception. Extracts HTTP
    status codes instead of echoing response bodies."""
    text = str(err)
    m = re.search(r"(?:returned|status)\s+(\d{3})", text)
    if m:
        code = int(m.group(1))
        names = {
            429: "Gemini quota exceeded (429)",
            400: "bad request (400)",
            401: "Gemini key rejected (401)",
            403: "Gemini access denied (403)",
            404: "Gemini model not found (404)",
        }
        if code in names:
            return names[code]
        if code >= 500:
            return f"Gemini server error ({code})"
        return f"HTTP {code}"
    if "GEMINI_API_KEY" in text:
        return "GEMINI_API_KEY is not set"
    if "disabled" in text.lower():
        return "Gemini is disabled in config"
    first = text.strip().splitlines()[0] if text.strip() else type(err).__name__
    first = re.sub(r"AQ\.[A-Za-z0-9_-]{10,}|AIza[A-Za-z0-9_-]{10,}", "***", first)
    return f"{type(err).__name__}: {first[:100]}"


_NUMS = re.compile(r"\d+")


def numbers_preserved(raw_result: str, narrated: str) -> bool:
    """True if every digit string in the real result appears in the
    narrated text (guards against a small model changing numbers)."""
    return all(n in narrated for n in _NUMS.findall(str(raw_result)))
