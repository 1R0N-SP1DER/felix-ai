"""
felix-ai Core -- device.py
See, hear and operate the phone (root): read the screen, look at it, tap, swipe, type, press keys, listen.

Reading uses uiautomator, which gives every labelled element with its exact position, so taps do not depend on
guessing from a picture. screen_look sends a screenshot to Gemini for what the accessibility tree cannot show
(a camera preview, a game, an unlabelled button). While a task runs, Android's own "show touches" setting draws
a circle wherever a tap lands, which is the live cursor.

Everything here is gated by the orchestrator: device.control must be on or ask, higher-level tools must be
allowed, and each request gets a step budget.
"""

import os
import re
import shlex
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

import tool_helpers

CONTROL_MODES = ("off", "ask", "on")
SETTINGS = {"control": "off", "max_steps": 30, "cursor": True, "mic_cmd": ""}
MIC_TAP = {"fn": None}  # set by the web server: seconds -> PCM from the wake-word stream, or None
STATE = {"steps": 0, "cursor_on": False, "cursor_prev": "0", "size": None}
KEYS = {"home": 3, "back": 4, "enter": 66, "recent": 187, "menu": 82, "search": 84, "tab": 61, "delete": 67, "camera": 27,
        "volume_up": 24, "volume_down": 25, "up": 19, "down": 20, "left": 21, "right": 22, "escape": 111, "space": 62}
MAX_LISTEN_SECONDS = 15
MAX_ELEMENTS = 60


class DeviceError(Exception):
    """Something on the phone could not be done; the message is for the model and the user."""


def configure(config: dict) -> None:
    d = config.get("device") or {}
    SETTINGS["control"] = d.get("control", "off") if d.get("control", "off") in CONTROL_MODES else "off"
    try:
        SETTINGS["max_steps"] = max(5, min(100, int(d.get("max_steps", 30))))
    except (TypeError, ValueError):
        SETTINGS["max_steps"] = 30
    SETTINGS["cursor"] = bool(d.get("cursor", True))
    SETTINGS["mic_cmd"] = (config.get("wake") or {}).get("mic_cmd", "")


def begin_turn() -> None:
    STATE["steps"] = 0


def count_step() -> None:
    STATE["steps"] += 1
    if STATE["steps"] > SETTINGS["max_steps"]:
        raise DeviceError(f"step limit reached ({SETTINGS['max_steps']} actions for one request). Stop and tell the user where you got to.")


def _root(cmd, timeout: float = 15.0, cap: int = tool_helpers.OUTPUT_CAP) -> str:
    try:
        return tool_helpers.run(cmd, root=True, timeout=timeout, cap=cap)
    except RuntimeError as e:
        raise DeviceError(str(e))


# ---------------------------------------------------------------- the live cursor

def cursor(on: bool) -> None:
    """Show Android's touch circles while Faze is driving; put the setting back afterwards. Cosmetic: never fails a task."""
    if not SETTINGS["cursor"]:
        return
    try:
        if on and not STATE["cursor_on"]:
            prev = _root(["settings", "get", "system", "show_touches"], 8).strip()
            STATE["cursor_prev"] = prev if prev in ("0", "1") else "0"
            _root(["settings", "put", "system", "show_touches", "1"], 8)
            STATE["cursor_on"] = True
        elif not on and STATE["cursor_on"]:
            _root(["settings", "put", "system", "show_touches", STATE["cursor_prev"]], 8)
            STATE["cursor_on"] = False
    except DeviceError:
        pass


# ---------------------------------------------------------------- reading the screen

def screen_size() -> tuple:
    if STATE["size"]:
        return STATE["size"]
    out = _root(["wm", "size"], 8)
    sizes = re.findall(r"(\d{3,5})x(\d{3,5})", out)
    if not sizes:
        raise DeviceError("could not read the screen size")
    STATE["size"] = (int(sizes[-1][0]), int(sizes[-1][1]))  # the override size, if there is one, is listed last
    return STATE["size"]


