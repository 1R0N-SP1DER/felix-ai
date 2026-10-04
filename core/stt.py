"""
felix-ai Core -- stt.py
Offline speech-to-text through whisper.cpp (whisper-cli), so voice input
doesn't depend on Chrome's cloud recognizer or a downloaded Google pack.

The web app records the microphone, converts it to 16 kHz mono 16-bit
WAV in the browser, and POSTs it to /api/transcribe. Nothing here
leaves the phone.
"""

import os
import re
import struct
import subprocess
import tempfile
from pathlib import Path

MAX_WAV_BYTES = 3 * 1024 * 1024          # about 90 seconds of 16 kHz mono
SAMPLE_RATE = 16000


class TranscribeError(Exception):
    pass


def _paths(config: dict):
    cfg = config["stt"]
    return os.path.expanduser(cfg["bin"]), os.path.expanduser(cfg["model_path"])


def available(config: dict) -> bool:
    binary, model = _paths(config)
    return os.path.isfile(binary) and os.access(binary, os.X_OK) and os.path.isfile(model)


def missing_reason(config: dict) -> str:
    binary, model = _paths(config)
    if not os.path.isfile(binary):
        return f"whisper-cli not found at {binary}"
    if not os.path.isfile(model):
        return f"whisper model not found at {model}"
    return ""


def check_wav(data: bytes) -> None:
    """Accept only 16 kHz mono 16-bit PCM WAV, which is what the app sends."""
    if len(data) < 44 or data[:4] != b"RIFF" or data[8:12] != b"WAVE":
        raise TranscribeError("audio must be a WAV file")
    if len(data) > MAX_WAV_BYTES:
        raise TranscribeError("recording too long")
    fmt = data.find(b"fmt ")
    if fmt < 0 or fmt + 24 > len(data):
        raise TranscribeError("WAV header is incomplete")
    audio_format, channels, rate = struct.unpack("<HHI", data[fmt + 8:fmt + 16])
    bits = struct.unpack("<H", data[fmt + 22:fmt + 24])[0]
    if (audio_format, channels, rate, bits) != (1, 1, SAMPLE_RATE, 16):
        raise TranscribeError("audio must be 16 kHz mono 16-bit PCM")


_NOISE = re.compile(r"\[(?:BLANK_AUDIO|MUSIC|NOISE|SILENCE)\]|\((?:silence|music|noise)\)", re.IGNORECASE)


def clean_text(raw: str) -> str:
    text = _NOISE.sub(" ", raw)
    return re.sub(r"\s+", " ", text).strip()


def _tmp_dir() -> Path:
    base = Path(os.environ.get("FAZE_TMP") or (Path.home() / ".felix" / "tmp"))
    base.mkdir(parents=True, exist_ok=True)
    return base


def transcribe_wav(data: bytes, config: dict) -> str:
    check_wav(data)
    reason = missing_reason(config)
    if reason:
        raise TranscribeError(reason)
    binary, model = _paths(config)
    cfg = config["stt"]
    with tempfile.TemporaryDirectory(dir=_tmp_dir()) as tmp:
        wav = os.path.join(tmp, "clip.wav")
        with open(wav, "wb") as f:
            f.write(data)
        cmd = [binary, "-m", model, "-f", wav, "-nt", "-np",
               "-t", str(cfg["threads"]), "-l", str(cfg["language"])]
        try:
            run = subprocess.run(cmd, capture_output=True, text=True, timeout=float(cfg["timeout"]))
        except subprocess.TimeoutExpired:
            raise TranscribeError(f"transcription took longer than {cfg['timeout']}s")
        except OSError as e:
            raise TranscribeError(f"could not run whisper-cli ({e.strerror or e})")
    if run.returncode != 0:
        tail = (run.stderr or "").strip().splitlines()[-1:] or ["no error text"]
        raise TranscribeError(f"whisper-cli failed (code {run.returncode}): {tail[0][:120]}")
    return clean_text(run.stdout)
