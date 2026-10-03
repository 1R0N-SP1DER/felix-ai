"""
felix-ai Core — orchestrator.py
Phase 4: real tool-calling loop, now handling MULTIPLE tool calls in a
single turn.

Routing order per message:
  1. direct_tool_router (regex)  -- free, instant, known-safe phrasings
  2. needle_router (Needle model) -- catches tool-shaped requests regex
     missed; only trusted above a confidence threshold
  3. LFM2.5 tool-calling loop      -- fallback for everything else
     (will become the Gemini handoff once wired in)
"""

import re
import json
import requests
import subprocess
import time
import os
from action_log import log_action
from config_loader import load_config, get_llm_url
from memory_store import init_db, log_message
from prompt_assembler import build_system_message
from tool_schema import validate_tool_call, ToolCallValidationError, TOOL_REGISTRY
from permission_policy import get_permission_tier, UnknownToolError
from tool_executor import execute_tool, ToolExecutionError
from direct_tool_router import try_direct_route
from action_log import log_action
from needle_router import ask_needle
from gemini_client import ask_gemini, GeminiError
from gemini_tools import build_gemini_tools_schema

GEMINI_TOOLS_SCHEMA = build_gemini_tools_schema()
LOCAL_TOOLS_SCHEMA = GEMINI_TOOLS_SCHEMA  # same OpenAI-style shape works for llama-server

DEVICE = "phone"
MAX_TOOL_CALLS_PER_TURN = 5

TOOL_INSTRUCTIONS = """
TOOLS (call ONLY if the user is asking for one of these things right now):
- get_current_datetime() : no params
- get_battery_status() : no params
- read_clipboard() : no params
- write_clipboard(text) : text=string to save
- vibrate() : no params
- set_volume(level) : level=0-15
- delegate_to_gemini(request) : ask a vastly more capable model for help
- search_web(query) : get a real, current answer from the internet
- enable_thinking_mode() : reason step by step instead of answering fast

RULES:
- No tool needed? Just reply normally, no tool call.
- Never invent a time, battery %, or clipboard content without a real tool result THIS turn.
- You are a small, fast model. You are NOT as capable as Gemini, which
  is vastly larger and more reliable. If a question needs real
  knowledge, judgment, writing, or anything you are not fully
  confident about, call delegate_to_gemini instead of guessing.
- Never state a fact, number, or current event as if you know it
  unless it came from a real tool result THIS turn. If you are not
  sure, say so, or delegate.
- For anything current, recent, or about the real world right now
  (news, someone's latest activity, prices), call search_web. Never
  answer these from memory.
- Only call enable_thinking_mode if delegate_to_gemini just failed
  (Gemini unreachable) and the task is still too hard to answer
  quickly and correctly.
"""

def _log_action(actor: str, tool_name: str, args: dict):
    print(f"[{actor} -> {tool_name}]: {args}")


def extract_tool_calls(reply: str) -> list:
    calls = []
    matches = re.findall(
        r"<\|tool_call_start\|>\s*\[(.*?)\]\s*<\|tool_call_end\|>",
        reply,
        re.DOTALL
    )
    for match in matches:
        call_strings = re.findall(r"(\w+)\((.*?)\)", match)
        for func_name, args_str in call_strings:
            parameters = {}
            if args_str.strip():
                arg_pairs = re.findall(r'(\w+)\s*=\s*("(?:[^"\\]|\\.)*"|\d+\.?\d*|true|false)', args_str)
                for key, value in arg_pairs:
                    value = value.strip()
                    if value.startswith('"') and value.endswith('"'):
                        value = value[1:-1]
                    elif value.lower() == "true":
                        value = True
                    elif value.lower() == "false":
                        value = False
                    else:
                        try:
                            value = int(value)
                        except ValueError:
                            try:
                                value = float(value)
                            except ValueError:
                                pass
                    parameters[key] = value
            calls.append({"tool": func_name, "parameters": parameters})
    return calls

