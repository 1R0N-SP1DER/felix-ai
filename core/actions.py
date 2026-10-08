"""
felix-ai Core -- actions.py
Things Faze can do on the device: open a link, launch an app, play a YouTube video.

On Android (Termux) this goes through `am start`; if Android refuses a background launch and
actions.root allows it, the same command is retried through `su` (rooted phones skip that
restriction). On a PC it falls back to xdg-open. Nothing here is shell-injectable: every command
is an argument list, and URLs must be http(s).
"""

import json
import os
import re
import shlex
import shutil
import subprocess
import urllib.parse

import requests

ROOT_MODES = ("auto", "always", "never")
SETTINGS = {"root": "auto"}
UA = ("Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) "
      "Chrome/124.0 Mobile Safari/537.36")

APP_PACKAGES = {
    "youtube": "com.google.android.youtube", "chrome": "com.android.chrome", "spotify": "com.spotify.music",
    "whatsapp": "com.whatsapp", "telegram": "org.telegram.messenger", "maps": "com.google.android.apps.maps",
    "google maps": "com.google.android.apps.maps", "gmail": "com.google.android.gm", "settings": "com.android.settings",
    "play store": "com.android.vending", "discord": "com.discord", "instagram": "com.instagram.android",
    "netflix": "com.netflix.mediaclient", "termux": "com.termux", "photos": "com.google.android.apps.photos",
    "calendar": "com.google.android.calendar", "clock": "com.google.android.deskclock", "twitter": "com.twitter.android",
    "x": "com.twitter.android", "reddit": "com.reddit.frontpage", "tiktok": "com.zhiliaoapp.musically",
    "snapchat": "com.snapchat.android", "twitch": "tv.twitch.android.app", "firefox": "org.mozilla.firefox",
    "camera": "com.android.camera", "files": "com.google.android.documentsui", "messages": "com.google.android.apps.messaging",
    "phone": "com.google.android.dialer", "contacts": "com.google.android.contacts", "drive": "com.google.android.apps.docs",
}


class ActionError(Exception):
    """An action could not be carried out; the message is safe to show the user."""


def configure(config: dict) -> None:
    mode = (config.get("actions") or {}).get("root", "auto")
    SETTINGS["root"] = mode if mode in ROOT_MODES else "auto"


def system_env() -> dict:
    """The environment without Termux's preload and library paths. Android's own binaries (am, pm, su,
    app_process) can die in the linker with a "CFI ... control flow integrity" error when those are set."""
    return {k: v for k, v in os.environ.items() if k not in ("LD_PRELOAD", "LD_LIBRARY_PATH")}


def _linker_crash(proc) -> bool:
    return bool(re.search(r"\bCFI:|linker64|CANNOT LINK", (proc.stdout or "") + (proc.stderr or "")))


def _exec(cmd: list, timeout: float, env=None):
    try:
        return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, env=env)
    except subprocess.TimeoutExpired:
        raise ActionError(f"{cmd[0]} timed out")
    except OSError as e:
        raise ActionError(f"could not run {cmd[0]}: {e.strerror or e}")


def _run(cmd: list, timeout: float = 15.0):
    proc = _exec(cmd, timeout)
    if _linker_crash(proc):
        proc = _exec(cmd, timeout, system_env())  # same command, cleaner environment
    return proc


def _am_failed(proc) -> str:
    """`am` often exits 0 on failure; the text is the truth."""
    text = ((proc.stdout or "") + (proc.stderr or "")).strip()
    if proc.returncode != 0 or re.search(r"\bError\b|Exception|not found|unable to", text, re.IGNORECASE):
        return text.splitlines()[-1][:200] if text else f"exit code {proc.returncode}"
    return ""


def _android_start(args: list) -> str:
    """Run `am start <args>`; retry through su when allowed. Returns a note ('' = plain, 'via root')."""
    mode = SETTINGS["root"]
    problem = ""
    if mode != "always":
        problem = _am_failed(_run(["am", "start"] + args))
        if not problem:
            return ""
        if mode == "never":
            raise ActionError(f"Android refused to start it ({problem}); allow root with /settings set actions.root auto")
    if not shutil.which("su"):
        raise ActionError(f"Android refused to start it ({problem or 'no su available'})")
    again = _am_failed(_run(["su", "-c", "unset LD_PRELOAD LD_LIBRARY_PATH; am start " + " ".join(shlex.quote(a) for a in args)]))
    if again:
        raise ActionError(f"could not start it, even as root ({again})")
    return "via root"


def _is_android() -> bool:
    return bool(shutil.which("am"))


