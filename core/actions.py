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


# ---------------------------------------------------------------- installed apps

_APPS = {"at": 0.0, "packages": None, "launchers": None}
APP_CACHE_SECONDS = 60.0


def _sys(cmd: list, root: bool = False, timeout: float = 20.0) -> str:
    import tool_helpers
    try:
        return tool_helpers.run(cmd, root=root, timeout=timeout)
    except RuntimeError as e:
        raise ActionError(str(e))


def _root_modes() -> list:
    """Root first when allowed (without it Android hides most installed apps from Termux), then plain."""
    modes = []
    if SETTINGS["root"] in ("auto", "always") and shutil.which("su"):
        modes.append(True)
    if SETTINGS["root"] != "always":
        modes.append(False)
    return modes


def installed_apps(force: bool = False) -> tuple:
    """(all packages, {launchable package: component}). Cached for a minute."""
    import time
    if not force and _APPS["packages"] is not None and time.monotonic() - _APPS["at"] < APP_CACHE_SECONDS:
        return _APPS["packages"], _APPS["launchers"]
    last = "no packages found"
    for root in _root_modes():
        try:
            out = _sys(["pm", "list", "packages"], root)
        except ActionError as e:
            last = str(e)
            continue
        packages = sorted({l.split(":", 1)[1].strip() for l in out.splitlines() if l.startswith("package:")})
        if not packages:
            continue
        launchers = {}
        try:
            listing = _sys(["cmd", "package", "query-activities", "--brief", "-a", "android.intent.action.MAIN",
                            "-c", "android.intent.category.LAUNCHER"], root)
            for m in re.finditer(r"^\s*([A-Za-z0-9_.]+)/([A-Za-z0-9_.$]+)\s*$", listing, re.MULTILINE):
                launchers.setdefault(m.group(1), m.group(2))
        except ActionError:
            pass
        _APPS.update(at=time.monotonic(), packages=packages, launchers=launchers)
        return packages, launchers
    raise ActionError(f"could not list the installed apps ({last})")


def _score(key: str, package: str) -> int:
    squashed = re.sub(r"[^a-z0-9]", "", package.lower())
    segments = [re.sub(r"[^a-z0-9]", "", seg) for seg in package.lower().split(".")]
    if key in segments:
        return 3
    if any(seg.startswith(key) or key in seg for seg in segments):
        return 2
    return 1 if key in squashed else 0


def find_app(name: str) -> tuple:
    """(package, component or None, notes). Known names first, but only if that app is really installed:
    a modded YouTube has a different package, so it is found by name instead."""
    key = re.sub(r"[^a-z0-9]", "", re.sub(r"\s+(app|application)$", "", name.strip().lower()))
    if re.fullmatch(r"[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+", name.strip().lower()):
        return name.strip().lower(), None, ""
    try:
        packages, launchers = installed_apps()
    except ActionError:
        known = APP_PACKAGES.get(re.sub(r"\s+(app|application)$", "", name.strip().lower()))
        if known:
            return known, None, ""  # cannot check, so trust the built-in name
        raise
    known = APP_PACKAGES.get(re.sub(r"\s+(app|application)$", "", name.strip().lower()))
    if known and known in packages:
        return known, launchers.get(known), ""
    pool = [p for p in packages if p in launchers] or packages
    scored = sorted(((_score(key, p), p in launchers, -len(p), p) for p in pool if key and _score(key, p)), reverse=True)
    if not scored:
        raise ActionError(f"no installed app matches '{name}' (try list_apps)")
    best = scored[0][3]
    others = [s[3] for s in scored[1:3]]
    return best, launchers.get(best), (f"; also matched {', '.join(others)}" if others else "")


def list_apps(search: str = "") -> str:
    packages, launchers = installed_apps()
    pool = [p for p in packages if p in launchers] or packages
    needle = re.sub(r"[^a-z0-9]", "", str(search or "").lower())
    hits = [p for p in pool if not needle or needle in re.sub(r"[^a-z0-9]", "", p.lower())]
    if not hits:
        return f"No installed app matches '{str(search).strip()}'." if needle else "No apps found."
    shown = hits[:40]
    more = f"\n...and {len(hits) - 40} more; narrow the search" if len(hits) > 40 else ""
    return f"{len(hits)} app{'s' if len(hits) != 1 else ''}" + (f" matching '{str(search).strip()}'" if needle else "") + ":\n" + "\n".join(shown) + more


def _component_of(package: str):
    """The launcher activity as pkg/Activity, asked of the package manager (root sees every package)."""
    for root in _root_modes():
        try:
            out = _sys(["cmd", "package", "resolve-activity", "--brief", "-c", "android.intent.category.LAUNCHER", package], root)
        except ActionError:
            continue
        for line in reversed(out.splitlines()):
            if "/" in line and not line.startswith(("priority", "No ")):
                return line.strip()
    return None


def open_app(app: str) -> str:
    app = str(app or "").strip()
    if not app or len(app) > 60:
        raise ActionError("open_app needs an app name")
    if not _is_android():
        binary = shutil.which(app.lower().replace(" ", "-"))
        if not binary:
            raise ActionError(f"I can't find '{app}' on this computer")
        subprocess.Popen([binary], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
        return f"Opened {app}"
    package, component, note = find_app(app)
    tried = []

    def explicit():
        found = component or _component_of(package)
        if not found:
            raise ActionError("no launcher activity found")
        return _android_start(["-n", found if "/" in found else f"{package}/{found}"])

    for label, start in (
        ("launch intent", lambda: _android_start(["-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", "-p", package])),
        ("component", explicit),
        ("monkey", lambda: _sys(["monkey", "-p", package, "-c", "android.intent.category.LAUNCHER", "1"], bool(_root_modes() and _root_modes()[0]))),
    ):
        try:
            start()
            return f"Opened {app} ({package}{note})"
        except ActionError as e:
            tried.append(f"{label}: {str(e)[:110]}")
    raise ActionError(f"could not open {app} ({package}). Tried " + "; ".join(tried))


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