class Conversation:
    def __init__(self, config: dict):
        self.config = config
        self.url = get_llm_url(config)
        self.forced_model = None
        self.forced_remaining = 0
        self.lfm_process = None
        system_message = build_system_message("hi") + "\n" + TOOL_INSTRUCTIONS
        self.messages = [{"role": "system", "content": system_message}]

    def _trim_history(self):
        """Keep the system message plus the most recent messages, so
        the conversation never grows unbounded and never re-approaches
        the context ceiling no matter how long the session runs."""
        MAX_HISTORY_MESSAGES = 20
        system_msg = self.messages[0]
        rest = self.messages[1:]
        if len(rest) > MAX_HISTORY_MESSAGES:
            rest = rest[-MAX_HISTORY_MESSAGES:]
        self.messages = [system_msg] + rest

    def _call_llm(self, allow_thinking: bool = False, tools: list = None) -> dict:
        """Streams the reply token by token, printing content live and
        printing reasoning_content (if any) prefixed so it's clearly
        labeled as thinking, not the final answer. Returns a dict:
        {"content": str, "tool_calls": list or None} so the caller can
        tell a plain reply apart from a tool-call request."""
        self._trim_history()
        payload = {
            "messages": self.messages,
            "chat_template_kwargs": {"enable_thinking": allow_thinking},
            "stream": True,
            "temperature": 0.3,
            "top_p": 0.9,
            "repeat_penalty": 1.1,
            "max_tokens": 200,
        }
        if tools:
            payload["tools"] = tools

        try:
            response = requests.post(self.url, json=payload, timeout=120, stream=True)
            response.raise_for_status()
        except (requests.exceptions.RequestException, Exception) as e:
            # Catching Exception to handle http.client.IncompleteRead which might wrap or propagate
            print(f"[orchestrator] Error connecting/streaming: {e}")
            return {"content": f"I'm having trouble connecting to my local brain right now (Error: {type(e).__name__}). Try again in a moment.", "tool_calls": None}
            
        response.encoding = "utf-8"

        full_content = ""
        tool_calls_acc = {}
        shown_thinking_label = False

        for line in response.iter_lines(decode_unicode=True):
            if not line or not line.startswith("data: "):
                continue
            raw = line[len("data: "):]
            if raw.strip() == "[DONE]":
                break
            try:
                chunk = json.loads(raw)
            except json.JSONDecodeError:
                continue

            delta = chunk.get("choices", [{}])[0].get("delta", {})

            reasoning_piece = delta.get("reasoning_content")
            if reasoning_piece:
                if not shown_thinking_label:
                    print("[thinking] ", end="", flush=True)
                    shown_thinking_label = True
                print(reasoning_piece, end="", flush=True)

            content_piece = delta.get("content")
            if content_piece:
                if shown_thinking_label:
                    print("\n[answer] ", end="", flush=True)
                    shown_thinking_label = False
                print(content_piece, end="", flush=True)
                full_content += content_piece

            for tc in delta.get("tool_calls", []) or []:
                idx = tc.get("index", 0)
                if idx not in tool_calls_acc:
                    tool_calls_acc[idx] = {"id": tc.get("id", ""), "name": "", "arguments": ""}
                fn = tc.get("function", {})
                if fn.get("name"):
                    tool_calls_acc[idx]["name"] += fn["name"]
                if fn.get("arguments"):
                    tool_calls_acc[idx]["arguments"] += fn["arguments"]

        print()
        tool_calls = list(tool_calls_acc.values()) if tool_calls_acc else None
        return {"content": full_content, "tool_calls": tool_calls}

    def _run_direct_call(self, direct_call: dict) -> str:
        """Validate, permission-check, execute a tool call from the
        regex router or Needle, then narrate the raw result through
        LFM2.5 so it reads as natural Faze conversation, not a data
        dump. LFM2.5 is only ever asked to REPHRASE a real, already-
        known result -- never to originate or guess at the fact
        itself, so this can't reintroduce the old hallucination bug."""
        tool_name = direct_call["tool"]
        try:
            validate_tool_call(direct_call)
            tier = get_permission_tier(tool_name, DEVICE, self.config)
            if tier == "block":
                return f"[Tool '{tool_name}' is blocked and cannot be run.]"
            if tier == "confirm":
                print(f"[Would ask for confirmation to run '{tool_name}' — auto-proceeding for now]")
            start_time = time.time()
            raw_result = execute_tool(tool_name, direct_call.get("parameters", {}))
            ms = int((time.time() - start_time) * 1000)
            log_action("regex", tool_name, direct_call.get("parameters", {}), raw_result, ms)
        except (ToolCallValidationError, UnknownToolError, ToolExecutionError) as e:
            log_action("regex", tool_name, direct_call.get("parameters", {}), f"Error: {e}", int((time.time() - start_time) * 1000))
            return f"[Tool error: {e}]"

        result = self._narrate_tool_result(tool_name, raw_result)
        # P1 template check for direct calls (regex-routed tools)
        template = TOOL_REGISTRY.get(tool_name, {}).get("template")
        if template:
            # Simple format based on regex-routed tool results
            if tool_name == "get_battery_status":
                # Expect raw_result to contain battery % and status
                # Example: battery_level: 19%, charging: True
                # Parse percent and status
                pct = re.search(r"battery_level:\s*(\d+)", raw_result)
                chg = re.search(r"charging:\s*(True|False)", raw_result)
                pct_val = pct.group(1) if pct else "unknown"
                chg_status = "charging" if (chg and chg.group(1) == "True") else "discharging"
                result = template.format(percent=pct_val, status=chg_status)
            elif tool_name == "get_current_datetime":
                # Example raw_result format (assume ISO or similar)
                # Let's use simple formatting or pass through if too complex
                # For simplicity, if we cannot parse, we just use raw_result.
                result = template.format(time=raw_result, date="")
            elif tool_name == "vibrate":
                result = template.format()
            elif tool_name == "read_clipboard":
                result = template.format(text=raw_result)
            elif tool_name == "write_clipboard":
                result = template.format()
            elif tool_name == "set_volume":
                # parameters has level
                level = direct_call.get("parameters", {}).get("level", "unknown")
                result = template.format(level=level)
        
        if not result:
            print(f"[{tool_name}]: <empty result>")
            log_message(DEVICE, "system", f"Fallback triggered for empty tool result: {tool_name}")
            return f"[{tool_name} returned no result.]"
        return result

    def _narrate_tool_result(self, tool_name: str, raw_result: str) -> str:
        """Ask LFM2.5 to rephrase a real tool result naturally, using a
        tiny persona prompt instead of the full memory system message
        so a 230M model can answer fast. Falls back to the raw result
        on any failure, and prints why so failures are visible."""
        name = self.config.get('assistant_name', 'Faze')
        persona = (
            f'You are {name}, a casual, friendly assistant talking to '
            f'your friend Felix. Reply in one short, natural sentence.'
        )
        narration_prompt = (
            f'Tool result: {raw_result}\n'
            f'Tell Felix this in your own casual words. Use only the '
            f'facts in the tool result. Do not add anything new.'
        )
        narration_messages = [
            {'role': 'system', 'content': persona},
            {'role': 'user', 'content': narration_prompt},
        ]
        try:
            payload = {
                'messages': narration_messages,
                'max_tokens': 60,
                'chat_template_kwargs': {'enable_thinking': False},
                "temperature": 0.3,
                "top_p": 0.9,
                "repeat_penalty": 1.1,
            }
            response = requests.post(self.url, json=payload, timeout=30)
            response.raise_for_status()
            narrated = response.json()['choices'][0]['message']['content'].strip()
            return narrated if narrated else raw_result
        except (requests.RequestException, KeyError, IndexError) as e:
            print(f'[narration failed, using raw result]: {e}')
            return raw_result

    def send(self, user_message: str) -> str:
        """
        Offline-first: regex, then Needle, then the local model with
        real tool-calling. Gemini is now the LAST resort.
        """
        self.messages.append({"role": "user", "content": user_message})
        log_message(DEVICE, "user", user_message)

        direct_call = try_direct_route(user_message)
        route_label = None
        if direct_call is not None:
            route_label = "regex"
        else:
            needle_call = ask_needle(user_message)
            if needle_call is not None:
                direct_call = needle_call
                route_label = "needle"

        if direct_call is not None:
            print(f"[Routed via {route_label}]: {direct_call}")
            reply = self._run_direct_call(direct_call)
            if not reply:
                reply = f"[{route_label} call completed but returned no result.]"
                print(reply)
            else:
                print(reply)
            self.messages.append({"role": "assistant", "content": reply})
            log_message(DEVICE, "assistant", reply)
            return reply

        # Handle forced model routing
        if self.forced_model:
            model_to_use = self.forced_model
            if self.forced_remaining > 0:
                self.forced_remaining -= 1
                if self.forced_remaining == 0:
                    self.forced_model = None
                    print("Forced model expired, reverting to auto")
            
            try:
                if model_to_use == "gemini":
                    reply = self._call_gemini_with_tools()
                elif model_to_use == "qwen":
                    reply = self._call_local_with_tools()
                elif model_to_use == "lfm":
                    return "[LFM mode not yet implemented]"
                else:
                    return f"[Unknown forced model: {model_to_use}]"
            except Exception as e:
                print(f"[Forced model {model_to_use} failed]: {e}")
                return f"[Forced model {model_to_use} failed: {e}]"
            
            self.messages.append({"role": "assistant", "content": reply})
            log_message(DEVICE, "assistant", reply)
            return reply

        try:
            reply = self._call_local_with_tools()
        except Exception as e:
            print(f"[Local model failed, falling back to Gemini]: {e}")
            try:
                reply = self._call_gemini_with_tools()
            except GeminiError as ge:
                print(f"[Gemini also unavailable]: {ge}")
                reply = ("Sorry, I couldn't reach either my local model or "
                         "Gemini right now — try again in a moment.")

        self.messages.append({"role": "assistant", "content": reply})
        log_message(DEVICE, "assistant", reply)
        return reply

    def _call_local_with_tools(self, max_rounds: int = 3, allow_thinking: bool = False) -> str:
        """
        Calls the local model with the real tool schema. Thinking off
        by default. If the model calls enable_thinking_mode, this
        re-runs the SAME turn with allow_thinking=True instead of
        treating it as a normal tool result. Raises on failure so
        send() can fall back to Gemini.
        """
        local_history = list(self.messages)

        for _ in range(max_rounds):
            # DEBUG: Log the messages sent to the model on each round
            # print(f"[DEBUG] Round {_} messages: {[{'role': m['role']} for m in local_history if 'role' in m]}")
            result = self._call_llm(allow_thinking=allow_thinking, tools=LOCAL_TOOLS_SCHEMA)
            # DEBUG: Log raw response
            # print(f"[DEBUG] Raw result: {result}")

            if not result["tool_calls"]:
                if not result["content"]:
                    # DEBUG: print(f"[DEBUG] Empty response detected!")
                    raise RuntimeError("Local model returned an empty response.")
                return result["content"]

            # If we get here, it means we have tool calls.
            # We must ensure we don't return an empty reply in the next loop iteration if the model gives no content after the tool call.
            
            thinking_requested = False

            for tc in result["tool_calls"]:
                tool_name = tc["name"]
                try:
                    args = json.loads(tc["arguments"]) if tc["arguments"] else {}
                except json.JSONDecodeError:
                    args = {}

                _log_action("qwen", tool_name, args)
                call = {"tool": tool_name, "parameters": args}
                try:
                    validate_tool_call(call)
                    tier = get_permission_tier(tool_name, DEVICE, self.config)
                    if tier == "block":
                        tool_result = f"Tool '{tool_name}' is blocked and cannot be run."
                    else:
                        if tier == "confirm":
                            print(f"[Would ask for confirmation to run '{tool_name}' — auto-proceeding for now]")
                        tool_result = execute_tool(tool_name, args)
                except (ToolCallValidationError, UnknownToolError, ToolExecutionError) as e:
                    # Provide clearer error surfacing than just the exception text
                    tool_result = f"Error running {tool_name}: {str(e)[:100]}"

                if tool_name == "enable_thinking_mode" and tool_result == "THINKING_MODE_REQUESTED":
                    thinking_requested = True
                    continue

                local_history.append({
                    "role": "assistant",
                    "content": "",
                    "tool_calls": [{"id": tc["id"], "type": "function", "function": {"name": tool_name, "arguments": tc["arguments"]}}],
                })
                local_history.append({"role": "tool", "tool_call_id": tc["id"], "content": tool_result})

            if thinking_requested:
                allow_thinking = True
                continue
            
            # Safety net: If content is still empty after tool calls, try to return something useful
            if not result.get("content", "").strip():
                # Try to use the last tool result as the reply if we are out of rounds or content is empty
                # For now, just return a default fallback string to avoid silence.
                return "I completed the action you asked for."

            self.messages = local_history

        raise RuntimeError("Local model kept calling tools without giving a final answer.")

    def _call_gemini_with_tools(self, max_rounds: int = 3) -> str:
        """Calls Gemini with the real tool schema -- now the fallback."""
        system_message = self.messages[0]["content"]
        gemini_history = list(self.messages[1:])

        for _ in range(max_rounds):
            result = ask_gemini(system_message, gemini_history, tools_schema=GEMINI_TOOLS_SCHEMA)

            if result["type"] == "text":
                print(f"[Routed via gemini]: {result['content']!r}")
                return result["content"]

            tool_name = result["name"]
            args = result["args"]
            _log_action("gemini", tool_name, args)

            call = {"tool": tool_name, "parameters": args}
            try:
                validate_tool_call(call)
                tier = get_permission_tier(tool_name, DEVICE, self.config)
                if tier == "block":
                    tool_result = f"Tool '{tool_name}' is blocked and cannot be run."
                else:
                    if tier == "confirm":
                        print(f"[Would ask for confirmation to run '{tool_name}' — auto-proceeding for now]")
                    tool_result = execute_tool(tool_name, args)
            except (ToolCallValidationError, UnknownToolError, ToolExecutionError) as e:
                tool_result = f"Tool error: {e}"

            gemini_history.append({"role": "assistant", "content": f"[called {tool_name}]"})
            gemini_history.append({"role": "tool", "name": tool_name, "content": tool_result})

        raise GeminiError("Gemini kept calling tools without giving a final answer.")

    def _execute_and_record(self, tool_calls: list):
        result_lines = []

        for tool_call in tool_calls:
            tool_name = tool_call.get("tool", "<unknown>")

            try:
                validate_tool_call(tool_call)
            except ToolCallValidationError as e:
                result_lines.append(f"[{tool_name}] rejected: {e}")
                continue

            try:
                tier = get_permission_tier(tool_name, DEVICE, self.config)
            except UnknownToolError as e:
                result_lines.append(f"[{tool_name}] rejected: {e}")
                continue

            if tier == "block":
                result_lines.append(f"[{tool_name}] blocked, cannot run.")
                continue

            if tier == "confirm":
                print(f"[Would ask for confirmation to run '{tool_name}' — auto-proceeding for now]")

            try:
                result = execute_tool(tool_name, tool_call.get("parameters", {}))
                result_lines.append(f"[{tool_name}]: {result}")
            except ToolExecutionError as e:
                result_lines.append(f"[{tool_name}] execution failed: {e}")

        combined = "\n".join(result_lines)
        self.messages.append({
            "role": "user",
            "content": f"Tool results:\n{combined}\nNow answer the user's original question using this real data, in plain text."
        })


