"""
felix-ai Core -- needle_router.py
Layer 2 of the tool-routing pipeline: sends a message to a locally
running Needle server (see start.sh) and returns a validated
tool call if -- and only if -- Needle's confidence clears the
threshold. Below threshold, or no tool call at all, returns None so
the caller falls through (currently: LFM2.5; Gemini once wired in).
"""

import requests

NEEDLE_URL = "http://127.0.0.1:8765/complete"
CONFIDENCE_THRESHOLD = 0.5


def ask_needle(user_message: str):
    """
    Returns {"tool": name, "parameters": {...}} if Needle is confident
    about a single tool call, otherwise None.
    """
    try:
        response = requests.post(
            NEEDLE_URL,
            json={"input": user_message},
            timeout=10,
        )
        response.raise_for_status()
        data = response.json()
    except (requests.RequestException, ValueError):
        return None

    if data.get("type") != "call":
        return None
    if not data.get("success"):
        return None

    confidence = data.get("confidence", 0.0)
    if confidence < CONFIDENCE_THRESHOLD:
        return None

    calls = data.get("function_calls", [])
    if len(calls) != 1:
        return None

    call = calls[0]
    return {"tool": call["name"], "parameters": call.get("arguments", {})}


if __name__ == "__main__":
    tests = [
        "vibrate my phone",
        "whats my battery",
        "hey whats up",
        "tell me about ben 10",
        "set volume to 8",
    ]
    for t in tests:
        print(t, "->", ask_needle(t))
