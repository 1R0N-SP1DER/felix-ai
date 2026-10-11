"""
felix-ai Core — tool_executor.py
Phase 4: ten tools total now. Added delegate_to_gemini, search_web,
and enable_thinking_mode -- the local model's real escape hatches for
anything beyond its own reliability.
"""

import re
import subprocess
import json

from gemini_client import ask_gemini, GeminiError


class ToolExecutionError(Exception):
    pass


def get_current_datetime(parameters: dict) -> str:
    try:
        result = subprocess.run(
            ["date", "+%Y-%m-%d %H:%M:%S %A"],
            capture_output=True, text=True, timeout=5, check=True
        )
        return result.stdout.strip()
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as e:
        raise ToolExecutionError(f"Failed to get datetime: {e}")


def get_battery_status(parameters: dict) -> str:
    try:
        result = subprocess.run(
            ["termux-battery-status"],
            capture_output=True, text=True, timeout=5, check=True
        )
        data = json.loads(result.stdout)
        percentage = data.get("percentage", "unknown")
        status = data.get("status", "unknown")
        return f"{percentage}% battery, status: {status}"
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as e:
        raise ToolExecutionError(f"Failed to get battery status: {e}")
    except json.JSONDecodeError as e:
        raise ToolExecutionError(f"Failed to parse battery status output: {e}")


def set_volume(parameters: dict) -> str:
    level = parameters.get("level")
    if level is None:
        raise ToolExecutionError("set_volume requires a 'level' parameter.")
    try:
        subprocess.run(
            ["termux-volume", "music", str(level)],
            capture_output=True, text=True, timeout=5, check=True
        )
        return f"Volume set to {level}."
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as e:
        raise ToolExecutionError(f"Failed to set volume: {e}")


def toggle_flashlight(parameters: dict) -> str:
    state = parameters.get("state")
    if state not in ("on", "off"):
        raise ToolExecutionError("toggle_flashlight requires state 'on' or 'off'.")
    try:
        subprocess.run(
            ["termux-torch", state],
            capture_output=True, text=True, timeout=5, check=True
        )
        return f"Flashlight turned {state}."
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as e:
        raise ToolExecutionError(f"Failed to toggle flashlight: {e}")


def get_wifi_status(parameters: dict) -> str:
    try:
        result = subprocess.run(
            ["termux-wifi-connectioninfo"],
            capture_output=True, text=True, timeout=5, check=True
        )
        data = json.loads(result.stdout)
        ssid = data.get("ssid", "unknown")
        state = data.get("supplicant_state", "unknown")
        return f"Connected to '{ssid}', state: {state}"
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as e:
        raise ToolExecutionError(f"Failed to get WiFi status: {e}")
    except json.JSONDecodeError as e:
        raise ToolExecutionError(f"Failed to parse WiFi status output: {e}")


def read_clipboard(parameters: dict) -> str:
    try:
        result = subprocess.run(
            ["termux-clipboard-get"],
            capture_output=True, text=True, timeout=5, check=True
        )
        content = result.stdout.strip()
        return content if content else "(clipboard is empty)"
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as e:
        raise ToolExecutionError(f"Failed to read clipboard: {e}")


def write_clipboard(parameters: dict) -> str:
    text = parameters.get("text")
    if text is None:
        raise ToolExecutionError("write_clipboard requires a 'text' parameter.")
    try:
        subprocess.run(
            ["termux-clipboard-set"],
            input=text, capture_output=True, text=True, timeout=5, check=True
        )
        return "Copied to clipboard."
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as e:
        raise ToolExecutionError(f"Failed to write clipboard: {e}")


def vibrate(parameters: dict) -> str:
    try:
        subprocess.run(
            ["termux-vibrate"],
            capture_output=True, text=True, timeout=5, check=True
        )
        return "Vibrated."
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as e:
        raise ToolExecutionError(f"Failed to vibrate: {e}")


def delegate_to_gemini(parameters: dict) -> str:
    """
    Called by the LOCAL model when it decides a request needs real
    reasoning, knowledge, or judgment beyond its own reliability.
    Sends a fresh, isolated request to Gemini (not the full
    conversation history) and returns the answer as the tool result.
    """
    request = parameters.get("request")
    if not request:
        raise ToolExecutionError("delegate_to_gemini requires a 'request' parameter.")
    try:
        result = ask_gemini(
            system_message="You are Faze's more capable reasoning layer, being asked to help with something the smaller local model could not handle alone. Answer clearly and directly.",
            conversation_messages=[{"role": "user", "content": request}],
        )
    except GeminiError as e:
        raise ToolExecutionError(f"Could not reach Gemini for delegation: {e}")

    if result["type"] == "text":
        return result["content"]
    return f"Gemini responded with a tool request ({result['name']}) instead of an answer -- unexpected for a delegated request."


SEARCH = {"engine": "auto", "blocked_until": 0.0}
BREAKER_SECONDS = 600.0


def configure(config: dict) -> None:
    """Pick up settings that tools depend on (search engine, root mode)."""
    import actions
    engine = (config.get("search") or {}).get("engine", "auto")
    SEARCH["engine"] = engine if engine in ("auto", "gemini", "web") else "auto"
    actions.configure(config)
    import device
    device.configure(config)


