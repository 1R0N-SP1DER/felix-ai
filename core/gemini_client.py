"""
felix-ai Core -- gemini_client.py
Plain REST calls to the Gemini API (no SDK -- google-genai's
dependency chain needs Rust targets with no aarch64-linux-android
wheels).

v2: adds real function calling (Gemini decides which tool to call, not
keyword matching) and streaming (tokens print as they arrive instead
of waiting for the whole reply).
"""

import os
import json
import requests

GEMINI_MODEL = "gemini-3.1-flash-lite"
_MODELS = {"chat": GEMINI_MODEL, "search": GEMINI_MODEL}


THINKING_LEVELS = ("minimal", "low", "medium", "high")
_THINKING = {"level": "low"}  # Gemini 3 thinks before answering; less thinking means a much faster reply


def configure(chat: str = None, search: str = None, thinking: str = None) -> None:
    """Pick the models for normal requests and for Google Search grounding, and how much Gemini thinks
    (minimal | low | medium | high, or anything else to leave it to the model)."""
    if chat:
        _MODELS["chat"] = chat
    if search:
        _MODELS["search"] = search
    if thinking is not None:
        _THINKING["level"] = thinking if thinking in THINKING_LEVELS else ""


def _post(url: str, headers: dict, body: dict, timeout, stream: bool = False):
    """requests.post with the thinking level attached. If the model rejects that field, drop it for good
    and send the request again, so a model that does not know it still works."""
    if _THINKING["level"]:
        body = {**body, "generationConfig": {**body.get("generationConfig", {}), "thinkingConfig": {"thinkingLevel": _THINKING["level"]}}}
    try:
        response = requests.post(url, headers=headers, json=body, timeout=timeout, stream=stream)
        if response.status_code == 400 and _THINKING["level"] and "think" in (response.text or "").lower():
            _THINKING["level"] = ""
            body.pop("generationConfig", None)
            response = requests.post(url, headers=headers, json=body, timeout=timeout, stream=stream)
    except requests.RequestException as e:
        raise GeminiError(f"Network error calling Gemini: {e}")
    return response


def _base_url(grounded: bool = False) -> str:
    return f"https://generativelanguage.googleapis.com/v1beta/models/{_MODELS['search' if grounded else 'chat']}"


class GeminiError(Exception):
    pass


def _http_error(response) -> "GeminiError":
    """Build the error for a non-200 reply. For quota errors, append the
    quota metric id and retry delay (short, safe fields) so a 429 says
    WHICH limit was hit."""
    extra = ""
    try:
        ids, delays = set(), set()

        def walk(o):
            if isinstance(o, dict):
                for k, v in o.items():
                    if k in ("quotaId", "quotaMetric") and isinstance(v, str):
                        ids.add(v)
                    elif k == "retryDelay" and isinstance(v, str):
                        delays.add(v)
                    else:
                        walk(v)
            elif isinstance(o, list):
                for v in o:
                    walk(v)

        walk(response.json())
        if ids:
            extra += " quota=" + ",".join(sorted(ids))
        if delays:
            extra += " retry=" + sorted(delays)[0]
    except ValueError:
        pass
    return GeminiError(f"Gemini API returned {response.status_code}: {response.text[:300]}{extra}")


def _to_gemini_contents(conversation_messages: list) -> list:
    contents = []
    for msg in conversation_messages:
        role = msg.get("role")
        if role == "system":
            continue
        if role == "tool":
            contents.append({
                "role": "user",
                "parts": [{
                    "functionResponse": {
                        "name": msg.get("name", "unknown_tool"),
                        "response": {"result": msg.get("content", "")},
                    }
                }],
            })
            continue

        gemini_role = "model" if role == "assistant" else "user"
        contents.append({"role": gemini_role, "parts": [{"text": msg.get("content", "")}]})
    return contents


def _tools_to_function_declarations(tools_schema: list) -> list:
    declarations = []
    for tool in tools_schema:
        fn = tool["function"]
        declarations.append({
            "name": fn["name"],
            "description": fn.get("description", ""),
            "parameters": fn.get("parameters", {"type": "object", "properties": {}}),
        })
    return declarations


def _extract_result(data: dict):
    try:
        candidate = data["candidates"][0]
        parts = candidate["content"]["parts"]
    except (KeyError, IndexError) as e:
        raise GeminiError(f"Unexpected Gemini response shape: {e} -- raw: {json.dumps(data)[:300]}")

    calls = [{"type": "function_call", "name": p["functionCall"]["name"], "args": p["functionCall"].get("args", {})}
             for p in parts if "functionCall" in p]
    if calls:
        # the first call keeps the old shape; "calls" carries every call when Gemini asked for several at once
        return {**calls[0], "calls": calls} if len(calls) > 1 else calls[0]

    text = "".join(p.get("text", "") for p in parts)
    if not text:
        raise GeminiError("Gemini returned no text and no function call.")
    return {"type": "text", "content": text}


