"""
Mock-backed tests for the orchestrator patch. No real llama-server, no
Gemini calls, no Termux binaries. Run from ~/felix-ai/core:

    PYTHONPATH=. python3 -m unittest tests.test_patch1 -v
"""

import json
import os
import socket
import stat
import sys
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
from unittest import mock

CORE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, CORE)

TMP = tempfile.mkdtemp()
os.environ["FELIX_DB"] = os.path.join(TMP, "t.db")
os.environ["FELIX_LOG_DIR"] = os.path.join(TMP, "logs")
os.environ["FAZE_SETTINGS"] = os.path.join(TMP, "settings-default.json")
os.environ.pop("FAZE_GEMINI_DISABLED", None)

import sqlite3  # noqa: E402

import memory_store  # noqa: E402
from memory_recall import setup_fts  # noqa: E402

memory_store.init_db()
_conn = sqlite3.connect(os.environ["FELIX_DB"])
for fact, cat in [
    ("My phone is a Redmi 10C.", "devices"),
    ("Some of my favorite anime include Naruto, Attack on Titan, Dandadan, and Solo Leveling.", "me"),
    ("I like anime with strong characters.", "me"),
    ("Goes by Felix.", "me"),
]:
    _conn.execute(
        "INSERT INTO memories (created_at, updated_at, fact, source, confidence, active, category) "
        "VALUES ('2026-10-01','2026-10-01',?, 'stated', 1.0, 1, ?)", (fact, cat))
_conn.commit()
_conn.close()
setup_fts()

import orchestrator  # noqa: E402
from orchestrator import Conversation, LocalModelError, LOCAL_TOOLS_SCHEMA, GEMINI_TOOLS_SCHEMA  # noqa: E402
from config_loader import load_config  # noqa: E402
from gemini_client import GeminiError  # noqa: E402
from tool_executor import ToolExecutionError  # noqa: E402
from tool_schema import TOOL_REGISTRY, validate_tool_call  # noqa: E402
import tool_replies  # noqa: E402
from intents import match_intent  # noqa: E402
import prompt_assembler  # noqa: E402

BODY429 = 'Web search failed: Gemini API returned 429: {"error": {"code": 429, "message": "quota"}}'


# ---------------------------------------------------------------- fake llama-server

