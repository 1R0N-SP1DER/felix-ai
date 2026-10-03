"""
felix-ai Core -- orchestrator.py

Routing, per message:
  1. Deterministic code first: explicit intents ("ask gemini ...",
     "search ..."), then the regex router. Tool results are turned into
     replies by templates in tool_replies.py -- no model narration, so
     real data can't be mangled.
  2. Needle (auto mode only).
  3. Forced backend (/model), or auto: local Qwen with tools, falling
     back to Gemini.

Rules this file keeps:
  - No silent failures: every failure ends in a one-line message that
    names the real cause (never a key or a response body).
  - Retrieved memories ride on the user turn, never the system prompt,
    so the cached prompt prefix stays byte-identical turn to turn.
  - A forced backend never silently falls back to another one.
"""

import atexit
import http.client
import json
import os
import subprocess
import time

import requests

from action_log import log_action
from config_loader import load_config, get_llm_url
from memory_store import init_db, log_message
from memory_recall import setup_fts
from prompt_assembler import build_static_system_message, build_turn_context
from tool_schema import validate_tool_call, ToolCallValidationError
from permission_policy import get_permission_tier, UnknownToolError
from tool_executor import execute_tool, ToolExecutionError
from tool_replies import format_reply, summarize_error, numbers_preserved
from direct_tool_router import try_direct_route
from intents import match_intent
from needle_router import ask_needle
from gemini_client import ask_gemini, GeminiError
from gemini_tools import build_gemini_tools_schema, build_local_tools_schema

GEMINI_TOOLS_SCHEMA = build_gemini_tools_schema()
LOCAL_TOOLS_SCHEMA = build_local_tools_schema()

DEVICE = "phone"
MAX_TOOL_CALLS_PER_TURN = 5
MAX_HISTORY_MESSAGES = 20
FORCEABLE_MODELS = ("qwen", "gemini", "lfm")

# Tools where the model's paraphrase of the request is replaced by the
# user's exact words (a 0.8B model turned "camero" into "camera").
RAW_TEXT_TOOLS = {"delegate_to_gemini": "request", "search_web": "query"}

