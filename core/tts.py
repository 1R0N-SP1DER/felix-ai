"""
felix-ai Core -- tts.py
Natural-sounding online voices through the Gemini API's speech models.
Optional: the app falls back to the device's own voice if this fails or
you're offline. The text of the reply is sent to Google; nothing else.
"""

import base64
import os
import re
import struct

import requests

from gemini_client import GeminiError, _http_error

# (name, style) -- the voices Gemini's speech models ship with.
VOICES = [
    ("Zephyr", "Bright"), ("Puck", "Upbeat"), ("Charon", "Informative"), ("Kore", "Firm"),
    ("Fenrir", "Excitable"), ("Leda", "Youthful"), ("Orus", "Firm"), ("Aoede", "Breezy"),
    ("Callirrhoe", "Easy-going"), ("Autonoe", "Bright"), ("Enceladus", "Breathy"), ("Iapetus", "Clear"),
    ("Umbriel", "Easy-going"), ("Algieba", "Smooth"), ("Despina", "Smooth"), ("Erinome", "Clear"),
    ("Algenib", "Gravelly"), ("Rasalgethi", "Informative"), ("Laomedeia", "Upbeat"), ("Achernar", "Soft"),
    ("Alnilam", "Firm"), ("Schedar", "Even"), ("Gacrux", "Mature"), ("Pulcherrima", "Forward"),
    ("Achird", "Friendly"), ("Zubenelgenubi", "Casual"), ("Vindemiatrix", "Gentle"), ("Sadachbia", "Lively"),
    ("Sadaltager", "Knowledgeable"), ("Sulafat", "Warm"),
]
VOICE_NAMES = tuple(name.lower() for name, _ in VOICES)
MAX_TEXT = 800


class TtsError(Exception):
    pass


def wav_from_pcm(pcm: bytes, rate: int) -> bytes:
    return (b"RIFF" + struct.pack("<I", 36 + len(pcm)) + b"WAVEfmt "
            + struct.pack("<IHHIIHH", 16, 1, 1, rate, rate * 2, 2, 16)
            + b"data" + struct.pack("<I", len(pcm)) + pcm)


def synthesize(text: str, voice: str, model: str, timeout: float = 60.0) -> bytes:
    """Speak `text` and return a WAV file. Raises TtsError with a short reason."""
    text = (text or "").strip()
    if not text:
        raise TtsError("nothing to say")
    if len(text) > MAX_TEXT:
        raise TtsError(f"text over {MAX_TEXT} characters")
    if voice.lower() not in VOICE_NAMES:
        raise TtsError(f"unknown voice '{voice}'")
    key = os.environ.get("GEMINI_API_KEY")
    if not key:
        raise TtsError("GEMINI_API_KEY is not set")
    body = {
        "contents": [{"parts": [{"text": text}]}],
        "generationConfig": {
            "responseModalities": ["AUDIO"],
            "speechConfig": {"voiceConfig": {"prebuiltVoiceConfig": {"voiceName": voice.capitalize()}}},
        },
    }
    try:
        response = requests.post(
            f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent",
            headers={"Content-Type": "application/json", "x-goog-api-key": key}, json=body, timeout=timeout)
    except requests.RequestException as e:
        raise TtsError(f"network error ({type(e).__name__})")
    if response.status_code != 200:
        from tool_replies import summarize_error
        raise TtsError(summarize_error(_http_error(response)))
    try:
        part = response.json()["candidates"][0]["content"]["parts"][0]["inlineData"]
        pcm = base64.b64decode(part["data"])
    except (KeyError, IndexError, TypeError, ValueError):
        raise TtsError("the speech model returned no audio")
    rate_match = re.search(r"rate=(\d+)", part.get("mimeType", ""))
    return wav_from_pcm(pcm, int(rate_match.group(1)) if rate_match else 24000)
