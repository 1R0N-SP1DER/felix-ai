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
import re
import subprocess
import sys
import threading
import time

import requests

import action_log
from action_log import log_action
from config_loader import (load_config, get_llm_url, describe_settings,
                           set_user_setting, unset_user_setting, SETTINGS)
from local_server import ServerManager
from memory_store import init_db, log_message
from memory_recall import setup_fts
from identity import fix_identity
import agent
import device
from gemini_tools import DEVICE_TOOLS
from prompt_assembler import build_static_system_message, build_turn_context, set_names
from tool_schema import validate_tool_call, ToolCallValidationError
from permission_policy import get_permission_tier, UnknownToolError
import tool_executor
from tool_executor import execute_tool, ToolExecutionError
from tool_replies import format_reply, summarize_error, numbers_preserved
from direct_tool_router import try_direct_route
from intents import match_intent
from needle_router import ask_needle
import gemini_client
from gemini_client import ask_gemini, ask_gemini_stream, GeminiError
from gemini_tools import build_gemini_tools_schema, build_local_tools_schema

GEMINI_TOOLS_SCHEMA = build_gemini_tools_schema()
LOCAL_TOOLS_SCHEMA = build_local_tools_schema()

DEVICE = "phone"
MAX_TOOL_CALLS_PER_TURN = 5
MAX_PARALLEL_CALLS = 4  # tool calls Gemini may make in one round
MAX_HISTORY_MESSAGES = 20
FORCEABLE_MODELS = ("qwen", "gemini", "lfm")

# Slash commands: the single list used by /help, the terminal's tab
# completion and the web app's command menu. `args` are the choices for the
# first argument; `complete` False means the command still needs an argument.
COMMANDS = [
    {"name": "model", "description": "Choose which model answers",
     "usage": "/model [auto|qwen|lfm|gemini] [messages]",
     "args": [
         {"value": "auto", "description": "Qwen when ready, LFM while it boots, Gemini as backup"},
         {"value": "qwen", "description": "Local Qwen with tools"},
         {"value": "lfm", "description": "Local LFM, fast chat without tools"},
         {"value": "gemini", "description": "Gemini online helper"},
     ]},
    {"name": "think", "description": "Turn Qwen's thinking on or off",
     "usage": "/think [auto|on|off]",
     "args": [
         {"value": "auto", "description": "Qwen thinks only when it asks to"},
         {"value": "on", "description": "Think before every reply (slow)"},
         {"value": "off", "description": "Never think"},
     ]},
    {"name": "reset", "description": "Start a new chat and clear the history", "usage": "/reset", "args": []},
    {"name": "status", "description": "Show what is running", "usage": "/status", "args": []},
    {"name": "settings", "description": "View or change settings",
     "usage": "/settings [set <key> <value> | unset <key>]", "needs_args": True,
     "args": [
         {"value": "set", "description": "Change a setting"},
         {"value": "unset", "description": "Go back to a setting's default"},
     ]},
    {"name": "agent", "description": "How Gemini may add new tools",
     "usage": "/agent [off|ask|yolo]",
     "args": [
         {"value": "ask", "description": "Show each new tool and wait for approval"},
         {"value": "yolo", "description": "Add tools without asking (you still get the description)"},
         {"value": "off", "description": "Gemini cannot create tools"},
     ]},
    {"name": "elevated", "description": "Allow higher-level tools (network, shell, files)",
     "usage": "/elevated [on|off]",
     "args": [{"value": "on", "description": "Higher-level tools may be created and run"},
              {"value": "off", "description": "Only pure-computation tools"}]},
    {"name": "tools", "description": "List or remove tools Gemini made",
     "usage": "/tools [list|remove <name>|clear]",
     "args": [{"value": "list", "description": "Show saved tools"}, {"value": "clear", "description": "Delete all saved tools"}]},
    {"name": "tasks", "description": "List or remove saved tasks",
     "usage": "/tasks [list|remove <id>|clear]",
     "args": [{"value": "list", "description": "Show saved tasks"}, {"value": "clear", "description": "Delete all saved tasks"}]},
    {"name": "control", "description": "Let Gemini see and operate the phone",
     "usage": "/control [off|ask|on]",
     "args": [{"value": "ask", "description": "Ask once per request, then Gemini can look, tap and type"},
              {"value": "on", "description": "No questions asked (still needs /elevated on)"},
              {"value": "off", "description": "Gemini cannot touch the screen"}]},
    {"name": "doctor", "description": "Test Gemini, search, tools, actions, voice and the mic",
     "usage": "/doctor", "args": []},
    {"name": "log", "description": "Show recent tool and routing activity", "usage": "/log [count]", "args": []},
    {"name": "help", "description": "List every command", "usage": "/help", "args": []},
]

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
- open_url(url) : open a web link on the device
- open_app(app) : launch an app by name
- list_apps(search) : list installed apps whose name contains the text (use when open_app cannot find one)
- youtube_play(query) : find and play a YouTube video, e.g. "mrwhosetheboss latest video"

RULES:
- To open, launch or play something, call the matching tool. Never say you cannot.
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

