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


def package_audio(audio: bytes, mime: str):
    """(bytes, content type) the browser can play. Raw PCM gets a WAV header; audio that already
    is a file (wav, mp3, ogg...) passes through untouched; wrapping that in a second header is what
    makes a browser say "no supported source"."""
    head, kind = audio[:12], (mime or "").lower()
    if head[:4] == b"RIFF" and head[8:12] == b"WAVE":
        return audio, "audio/wav"
    if head[:3] == b"ID3" or head[:2] in (b"\xff\xfb", b"\xff\xf3", b"\xff\xf2"):
        return audio, "audio/mpeg"
    if head[:4] == b"OggS":
        return audio, "audio/ogg"
    if head[:4] == b"fLaC":
        return audio, "audio/flac"
    if head[4:8] == b"ftyp":
        return audio, "audio/mp4"
    if kind.startswith("audio/") and not any(k in kind for k in ("l16", "pcm", "raw")) and kind not in ("audio/", "audio/x-raw"):
        return audio, kind.split(";")[0]
    rate_match = re.search(r"rate=(\d+)", kind)
    return wav_from_pcm(audio, int(rate_match.group(1)) if rate_match else 24000), "audio/wav"


def synthesize(text: str, voice: str, model: str, timeout: float = 60.0, post=None) -> bytes:
    """Speak `text`; returns playable audio bytes (a WAV for PCM replies). Raises TtsError with a short reason."""
    return synthesize_audio(text, voice, model, timeout, post)[0]


def synthesize_audio(text: str, voice: str, model: str, timeout: float = 60.0, post=None):
    """Like synthesize, but returns (bytes, content type)."""
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
            return package_audio(pcm, mime)
        reason = mime
    raise TtsError(reason)


if __name__ == "__main__":
    import env_loader
    env_loader.load_env()
    import netfix
    from config_loader import load_config as _load_config
    netfix.install(_load_config())
    # python3 tts.py "hello there" [Voice]   -> writes ~/faze_tts_test.wav, or prints exactly why not
    import sys
    from config_loader import load_config
    cfg = load_config()["tts"]
    words = sys.argv[1] if len(sys.argv) > 1 else "Hello, this is a voice test."
    who = sys.argv[2] if len(sys.argv) > 2 else cfg["voice"]
    try:
        audio, kind = synthesize_audio(words, who, cfg["model"])
    except TtsError as e:
        print(f"FAIL ({cfg['model']}, {who}): {e}")
        raise SystemExit(1)
    out = os.path.expanduser("~/faze_tts_test." + {"audio/mpeg": "mp3", "audio/ogg": "ogg", "audio/flac": "flac", "audio/mp4": "m4a"}.get(kind, "wav"))
    with open(out, "wb") as f:
        f.write(audio)
    print(f"OK: {len(audio)} bytes, {kind}, starts {audio[:12].hex()} -> {out} ({cfg['model']}, {who})")
