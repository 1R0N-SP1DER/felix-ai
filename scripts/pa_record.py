#!/usr/bin/env python3
"""Raw mic audio on stdout (signed 16-bit mono), a drop-in for `parec`.

Termux's parec/parecord fail with "Failed to set media name" because of a broken
locale conversion in the pulse command-line tools. This talks to libpulse-simple
directly, which skips that step.

    python3 pa_record.py [source] [rate]     source: $FAZE_MIC_SOURCE, else the server's default source
"""
import ctypes
import ctypes.util
import os
import subprocess
import sys

PA_SAMPLE_S16LE = 3
PA_STREAM_RECORD = 2


class SampleSpec(ctypes.Structure):
    _fields_ = [("format", ctypes.c_int), ("rate", ctypes.c_uint32), ("channels", ctypes.c_uint8)]


def load_lib():
    for name in ("libpulse-simple.so.0", "libpulse-simple.so", ctypes.util.find_library("pulse-simple")):
        if name:
            try:
                return ctypes.CDLL(name)
            except OSError:
                continue
    return None


def choose_source(listing: str):
    """Pick the real microphone from `pactl list short sources`. The pulse default is often a
    sink *monitor* (what the phone is playing), which records pure silence."""
    names = [line.split("\t")[1] if "\t" in line else (line.split() + ["", ""])[1] for line in listing.splitlines() if line.strip()]
    mics = [n for n in names if n and not n.endswith(".monitor")]
    return "OpenSL_ES_source" if "OpenSL_ES_source" in mics else (mics[0] if mics else None)


def detect_source():
    try:
        out = subprocess.run(["pactl", "list", "short", "sources"], capture_output=True, text=True, timeout=5)
    except (OSError, subprocess.SubprocessError):
        return None
    return choose_source(out.stdout) if out.returncode == 0 else None


def main(argv) -> int:
    source = (argv[1] if len(argv) > 1 else os.environ.get("FAZE_MIC_SOURCE", "")) or None
    if source in (None, "auto"):
        source = detect_source()
    rate = int(argv[2]) if len(argv) > 2 else 16000
    lib = load_lib()
    if lib is None:
        print("pa_record: libpulse-simple not found (pkg install pulseaudio)", file=sys.stderr)
        return 2
    c = ctypes
    lib.pa_simple_new.restype = c.c_void_p
    lib.pa_simple_new.argtypes = [c.c_char_p, c.c_char_p, c.c_int, c.c_char_p, c.c_char_p,
                                  c.POINTER(SampleSpec), c.c_void_p, c.c_void_p, c.POINTER(c.c_int)]
    lib.pa_simple_read.argtypes = [c.c_void_p, c.c_void_p, c.c_size_t, c.POINTER(c.c_int)]
    lib.pa_simple_free.argtypes = [c.c_void_p]
    lib.pa_strerror.restype = c.c_char_p
    spec = SampleSpec(PA_SAMPLE_S16LE, rate, 1)
    err = c.c_int(0)
    stream = lib.pa_simple_new(None, b"faze", PA_STREAM_RECORD, source.encode() if source else None,
                               b"wake", c.byref(spec), None, None, c.byref(err))
    if not stream:
        print("pa_record: " + lib.pa_strerror(err.value).decode(), file=sys.stderr)
        return 1
    buf = c.create_string_buffer(rate // 10 * 2)  # 100 ms
    code = 0
    try:
        while True:
            if lib.pa_simple_read(stream, buf, len(buf), c.byref(err)) < 0:
                print("pa_record: " + lib.pa_strerror(err.value).decode(), file=sys.stderr)
                code = 1
                break
            os.write(1, buf.raw)
    except (BrokenPipeError, KeyboardInterrupt):
        pass
    finally:
        lib.pa_simple_free(stream)
    return code


if __name__ == "__main__":
    sys.exit(main(sys.argv))
