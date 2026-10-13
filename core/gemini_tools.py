"""
felix-ai Core -- gemini_tools.py
Builds a Gemini-ready function-declarations list from TOOL_REGISTRY,
with real, specific descriptions -- thin auto-generated descriptions
already caused Qwen to misfire on the PC side, so this is written out
by hand rather than generated from the tool name.
"""

from tool_schema import TOOL_REGISTRY

TOOL_DESCRIPTIONS = {
    "get_current_datetime": "Call this whenever the user asks for date or time. Returns current date and time. No arguments.",
    "get_battery_status": "Call this whenever the user asks about battery, charge level, or power status. Returns battery percentage. No arguments.",
    "set_volume": "Call this whenever the user asks to change, raise, lower, mute, or set phone volume. Sets media volume.",
    "get_wifi_status": "Call this whenever the user asks about WiFi connection or network status. Returns WiFi state. No arguments.",
    "read_clipboard": "Call this whenever the user asks about clipboard contents. Returns clipboard text. No arguments.",
    "write_clipboard": "Call this whenever the user asks to copy, save, or put text into clipboard. Saves text.",
    "vibrate": "Call this whenever the user asks to vibrate the phone or get attention physically. No arguments.",
    "delegate_to_gemini": "Call this for reasoning, knowledge, or judgment beyond your ability. Pass a clear description of needed help.",
    "enable_thinking_mode": "Call this ONLY if delegate_to_gemini or search_web just failed and the question still needs careful step-by-step reasoning. No arguments.",
    "search_web": "Call this for current, real-world information like news or live facts. Performs grounded web search. Never answer from memory.",
    "open_url": "Call this when the user asks to open a link or website. Opens the http(s) link on the device.",
    "open_app": "Call this when the user asks to open, launch or start an app (YouTube, Spotify, Chrome, Settings...). Launches it by name.",
    "list_apps": "Call this to see which apps are installed on the phone, optionally filtered by name. Use it when open_app cannot find an app.",
    "youtube_play": "Call this when the user asks to play, watch or find a YouTube video or a channel's latest video. Searches YouTube and starts the video.",
    "screen_read": "Call this to see what is on the phone screen right now: every labelled element with its position. Use it before tapping and to check the result of an action.",
    "screen_look": "Call this to look at a screenshot of the screen and answer a question about it, for things screen_read cannot show (a camera preview, a game, an unlabelled button). Ask it for coordinates when you need to tap something.",
    "tap": "Call this to tap the screen at pixel coordinates x,y.",
    "tap_text": "Call this to tap the on-screen element whose label contains the given text, for example Shutter or Search.",
    "swipe": "Call this to swipe from one point to another, for example to scroll.",
    "type_text": "Call this to type plain text into the focused field.",
    "press_key": "Call this to press a hardware key: home, back, enter, recent, volume_down, volume_up, camera, delete, tab, search, up, down, left, right.",
    "get_weather": "Call this for the current weather or today's temperature, rain chance and conditions in a place. Fast and reliable; prefer it over search_web for weather. Pass an empty city for the user's default place.",
    "listen": "Call this to listen through the microphone for a few seconds and report what is said or heard.",
    "wait": "Call this to wait 1 to 10 seconds, for example after opening an app so it can finish loading.",
}


# Per-tool parameter descriptions for tools added at runtime (see agent.py).
TOOL_PARAM_DESCRIPTIONS = {}

PARAM_DESCRIPTIONS = {
    "level": "volume level, an integer from 0 to 15",
    "text": "the exact text to copy to the clipboard",
    "url": "the full link, starting with https://",
    "city": "the place to get the weather for, for example Lagos; an empty string means the user's default place",
    "app": "the app's name, for example YouTube or Spotify",
    "search": "part of an app's name to filter by, or a single space for every app",
    "question": "what to find out about the screenshot; ask for x,y pixel coordinates when you need to tap something",
    "label": "text or description of the element, for example Shutter",
    "content": "the plain text to type",
    "key": "the key to press, for example back or volume_down",
    "seconds": "how many seconds",
    "x": "horizontal pixel position", "y": "vertical pixel position",
    "x1": "start x", "y1": "start y", "x2": "end x", "y2": "end y",
    "query": "what to search for; for a channel's newest video use the channel name plus the word latest",
}


def _type_to_json_schema(python_type):
    if python_type is int:
        return {"type": "integer"}
    if python_type is str:
        return {"type": "string"}
    if python_type is float:
        return {"type": "number"}
    if python_type is bool:
        return {"type": "boolean"}
    return {"type": "string"}


# Tools Gemini must never be offered: it cannot delegate to itself, and
# thinking mode is a local-model-only signal.
GEMINI_EXCLUDED = {"delegate_to_gemini", "enable_thinking_mode"}

# Phone control: offered to Gemini only while /control is on or ask, and never to the small local models.
DEVICE_TOOLS = frozenset({"screen_read", "screen_look", "tap", "tap_text", "swipe", "type_text", "press_key", "listen", "wait"})


def build_tools_schema(exclude=()) -> list:
    schema = []
    for name, params in TOOL_REGISTRY.items():
        if name in exclude:
            continue
        properties = {}
        for param_name, param_type in params.items():
            prop = _type_to_json_schema(param_type)
            prop["description"] = (TOOL_PARAM_DESCRIPTIONS.get(name, {}).get(param_name)
                                   or PARAM_DESCRIPTIONS.get(param_name, param_name))
            properties[param_name] = prop

        schema.append({
            "type": "function",
            "function": {
                "name": name,
                "description": TOOL_DESCRIPTIONS.get(name, name.replace("_", " ")),
                "parameters": {
                    "type": "object",
                    "properties": properties,
                    "required": list(params.keys()),
                },
            },
        })
    return schema


def build_gemini_tools_schema(device: bool = False) -> list:
    return build_tools_schema(GEMINI_EXCLUDED | (set() if device else DEVICE_TOOLS))


def build_local_tools_schema() -> list:
    """The local model gets every tool except phone control, including delegate_to_gemini."""
    return build_tools_schema(DEVICE_TOOLS)
