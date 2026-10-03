import requests
import json
import time
from prompt_assembler import build_system_message
from gemini_tools import build_gemini_tools_schema

def warmup():
    try:
        start_time = time.time()
        system_msg = build_system_message("hi")
        tools = build_gemini_tools_schema()
        
        payload = {
            "messages": [
                {"role": "system", "content": system_msg},
                {"role": "user", "content": "hi"}
            ],
            "tools": tools,
            "max_tokens": 1
        }
        
        response = requests.post("http://127.0.0.1:8080/v1/chat/completions", json=payload, timeout=240)
        response.raise_for_status()
        
        duration = time.time() - start_time
        print(f"Warmup successful in {duration:.2f}s.")
    except Exception as e:
        print(f"Warmup failed: {e}")

if __name__ == "__main__":
    warmup()
