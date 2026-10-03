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
    "search_web": "Call this for current, real-world information like news or live facts. Performs grounded web search. Never answer from memory.",
}


PARAM_DESCRIPTIONS = {
    "level": "volume level, an integer from 0 to 15",
    "text": "the exact text to copy to the clipboard",
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


def build_gemini_tools_schema() -> list:
    schema = []
    for name, params in TOOL_REGISTRY.items():
        if name == "delegate_to_gemini":
            continue
        properties = {}
        for param_name, param_type in params.items():
            prop = _type_to_json_schema(param_type)
            prop["description"] = PARAM_DESCRIPTIONS.get(param_name, param_name)
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
