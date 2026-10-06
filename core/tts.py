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


# Speech models sometimes answer with no audio (finishReason OTHER) or a transient 5xx; a second try,
# phrased as a reading instruction, nearly always works. Quota and auth errors are not retried.
DIRECTOR = "Read aloud in a natural, conversational tone:\n\n"
ATTEMPTS = 3


def _audio_from(payload):
    """(pcm, mime) from a generateContent reply, or (None, reason)."""
    try:
        block = (payload.get("promptFeedback") or {}).get("blockReason")
        if block:
            return None, f"blocked by the model ({block})"
        candidates = payload.get("candidates") or []
        if not candidates:
            return None, "the speech model returned no audio (no candidates)"
        for part in (candidates[0].get("content") or {}).get("parts") or []:
            inline = part.get("inlineData") or part.get("inline_data") or {}
            if inline.get("data"):
                return base64.b64decode(inline["data"]), inline.get("mimeType") or inline.get("mime_type") or ""
        return None, f"the speech model returned no audio (finishReason {candidates[0].get('finishReason', 'unknown')})"
    except (AttributeError, TypeError, ValueError):
        return None, "the speech model returned no audio (unreadable data)"


def synthesize(text: str, voice: str, model: str, timeout: float = 60.0, post=None) -> bytes:
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
    post = post or requests.post
    reason = "no attempt made"
    for attempt in range(ATTEMPTS):
        prompt = text if attempt == 0 else DIRECTOR + text
        body = {
            "contents": [{"parts": [{"text": prompt}]}],
            "generationConfig": {
                "responseModalities": ["AUDIO"],
                "speechConfig": {"voiceConfig": {"prebuiltVoiceConfig": {"voiceName": voice.capitalize()}}},
            },
        }
        try:
            response = post(
                f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent",
                headers={"Content-Type": "application/json", "x-goog-api-key": key}, json=body, timeout=timeout)
        except requests.RequestException as e:
            raise TtsError(f"network error ({type(e).__name__})")
        if response.status_code != 200:
            from tool_replies import summarize_error
            reason = summarize_error(_http_error(response))
            if response.status_code in (500, 502, 503, 504):
                continue
            raise TtsError(reason)
        try:
            payload = response.json()
        except ValueError:
            reason = "the speech model's reply was not JSON"
            continue
        pcm, mime = _audio_from(payload)
        if pcm:
            rate_match = re.search(r"rate=(\d+)", mime)
            return wav_from_pcm(pcm, int(rate_match.group(1)) if rate_match else 24000)
        reason = mime
    raise TtsError(reason)


if __name__ == "__main__":
    # python3 tts.py "hello there" [Voice]   -> writes ~/faze_tts_test.wav, or prints exactly why not
    import sys
    from config_loader import load_config
    cfg = load_config()["tts"]
    words = sys.argv[1] if len(sys.argv) > 1 else "Hello, this is a voice test."
    who = sys.argv[2] if len(sys.argv) > 2 else cfg["voice"]
    try:
        wav = synthesize(words, who, cfg["model"])
    except TtsError as e:
        print(f"FAIL ({cfg['model']}, {who}): {e}")
        raise SystemExit(1)
    out = os.path.expanduser("~/faze_tts_test.wav")
    with open(out, "wb") as f:
        f.write(wav)
    print(f"OK: {len(wav)} bytes -> {out} ({cfg['model']}, {who})")