class FakeLlama:
    """Scripted llama-server. Script items: {"content": str},
    {"tool": (name, args_json)}, or {"drop": True} (cuts the stream)."""

    def __init__(self, script=None):
        self.script = list(script or [])
        self.requests = []
        outer = self

        class H(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def do_GET(self):
                self.send_response(200)
                self.end_headers()
                self.wfile.write(b"ok")

            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                outer.requests.append(body)
                item = outer.script.pop(0) if outer.script else {"content": "default reply"}
                if item.get("drop"):
                    self.send_response(200)
                    self.send_header("Content-Type", "text/event-stream")
                    self.send_header("Content-Length", "5000")
                    self.end_headers()
                    self.wfile.write(b'data: {"choices":[{"delta":{"content":"par"}}]}\n\n')
                    self.wfile.flush()
                    self.close_connection = True
                    return
                chunks = []
                if "content" in item:
                    chunks.append({"choices": [{"delta": {"content": item["content"]}}]})
                if "tool" in item:
                    name, args = item["tool"]
                    chunks.append({"choices": [{"delta": {"tool_calls": [
                        {"index": 0, "id": "call_1", "function": {"name": name, "arguments": args}}]}}]})
                payload = "".join(f"data: {json.dumps(c)}\n\n" for c in chunks) + "data: [DONE]\n\n"
                data = payload.encode()
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

        self.server = HTTPServer(("127.0.0.1", 0), H)
        self.port = self.server.server_address[1]
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def close(self):
        self.server.shutdown()
        self.server.server_close()


def free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


def make_convo(port, **extra):
    config = load_config()
    config["llm"]["port"] = port
    config.update(extra)
    return Conversation(config)


def fake_exec(table):
    def _exec(name, params):
        value = table[name]
        if isinstance(value, Exception):
            raise value
        return value
    return _exec


class Base(unittest.TestCase):
    def setUp(self):
        os.environ["FAZE_SETTINGS"] = os.path.join(TMP, f"s-{time.time_ns()}.json")
        os.environ.pop("FAZE_GEMINI_DISABLED", None)
        self.llama = FakeLlama()
        self.needle = mock.patch("orchestrator.ask_needle", return_value=None)
        self.needle.start()

    def tearDown(self):
        self.needle.stop()
        self.llama.close()
        os.environ.pop("FAZE_GEMINI_DISABLED", None)

    def convo(self, **extra):
        c = make_convo(self.llama.port, **extra)
        self.addCleanup(c.stop_lfm)
        return c


# ---------------------------------------------------------------- P1: registry / schemas / replies

class TestRegistryAndReplies(unittest.TestCase):
    def test_every_tool_validates(self):
        for name, params in TOOL_REGISTRY.items():
            args = {k: (5 if t is int else "x") for k, t in params.items()}
            self.assertTrue(validate_tool_call({"tool": name, "parameters": args}), name)

    def test_schemas(self):
        local = {t["function"]["name"] for t in LOCAL_TOOLS_SCHEMA}
        gem = {t["function"]["name"] for t in GEMINI_TOOLS_SCHEMA}
        self.assertIn("delegate_to_gemini", local)
        self.assertNotIn("delegate_to_gemini", gem)
        self.assertNotIn("enable_thinking_mode", gem)
        self.assertNotIn("template", json.dumps(LOCAL_TOOLS_SCHEMA))
        bat = [t for t in LOCAL_TOOLS_SCHEMA if t["function"]["name"] == "get_battery_status"][0]
        self.assertEqual(bat["function"]["parameters"]["required"], [])

    def test_templates(self):
        f = tool_replies.format_reply
        self.assertEqual(f("get_battery_status", "19% battery, status: CHARGING"), "Battery is at 19%, charging.")
        self.assertEqual(f("get_battery_status", "27% battery, status: DISCHARGING"), "Battery is at 27%, not charging.")
        self.assertEqual(f("get_current_datetime", "2026-10-03 16:20:11 Saturday"),
                         "It's 4:20 PM on Saturday, October 3, 2026.")
        self.assertEqual(f("get_current_datetime", "2026-10-03 00:05:00 Saturday"),
                         "It's 12:05 AM on Saturday, October 3, 2026.")
        self.assertEqual(f("vibrate", "Vibrated."), "Done, phone vibrated.")
        self.assertEqual(f("set_volume", "x", {"level": 7}), "Volume set to 7.")

    def test_quota_error_names_the_limit(self):
        import gemini_client

        class R:
            status_code = 429
            text = '{"error": {"code": 429, "details": [{"violations": [{"quotaId": "GenerateRequestsPerMinutePerProjectPerModel-FreeTier"}]}, {"retryDelay": "36s"}]}}'

            def json(self):
                return json.loads(self.text)
        err = gemini_client._http_error(R())
        self.assertEqual(tool_replies.summarize_error(err),
                         "Gemini quota exceeded (429: GenerateRequestsPerMinutePerProjectPerModel-FreeTier, retry in 36s)")

    def test_error_summary_hides_body(self):
        msg = tool_replies.summarize_error(ToolExecutionError(BODY429))
        self.assertEqual(msg, "Gemini quota exceeded (429)")
        self.assertNotIn("{", msg)


# ---------------------------------------------------------------- P2: intents

class TestIntents(unittest.TestCase):
    def test_raw_text(self):
        self.assertEqual(match_intent("ask gemini who is nicky minaj"),
                         {"tool": "delegate_to_gemini", "parameters": {"request": "who is nicky minaj"}})
        self.assertEqual(match_intent("Ask Gemini, who is tara strong?")["parameters"]["request"], "who is tara strong?")
        self.assertEqual(match_intent("search the web for mrwhosetheboss latest video")["parameters"]["query"],
                         "mrwhosetheboss latest video")
        self.assertEqual(match_intent("search whats chatgpt latest model")["parameters"]["query"],
                         "whats chatgpt latest model")
        self.assertIsNone(match_intent("yo whats up"))

    def test_leading_filler_and_more_verbs(self):
        q = lambda m: match_intent(m)["parameters"]
        self.assertEqual(q("okay, search the web whats mrwhosetheboss latest video"),
                         {"query": "whats mrwhosetheboss latest video"})
        self.assertEqual(q("yo ask gemini, who is tara strong?"), {"request": "who is tara strong?"})
        self.assertEqual(q("can you search for tara strong"), {"query": "tara strong"})
        self.assertEqual(q("look up tara strong"), {"query": "tara strong"})
        self.assertEqual(q("Google the fastest camaro"), {"query": "the fastest camaro"})
        self.assertEqual(q("so ask gemini x"), {"request": "x"})
        for miss in ("I want to search the web", "searching is fun", "do you know who to ask gemini about",
                     "search", "ask gemini"):
            self.assertIsNone(match_intent(miss), miss)


# ---------------------------------------------------------------- memory context

class TestMemoryContext(unittest.TestCase):
    def test_per_turn_and_static(self):
        static = prompt_assembler.build_static_system_message()
        self.assertNotIn("Redmi", static)
        self.assertEqual(static, prompt_assembler.build_static_system_message())
        ctx = prompt_assembler.build_turn_context("whats my phone?")
        self.assertIn("- Felix's phone is a Redmi 10C.", ctx)
        self.assertNotIn("My phone", ctx)
        self.assertEqual(prompt_assembler.build_turn_context("hi"), "")
        self.assertEqual(prompt_assembler.build_turn_context("whats up"), "")
        self.assertIn("Naruto", prompt_assembler.build_turn_context("anime!!!"))
        self.assertIn("Goes by Felix", prompt_assembler.build_turn_context("who am i?"))

    def test_greeting_gets_no_memories_in_full_prompt(self):
        self.assertNotIn("FACTS ABOUT FELIX", prompt_assembler.build_system_message("hi"))

    def test_p8_greeting_lengths_do_not_grow(self):
        a = len(prompt_assembler.build_system_message("hi"))
        b = len(prompt_assembler.build_system_message("what's up"))
        self.assertEqual(a, len(prompt_assembler.build_static_system_message()))
        self.assertEqual(a, b)


class TestPronouns(unittest.TestCase):
    def test_facts_become_third_person(self):
        from pronouns import to_third_person as t
        cases = {
            "My phone is a Redmi 10C.": "Felix's phone is a Redmi 10C.",
            "Some of my favorite anime include Naruto.": "Some of his favorite anime include Naruto.",
            "Attack on Titan is probably the best anime I've watched.": "Attack on Titan is probably the best anime he has watched.",
            "Dandadan is an anime I strongly relate to.": "Dandadan is an anime he strongly relates to.",
            "I like anime with strong characters.": "Felix likes anime with strong characters.",
            "I often ask about anime without wanting spoilers.": "Felix often asks about anime without wanting spoilers.",
            "I'm into custom ROMs and I don't like bloat.": "Felix is into custom ROMs and he doesn't like bloat.",
            "Goes by Felix.": "Goes by Felix.",
        }
        for src, want in cases.items():
            self.assertEqual(t(src), want, src)

    def test_no_first_person_left_in_prompt_context(self):
        import re
        for q in ("anime!!!", "whats my phone?", "who am i?"):
            ctx = prompt_assembler.build_turn_context(q)
            self.assertIsNone(re.search(r"\b(I|my|me|I'm|I've)\b", ctx.split("\n", 1)[1]), ctx)

    def test_persona_states_who_is_who(self):
        static = prompt_assembler.build_static_system_message()
        self.assertIn("When you say 'I' or 'my', that means you, Faze", static)
        self.assertIn("You are Faze, not Felix", static)


# ---------------------------------------------------------------- deterministic routes (no model)

class TestDeterministic(Base):
    def test_battery_template_no_model_call(self):
        c = self.convo()
        with mock.patch("orchestrator.execute_tool", side_effect=fake_exec({"get_battery_status": "19% battery, status: CHARGING"})):
            reply = c.send("whats my battery")
        self.assertEqual(reply, "Battery is at 19%, charging.")
        self.assertFalse(c.last_reply_streamed)
        self.assertEqual(len(self.llama.requests), 0)

    def test_time_and_vibrate(self):
        c = self.convo()
        table = {"get_current_datetime": "2026-10-03 16:20:11 Saturday", "vibrate": "Vibrated."}
        with mock.patch("orchestrator.execute_tool", side_effect=fake_exec(table)):
            self.assertEqual(c.send("whats the time"), "It's 4:20 PM on Saturday, October 3, 2026.")
            self.assertEqual(c.send("vibrate my phone"), "Done, phone vibrated.")

    def test_intent_passes_raw_text_and_returns_gemini_text_verbatim(self):
        c = self.convo()
        seen = []

        def _exec(name, params):
            seen.append((name, params))
            return "Nicki Minaj is a rapper."
        with mock.patch("orchestrator.execute_tool", side_effect=_exec):
            reply = c.send("ask gemini who is nicky minaj")
        self.assertEqual(seen, [("delegate_to_gemini", {"request": "who is nicky minaj"})])
        self.assertEqual(reply, "Nicki Minaj is a rapper.")

    def test_tool_error_surfaces_real_cause(self):
        c = self.convo()
        with mock.patch("orchestrator.execute_tool", side_effect=fake_exec({"search_web": ToolExecutionError(BODY429)})):
            reply = c.send("search the web for mrwhosetheboss latest video")
        self.assertEqual(reply, "search_web failed: Gemini quota exceeded (429)")

    def test_empty_tool_result_never_silent(self):
        c = self.convo()
        with mock.patch("orchestrator.execute_tool", side_effect=fake_exec({"get_battery_status": ""})):
            reply = c.send("whats my battery")
        self.assertEqual(reply, "get_battery_status: no result returned.")

    def test_crashing_executor_is_caught(self):
        c = self.convo()
        with mock.patch("orchestrator.execute_tool", side_effect=fake_exec({"vibrate": FileNotFoundError("termux-vibrate")})):
            reply = c.send("vibrate")
        self.assertTrue(reply.startswith("vibrate failed:"), reply)

    def test_gemini_disabled_flag(self):
        c = self.convo(gemini_enabled=False)
        reply = c.send("search the web for anything")
        self.assertEqual(reply, "search_web failed: Gemini is disabled in config")


# ---------------------------------------------------------------- local model path

class TestLocal(Base):
    def test_plain_reply_streams_and_context_is_per_turn(self):
        self.llama.script = [{"content": "yo it's a Redmi"}, {"content": "sure"}]
        c = self.convo()
        r1 = c.send("whats my phone?")
        self.assertEqual(r1, "yo it's a Redmi")
        self.assertTrue(c.last_reply_streamed)
        c.send("what anime do i like")
        req1, req2 = self.llama.requests
        self.assertEqual(req1["messages"][0], req2["messages"][0])  # byte-identical system prompt
        self.assertNotIn("Redmi", req1["messages"][0]["content"])
        self.assertIn("Felix's phone is a Redmi 10C.", req1["messages"][-1]["content"])
        self.assertIn("Naruto", req2["messages"][-1]["content"])
        self.assertEqual(c.messages[1]["content"], "whats my phone?")  # context not stored in history
        self.assertEqual(req1["temperature"], 0.3)

    def test_tool_call_replies_from_template_not_model(self):
        self.llama.script = [{"tool": ("get_battery_status", "{}")}]
        c = self.convo()
        with mock.patch("orchestrator.execute_tool", side_effect=fake_exec({"get_battery_status": "33% battery, status: DISCHARGING"})):
            reply = c.send("how much juice is left")
        self.assertEqual(reply, "Battery is at 33%, not charging.")
        self.assertEqual(len(self.llama.requests), 1)  # one model call, no narration round

    def test_model_paraphrase_replaced_by_raw_text(self):
        self.llama.script = [{"tool": ("delegate_to_gemini", json.dumps({"request": "What is the fastest camera"}))}]
        c = self.convo()
        seen = []

        def _exec(name, params):
            seen.append((name, params))
            return "The Camaro ZL1 1LE."
        with mock.patch("orchestrator.execute_tool", side_effect=_exec):
            reply = c.send("whats the fastest camero ever made")
        self.assertEqual(seen, [("delegate_to_gemini", {"request": "whats the fastest camero ever made"})])
        self.assertEqual(reply, "The Camaro ZL1 1LE.")

    def test_thinking_mode_reruns_turn_with_thinking_on(self):
        self.llama.script = [{"tool": ("enable_thinking_mode", "{}")}, {"content": "worked it out"}]
        c = self.convo()
        self.assertEqual(c.send("prove something hard"), "worked it out")
        self.assertFalse(self.llama.requests[0]["chat_template_kwargs"]["enable_thinking"])
        self.assertTrue(self.llama.requests[1]["chat_template_kwargs"]["enable_thinking"])
        self.assertGreater(self.llama.requests[1]["max_tokens"], 200)

    def test_empty_model_reply_is_an_error_not_blank(self):
        self.llama.script = [{"content": ""}]
        c = self.convo()
        with mock.patch("orchestrator.ask_gemini", side_effect=GeminiError("Gemini API returned 429: x")):
            reply = c.send("yo")
        self.assertIn("empty reply", reply)
        self.assertIn("quota exceeded (429)", reply)


# ---------------------------------------------------------------- P6: robustness

class TestRobustness(Base):
    def test_local_down_falls_back_to_gemini(self):
        c = make_convo(free_port())
        seen = {}

        def _gem(system, history, tools_schema=None, **kw):
            seen["tools"] = [t["function"]["name"] for t in tools_schema]
            seen["system"] = system
            return {"type": "text", "content": "gemini says hi"}
        with mock.patch("orchestrator.ask_gemini", side_effect=_gem):
            reply = c.send("yo")
        self.assertEqual(reply, "gemini says hi")
        self.assertNotIn("delegate_to_gemini", seen["tools"])
        self.assertNotIn("TOOLS (call ONLY", seen["system"])

    def test_both_down_no_crash_and_history_clean(self):
        c = make_convo(free_port())
        with mock.patch("orchestrator.ask_gemini", side_effect=GeminiError(BODY429)):
            reply = c.send("yo")
        self.assertIn("can't reach llama-server", reply)
        self.assertIn("quota exceeded (429)", reply)
        self.assertEqual([m["role"] for m in c.messages], ["system"])

    def test_midstream_drop(self):
        self.llama.script = [{"drop": True}]
        c = self.convo()
        with self.assertRaises(LocalModelError) as cm:
            c._call_llm(c._request_messages())
        self.assertIn("stream interrupted", str(cm.exception))
        self.llama.script = [{"drop": True}]
        with mock.patch("orchestrator.ask_gemini", side_effect=GeminiError("Gemini API returned 500: x")):
            reply = c.send("yo")  # must not raise
        self.assertIn("stream interrupted", reply)


# ---------------------------------------------------------------- P4: Gemini loop

class TestGeminiLoop(Base):
    def test_dedupe_error_feedback_and_final_round_without_tools(self):
        c = self.convo()
        calls = []

        def _gem(system, history, tools_schema=None, **kw):
            calls.append((tools_schema is not None, [m["content"] for m in history][-1]))
            if tools_schema is None:
                return {"type": "text", "content": "Tara Strong is a voice actress."}
            return {"type": "function_call", "name": "search_web", "args": {"query": "who is tara strong"}}
        ran = []

        def _exec(name, params):
            ran.append(name)
            raise ToolExecutionError(BODY429)
        with mock.patch("orchestrator.ask_gemini", side_effect=_gem), \
                mock.patch("orchestrator.execute_tool", side_effect=_exec):
            c.handle_command("/model gemini")
            reply = c.send("so who is she?")
        self.assertEqual(reply, "Tara Strong is a voice actress.")
        self.assertEqual(len(calls), 3)
        self.assertEqual(ran, ["search_web"])  # identical repeat not re-run
        self.assertIn("quota exceeded (429)", calls[1][1])
        self.assertIn("Do not retry", calls[1][1])
        self.assertIn("already made this exact call", calls[2][1])
        self.assertFalse(calls[2][0])  # last request has tools off

    def test_unoffered_tool_is_not_executed(self):
        c = self.convo()
        seq = [{"type": "function_call", "name": "delegate_to_gemini", "args": {"request": "x"}},
               {"type": "text", "content": "answer"}]
        with mock.patch("orchestrator.ask_gemini", side_effect=lambda *a, **k: seq.pop(0)), \
                mock.patch("orchestrator.execute_tool") as ex:
            c.handle_command("/model gemini")
            reply = c.send("hello there")
        self.assertEqual(reply, "answer")
        ex.assert_not_called()

    def test_gemini_gets_memories_per_turn(self):
        c = self.convo()
        seen = {}

        def _gem(system, history, tools_schema=None, **kw):
            seen["last_user"] = history[-1]["content"]
            seen["system"] = system
            return {"type": "text", "content": "ok"}
        with mock.patch("orchestrator.ask_gemini", side_effect=_gem):
            c.handle_command("/model gemini")
            c.send("what other anime do i like")
        self.assertIn("Naruto", seen["last_user"])
        self.assertNotIn("Naruto", seen["system"])


# ---------------------------------------------------------------- P3: /model

class TestModelCommand(Base):
    def test_forced_gemini_then_back_to_auto(self):
        self.llama.script = [{"content": "local yo"}]
        c = self.convo()
        self.assertEqual(c.handle_command("/model gemini 1"), "Forcing gemini for next 1 turns")
        with mock.patch("orchestrator.ask_gemini", return_value={"type": "text", "content": "gemini yo"}) as g:
            self.assertEqual(c.send("yo"), "gemini yo")
            self.assertEqual(len(self.llama.requests), 0)
            self.assertEqual(c.send("yo again"), "local yo")
            self.assertEqual(g.call_count, 1)
        self.assertIsNone(c.forced_model)

    def test_forced_qwen_never_touches_gemini(self):
        self.llama.script = [{"content": "qwen only"}]
        c = self.convo()
        c.handle_command("/model qwen")
        with mock.patch("orchestrator.ask_gemini") as g:
            self.assertEqual(c.send("hello"), "qwen only")
        g.assert_not_called()

    def test_forced_failure_is_loud_with_no_silent_fallback(self):
        c = make_convo(free_port())
        c.handle_command("/model qwen")
        with mock.patch("orchestrator.ask_gemini") as g:
            reply = c.send("hello")
        g.assert_not_called()
        self.assertIn("[qwen failed:", reply)
        self.assertIn("can't reach llama-server", reply)

    def test_lfm_without_config_is_a_clear_error(self):
        c = self.convo()
        c.handle_command("/model lfm 1")
        with mock.patch("orchestrator.ask_gemini") as g:
            reply = c.send("yo")
        g.assert_not_called()
        self.assertEqual(len(self.llama.requests), 0)
        self.assertIn("LFM not configured", reply)

    def test_command_parsing(self):
        c = self.convo()
        self.assertEqual(c.handle_command("/model"), "Current mode: auto")
        self.assertEqual(c.handle_command("/model qwen 2"), "Forcing qwen for next 2 turns")
        self.assertEqual(c.handle_command("/model"), "Current mode: qwen (2 turns left)")
        self.assertEqual(c.handle_command("/model gemini x"), "Usage: /model gemini|qwen|lfm [n]")
        self.assertEqual(c.handle_command("/model gemini 0"), "Usage: /model gemini|qwen|lfm [n]")
        self.assertEqual(c.handle_command("/model bogus"), "Invalid mode. Use auto, qwen, gemini, or lfm")
        self.assertTrue(c.handle_command("/bogus").startswith("Unknown command"))
        self.assertEqual(c.handle_command("/model auto"), "Switched to auto routing")
        self.assertIsNone(c.handle_command("yo"))

    def test_lfm_server_lifecycle(self):
        fake_bin = os.path.join(TMP, "fake-llama-server")
        with open(fake_bin, "w") as f:
            f.write('''#!/usr/bin/env python3
import json, sys
from http.server import BaseHTTPRequestHandler, HTTPServer
port = int(sys.argv[sys.argv.index("--port") + 1])
class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def do_GET(self):
        self.send_response(200); self.end_headers(); self.wfile.write(b"ok")
    def do_POST(self):
        self.rfile.read(int(self.headers["Content-Length"]))
        data = ("data: " + json.dumps({"choices": [{"delta": {"content": "lfm says yo"}}]}) + "\\n\\ndata: [DONE]\\n\\n").encode()
        self.send_response(200); self.send_header("Content-Length", str(len(data))); self.end_headers(); self.wfile.write(data)
HTTPServer(("127.0.0.1", port), H).serve_forever()
''')
        os.chmod(fake_bin, os.stat(fake_bin).st_mode | stat.S_IEXEC)
        fake_model = os.path.join(TMP, "lfm.gguf")
        open(fake_model, "w").close()
        port = free_port()
        c = self.convo(lfm={"model_path": fake_model, "server_bin": fake_bin, "port": port,
                            "log": os.path.join(TMP, "lfm.log"), "start_timeout": 20})
        c.handle_command("/model lfm")
        self.assertEqual(c.send("yo"), "lfm says yo")
        self.assertIsNotNone(c.lfm_process)
        self.assertIsNone(c.lfm_process.poll())
        proc = c.lfm_process
        self.assertEqual(len(self.llama.requests), 0)  # main model untouched
        c.handle_command("/model auto")
        self.assertIsNotNone(proc.poll())  # server stopped on switch away
        self.assertIsNone(c.lfm_process)

    def test_lfm_server_that_dies_reports_it(self):
        bad_bin = os.path.join(TMP, "dead-server")
        with open(bad_bin, "w") as f:
            f.write("#!/bin/sh\nexit 3\n")
        os.chmod(bad_bin, os.stat(bad_bin).st_mode | stat.S_IEXEC)
        model = os.path.join(TMP, "lfm2.gguf")
        open(model, "w").close()
        c = self.convo(lfm={"model_path": model, "server_bin": bad_bin, "port": free_port(),
                            "log": os.path.join(TMP, "lfm2.log"), "start_timeout": 10})
        c.handle_command("/model lfm")
        self.assertIn("exited early (code 3)", c.send("yo"))


# ---------------------------------------------------------------- warmup prefix

class TestWarmup(Base):
    def test_warmup_prefix_matches_production(self):
        import warmup
        c = self.convo()
        with mock.patch("warmup.requests.post") as post:
            warmup.warmup()
        payload = post.call_args.kwargs["json"]
        self.assertEqual(payload["messages"][0]["content"], c.messages[0]["content"])
        self.assertEqual(payload["tools"], LOCAL_TOOLS_SCHEMA)


if __name__ == "__main__":
    unittest.main()
