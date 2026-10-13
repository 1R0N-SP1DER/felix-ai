"""
felix-ai Core — permission_policy.py
Phase 2, slice 5: classifies a validated tool call into one of three
permission tiers before execution is ever considered:
  - "safe"    -> execute automatically, no user interaction
  - "confirm" -> ask the user before executing
  - "block"   -> never execute, regardless of context

This is pure classification logic. It does not execute anything and does
not know how to execute anything — that's the tool executor, built in
Phase 4. Also respects device_overrides from the Profile config (e.g.
Felix wants PC to default to low proactivity / always-confirm).
"""

# Default permission tier per tool. Empty for now since no real tools
# exist yet (Phase 4). Format: "tool_name": "safe" | "confirm" | "block"
PERMISSION_REGISTRY = {
    "get_current_datetime": "safe",
    "get_battery_status": "safe",
    "set_volume": "safe",
    "get_wifi_status": "safe",
    "read_clipboard": "confirm",
    "write_clipboard": "confirm",
    "vibrate": "safe",
    # Escape hatches: these were never registered, so the policy refused
    # to run them ("no registered permission tier") and every call
    # surfaced as a generic tool failure.
    "delegate_to_gemini": "safe",
    "search_web": "safe",
    "enable_thinking_mode": "safe",
    "open_url": "safe",
    "open_app": "safe",
    "list_apps": "safe",
    "screen_read": "safe", "screen_look": "safe", "tap": "safe", "tap_text": "safe", "swipe": "safe",
    "type_text": "safe", "press_key": "safe", "listen": "safe", "wait": "safe",
    "youtube_play": "safe",
    "get_weather": "safe",
}

VALID_TIERS = ("safe", "confirm", "block")


class UnknownToolError(Exception):
    """Raised when asking for the permission tier of a tool not in the registry."""
    pass


def get_permission_tier(tool_name: str, device: str, config: dict) -> str:
    """
    Return the permission tier ("safe", "confirm", or "block") for a given
    tool on a given device ("phone" or "pc"), taking device_overrides into
    account.

    Policy: device_overrides can only make a tool STRICTER, never looser.
    e.g. if a tool is globally "safe" but the PC's device_overrides sets
    always_confirm=true, the effective tier on PC becomes "confirm" — but
    a globally "confirm" or "block" tool is never downgraded to "safe" by
    a device override, since overrides are meant to add caution, not
    remove it.
    """
    if tool_name not in PERMISSION_REGISTRY:
        raise UnknownToolError(
            f"'{tool_name}' has no registered permission tier. "
            "Refusing to guess — tools must be explicitly classified."
        )

    base_tier = PERMISSION_REGISTRY[tool_name]

    device_overrides = config.get("device_overrides", {})
    device_settings = device_overrides.get(device, {})

    if device_settings.get("always_confirm") and base_tier == "safe":
        return "confirm"

    return base_tier


if __name__ == "__main__":
    # Quick manual tests using temporary fake registry entries
    PERMISSION_REGISTRY["get_current_time"] = "safe"
    PERMISSION_REGISTRY["send_whatsapp_message"] = "confirm"
    PERMISSION_REGISTRY["factory_reset"] = "block"

    fake_config = {
        "device_overrides": {
            "pc": {"proactivity": "low", "always_confirm": True},
            "phone": {"proactivity": "normal", "always_confirm": False},
        }
    }

    tests = [
        ("get_current_time", "phone"),  # safe stays safe
        ("get_current_time", "pc"),     # safe -> confirm (PC override)
        ("send_whatsapp_message", "phone"),  # confirm stays confirm
        ("factory_reset", "pc"),        # block stays block, never loosened
    ]

    for tool, device in tests:
        tier = get_permission_tier(tool, device, fake_config)
        print(f"{tool} on {device}: {tier}")

    try:
        get_permission_tier("nonexistent_tool", "phone", fake_config)
    except UnknownToolError as e:
        print(f"REJECTED: {e}")
