import time

import requests

from prompt_assembler import build_static_system_message
from orchestrator import TOOL_INSTRUCTIONS, LOCAL_TOOLS_SCHEMA


def warmup():
    """Heat llama-server's KV cache with the exact production prefix
    (static system prompt + tool instructions + local tool schema)."""
    try:
        start_time = time.time()
        system_msg = build_static_system_message() + "\n" + TOOL_INSTRUCTIONS

        payload = {
            "messages": [
                {"role": "system", "content": system_msg},
                {"role": "user", "content": "hi"},
            ],
            "tools": LOCAL_TOOLS_SCHEMA,
            "chat_template_kwargs": {"enable_thinking": False},
            "max_tokens": 1,
        }

        response = requests.post("http://127.0.0.1:8080/v1/chat/completions", json=payload, timeout=240)
        response.raise_for_status()

        print(f"Warmup successful in {time.time() - start_time:.2f}s.")
    except Exception as e:
        print(f"Warmup failed: {e}")


if __name__ == "__main__":
    warmup()