def parse_nodes(xml_text: str) -> list:
    start = xml_text.find("<hierarchy")
    end = xml_text.rfind("</hierarchy>")
    if start < 0 or end < 0:
        raise DeviceError("the screen dump was empty (is the screen off or locked?)")
    try:
        root = ET.fromstring(xml_text[start:end + len("</hierarchy>")])
    except ET.ParseError:
        raise DeviceError("could not read the screen dump")
    nodes = []
    for el in root.iter("node"):
        m = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", el.get("bounds", ""))
        if not m:
            continue
        l, t, r, b = (int(v) for v in m.groups())
        if r <= l or b <= t:
            continue
        label = (el.get("text") or "").strip() or (el.get("content-desc") or "").strip()
        clickable = el.get("clickable") == "true" or el.get("long-clickable") == "true"
        if not label and not clickable:
            continue
        nodes.append({"label": label, "id": (el.get("resource-id") or "").split("/")[-1], "kind": (el.get("class") or "").split(".")[-1],
                      "clickable": clickable, "x": (l + r) // 2, "y": (t + b) // 2, "package": el.get("package", "")})
    return nodes


_DUMP_HINTS = (
    ("idle state", "the screen never settles (a camera preview, video or game keeps animating), so it cannot be read"),
    ("null root node", "no readable window (another accessibility service may be holding the reader, or the window is secure)"),
    ("locked", "the screen is locked"),
)


def _dump_file(path: str) -> str:
    """The dump file's XML if uiautomator wrote one, whatever it did or did not print."""
    try:
        xml = _root(["cat", path], 10, cap=400000)
    except DeviceError:
        return ""
    return xml if "<hierarchy" in xml else ""


def read_nodes() -> list:
    """The screen's elements via uiautomator. It normally prints a success line and writes a file, but on some phones
    it exits 0 having printed nothing, so a dump file that exists counts as success, and as a last resort the dump is
    streamed on stdout. When everything fails, what each try said is in the error."""
    path = "/data/local/tmp/faze_ui.xml"
    said = []
    for flags in ([], ["--compressed"]):
        try:
            _root(["rm", "-f", path], 5)
            out = _root(["uiautomator", "dump", *flags, path], 15)
        except DeviceError as e:
            said.append(str(e))
            continue
        xml = _dump_file(path)
        if xml:
            return parse_nodes(xml)
        said.append(out or "no output, no file")
    streamed = ""
    try:
        out = _root(["uiautomator", "dump", "/dev/stdout"], 15, cap=400000)
        m = re.search(r"<\?xml.*?</hierarchy>", out, re.DOTALL)
        if m:
            return parse_nodes(m.group(0))
        streamed = "stdout fallback: " + (out[:60] or "empty")
    except DeviceError as e:
        streamed = "stdout fallback: " + str(e)[:80]
    first = next((x for x in said if x), "")
    why = next((hint for key, hint in _DUMP_HINTS if key in " ".join(said).lower()), "")
    raise DeviceError(f"uiautomator could not read the screen: {first.strip()[:160] or 'no output'}"
                      + (f" ({why})" if why else "") + f" [{len(said) + 1} tries; {streamed}]. Use screen_look instead.")


def screen_read() -> str:
    nodes = read_nodes()
    w, h = screen_size()
    package = next((n["package"] for n in nodes if n["package"]), "unknown")
    lines = [f"Screen {w}x{h}, app {package}"]
    for i, n in enumerate(nodes[:MAX_ELEMENTS], 1):
        name = n["label"] or f"(unlabelled {n['id'] or n['kind']})"
        lines.append(f"{i}. {name} [{n['kind'] or 'view'}{', tappable' if n['clickable'] else ''}] at {n['x']},{n['y']}")
    if len(nodes) > MAX_ELEMENTS:
        lines.append(f"...and {len(nodes) - MAX_ELEMENTS} more elements")
    if not nodes:
        lines.append("No readable elements. The app may draw its own screen; use screen_look.")
    return "\n".join(lines)


def screen_look(question: str) -> str:
    from gemini_client import GeminiError, ask_media
    w, h = screen_size()
    try:
        png = tool_helpers.run_bytes(["screencap", "-p"], root=True, timeout=20)
    except RuntimeError as e:
        raise DeviceError(str(e))
    if not png.startswith(b"\x89PNG"):
        raise DeviceError("the screenshot came back empty (screen off or locked?)")
    prompt = (f"This is a screenshot of an Android phone, {w}x{h} pixels. {question}\n"
              "Be brief. If you point at something, give its centre as pixel coordinates x,y on this screen.")
    try:
        return ask_media(prompt, png, "image/png")
    except GeminiError as e:
        raise DeviceError(f"could not ask Gemini about the screenshot: {e}")


# ---------------------------------------------------------------- acting

def _inside(x: int, y: int) -> None:
    w, h = screen_size()
    if not (0 <= x < w and 0 <= y < h):
        raise DeviceError(f"({x},{y}) is off the screen ({w}x{h})")


def tap(x, y) -> str:
    x, y = int(x), int(y)
    _inside(x, y)
    _root(["input", "tap", str(x), str(y)], 8)
    return f"Tapped {x},{y}"


def tap_text(label: str) -> str:
    label = str(label or "").strip()
    if not label:
        raise DeviceError("tap_text needs a label")
    nodes = read_nodes()
    want = label.casefold()
    exact = [n for n in nodes if n["label"].casefold() == want]
    partial = [n for n in nodes if want in n["label"].casefold()]
    pool = exact or partial
    if not pool:
        seen = ", ".join(sorted({n["label"] for n in nodes if n["label"]})[:12])
        raise DeviceError(f"nothing on screen is labelled '{label}'. Labels seen: {seen or 'none'}")
    best = sorted(pool, key=lambda n: (not n["clickable"], len(n["label"])))[0]
    _root(["input", "tap", str(best["x"]), str(best["y"])], 8)
    return f"Tapped '{best['label']}' at {best['x']},{best['y']}"


def swipe(x1, y1, x2, y2) -> str:
    x1, y1, x2, y2 = (int(v) for v in (x1, y1, x2, y2))
    _inside(x1, y1)
    _inside(x2, y2)
    _root(["input", "swipe", str(x1), str(y1), str(x2), str(y2), "300"], 8)
    return f"Swiped from {x1},{y1} to {x2},{y2}"


def type_text(content: str) -> str:
    content = str(content or "")
    if not content or len(content) > 200:
        raise DeviceError("type_text needs 1 to 200 characters")
    if not content.isascii():
        raise DeviceError("typing supports plain ASCII only")
    _root(["input", "text", content.replace(" ", "%s")], 8)
    return f"Typed {len(content)} characters"


def press_key(key: str) -> str:
    name = str(key or "").strip().lower().replace(" ", "_")
    if name not in KEYS:
        raise DeviceError(f"unknown key '{key}'. Keys: {', '.join(sorted(KEYS))}")
    _root(["input", "keyevent", str(KEYS[name])], 8)
    return f"Pressed {name}"


def wait(seconds) -> str:
    n = max(1, min(10, int(seconds)))
    time.sleep(n)
    return f"Waited {n}s"


# ---------------------------------------------------------------- hearing

def record_pcm(seconds: int) -> bytes:
    """Raw 16 kHz mono 16-bit audio. The wake-word stream is used when it is running (it already owns the
    microphone source); otherwise the mic command is started for the duration."""
    tap = MIC_TAP["fn"]
    if tap:
        pcm = tap(seconds)
        if pcm:
            return pcm
    cmd = shlex.split(SETTINGS["mic_cmd"])
    if not cmd:
        raise DeviceError("no mic command is configured (wake.mic_cmd)")
    need = seconds * 32000
    try:
        proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    except OSError as e:
        raise DeviceError(f"could not start the mic ({e.strerror or e})")
    data = b""
    try:
        deadline = time.monotonic() + seconds + 6
        while len(data) < need and time.monotonic() < deadline:
            chunk = proc.stdout.read(min(4096, need - len(data)))
            if not chunk:
                break
            data += chunk
    finally:
        try:
            proc.terminate()
            proc.wait(timeout=3)
        except (OSError, subprocess.TimeoutExpired):
            proc.kill()
        proc.stdout.close()
    if len(data) < 3200:
        said = ""
        try:
            said = (proc.stderr.read() or b"").decode("utf-8", "replace").strip().splitlines()[-1][:120]
        except (OSError, IndexError, ValueError):
            pass
        raise DeviceError("the microphone gave no audio" + (f" ({said})" if said else ""))
    return data


def record_termux_api(seconds: int) -> tuple:
    """Android's own recorder through Termux:API (termux-microphone-record), which runs as its own foreground
    service and is not silenced the way a background pulse stream can be. Returns (bytes, mime)."""
    import tempfile
    path = os.path.join(tempfile.gettempdir(), f"faze_listen_{os.getpid()}.m4a")
    try:
        os.remove(path)
    except OSError:
        pass
    try:
        tool_helpers.run(["termux-microphone-record", "-f", path, "-l", str(seconds), "-e", "aac", "-r", "16000", "-c", "1"], timeout=10)
        time.sleep(seconds + 1.5)
        try:
            tool_helpers.run(["termux-microphone-record", "-q"], timeout=5)
        except RuntimeError:
            pass
        with open(path, "rb") as f:
            data = f.read()
    except RuntimeError as e:
        raise DeviceError(f"Termux:API recording failed ({e})")
    except OSError:
        raise DeviceError("Termux:API recorded nothing (is the Termux:API app installed with microphone permission?)")
    finally:
        try:
            os.remove(path)
        except OSError:
            pass
    if len(data) < 2000:
        raise DeviceError("Termux:API recorded nothing (microphone permission for Termux:API?)")
    if data[4:8] == b"ftyp":  # an mp4 container: Gemini wants a plain wav, ffmpeg can make one
        try:
            wav = subprocess.run(["ffmpeg", "-loglevel", "error", "-i", "pipe:0", "-ar", "16000", "-ac", "1", "-f", "wav", "pipe:1"],
                                 input=data, capture_output=True, timeout=30)
            if wav.returncode == 0 and wav.stdout[:4] == b"RIFF":
                return wav.stdout, "audio/wav"
        except (OSError, subprocess.TimeoutExpired):
            pass
        return data, "audio/mp4"
    return data, "audio/aac"


def listen(seconds) -> str:
    from gemini_client import GeminiError, ask_media
    from tts import wav_from_pcm
    n = max(1, min(MAX_LISTEN_SECONDS, int(seconds)))
    audio, mime, problems = None, None, []
    try:
        pcm = record_pcm(n)
        if any(pcm):
            audio, mime = wav_from_pcm(pcm, 16000), "audio/wav"
        else:
            problems.append("the pulse microphone captured only silence")
    except DeviceError as e:
        problems.append(str(e))
    if audio is None:
        try:
            audio, mime = record_termux_api(n)
        except DeviceError as e:
            problems.append(str(e))
    if audio is None:
        raise DeviceError("could not hear anything: " + "; ".join(problems) + ". Check the mic with python3 core/wake.py --check")
    try:
        heard = ask_media("Transcribe any speech in this audio word for word. If there is no speech, describe the main sounds in a few words.",
                          audio, mime)
    except GeminiError as e:
        raise DeviceError(f"could not ask Gemini about the audio: {e}")
    return f"Heard over {n}s: {heard or '(nothing clear)'}"
