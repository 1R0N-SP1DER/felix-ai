"""
felix-ai Core -- identity.py
Last line of defence against a small model claiming to be the user.
"""

import re


def fix_identity(text: str, assistant: str, user: str) -> str:
    """"I'm Felix" / "my name is Felix" -> "I'm Faze". Possessives
    ("Felix's phone") and "I'm not Felix" are left alone."""
    if not text or assistant == user:
        return text
    pattern = re.compile(
        rf"\b(I'm|I\u2019m|I am|Im|my name is|call me)(\s+){re.escape(user)}\b(?!['\u2019]s\b)",
        re.IGNORECASE)
    return pattern.sub(lambda m: f"{m.group(1)}{m.group(2)}{assistant}", text)
