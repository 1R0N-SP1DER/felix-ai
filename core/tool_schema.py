"""
felix-ai Core — tool_schema.py
Phase 2, slice 4: defines what a valid tool_call looks like, and validates
candidate tool calls against it. No real tools exist yet (that's Phase 4) —
this is purely the structural contract + validation logic, built now so
the security model (LLM is untrusted, nothing executes without passing
schema validation) is in place from the start rather than bolted on later.
"""

# Registry of known tools and their expected parameter schemas.
# Empty for now — Phase 4 will populate this with real Android/PC tools.
# Format: "tool_name": {"param_name": expected_python_type, ...}
TOOL_REGISTRY = {
    "get_current_datetime": {},
    "get_battery_status": {},
    "set_volume": {"level": int},
    "get_wifi_status": {},
    "read_clipboard": {},
    "write_clipboard": {"text": str},
    "vibrate": {},
    "delegate_to_gemini": {"request": str},
    "search_web": {"query": str},
    "enable_thinking_mode": {},
}


class ToolCallValidationError(Exception):
    """Raised when a candidate tool call fails schema validation."""
    pass


def validate_tool_call(candidate: dict) -> bool:
    """
    Check that a candidate tool call dict matches the required structure:
      {
        "tool": "<tool_name>",
        "parameters": {...}
      }
    and that the tool exists in TOOL_REGISTRY with matching parameter types.

    Raises ToolCallValidationError with a specific reason on failure.
    Returns True if valid. Never executes anything — validation only.
    """
    if not isinstance(candidate, dict):
        raise ToolCallValidationError("Tool call must be a JSON object.")

    if "tool" not in candidate:
        raise ToolCallValidationError("Tool call missing required 'tool' field.")

    if "parameters" not in candidate:
        raise ToolCallValidationError("Tool call missing required 'parameters' field.")

    tool_name = candidate["tool"]
    parameters = candidate["parameters"]

    if not isinstance(tool_name, str):
        raise ToolCallValidationError("'tool' field must be a string.")

    if not isinstance(parameters, dict):
        raise ToolCallValidationError("'parameters' field must be an object.")

    if tool_name not in TOOL_REGISTRY:
        raise ToolCallValidationError(f"Unknown tool: '{tool_name}' is not in TOOL_REGISTRY.")

    expected_params = TOOL_REGISTRY[tool_name]

    # Check for unexpected extra parameters
    for param_name in parameters:
        if param_name not in expected_params:
            raise ToolCallValidationError(
                f"Unexpected parameter '{param_name}' for tool '{tool_name}'."
            )

    # Check all required parameters are present with correct types
    for param_name, expected_type in expected_params.items():
        if param_name not in parameters:
            raise ToolCallValidationError(
                f"Missing required parameter '{param_name}' for tool '{tool_name}'."
            )
        value = parameters[param_name]
        if expected_type is float and isinstance(value, int) and not isinstance(value, bool):
            continue  # JSON has no int/float split; 10 is a fine number
        if not isinstance(value, expected_type):
            raise ToolCallValidationError(
                f"Parameter '{param_name}' for tool '{tool_name}' must be "
                f"{expected_type.__name__}, got {type(value).__name__}."
            )

    return True


if __name__ == "__main__":
    # Quick manual tests using a temporary fake registry entry
    TOOL_REGISTRY["get_current_time"] = {}
    TOOL_REGISTRY["set_volume"] = {"level": int}

    test_cases = [
        {"tool": "get_current_time", "parameters": {}},
        {"tool": "set_volume", "parameters": {"level": 5}},
        {"tool": "set_volume", "parameters": {"level": "loud"}},  # wrong type
        {"tool": "nonexistent_tool", "parameters": {}},  # unknown tool
        {"tool": "set_volume", "parameters": {}},  # missing param
        {"not_a_tool_call": True},  # malformed
    ]

    for case in test_cases:
        try:
            validate_tool_call(case)
            print(f"VALID:   {case}")
        except ToolCallValidationError as e:
            print(f"REJECTED: {case} -> {e}")