def open_url(url: str) -> str:
    url = str(url or "").strip()
    parts = urllib.parse.urlparse(url)
    if parts.scheme not in ("http", "https") or not parts.netloc or len(url) > 2000 or any(c in url for c in "\r\n\x00"):
        raise ActionError("open_url needs a full http(s) link")
    if _is_android():
        note = _android_start(["-a", "android.intent.action.VIEW", "-d", url])
        return f"Opened {parts.netloc}" + (f" ({note})" if note else "")
    opener = shutil.which("xdg-open") or shutil.which("open")
    if not opener:
        raise ActionError("no way to open links here (no am or xdg-open)")
    subprocess.Popen([opener, url], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
    return f"Opened {parts.netloc}"


def _find_package(name: str) -> str:
    key = re.sub(r"\s+(app|application)$", "", name.strip().lower())
    if key in APP_PACKAGES:
        return APP_PACKAGES[key]
    if re.fullmatch(r"[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+", key):
        return key
    try:
        listing = _run(["pm", "list", "packages"], 10).stdout
    except ActionError:
        return ""
    squashed = re.sub(r"[^a-z0-9]", "", key)
    hits = [p.split(":", 1)[1] for p in listing.split() if p.startswith("package:") and squashed and squashed in p.lower()]
    return sorted(hits, key=len)[0] if hits else ""


def open_app(app: str) -> str:
    app = str(app or "").strip()
    if not app or len(app) > 60:
        raise ActionError("open_app needs an app name")
    if _is_android():
        package = _find_package(app)
        if not package:
            raise ActionError(f"I don't know an app called '{app}'")
        note = _android_start(["-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", "-p", package])
        return f"Opened {app}" + (f" ({note})" if note else "")
    binary = shutil.which(app.lower().replace(" ", "-"))
    if not binary:
        raise ActionError(f"I can't find '{app}' on this computer")
    subprocess.Popen([binary], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
    return f"Opened {app}"


# ---------------------------------------------------------------- YouTube

_VIDEO_ID = re.compile(r'"videoId":"([\w-]{11})"')


def _first_video(html: str):
    """(id, title) of the first video in a YouTube page's embedded data."""
    m = _VIDEO_ID.search(html)
    if not m:
        return None
    vid = m.group(1)
    t = re.search(r'"videoId":"' + vid + r'".{0,1500}?"title":\{"runs":\[\{"text":"((?:[^"\\]|\\.)*)"', html, re.DOTALL)
    try:
        title = json.loads('"' + t.group(1) + '"') if t else ""
    except ValueError:
        title = t.group(1) if t else ""
    return vid, title


def _fetch(url: str) -> str:
    try:
        r = requests.get(url, headers={"User-Agent": UA, "Accept-Language": "en-US,en;q=0.9", "Cookie": "CONSENT=YES+1; SOCS=CAI"}, timeout=12)
    except requests.RequestException as e:
        raise ActionError(f"could not reach YouTube ({type(e).__name__})")
    if r.status_code != 200:
        raise ActionError(f"YouTube answered {r.status_code}")
    return r.text


def youtube_find(query: str, latest: bool = False):
    """(video id, title) for a search; with latest, a bare channel name tries that channel's newest upload first."""
    query = query.strip()
    if latest and re.fullmatch(r"@?[A-Za-z0-9._-]{2,40}", query):
        try:
            found = _first_video(_fetch(f"https://www.youtube.com/@{query.lstrip('@')}/videos"))
            if found:
                return found
        except ActionError:
            pass
    sort = "&sp=CAI%253D" if latest else ""
    return _first_video(_fetch("https://www.youtube.com/results?search_query=" + urllib.parse.quote_plus(query) + sort))


def youtube_play(query: str, latest: bool = False) -> str:
    query = str(query or "").strip()
    if not query or len(query) > 120:
        raise ActionError("youtube_play needs something to search for")
    words = query.split()
    fresh = [w for w in words if w.lower() in ("latest", "newest", "recent", "new", "last")]
    if fresh:  # "mrwhosetheboss latest video": sort by upload date, drop the filler words
        latest = True
        rest = [w for w in words if w.lower() not in ("latest", "newest", "recent", "new", "last", "video", "videos", "upload", "uploads", "the", "a", "of")]
        query = " ".join(rest) or query
    found = None
    try:
        found = youtube_find(query, bool(latest))
    except ActionError:
        pass
    if found:
        vid, title = found
        open_url(f"https://www.youtube.com/watch?v={vid}")
        return f"Playing {title or 'the top result'} (youtu.be/{vid})"
    open_url("https://www.youtube.com/results?search_query=" + urllib.parse.quote_plus(query))
    return f"Opened YouTube search for '{query}' (couldn't pick a video automatically)"