TOOL_INSTRUCTIONS = """
TOOLS (call ONLY if the user is asking for one of these things right now):
- get_current_datetime() : no params
- get_battery_status() : no params
- get_wifi_status() : no params
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

GEMINI_TOOL_NOTE = (
    "\nYou can call these tools: "
    + ", ".join(t["function"]["name"] for t in GEMINI_TOOLS_SCHEMA)
    + ". Call a tool only when the user asks for it right now. If a tool "
      "fails, do not retry it: answer from your own knowledge or say you "
      "could not check. Never invent tool results.\n"
)


class LocalModelError(Exception):
    """A local llama-server call failed (connection, HTTP or mid-stream)."""


class RouteError(Exception):
    """Every backend in the chain failed; the message is user-facing."""


def _local_error(err, url: str) -> str:
    if isinstance(err, requests.exceptions.ConnectionError):
        return f"can't reach llama-server at {url}"
    if isinstance(err, requests.exceptions.Timeout):
        return "llama-server timed out"
    return summarize_error(err)


class Conversation:
    def __init__(self, config: dict):
        self.config = config
        self.url = get_llm_url(config)
        self.forced_model = None
        self.forced_remaining = 0
        self.lfm_process = None
        self.last_reply_streamed = False
        self._turn_context = ""
        self._last_user_message = ""
        if config.get("gemini_enabled", True) is False:
            os.environ["FAZE_GEMINI_DISABLED"] = "1"
        self.system_static = build_static_system_message()
        self.messages = [{"role": "system",
                          "content": self.system_static + "\n" + TOOL_INSTRUCTIONS}]
        atexit.register(self.stop_lfm)

    # ------------------------------------------------------------------
    # history / request building
    # ------------------------------------------------------------------

    def _trim_history(self):
        system_msg = self.messages[0]
        rest = self.messages[1:]
        if len(rest) > MAX_HISTORY_MESSAGES:
            rest = rest[-MAX_HISTORY_MESSAGES:]
        self.messages = [system_msg] + rest

    def _request_messages(self) -> list:
        """History for a model request, with this turn's retrieved
        memories attached to the newest user message."""
        self._trim_history()
        msgs = list(self.messages)
        if self._turn_context and msgs and msgs[-1]["role"] == "user":
            msgs[-1] = {"role": "user",
                        "content": f"{self._turn_context}\n\n{msgs[-1]['content']}"}
        return msgs

    # ------------------------------------------------------------------
    # local model (llama-server)
    # ------------------------------------------------------------------

    def _call_llm(self, messages: list, allow_thinking: bool = False,
                  tools: list = None, url: str = None) -> dict:
        """Streams a reply, printing tokens live. Returns
        {"content", "tool_calls", "streamed"}. Raises LocalModelError on
        any connection, HTTP or mid-stream failure."""
        url = url or self.url
        payload = {
            "messages": messages,
            "chat_template_kwargs": {"enable_thinking": allow_thinking},
            "stream": True,
            "temperature": 0.3,
            "top_p": 0.9,
            "repeat_penalty": 1.1,
            "max_tokens": 1500 if allow_thinking else 200,
        }
        if tools:
            payload["tools"] = tools

        try:
            response = requests.post(url, json=payload, timeout=120, stream=True)
            response.raise_for_status()
        except requests.exceptions.RequestException as e:
            raise LocalModelError(_local_error(e, url)) from e
        response.encoding = "utf-8"

        full_content = ""
        tool_calls_acc = {}
        shown_thinking_label = False
        streamed = False

        try:
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

                choices = chunk.get("choices") or [{}]
                delta = choices[0].get("delta", {})

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
                    streamed = True

                for tc in delta.get("tool_calls", []) or []:
                    idx = tc.get("index", 0)
                    if idx not in tool_calls_acc:
                        tool_calls_acc[idx] = {"id": tc.get("id", ""), "name": "", "arguments": ""}
                    fn = tc.get("function", {})
                    if fn.get("name"):
                        tool_calls_acc[idx]["name"] += fn["name"]
                    if fn.get("arguments"):
                        tool_calls_acc[idx]["arguments"] += fn["arguments"]
        except (requests.exceptions.RequestException, http.client.HTTPException, OSError) as e:
            if streamed:
                print()
            raise LocalModelError(f"stream interrupted ({type(e).__name__})") from e

        if streamed:
            print()
        tool_calls = list(tool_calls_acc.values()) if tool_calls_acc else None
        return {"content": full_content, "tool_calls": tool_calls, "streamed": streamed}

    def _call_local_with_tools(self, max_rounds: int = 3, allow_thinking: bool = False) -> str:
        """One model call picks a tool (or just answers). Tool results are
        turned into replies in code. enable_thinking_mode re-runs the
        same turn with thinking on."""
        for _ in range(max_rounds):
            result = self._call_llm(self._request_messages(), allow_thinking,
                                    tools=LOCAL_TOOLS_SCHEMA)
            calls = result["tool_calls"]

            if not calls:
                text = (result["content"] or "").strip()
                if not text:
                    raise LocalModelError("local model returned an empty reply")
                self.last_reply_streamed = result["streamed"]
                return text

            replies = []
            thinking_requested = False
            for tc in calls[:MAX_TOOL_CALLS_PER_TURN]:
                name = tc["name"]
                try:
                    args = json.loads(tc["arguments"]) if tc["arguments"] else {}
                    if not isinstance(args, dict):
                        args = {}
                except json.JSONDecodeError:
                    args = {}

                if name == "enable_thinking_mode":
                    log_action("qwen", name, args, "re-running with thinking on", 0)
                    thinking_requested = True
                    continue

                extra = None
                if name in RAW_TEXT_TOOLS:
                    key = RAW_TEXT_TOOLS[name]
                    model_arg = args.get(key)
                    args = {key: self._last_user_message}
                    if model_arg != self._last_user_message:
                        extra = f"model paraphrase replaced with raw text: {model_arg!r}"
                replies.append(self._run_direct_call({"tool": name, "parameters": args},
                                                     "qwen", extra))

            if replies:
                self.last_reply_streamed = False
                return "\n".join(replies)
            if thinking_requested:
                allow_thinking = True
                continue
            raise LocalModelError("local model requested no usable tool")

        raise LocalModelError("local model kept requesting tools without answering")

    # ------------------------------------------------------------------
    # tools
    # ------------------------------------------------------------------

    def _execute_tool(self, name: str, args: dict, layer: str, extra: str = None):
        """Validate, permission-check and run one tool. Returns
        (ok, text): the raw result on success, a short real error on
        failure. Always logged."""
        call = {"tool": name, "parameters": args}
        start = time.time()
        try:
            validate_tool_call(call)
            tier = get_permission_tier(name, DEVICE, self.config)
            if tier == "block":
                raise ToolExecutionError(f"'{name}' is blocked by permission policy")
            if tier == "confirm":
                print(f"[Would ask for confirmation to run '{name}' -- auto-proceeding for now]")
            result = execute_tool(name, args)
            ok = True
        except (ToolCallValidationError, UnknownToolError, ToolExecutionError) as e:
            ok, result = False, summarize_error(e)
            prefix = f"{type(e).__name__}: "
            if result.startswith(prefix):
                result = result[len(prefix):]
        except Exception as e:  # executor bug or missing termux binary
            ok, result = False, summarize_error(e)
        ms = int((time.time() - start) * 1000)
        log_action(layer, name, args, result if ok else f"ERROR {result}", ms, extra)
        return ok, result

    def _tool_reply(self, name: str, args: dict, ok: bool, result: str) -> str:
        if not ok:
            return f"{name} failed: {result}"
        reply = format_reply(name, result, args)
        if reply is None:
            reply = f"{name}: done."
        if self.config.get("narrate_tool_results") and name not in ("search_web", "delegate_to_gemini"):
            narrated = self._narrate_tool_result(result)
            if narrated and numbers_preserved(result, narrated):
                return narrated
        return reply

    def _run_direct_call(self, call: dict, layer: str, extra: str = None) -> str:
        name = call["tool"]
        args = call.get("parameters", {})
        ok, result = self._execute_tool(name, args, layer, extra)
        return self._tool_reply(name, args, ok, result)

    def _narrate_tool_result(self, raw_result: str):
        """Optional (config narrate_tool_results): rephrase a real result
        with a minimal prompt and no history. Returns None on failure."""
        messages = [
            {"role": "system", "content": "Restate this tool result in one casual sentence. Keep every number exactly."},
            {"role": "user", "content": str(raw_result)},
        ]
        try:
            payload = {"messages": messages, "max_tokens": 60, "temperature": 0.3,
                       "chat_template_kwargs": {"enable_thinking": False}}
            response = requests.post(self.url, json=payload, timeout=30)
            response.raise_for_status()
            return response.json()["choices"][0]["message"]["content"].strip() or None
        except (requests.exceptions.RequestException, KeyError, IndexError, ValueError):
            return None

    # ------------------------------------------------------------------
    # Gemini
    # ------------------------------------------------------------------

    def _call_gemini_with_tools(self, max_rounds: int = 2) -> str:
        """Gemini picks tools from its own schema (never delegate_to_gemini).
        Identical repeated calls are not re-run, tool errors go back as
        text, and the last request is sent with tools off so it must
        answer."""
        system_message = self.system_static + GEMINI_TOOL_NOTE
        history = [m for m in self._request_messages()[1:]
                   if m["role"] in ("user", "assistant")]
        allowed = {t["function"]["name"] for t in GEMINI_TOOLS_SCHEMA}
        seen = set()

        for round_no in range(max_rounds + 1):
            final_round = round_no == max_rounds
            start = time.time()
            result = ask_gemini(system_message, history,
                                tools_schema=None if final_round else GEMINI_TOOLS_SCHEMA)
            if result["type"] == "text":
                log_action("gemini", "reply", {}, f"{len(result['content'])} chars",
                           int((time.time() - start) * 1000))
                return result["content"]

            name, args = result["name"], result["args"]
            key = (name, json.dumps(args, sort_keys=True, default=str))
            if name not in allowed:
                tool_text = f"Tool '{name}' is not available. Available: {', '.join(sorted(allowed))}."
                log_action("gemini", name, args, "ERROR not offered to Gemini", 0)
            elif key in seen:
                tool_text = "You already made this exact call. Do not repeat it; answer now with what you have."
                log_action("gemini", name, args, "duplicate call skipped", 0)
            else:
                seen.add(key)
                ok, res = self._execute_tool(name, args, "gemini")
                tool_text = res if ok else (
                    f"{name} failed: {res}. Do not retry; answer from your own "
                    f"knowledge or say you could not check.")

            history.append({"role": "assistant", "content": f"[called {name}]"})
            history.append({"role": "user", "content": f"[tool result from {name}] {tool_text}"})

        raise GeminiError("Gemini returned no final answer")

    # ------------------------------------------------------------------
    # LFM (second llama-server, chat only)
    # ------------------------------------------------------------------

    @staticmethod
    def _healthy(health_url: str) -> bool:
        try:
            return requests.get(health_url, timeout=2).status_code == 200
        except requests.exceptions.RequestException:
            return False

    def _ensure_lfm(self) -> str:
        lfm = self.config.get("lfm") or {}
        model = os.path.expanduser(lfm.get("model_path", ""))
        if not model or not os.path.isfile(model):
            raise LocalModelError("LFM not configured: set lfm.model_path in config/profile.json to the LFM2.5 .gguf file")
        port = int(lfm.get("port", 8081))
        url = f"http://127.0.0.1:{port}/v1/chat/completions"
        health = f"http://127.0.0.1:{port}/health"
        if self._healthy(health):
            return url

        binary = os.path.expanduser(lfm.get("server_bin", "~/llama.cpp/build/bin/llama-server"))
        if not os.path.isfile(binary):
            raise LocalModelError(f"llama-server binary not found at {binary}")
        log_path = os.path.expanduser(lfm.get("log", "~/felix-ai/llama-lfm.log"))
        os.makedirs(os.path.dirname(log_path), exist_ok=True)
        log_file = open(log_path, "ab")
        print("[lfm] starting server...")
        self.lfm_process = subprocess.Popen(
            [binary, "-m", model, "--port", str(port), "-c", str(lfm.get("ctx", 2048)),
             "-t", str(lfm.get("threads", 4)), "--host", "127.0.0.1"],
            stdout=log_file, stderr=subprocess.STDOUT)
        log_file.close()  # the child keeps its own copy of the descriptor
        deadline = time.time() + float(lfm.get("start_timeout", 60))
        while time.time() < deadline:
            code = self.lfm_process.poll()
            if code is not None:
                self.lfm_process = None
                raise LocalModelError(f"LFM server exited early (code {code}); see {log_path}")
            if self._healthy(health):
                return url
            time.sleep(0.5)
        self.stop_lfm()
        raise LocalModelError(f"LFM server not ready after {lfm.get('start_timeout', 60)}s; see {log_path}")

    def stop_lfm(self):
        proc = self.lfm_process
        self.lfm_process = None
        if proc is None or proc.poll() is not None:
            return
        proc.terminate()
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            proc.kill()

    def _call_lfm(self) -> str:
        url = self._ensure_lfm()
        recent = [m for m in self._request_messages()[1:]
                  if m["role"] in ("user", "assistant")][-6:]
        messages = [{"role": "system", "content": self.system_static}] + recent
        result = self._call_llm(messages, tools=None, url=url)
        text = (result["content"] or "").strip()
        if not text:
            raise LocalModelError("LFM returned an empty reply")
        self.last_reply_streamed = result["streamed"]
        return text

    # ------------------------------------------------------------------
    # /model
    # ------------------------------------------------------------------

    def handle_command(self, line: str):
        """Slash commands, typed by the user only. Returns the text to
        print, or None if `line` is not a command."""
        parts = line.strip().split()
        if not parts or not parts[0].startswith("/"):
            return None
        if parts[0].lower() != "/model":
            return f"Unknown command {parts[0]}. Available: /model"
        if len(parts) == 1:
            if not self.forced_model:
                return "Current mode: auto"
            left = f" ({self.forced_remaining} turns left)" if self.forced_remaining else ""
            return f"Current mode: {self.forced_model}{left}"
        mode = parts[1].lower()
        if mode == "auto":
            self.forced_model, self.forced_remaining = None, 0
            self.stop_lfm()
            return "Switched to auto routing"
        if mode in FORCEABLE_MODELS:
            turns = 0
            if len(parts) > 2:
                try:
                    turns = int(parts[2])
                    if turns < 1:
                        raise ValueError
                except ValueError:
                    return "Usage: /model gemini|qwen|lfm [n]"
            self.forced_model, self.forced_remaining = mode, turns
            if mode != "lfm":
                self.stop_lfm()
            return f"Forcing {mode} for {'next ' + str(turns) + ' turns' if turns else 'session'}"
        return "Invalid mode. Use auto, qwen, gemini, or lfm"

    def _consume_forced(self):
        model = self.forced_model
        if model and self.forced_remaining > 0:
            self.forced_remaining -= 1
            if self.forced_remaining == 0:
                self.forced_model = None
                print("[Forced model expires after this reply; back to auto]")
        return model

    # ------------------------------------------------------------------
    # send
    # ------------------------------------------------------------------

    def _finish(self, reply: str) -> str:
        reply = (reply or "").strip() or "[no reply produced]"
        self.messages.append({"role": "assistant", "content": reply})
        log_message(DEVICE, "assistant", reply)
        return reply

    def _fail(self, message: str) -> str:
        """A turn with no real answer: keep the error out of history."""
        if self.messages and self.messages[-1]["role"] == "user":
            self.messages.pop()
        self.last_reply_streamed = False
        log_action("orchestrator", "failure", {}, message, 0, echo=False)
        return message

    def _run_auto(self) -> str:
        try:
            return self._call_local_with_tools()
        except Exception as e:
            cause = summarize_error(e) if not isinstance(e, LocalModelError) else str(e)
            log_action("local", "fallback", {}, f"{cause} -> trying gemini", 0)
            try:
                return self._call_gemini_with_tools()
            except Exception as ge:
                raise RouteError(f"local model: {cause}; gemini: {summarize_error(ge)}")

    def send(self, user_message: str) -> str:
        self.last_reply_streamed = False
        self._last_user_message = user_message
        self.messages.append({"role": "user", "content": user_message})
        log_message(DEVICE, "user", user_message)

        try:
            self._turn_context = build_turn_context(user_message)
        except Exception as e:
            self._turn_context = ""
            log_action("memory", "recall", {}, f"ERROR {summarize_error(e)}", 0)

        # 1. Deterministic routes: free, instant, never a model guess.
        call = match_intent(user_message)
        layer = "intent"
        if call is None:
            call, layer = try_direct_route(user_message), "regex"
        if call is None and not self.forced_model:
            call, layer = ask_needle(user_message), "needle"
        if call is not None:
            return self._finish(self._run_direct_call(call, layer))

        # 2. Model routes.
        forced = self._consume_forced()
        try:
            if forced == "gemini":
                reply = self._call_gemini_with_tools()
            elif forced == "qwen":
                reply = self._call_local_with_tools()
            elif forced == "lfm":
                reply = self._call_lfm()
            else:
                reply = self._run_auto()
        except RouteError as e:
            return self._fail(f"[couldn't answer -- {e}]")
        except Exception as e:
            cause = str(e) if isinstance(e, LocalModelError) else summarize_error(e)
            return self._fail(f"[{forced or 'auto'} failed: {cause}]")

        if forced and self.forced_model != "lfm":
            self.stop_lfm()
        return self._finish(reply)


if __name__ == "__main__":
    init_db()
    try:
        setup_fts()
    except Exception as e:
        print(f"[memory index unavailable: {summarize_error(e)}]")
    config = load_config()
    convo = Conversation(config)

    print(f"felix-ai Core -- {config['assistant_name']}")
    print(f"Talking to: {convo.url}")
    print("Type 'quit' to exit.\n")

    while True:
        try:
            user_input = input("You: ").strip()
        except (EOFError, KeyboardInterrupt):
            print()
            break
        if user_input.lower() in ("quit", "exit"):
            break
        if not user_input:
            continue
        if user_input.startswith("/"):
            print(convo.handle_command(user_input))
            continue

        print(f"{config['assistant_name']}: ", end="", flush=True)
        try:
            reply = convo.send(user_input)
        except Exception as e:  # last line of defence: never crash the loop
            reply = f"[unexpected error: {summarize_error(e)}]"
            log_action("orchestrator", "crash-guard", {}, reply, 0, echo=False)
        if not convo.last_reply_streamed:
            print(reply)
        print()