def ask_gemini(system_message: str, conversation_messages: list, tools_schema: list = None, use_search_grounding: bool = False, timeout: int = 30) -> dict:
    if os.environ.get("FAZE_GEMINI_DISABLED") == "1":
        raise GeminiError("Gemini layer is disabled in config (gemini_enabled=false).")
    api_key = os.environ.get("GEMINI_API_KEY")
    if not api_key:
        raise GeminiError("GEMINI_API_KEY is not set in the environment.")

    if tools_schema and use_search_grounding:
        raise GeminiError("Gemini does not support function-calling tools and search grounding in the same request.")

    body = {"contents": _to_gemini_contents(conversation_messages)}
    if system_message:
        body["systemInstruction"] = {"parts": [{"text": system_message}]}
    if tools_schema:
        body["tools"] = [{"functionDeclarations": _tools_to_function_declarations(tools_schema)}]
    if use_search_grounding:
        body["tools"] = [{"google_search": {}}]

    response = _post(f"{_base_url(use_search_grounding)}:generateContent",
                     {"Content-Type": "application/json", "x-goog-api-key": api_key}, body, timeout)

    if response.status_code != 200:
        raise _http_error(response)

    return _extract_result(response.json())


def ask_media(prompt: str, data: bytes, mime: str, timeout: float = 60.0) -> str:
    """Ask Gemini about an image or a sound clip. Returns its text answer."""
    import base64
    if os.environ.get("FAZE_GEMINI_DISABLED") == "1":
        raise GeminiError("Gemini layer is disabled in config (gemini_enabled=false).")
    api_key = os.environ.get("GEMINI_API_KEY")
    if not api_key:
        raise GeminiError("GEMINI_API_KEY is not set in the environment.")
    body = {"contents": [{"parts": [{"text": prompt}, {"inlineData": {"mimeType": mime, "data": base64.b64encode(data).decode()}}]}]}
    response = _post(f"{_base_url()}:generateContent", {"Content-Type": "application/json", "x-goog-api-key": api_key}, body, timeout)
    if response.status_code != 200:
        raise _http_error(response)
    try:
        parts = response.json()["candidates"][0]["content"]["parts"]
    except (KeyError, IndexError, ValueError):
        return ""
    return "".join(p.get("text", "") for p in parts).strip()


TRANSCRIBE_PROMPT = ("Transcribe the speech in this audio word for word. Reply with only the words spoken, "
                     "nothing else. If there is no clear speech, reply with nothing.")


def transcribe_audio(wav: bytes, timeout: float = 12.0) -> str:
    """Speech to text through Gemini (16 kHz mono WAV in). Empty string when nobody spoke."""
    import base64
    if os.environ.get("FAZE_GEMINI_DISABLED") == "1":
        raise GeminiError("Gemini layer is disabled in config (gemini_enabled=false).")
    api_key = os.environ.get("GEMINI_API_KEY")
    if not api_key:
        raise GeminiError("GEMINI_API_KEY is not set in the environment.")
    body = {"contents": [{"parts": [{"text": TRANSCRIBE_PROMPT},
                                    {"inlineData": {"mimeType": "audio/wav", "data": base64.b64encode(wav).decode()}}]}],
            "generationConfig": {"temperature": 0}}
    try:
        response = requests.post(f"{_base_url()}:generateContent", headers={"Content-Type": "application/json", "x-goog-api-key": api_key},
                                 json=body, timeout=timeout)
    except requests.RequestException as e:
        raise GeminiError(f"Network error calling Gemini: {e}")
    if response.status_code != 200:
        raise _http_error(response)
    try:
        parts = response.json()["candidates"][0]["content"]["parts"]
    except (KeyError, IndexError, ValueError):
        return ""
    return "".join(p.get("text", "") for p in parts).strip()


def ask_gemini_stream(system_message: str, conversation_messages: list, tools_schema: list = None, timeout: int = 60):
    api_key = os.environ.get("GEMINI_API_KEY")
    if not api_key:
        raise GeminiError("GEMINI_API_KEY is not set in the environment.")

    body = {"contents": _to_gemini_contents(conversation_messages)}
    if system_message:
        body["systemInstruction"] = {"parts": [{"text": system_message}]}
    if tools_schema:
        body["tools"] = [{"functionDeclarations": _tools_to_function_declarations(tools_schema)}]

    response = _post(f"{_base_url()}:streamGenerateContent?alt=sse",
                     {"Content-Type": "application/json", "x-goog-api-key": api_key}, body, timeout, stream=True)

    if response.status_code != 200:
        raise _http_error(response)

    got_anything = False
    for line in response.iter_lines(decode_unicode=True):
        if not line or not line.startswith("data: "):
            continue
        try:
            chunk = json.loads(line[len("data: "):])
        except json.JSONDecodeError:
            continue

        for cand in chunk.get("candidates", []):
            for part in cand.get("content", {}).get("parts", []):
                if "functionCall" in part:
                    fc = part["functionCall"]
                    got_anything = True
                    yield {"type": "function_call", "name": fc["name"], "args": fc.get("args", {})}
                    return
                if "text" in part:
                    got_anything = True
                    yield {"type": "text_chunk", "content": part["text"]}

    if not got_anything:
        raise GeminiError("Gemini streamed no text and no function call.")


if __name__ == "__main__":
    try:
        result = ask_gemini(
            system_message="You are a helpful assistant.",
            conversation_messages=[{"role": "user", "content": "Say hello in exactly five words."}],
        )
        print("SUCCESS (non-streaming):", result)
    except GeminiError as e:
        print("FAILED (non-streaming):", e)

    try:
        print("\nSUCCESS (streaming):", end=" ", flush=True)
        for chunk in ask_gemini_stream(
            system_message="You are a helpful assistant.",
            conversation_messages=[{"role": "user", "content": "Count from 1 to 5."}],
        ):
            if chunk["type"] == "text_chunk":
                print(chunk["content"], end="", flush=True)
        print()
    except GeminiError as e:
        print("\nFAILED (streaming):", e)