def gemini_tool_note(schema: list) -> str:
    return (
        "\nYou can call these tools: "
        + ", ".join(t["function"]["name"] for t in schema)
        + ". Call a tool only when the user asks for it right now. If a tool "
          "fails, do not retry it: answer from your own knowledge or say you "
          "could not check. Never invent tool results.\n"
    )


GEMINI_TOOL_NOTE = gemini_tool_note(GEMINI_TOOLS_SCHEMA)


def tool_instructions() -> str:
    """The fixed tool rules plus any tools Gemini has made."""
    return TOOL_INSTRUCTIONS + agent.custom_tools_text()


class LocalModelError(Exception):
    """A local llama-server call failed (connection, HTTP or mid-stream).
    server_down=True means the server itself is gone, not just a bad reply."""

    def __init__(self, message, server_down: bool = False):
        super().__init__(message)
        self.server_down = server_down


class TurnCancelled(Exception):
    """The user pressed stop (or left); the turn ends without a fallback."""


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
        saved = config.get("model", {}).get("forced", "auto")
        self._persisted_force = saved if saved in FORCEABLE_MODELS else None
        self.forced_model = self._persisted_force   # None = auto
        self.forced_remaining = 0                    # 0 = until turned off
        self._cancel = threading.Event()
        self._active_response = None
        self.lfm_process = None
        self._lfm_lock = threading.Lock()
        self.on_route = None          # hook(tag, note): called when a backend is chosen
        self.on_token = None          # hook(kind, text): streamed pieces; kind is 'thinking' or 'answer'
        self.last_route = ""
        self.last_reply_streamed = False
        self._turn_context = ""
        self._last_user_message = ""
        self.approver = None          # callable(info) -> bool: asks the user about a new tool (ask mode)
        self._agent_notes = []
        self._agent_steps = []
        self._agent_created = []
        self._control_ok = False      # the user has allowed phone control for the current request
        agent.init(config)
        tool_executor.configure(config)
        if config.get("gemini_enabled", True) is False:
            os.environ["FAZE_GEMINI_DISABLED"] = "1"
        names = config["assistant"]
        set_names(names["name"], names["user_name"])
        gemini_client.configure(chat=config["gemini"]["model"], search=config["gemini"]["search_model"], thinking=config["gemini"]["thinking"])
        self._gemini_down_until = 0.0
        self.system_static = build_static_system_message()
        self.messages = [{"role": "system",
                          "content": self.system_static + "\n" + tool_instructions()}]
        self.local = ServerManager(config, self._warm_local)
        atexit.register(self.stop_lfm)
        atexit.register(self._stop_local_on_exit)

    # ------------------------------------------------------------------
    # background boot, routing tags
    # ------------------------------------------------------------------

    def _warm_local(self):
        """Heat llama-server's KV cache with the exact production prefix."""
        payload = {
            "messages": [self.messages[0], {"role": "user", "content": "hi"}],
            "tools": agent.local_schema(),
            "chat_template_kwargs": {"enable_thinking": False},
            "max_tokens": 1,
        }
        response = requests.post(self.url, json=payload, timeout=240)
        response.raise_for_status()

    def _stop_local_on_exit(self):
        if self.config["local"].get("stop_on_exit"):
            self.local.stop()

    def _lfm_configured(self) -> bool:
        path = os.path.expanduser(self.config["lfm"].get("model_path", ""))
        return bool(path) and os.path.isfile(path)

    def _gemini_on(self) -> bool:
        return bool(self.config.get("gemini_enabled", True)) and os.environ.get("FAZE_GEMINI_DISABLED") != "1"

    def start_background(self):
        """Boot Qwen without blocking the chat loop, and bring LFM up so
        there is something to chat with while Qwen loads. Without this
        call Qwen is treated as already running (tests, phone_server)."""
        boot = self.config["boot"]
        if boot.get("qwen", "lazy") == "lazy" and boot["mode"] != "blocking":
            if self.local._healthy():
                self.local.start()  # already running from last time: attach and warm
                print("[qwen already running; attaching]")
            else:
                self.local.go_idle()
                print("[qwen starts on demand (boot.qwen = lazy); nothing heavy runs at launch]")
            return
        if boot["mode"] == "blocking":
            print("[qwen starting; waiting for it to be ready...]")
            self.local.start(block=True)
            for note in self.local.drain_notices():
                print(note)
            return
        print("[qwen booting in the background" +
              (", chatting via LFM until it's ready]" if boot["chat_while_booting"] == "lfm" and self._lfm_configured()
               else f", chat_while_booting={boot['chat_while_booting']}]"))
        self.local.start()
        if boot["chat_while_booting"] == "lfm" and self._lfm_configured():
            threading.Thread(target=self._preload_lfm, daemon=True).start()

    def _preload_lfm(self):
        try:
            self._ensure_lfm()
        except Exception as e:
            log_action("lfm", "preload", {}, f"ERROR {e}", 0, echo=False)

    def drain_notices(self) -> list:
        notes, self._agent_notes = self._agent_notes, []
        return self.local.drain_notices() + notes

    def _announce(self, tag: str, extra: str = ""):
        self.last_route = tag
        parts = [extra] if extra else []
        if tag != "qwen":
            note = self.local.status_note()
            if note:
                parts.append(note)
        if self.on_route:
            self.on_route(tag, ", ".join(parts))

    # ------------------------------------------------------------------
    # history / request building
    # ------------------------------------------------------------------

    def cancel(self):
        """Stop the turn in progress. Closing the stream also makes
        llama-server stop generating."""
        self._cancel.set()
        response = self._active_response
        if response is not None:
            try:
                response.close()
            except Exception:
                pass

    def reset(self):
        """New chat: forget the history (the system prompt stays)."""
        self.messages = [self.messages[0]]
        self._turn_context = ""

    def _rebuild_system(self):
        self.system_static = build_static_system_message()
        self.messages[0] = {"role": "system", "content": self.system_static + "\n" + tool_instructions()}

    def _names(self):
        return self.config["assistant"]["name"], self.config["assistant"]["user_name"]

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
            raise LocalModelError(_local_error(e, url), server_down=True) from e
        response.encoding = "utf-8"
        self._active_response = response

        full_content = ""
        tool_calls_acc = {}
        shown_thinking_label = False
        streamed = False

        def emit(kind: str, text: str):
            nonlocal shown_thinking_label
            if self.on_token:
                self.on_token(kind, text)
                return
            if kind == "thinking" and not shown_thinking_label:
                print("[thinking] ", end="", flush=True)
                shown_thinking_label = True
            elif kind == "answer" and shown_thinking_label:
                print("\n[answer] ", end="", flush=True)
                shown_thinking_label = False
            print(text, end="", flush=True)

        try:
            for line in response.iter_lines(decode_unicode=True):
                if self._cancel.is_set():
                    response.close()
                    raise TurnCancelled()
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
                    emit("thinking", reasoning_piece)

                content_piece = delta.get("content")
                if content_piece:
                    emit("answer", content_piece)
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
            if self._cancel.is_set():
                raise TurnCancelled() from e
            if streamed and not self.on_token:
                print()
            raise LocalModelError(f"stream interrupted ({type(e).__name__})", server_down=True) from e

        self._active_response = None
        if streamed and not self.on_token:
            print()
        tool_calls = list(tool_calls_acc.values()) if tool_calls_acc else None
        return {"content": full_content, "tool_calls": tool_calls, "streamed": streamed}

    def _call_local_with_tools(self, max_rounds: int = 3, allow_thinking: bool = False) -> str:
        """One model call picks a tool (or just answers). Tool results are
        turned into replies in code. enable_thinking_mode re-runs the
        same turn with thinking on."""
        mode = self.config.get("reasoning", {}).get("mode", "auto")
        if mode == "on":
            allow_thinking = True
        for _ in range(max_rounds):
            result = self._call_llm(self._request_messages(), allow_thinking,
                                    tools=agent.local_schema())
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
                    if mode == "off":
                        log_action("qwen", name, args, "refused: reasoning is off", 0)
                        replies.append("[thinking is turned off. Use /think auto to allow it]")
                        continue
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
        if name in DEVICE_TOOLS:
            blocked = self._control_gate(name)
            if blocked:
                log_action(layer, name, args, f"ERROR {blocked}", 0)
                return False, blocked
        if name == "create_tool":
            return self._create_tool(args, layer)
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

    def _approve(self, info: dict) -> bool:
        if self.approver is None:
            return False
        approved = bool(self.approver(info))
        if self._cancel.is_set():
            raise TurnCancelled()
        return approved

    def _control_gate(self, name: str) -> str:
        """Phone control needs: /control on or ask, higher-level tools allowed, the user's yes once per request
        (in ask mode), and a step budget. Returns why not, or '' when the action may go ahead."""
        mode = device.SETTINGS["control"]
        if mode == "off":
            return "Phone control is off. Ask the user to run /control ask (or /control on)."
        if not agent.POLICY["high_tools"]:
            return "Phone control needs higher-level tools. Ask the user to run /elevated on."
        if not self._control_ok:
            if mode == "ask":
                info = {"kind": "control", "name": "Control your phone", "level": "high", "replaces": False, "params": {}, "code": "",
                        "description": "Faze wants to look at your screen and tap, swipe, type and press keys for this request.",
                        "how_it_works": f"It reads the screen with uiautomator and screenshots, and sends taps and keys with root input commands. "
                                        f"Touches show on screen as circles. It stops after {device.SETTINGS['max_steps']} actions or when you press Stop.",
                        "reasons": ["uses root to read and control the screen"]}
                if not self._approve(info):
                    return "The user declined phone control for this request. Do not try again; tell them what you could not do."
            self._control_ok = True
        if name != "wait":
            try:
                device.count_step()
            except device.DeviceError as e:
                return str(e)
        if name in ("tap", "tap_text", "swipe", "type_text", "press_key"):
            device.cursor(True)
        return ""

    def _create_tool(self, args: dict, layer: str):
        """Gemini's create_tool: check it, get approval per the agent mode, save and register it."""
        start = time.time()
        cfg = self.config.get("agent", {})
        mode = cfg.get("mode", "ask")
        name = str(args.get("name", ""))[:40]

        def done(ok, text):
            log_action(layer, "create_tool", {"name": name}, text if ok else f"ERROR {text}", int((time.time() - start) * 1000))
            return ok, text

        if mode == "off":
            return done(False, "creating tools is turned off")
        try:
            info, meta, code = agent.prepare_tool(args, bool(cfg.get("high_tools")))
        except agent.ToolCreateError as e:
            return done(False, str(e))
        if mode == "ask" and not self._approve(info):
            return done(False, "the user declined this tool; do not create it again, continue without it")
        agent.STORE.add_tool(meta, code)
        self._rebuild_system()
        self._agent_created.append(meta["name"])
        if mode == "yolo":
            self._agent_notes.append("Added a tool (yolo mode)\n" + agent.format_info(info))
        signature = ", ".join(meta["params"])
        return done(True, f"tool '{meta['name']}' created ({meta['level']}). Call it now: {meta['name']}({signature})")

    def _save_agent_task(self, prompt: str, reply: str):
        if self.config.get("agent", {}).get("mode", "ask") == "off":
            return
        steps = [s for s in self._agent_steps if s["ok"]]
        custom = [s for s in steps if s["tool"] in agent.STORE.tools]
        if not (len(steps) >= 2 or self._agent_created or custom):
            return
        try:
            agent.STORE.save_task(prompt, steps, reply, self._agent_created)
        except OSError as e:
            log_action("agent", "save_task", {}, f"ERROR {summarize_error(e)}", 0)

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

    def _gemini_round(self, system_message: str, history: list, schema) -> dict:
        """One Gemini request. With a token hook (the web app) the text streams as it is written;
        a tool call after some text drops that text (kind 'reset'). If the streaming endpoint itself
        fails before any text arrived, the plain endpoint gets a turn."""
        if not self.on_token or not self.config.get("gemini", {}).get("stream", True):
            return ask_gemini(system_message, history, tools_schema=schema)
        parts, calls = [], []
        try:
            for chunk in ask_gemini_stream(system_message, history, tools_schema=schema):
                if chunk["type"] == "text_chunk":
                    parts.append(chunk["content"])
                    self.on_token("answer", chunk["content"])
                else:
                    calls.append(chunk)
        except GeminiError:
            if parts or calls:
                raise
            return ask_gemini(system_message, history, tools_schema=schema)
        if calls:
            if parts:
                self.on_token("reset", "")
            return {**calls[0], "calls": calls} if len(calls) > 1 else calls[0]
        return {"type": "text", "content": "".join(parts)}

    def _call_gemini_with_tools(self, max_rounds: int = 2) -> str:
        """Gemini picks tools from its own schema (never delegate_to_gemini). With the agent on it
        gets more rounds and create_tool. Identical repeated calls are not re-run, tool errors go
        back as text, and the last request is sent with tools off so it must answer."""
        agent_cfg = self.config.get("agent", {})
        if agent_cfg.get("mode", "ask") != "off":
            max_rounds = max(max_rounds, int(agent_cfg.get("max_rounds", 6)))
        schema = agent.gemini_schema(self.config)
        system_message = self.system_static + gemini_tool_note(schema) + agent.agent_note(self.config)
        history = [m for m in self._request_messages()[1:]
                   if m["role"] in ("user", "assistant")]
        allowed = {t["function"]["name"] for t in schema}
        seen = set()

        for round_no in range(max_rounds + 1):
            final_round = round_no == max_rounds
            start = time.time()
            result = self._gemini_round(system_message, history, None if final_round else schema)
            if result["type"] == "text":
                log_action("gemini", "reply", {}, f"{len(result['content'])} chars",
                           int((time.time() - start) * 1000))
                return result["content"]

            # Gemini may ask for several independent tools at once; run them all this round.
            for call in (result.get("calls") or [result])[:MAX_PARALLEL_CALLS]:
                name, args = call["name"], call["args"]
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
                    if name != "create_tool":
                        self._agent_steps.append({"tool": name, "args": args, "ok": ok, "result": str(res)[:120]})
                    if name == "create_tool":
                        schema = agent.gemini_schema(self.config)
                        allowed = {t["function"]["name"] for t in schema}
                        tool_text = res if ok else f"create_tool failed: {res}. Fix the problem and try once more, or answer without it."
                    else:
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
        with self._lfm_lock:
            return self._start_lfm_locked(lfm, model, port, url, health)

    def _start_lfm_locked(self, lfm, model, port, url, health) -> str:
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
        cmd, args = parts[0].lower(), parts[1:]
        if cmd == "/settings":
            return self._settings_command(args)
        if cmd == "/status":
            return self._status_text()
        if cmd == "/reset":
            self.reset()
            return "Conversation cleared"
        if cmd == "/think":
            return self._think_command(args)
        if cmd == "/model":
            return self._model_command(args)
        if cmd == "/log":
            return self._log_text(args)
        if cmd == "/control":
            return self._control_command(args)
        if cmd == "/doctor":
            import doctor
            return doctor.format_report(doctor.run_checks(self.config))
        if cmd == "/agent":
            return self._agent_command(args)
        if cmd == "/elevated":
            return self._elevated_command(args)
        if cmd == "/tools":
            return self._tools_command(args)
        if cmd == "/tasks":
            return self._tasks_command(args)
        if cmd == "/help":
            return "\n".join(f"/{c['name']:<9} {c['description']}" for c in COMMANDS)
        return f"Unknown command {parts[0]}. Type /help for the list"

    def commands(self) -> list:
        return COMMANDS

    def _agent_text(self) -> str:
        cfg = self.config["agent"]
        return (f"Agent mode: {cfg['mode']} ({agent.MODE_HELP[cfg['mode']]})\n"
                f"Higher-level tools: {'on' if cfg['high_tools'] else 'off'}\n"
                f"Saved: {len(agent.STORE.tools)} tools, {len(agent.STORE.tasks)} tasks")

    def _agent_command(self, args: list) -> str:
        if not args:
            return self._agent_text()
        if args[0].lower() not in agent.MODES:
            return "Usage: /agent off|ask|yolo"
        self.change_setting("agent.mode", args[0].lower())
        return self._agent_text()

    def _control_command(self, args: list) -> str:
        helps = {"off": "Gemini cannot see or touch the screen", "ask": "Gemini asks once per request, then can look, tap and type",
                 "on": "Gemini can look, tap and type without asking"}
        if args:
            if args[0].lower() not in device.CONTROL_MODES:
                return "Usage: /control off|ask|on"
            self.change_setting("device.control", args[0].lower())
        mode = self.config["device"]["control"]
        need = "" if self.config["agent"]["high_tools"] or mode == "off" else "\nIt also needs higher-level tools: run /elevated on"
        return f"Phone control: {mode} ({helps[mode]}){need}"

    def _elevated_command(self, args: list) -> str:
        if args:
            word = args[0].lower()
            if word not in ("on", "off", "true", "false", "yes", "no"):
                return "Usage: /elevated on|off"
            self.change_setting("agent.high_tools", "true" if word in ("on", "true", "yes") else "false")
        on = self.config["agent"]["high_tools"]
        return "Higher-level tools: " + ("on (tools may use the network, shell or files)" if on else "off (pure-computation tools only)")

    def _tools_command(self, args: list) -> str:
        store = agent.STORE
        sub = args[0].lower() if args else "list"
        if sub == "list":
            if not store.tools:
                return "No saved tools"
            return "\n".join(f"{m['name']} [{m['level']}] {m['description']} (used {m.get('uses', 0)}x)"
                             for _, m in sorted(store.tools.items()))
        if sub == "remove" and len(args) == 2:
            removed = store.remove_tool(args[1])
            self._rebuild_system()
            return f"Removed {args[1]}" if removed else f"No tool named {args[1]}"
        if sub == "clear":
            n = store.clear_tools()
            self._rebuild_system()
            return f"Removed {n} tool{'s' if n != 1 else ''}"
        return "Usage: /tools [list|remove <name>|clear]"

    def _tasks_command(self, args: list) -> str:
        store = agent.STORE
        sub = args[0].lower() if args else "list"
        if sub == "list":
            if not store.tasks:
                return "No saved tasks"
            return "\n".join(f"{t['id']}  {t['prompt'][:60]}  ({', '.join(sorted({s['tool'] for s in t['steps']})) or 'no tools'}; used {t.get('uses', 0)}x)"
                             for t in store.tasks)
        if sub == "remove" and len(args) == 2:
            return f"Removed {args[1]}" if store.remove_task(args[1]) else f"No task {args[1]}"
        if sub == "clear":
            n = store.clear_tasks()
            return f"Removed {n} task{'s' if n != 1 else ''}"
        return "Usage: /tasks [list|remove <id>|clear]"

    def _log_text(self, args: list) -> str:
        try:
            n = int(args[0]) if args else 15
        except ValueError:
            return "Usage: /log [count]"
        lines = action_log.recent_lines(n)
        return "\n".join(l["text"] for l in lines) if lines else "No activity yet"

    def restore_history(self, items) -> int:
        """Replace the history with earlier turns (the web app reopening an old
        chat). Only user/assistant text is accepted; returns how many were kept."""
        keep = []
        for it in items if isinstance(items, list) else []:
            if isinstance(it, dict) and it.get("role") in ("user", "assistant") and isinstance(it.get("content"), str) and it["content"].strip():
                keep.append({"role": it["role"], "content": it["content"][:4000]})
        keep = keep[-MAX_HISTORY_MESSAGES:]
        while keep and keep[0]["role"] != "user":
            keep.pop(0)
        self.messages = [self.messages[0]] + keep
        self._turn_context = ""
        return len(keep)

    def _mode_text(self) -> str:
        if not self.forced_model:
            return "auto"
        if self.forced_remaining:
            return f"{self.forced_model} ({self.forced_remaining} turns left)"
        return f"{self.forced_model} (until you turn it off)"

    def _persist(self, key: str, value) -> None:
        """Save a choice so it survives restarts; never let a disk problem break chat."""
        try:
            if value is None:
                unset_user_setting(key)
            else:
                set_user_setting(key, str(value))
        except (ValueError, OSError) as e:
            log_action("settings", "persist", {"key": key}, f"ERROR {e}", 0, echo=False)

    def _model_command(self, args: list) -> str:
        if not args:
            return "Current mode: " + self._mode_text()
        mode = args[0].lower()
        if mode == "auto":
            self._persisted_force = None
            self.forced_model, self.forced_remaining = None, 0
            self.config.setdefault("model", {})["forced"] = "auto"
            self._persist("model.forced", None)
            self.stop_lfm()
            return "Switched to auto routing"
        if mode not in FORCEABLE_MODELS:
            return "Invalid mode. Use auto, qwen, gemini, or lfm"
        turns = 0
        if len(args) > 1:
            try:
                turns = int(args[1])
                if turns < 1:
                    raise ValueError
            except ValueError:
                return "Usage: /model gemini|qwen|lfm [n]"
        self.forced_model, self.forced_remaining = mode, turns
        if turns == 0:  # no count: stays until turned off, and across restarts
            self._persisted_force = mode
            self.config.setdefault("model", {})["forced"] = mode
            self._persist("model.forced", mode)
        if mode != "lfm":
            self.stop_lfm()
        return f"Forcing {mode} " + (f"for next {turns} turns" if turns else "until you turn it off")

    def _think_command(self, args: list) -> str:
        if not args:
            return "Reasoning: " + self.config["reasoning"]["mode"]
        try:
            value, _ = self.change_setting("reasoning.mode", args[0])
        except ValueError:
            return "Usage: /think auto|on|off"
        note = {"on": " (Qwen thinks before every reply; slow on this phone)",
                "off": " (Qwen never thinks)", "auto": " (Qwen thinks only when it asks to)"}[value]
        return f"Reasoning: {value}{note}"

    def _status_text(self) -> str:
        local = self.local.state if self.local.state != "unmanaged" else "running (not managed)"
        if self.local.detail and self.local.state in ("down", "failed"):
            local += f" ({self.local.detail})"
        lfm = "running" if self.lfm_process and self.lfm_process.poll() is None else (
            "idle" if self._lfm_configured() else "not configured")
        mode = self.forced_model or "auto"
        gem = "on" if self._gemini_on() else "off"
        think = self.config["reasoning"]["mode"]
        return f"qwen: {local} | lfm: {lfm} | gemini: {gem} | mode: {mode} | think: {think}"

    def change_setting(self, key: str, raw: str):
        """Validate, persist and apply one setting. Returns (value,
        needs_restart); raises ValueError with a user-facing message."""
        value, restart = set_user_setting(key, raw)
        self._apply_setting(key, value)
        return value, restart

    def reset_setting(self, key: str) -> bool:
        removed = unset_user_setting(key)
        if removed:
            cur = load_config()
            for part in key.split("."):
                cur = cur[part]
            self._apply_setting(key, cur)
        return removed

    def settings_list(self) -> list:
        """Rows for a settings UI: key, value, type, choices, user override?, restart?"""
        from config_loader import load_user_overrides, _dig, _RESTART_PREFIXES
        overrides = load_user_overrides()
        rows = []
        for key, (kind, allowed) in SETTINGS.items():
            rows.append({"key": key, "value": _dig(self.config, key),
                         "type": {bool: "bool", int: "int", str: "str"}[kind],
                         "choices": list(allowed) if allowed else None,
                         "user": _dig(overrides, key) is not None,
                         "restart": key.startswith(_RESTART_PREFIXES)})
        return rows

    def _settings_command(self, args: list) -> str:
        if not args:
            return describe_settings(self.config)
        action = args[0].lower()
        try:
            if action == "set" and len(args) >= 3:
                key, raw = args[1], " ".join(args[2:])
                value, restart = self.change_setting(key, raw)
                return f"{key} = {value}" + (" (takes effect after restart)" if restart else "")
            if action == "unset" and len(args) == 2:
                removed = self.reset_setting(args[1])
                return f"{args[1]} reset to default" if removed else f"{args[1]} had no user override"
        except ValueError as e:
            return f"Setting not changed: {e}"
        return "Usage: /settings | /settings set <key> <value> | /settings unset <key>"

    def _apply_setting(self, key: str, value) -> None:
        """Make a changed setting take effect now where it can."""
        node = self.config
        parts = key.split(".")
        for part in parts[:-1]:
            node = node.setdefault(part, {})
        node[parts[-1]] = value
        if key == "gemini_enabled":
            if value:
                os.environ.pop("FAZE_GEMINI_DISABLED", None)
            else:
                os.environ["FAZE_GEMINI_DISABLED"] = "1"
        if key == "ui.show_trace":
            action_log.set_console(bool(value))
        if key in ("assistant.name", "assistant.user_name"):
            name, user = self._names()
            set_names(name, user)
            self.config["assistant_name"] = name
            self._rebuild_system()
        if key in ("gemini.model", "gemini.search_model", "gemini.thinking"):
            gemini_client.configure(chat=self.config["gemini"]["model"], search=self.config["gemini"]["search_model"],
                                    thinking=self.config["gemini"]["thinking"])
        if key.startswith(("search.", "actions.", "device.")):
            tool_executor.configure(self.config)
        if key == "agent.high_tools":
            agent.set_policy(high_tools=bool(value))
        if key == "model.forced":
            self._persisted_force = value if value in FORCEABLE_MODELS else None
            if self.forced_remaining == 0:
                self.forced_model = self._persisted_force

    def _consume_forced(self):
        model = self.forced_model
        if model and self.forced_remaining > 0:
            self.forced_remaining -= 1
            if self.forced_remaining == 0:
                self.forced_model = self._persisted_force
                print(f"[Forced model expires after this reply; back to {self._persisted_force or 'auto'}]")
        return model

    # ------------------------------------------------------------------
    # send
    # ------------------------------------------------------------------

    def _finish(self, reply: str) -> str:
        device.cursor(False)
        reply = (reply or "").strip() or "[no reply produced]"
        self.messages.append({"role": "assistant", "content": reply})
        log_message(DEVICE, "assistant", reply)
        return reply

    def _fail(self, message: str) -> str:
        """A turn with no real answer: keep the error out of history."""
        device.cursor(False)
        if self.messages and self.messages[-1]["role"] == "user":
            self.messages.pop()
        self.last_reply_streamed = False
        log_action("orchestrator", "failure", {}, message, 0, echo=False)
        return message

    def _wait_then_qwen(self) -> str:
        print("[waiting for qwen...]")
        if not self.local.wait_ready():
            raise LocalModelError(f"qwen not available ({self.local.state}: {self.local.detail or 'timed out'})")
        return self._call_local_with_tools()

    def _forced_qwen(self) -> str:
        self.local.ensure_started()
        if not self.local.usable:
            return self._wait_then_qwen()
        return self._call_local_with_tools()

    @staticmethod
    def _gemini_unreachable(e: Exception) -> bool:
        """Network trouble, quota or server errors: worth falling back to a local model.
        Bad keys, bad requests and a disabled layer are NOT, so they stay loud."""
        text = str(e)
        return isinstance(e, GeminiError) and (
            text.startswith("Network error") or " returned 429" in text or bool(re.search(r" returned 5\d\d", text)))

    def _forced_gemini(self) -> str:
        """Gemini stays on until you turn it off; if it can't be reached the
        reply comes from a local model instead, and Gemini is retried in a minute."""
        order = [t for t in self.config["fallback"]["order"].split(",") if t in ("lfm", "qwen")]
        now = time.monotonic()
        try:
            if now < self._gemini_down_until:
                raise GeminiError(f"Network error: Gemini unreachable, retrying in {int(self._gemini_down_until - now)}s")
            return self._call_gemini_with_tools()
        except TurnCancelled:
            raise
        except Exception as e:
            if not order or not self._gemini_unreachable(e):
                raise
            cause = summarize_error(e)
            self._gemini_down_until = max(self._gemini_down_until, now + 60)
            log_action("gemini", "fallback", {}, f"{cause} -> {','.join(order)}", 0)
            errors = [f"gemini: {cause}"]
            for tag in order:
                if tag == "lfm" and not self._lfm_configured():
                    continue
                self._announce(tag, "offline fallback")
                try:
                    return self._call_lfm() if tag == "lfm" else self._forced_qwen()
                except TurnCancelled:
                    raise
                except Exception as fe:
                    errors.append(f"{tag}: {str(fe) if isinstance(fe, LocalModelError) else summarize_error(fe)}")
            raise RouteError("; ".join(errors))

    def _auto_chain(self) -> list:
        """Backends to try, in order, for this message."""
        chat = self.config["boot"]["chat_while_booting"]
        lfm_ok = chat == "lfm" and self._lfm_configured()
        self.local.ensure_started()  # no-op unless Qwen is idle or down
        chain = []
        if self.local.usable:
            chain.append(("qwen", self._call_local_with_tools))
            if lfm_ok:
                chain.append(("lfm", self._call_lfm))
        elif lfm_ok:
            chain.append(("lfm", self._call_lfm))
        elif chat == "wait":
            chain.append(("qwen", self._wait_then_qwen))
        if self._gemini_on():
            chain.append(("gemini", self._call_gemini_with_tools))
        return chain

    def _run_auto(self) -> str:
        chain = self._auto_chain()
        if not chain:
            raise RouteError(f"no backend available right now ({self.local.status_note() or 'all disabled'})")
        errors = []
        for tag, call in chain:
            self._announce(tag)
            try:
                return call()
            except TurnCancelled:
                raise
            except Exception as e:
                cause = str(e) if isinstance(e, LocalModelError) else summarize_error(e)
                errors.append(f"{tag}: {cause}")
                log_action(tag, "fallback", {}, cause, 0)
                if tag == "qwen" and isinstance(e, LocalModelError) and e.server_down:
                    self.local.mark_down(cause)
        raise RouteError("; ".join(errors))

    def send(self, user_message: str) -> str:
        self.last_reply_streamed = False
        self._cancel.clear()
        self._last_user_message = user_message
        if (self.local.state == "ready" and self.lfm_process and self.forced_model != "lfm"
                and self.config["lfm"].get("stop_when_qwen_ready", True)):
            self.stop_lfm()  # Qwen took over; free the RAM LFM was using
        self.messages.append({"role": "user", "content": user_message})
        log_message(DEVICE, "user", user_message)
        self._agent_steps, self._agent_created = [], []
        self._control_ok = False
        device.begin_turn()

        try:
            self._turn_context = build_turn_context(user_message)
        except Exception as e:
            self._turn_context = ""
            log_action("memory", "recall", {}, f"ERROR {summarize_error(e)}", 0)
        if self.config.get("agent", {}).get("mode", "ask") != "off":
            hint = agent.STORE.task_hint(user_message)
            if hint:
                self._turn_context = (self._turn_context + "\n\n" + hint).strip()

        # 1. Deterministic routes: free, instant, never a model guess.
        call = match_intent(user_message)
        layer = "intent"
        if call is None:
            call, layer = try_direct_route(user_message), "regex"
        if call is None and not self.forced_model:
            call, layer = ask_needle(user_message), "needle"
        if call is not None:
            self._announce(layer)
            return self._finish(self._run_direct_call(call, layer))

        # 2. Model routes.
        forced = self._consume_forced()
        try:
            if forced:
                self._announce(forced, "forced")
            if forced == "gemini":
                reply = self._forced_gemini()
            elif forced == "qwen":
                reply = self._forced_qwen()
            elif forced == "lfm":
                reply = self._call_lfm()
            else:
                reply = self._run_auto()
        except TurnCancelled:
            return self._fail("[stopped]")
        except RouteError as e:
            return self._fail(f"[couldn't answer -- {e}]")
        except Exception as e:
            cause = str(e) if isinstance(e, LocalModelError) else summarize_error(e)
            return self._fail(f"[{forced or 'auto'} failed: {cause}]")

        if forced and self.forced_model != "lfm":
            self.stop_lfm()
        self._save_agent_task(user_message, reply)
        return self._finish(fix_identity(reply, *self._names()))