def _run_action(fn, *args):
    import actions
    try:
        return fn(*args)
    except actions.ActionError as e:
        raise ToolExecutionError(str(e))


def open_url(parameters: dict) -> str:
    import actions
    return _run_action(actions.open_url, parameters.get("url"))


def open_app(parameters: dict) -> str:
    import actions
    return _run_action(actions.open_app, parameters.get("app"))


def list_apps(parameters: dict) -> str:
    import actions
    return _run_action(actions.list_apps, parameters.get("search"))


def _device(fn, *args):
    import device
    try:
        return fn(*args)
    except device.DeviceError as e:
        raise ToolExecutionError(str(e))


def screen_read(parameters: dict) -> str:
    import device
    return _device(device.screen_read)


def screen_look(parameters: dict) -> str:
    import device
    return _device(device.screen_look, parameters.get("question"))


def tap(parameters: dict) -> str:
    import device
    return _device(device.tap, parameters.get("x"), parameters.get("y"))


def tap_text(parameters: dict) -> str:
    import device
    return _device(device.tap_text, parameters.get("label"))


def swipe(parameters: dict) -> str:
    import device
    return _device(device.swipe, *(parameters.get(k) for k in ("x1", "y1", "x2", "y2")))


def type_text(parameters: dict) -> str:
    import device
    return _device(device.type_text, parameters.get("content"))


def press_key(parameters: dict) -> str:
    import device
    return _device(device.press_key, parameters.get("key"))


def listen(parameters: dict) -> str:
    import device
    return _device(device.listen, parameters.get("seconds"))


def wait(parameters: dict) -> str:
    import device
    return _device(device.wait, parameters.get("seconds"))


def youtube_play(parameters: dict) -> str:
    import actions
    return _run_action(actions.youtube_play, parameters.get("query"))


def search_web(parameters: dict) -> str:
    """
    Called by the LOCAL model for anything current, recent, or
    real-world it cannot know from training. Makes a SEPARATE Gemini
    request with google_search grounding enabled (Gemini's API does
    not allow combining search grounding with function-calling tools
    in the same request, so this cannot reuse the normal tool-calling
    call -- it is its own isolated grounded request).
    """
    import time
    import web_search
    query = parameters.get("query")
    if not query:
        raise ToolExecutionError("search_web requires a 'query' parameter.")
    engine = SEARCH["engine"]
    why = ""
    if engine in ("auto", "gemini") and (engine == "gemini" or time.monotonic() >= SEARCH["blocked_until"]):
        try:
            result = ask_gemini(
                system_message="Answer the user's question using real, current web search results. Be specific and cite what you found.",
                conversation_messages=[{"role": "user", "content": query}],
                use_search_grounding=True,
            )
            if result["type"] == "text":
                return result["content"]
            why = "Gemini search gave no usable answer"
        except GeminiError as e:
            why = str(e)
            if re.search(r"429|quota|rate", why, re.IGNORECASE):
                SEARCH["blocked_until"] = time.monotonic() + BREAKER_SECONDS  # stop asking for a while
        if engine == "gemini":
            raise ToolExecutionError(f"Web search failed: {why}")
    try:
        return web_search.format_results(query, web_search.ddg_search(query))
    except web_search.SearchError as e:
        raise ToolExecutionError(f"Web search failed: {why + '; ' if why else ''}{e}")


def enable_thinking_mode(parameters: dict) -> str:
    """
    A signal tool, not a device action. Calling this tells send() to
    re-run the current request with allow_thinking=True. Only makes
    sense when delegate_to_gemini has already failed (Gemini
    unreachable) -- if Gemini were available, delegating is almost
    always the better choice than making the small model reason hard
    on its own.
    """
    return "THINKING_MODE_REQUESTED"


TOOL_EXECUTORS = {
    "get_current_datetime": get_current_datetime,
    "get_battery_status": get_battery_status,
    "set_volume": set_volume,
    "get_wifi_status": get_wifi_status,
    "read_clipboard": read_clipboard,
    "write_clipboard": write_clipboard,
    "vibrate": vibrate,
    "delegate_to_gemini": delegate_to_gemini,
    "search_web": search_web,
    "enable_thinking_mode": enable_thinking_mode,
    "open_url": open_url,
    "open_app": open_app,
    "list_apps": list_apps,
    "screen_read": screen_read, "screen_look": screen_look, "tap": tap, "tap_text": tap_text, "swipe": swipe,
    "type_text": type_text, "press_key": press_key, "listen": listen, "wait": wait,
    "youtube_play": youtube_play,
}


def execute_tool(tool_name: str, parameters: dict) -> str:
    if tool_name not in TOOL_EXECUTORS:
        raise ToolExecutionError(f"No executor registered for tool '{tool_name}'.")
    return TOOL_EXECUTORS[tool_name](parameters)


if __name__ == "__main__":
    print("datetime:", execute_tool("get_current_datetime", {}))
    print("battery:", execute_tool("get_battery_status", {}))