if __name__ == "__main__":
    init_db()
    config = load_config()
    convo = Conversation(config)

    print(f"felix-ai Core — {config['assistant_name']}")
    print(f"Talking to: {convo.url}")
    print("Type 'quit' to exit.\n")

    while True:
        user_input = input("You: ").strip()
        if user_input.lower() in ("quit", "exit"):
            break
        if not user_input:
            continue
        
        # Handle /model command
        if user_input.startswith("/model"):
            parts = user_input.split()
            if len(parts) == 1:
                print(f"Current mode: {convo.forced_model or 'auto'}")
                continue
            
            mode = parts[1].lower()
            if mode == "auto":
                convo.forced_model = None
                convo.forced_remaining = 0
                print("Switched to auto routing")
            elif mode in ("qwen", "gemini", "lfm"):
                convo.forced_model = mode
                if len(parts) > 2:
                    try:
                        convo.forced_remaining = int(parts[2])
                    except ValueError:
                        print("Usage: /model gemini|qwen|lfm [n]")
                        continue
                else:
                    convo.forced_remaining = 0
                print(f"Forcing {mode} for {'next ' + str(convo.forced_remaining) + ' turns' if convo.forced_remaining else 'session'}")
            else:
                print("Invalid mode. Use auto, qwen, gemini, or lfm")
            continue

        print(f"{config['assistant_name']}: ", end="", flush=True)
        reply = convo.send(user_input)
        print()