def make_prefix_hook(name: str, show_tag: bool):
    """Returns (state, hook). hook(tag, note) prints 'Faze [tag | note]: '
    when a backend is chosen, and again (on a new line) if the turn
    falls back to another backend."""
    state = {"printed": False}

    def hook(tag: str, note: str):
        if not show_tag:
            return
        label = tag + (f" | {note}" if note else "")
        print(("\n" if state["printed"] else "") + f"{name} [{label}]: ", end="", flush=True)
        action_log.set_inline_open(True)
        state["printed"] = True
    return state, hook


if __name__ == "__main__":
    init_db()
    try:
        setup_fts()
    except Exception as e:
        print(f"[memory index unavailable: {summarize_error(e)}]")
    try:
        config = load_config()
    except ValueError as e:
        print(f"[settings error: {e}]")
        raise SystemExit(1)
    action_log.set_console(config["ui"].get("show_trace", True))
    convo = Conversation(config)
    convo.start_background()

    if "--cli" not in sys.argv[1:]:  # the web app is the default; --cli keeps the terminal chat
        from web_server import serve
        serve(convo, config)
        raise SystemExit(0)

    try:  # tab-complete slash commands in the terminal where readline exists
        import readline
        words = ["/" + c["name"] for c in COMMANDS]

        def _complete(text, state):
            matches = [w for w in words if w.startswith(text)] if text.startswith("/") else []
            return matches[state] if state < len(matches) else None
        readline.set_completer(_complete)
        readline.set_completer_delims(" \t\n")
        readline.parse_and_bind("tab: complete")
    except (ImportError, OSError):
        pass

    def cli_approver(info):
        print("\n" + agent.format_info(info) + "\n--- code ---\n" + info["code"] + "\n------------")
        try:
            return input("Approve this tool? [y/N] ").strip().lower() in ("y", "yes")
        except (EOFError, KeyboardInterrupt):
            return False
    convo.approver = cli_approver

    name = config["assistant_name"]
    print(f"felix-ai Core -- {name}")
    print(f"Talking to: {convo.url}")
    print("Type /help for commands (Tab completes them), 'quit' to exit.\n")

    while True:
        for note in convo.drain_notices():
            print(note)
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

        state, convo.on_route = make_prefix_hook(name, config["ui"].get("show_model_tag", True))
        try:
            reply = convo.send(user_input)
        except Exception as e:  # last line of defence: never crash the loop
            reply = f"[unexpected error: {summarize_error(e)}]"
            log_action("orchestrator", "crash-guard", {}, reply, 0, echo=False)
        action_log.set_inline_open(False)
        if not state["printed"]:
            print(f"{name}: ", end="")
        if not convo.last_reply_streamed:
            print(reply)
        print()
