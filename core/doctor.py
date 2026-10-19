"""
felix-ai Core -- doctor.py
One command (/doctor) that tests every moving part with a real request and says exactly what fails.
Network checks cost a handful of tiny API calls.
"""

import os
import shutil
import time

import actions
import agent
import stt
import tts
import web_search

OK, WARN, FAIL, SKIP = "ok", "warn", "fail", "skip"


def _timed(fn):
    start = time.time()
    try:
        status, detail = fn()
    except Exception as e:  # a check must never take the report down
        status, detail = FAIL, f"{type(e).__name__}: {str(e)[:200]}"
    return status, f"{detail} ({time.time() - start:.1f}s)" if status != SKIP else detail


def run_checks(config: dict) -> list:
    from gemini_client import GeminiError, ask_gemini
    has_key = bool(os.environ.get("GEMINI_API_KEY"))
    out = []

    def add(name, fn):
        out.append((name,) + _timed(fn))

    def key():
        k = os.environ.get("GEMINI_API_KEY", "")
        return (OK, f"set ({len(k)} characters)") if k else (FAIL, "GEMINI_API_KEY is not set in this process; put GEMINI_API_KEY=... in ~/.felix/env (chmod 600)")
    add("Gemini key", key)

    def need_key(fn):
        return fn if has_key else (lambda: (SKIP, "needs the key"))

    def chat():
        r = ask_gemini("", [{"role": "user", "content": "Reply with the single word OK."}])
        return (OK, "answered") if r["type"] == "text" else (WARN, "returned a tool call")
    add("Gemini chat", need_key(chat))

    def tool_call():
        r = ask_gemini("Use a tool when one fits.", [{"role": "user", "content": "What time is it right now? Use a tool."}],
                       tools_schema=agent.gemini_schema(config))
        return (OK, f"called {r['name']}") if r["type"] == "function_call" else (WARN, "answered without calling a tool")
    add("Gemini tool calling", need_key(tool_call))

    def make_tool():
        if config.get("agent", {}).get("mode", "ask") == "off":
            return SKIP, "agent mode is off (/agent ask)"
        r = ask_gemini("Use create_tool when asked to make a tool.", [{"role": "user", "content":
                       "Make a tool named add_two_numbers that adds two numbers a and b. Use create_tool."}],
                       tools_schema=agent.gemini_schema(config))
        return (OK, "proposed a tool (nothing was saved)") if r["type"] == "function_call" and r["name"] == "create_tool" \
            else (WARN, "did not propose a tool; ask it more directly")
    add("Gemini writes tools", need_key(make_tool))

    def grounded():
        r = ask_gemini("Answer briefly.", [{"role": "user", "content": "What is the capital of France?"}], use_search_grounding=True)
        return (OK, "Google Search grounding works") if r["type"] == "text" else (WARN, "no text")
    add("Gemini web search", need_key(grounded))

    def ddg():
        results = web_search.ddg_search("python programming language", 3)
        return OK, f"{len(results)} results, first: {results[0]['title'][:50]}"
    add("Backup web search", ddg)

    def tavily():
        text = web_search.tavily_search("python programming language", os.environ["TAVILY_API_KEY"], 3)
        return OK, f"{text.count(chr(10) + '   http')} sources"
    add("Tavily search", tavily if os.environ.get("TAVILY_API_KEY") else (lambda: (SKIP, "no TAVILY_API_KEY")))

    import remote_models

    def provider_checks(name):
        label = remote_models.PROVIDERS[name]["label"]

        def say_ok():
            model = remote_models.model_for(name, config)
            r = remote_models.chat(name, model, [{"role": "user", "content": "Reply with the single word OK."}], max_tokens=20)
            return (OK, f"{model} answered") if (r["content"] or "").strip() else (WARN, f"{model} sent an empty reply")

        def uses_tools():
            model = remote_models.model_for(name, config)
            schema = [t for t in agent.gemini_schema(config) if t["function"]["name"] == "get_current_datetime"]
            r = remote_models.chat(name, model, [{"role": "user", "content": "What time is it right now? Use the tool."}], schema, max_tokens=80)
            names = [c["name"] for c in (r["tool_calls"] or [])]
            return (OK, f"{model} called {names[0]}") if names else (WARN, f"{model} answered without calling the tool")
        for title, fn in ((f"{label} chat", say_ok), (f"{label} tool calling", uses_tools)):
            if not remote_models.has_key(name):
                add(title, lambda: (SKIP, f"no {remote_models.PROVIDERS[name]['env']}"))
            elif not remote_models.available(name):
                add(title, lambda: (WARN, remote_models.note(name)))
            else:
                add(title, lambda fn=fn: _remote(fn))

    def _remote(fn):
        try:
            return fn()
        except remote_models.RemoteError as e:
            return FAIL, str(e)[:200]
    for provider in remote_models.PROVIDERS:
        provider_checks(provider)

    def device():
        if actions._is_android():
            return OK, "am found" + (", su found" if shutil.which("su") else ", no su (background launches may be blocked)")
        return (OK, "xdg-open found") if shutil.which("xdg-open") else (FAIL, "neither am nor xdg-open found")
    add("Open apps and links", device)

    def system_commands():
        if not actions._is_android():
            return SKIP, "not on Android"
        proc = actions._run(["am", "help"])
        text = ((proc.stdout or "") + (proc.stderr or "")).strip()
        first = text.splitlines()[0][:120] if text else "no output"
        if actions._linker_crash(proc):
            return FAIL, f"am crashes in the linker even with a clean environment: {first}"
        return (OK, "am runs") if "usage" in text.lower() or proc.returncode == 0 else (WARN, f"am said: {first}")
    add("Android system commands", system_commands)

    def apps():
        if not actions._is_android():
            return SKIP, "not on Android"
        packages, launchers = actions.installed_apps(force=True)
        return OK, f"{len(packages)} packages, {len(launchers)} launchable" + ("" if launchers else " (cannot tell which are launchable)")
    add("Installed apps", apps)

    def control():
        import device
        if not actions._is_android():
            return SKIP, "not on Android"
        if device.SETTINGS["control"] == "off":
            return SKIP, "phone control is off (/control ask to turn it on)"
        w, h = device.screen_size()
        nodes = device.read_nodes()
        return OK, f"screen {w}x{h}, {len(nodes)} readable elements"
    add("Phone control", control)

    def yt():
        found = actions.youtube_find("mrwhosetheboss", latest=True)
        return (OK, f"found {found[1][:50] or found[0]}") if found else (FAIL, "no video found (YouTube changed its page; pip install yt-dlp makes this reliable)")
    add("YouTube lookup", yt)

    def voice():
        audio, kind = tts.synthesize_audio("Testing.", config["tts"]["voice"], config["tts"]["model"])
        return OK, f"{len(audio)} bytes of {kind}"
    add("Gemini voice", need_key(voice))

    def mic():
        import sys
        sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "scripts"))
        import pa_record
        source = pa_record.detect_source()
        return (OK, f"source {source}") if source else (FAIL, "no microphone source (pactl load-module module-sles-source)")
    add("Microphone source", mic)

    def speech():
        online = stt.online_usable(config)
        offline = stt.available(config)
        if not (online or offline):
            return FAIL, "no engine: " + (stt.missing_reason(config) or "whisper missing") + " and no Gemini key"
        return OK, ("online" if online else "") + (" + " if online and offline else "") + ("whisper installed" if offline else "")
    add("Wake-word recognition", speech)

    def live():
        import live_client
        steps = live_client.check(config)
        bad = [x for x in steps if not x[1]]
        if bad:
            return FAIL, f"{bad[0][0]}: {bad[0][2]}"
        return OK, "; ".join(f"{n}: {d}" for n, _, d in steps if n in ("websocket", "audio player"))
    add("Gemini Live", need_key(live))
    return out


def format_report(results: list) -> str:
    mark = {OK: "ok  ", WARN: "warn", FAIL: "FAIL", SKIP: "skip"}
    lines = [f"[{mark[s]}] {name}: {detail}" for name, s, detail in results]
    bad = sum(1 for _, s, _ in results if s == FAIL)
    lines.append(f"{len(results)} checks, {bad} failed" if bad else f"{len(results)} checks, none failed")
    return "\n".join(lines)
