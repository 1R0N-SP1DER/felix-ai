"""
felix-ai Core -- intents.py
Explicit user commands matched in code before any model runs, so a
small model can't ignore or paraphrase them. The captured text is
passed on exactly as typed.
"""

import re

# Leading chatter that doesn't change what the user is asking for.
_FILLER = re.compile(
    r"^(?:(?:okay|ok|so|yo+|hey|bro|dude|bruh|aight|bet|well|alright|please|pls|plz|"
    r"just|now|uh|um|hmm|and|then|go|can you|could you|would you|will you)[\s,!.:]+)+",
    re.IGNORECASE,
)
_ASK = re.compile(r"^ask gemini[\s,:]+(.+)$", re.IGNORECASE | re.DOTALL)
_SEARCH = re.compile(
    r"^(?:search(?: the web| the internet| online| google)?(?: for)?|google|look up|lookup)"
    r"[\s,:]+(.+)$",
    re.IGNORECASE | re.DOTALL,
)


def match_intent(user_message):
    text = _FILLER.sub("", user_message.strip(), count=1)

    m = _ASK.match(text)
    if m:
        return {"tool": "delegate_to_gemini", "parameters": {"request": m.group(1).strip()}}

    m = _SEARCH.match(text)
    if m:
        return {"tool": "search_web", "parameters": {"query": m.group(1).strip()}}

    return None
