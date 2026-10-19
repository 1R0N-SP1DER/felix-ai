"""
Patch 25: more models in Faze (Groq and OpenRouter, with real tool loops, cooldowns and fallbacks), Tavily search,
`faze bench` to compare them on your real tools, and the model sheet rows for them. Everything is tested with fake
HTTP; the real services need your phone and your keys.

    PYTHONPATH=. python3 -m unittest tests.test_patch25 -v
"""

import copy
import json
import os
import sys
import tempfile
import time
import unittest
from unittest import mock

import requests

CORE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, CORE)

import tests.test_patch17 as p17  # noqa: E402
import tests.test_patch21 as p21  # noqa: E402
from tests.test_patch16 import VoiceBase  # noqa: E402
from tests.test_patch1 import Base  # noqa: E402
from tests.test_patch5 import WebBase  # noqa: E402
from tests.test_patch16 import HAVE_JSDOM, run_node  # noqa: E402

import bench  # noqa: E402
import config_loader  # noqa: E402
import doctor  # noqa: E402
import net_status  # noqa: E402
import remote_models  # noqa: E402
import tool_executor  # noqa: E402
import web_search  # noqa: E402
from orchestrator import FORCEABLE_MODELS, TurnCancelled  # noqa: E402
from remote_models import Cancelled, RemoteError  # noqa: E402


# ---------------------------------------------------------------- fake HTTP

class Resp:
    def __init__(self, status=200, lines=None, headers=None, text="", data=None):
        self.status_code, self._lines, self.headers, self.text, self._data = status, lines or [], headers or {}, text, data
        self.encoding, self.closed = None, False

    def iter_lines(self, decode_unicode=False):
        for line in self._lines:
            if isinstance(line, Exception):
                raise line
            yield line

    def json(self):
        if self._data is not None:
            return self._data
        return json.loads(self.text)

    def close(self):
        self.closed = True


def delta(content=None, tool_calls=None):
    d = {}
    if content is not None:
        d["content"] = content
    if tool_calls is not None:
        d["tool_calls"] = tool_calls
    return {"choices": [{"delta": d}]}


def sse(*chunks):
    return [f"data: {json.dumps(c)}" for c in chunks] + ["data: [DONE]"]


def tc(index, name="", args="", id=""):
    fn = {}
    if name:
        fn["name"] = name
    if args:
        fn["arguments"] = args
    out = {"index": index, "function": fn}
    if id:
        out["id"] = id
    return out


class Poster:
    def __init__(self, *responses):
        self.responses, self.calls = list(responses), []

    def __call__(self, url, headers=None, json=None, timeout=None, stream=None):
        self.calls.append({"url": url, "headers": headers, "json": json})
        r = self.responses.pop(0)
        if isinstance(r, Exception):
            raise r
        return r


class RemoteBase(unittest.TestCase):
    def setUp(self):
        remote_models.reset()
        net_status.reset()
        self.addCleanup(remote_models.reset)
        self.addCleanup(net_status.reset)
        env = mock.patch.dict(os.environ, {"GROQ_API_KEY": "gsk_test", "OPENROUTER_API_KEY": "sk-or-test", "TAVILY_API_KEY": "tvly-test"})
        env.start()
        self.addCleanup(env.stop)


# ---------------------------------------------------------------- the client

class TestChat(RemoteBase):
    def chat(self, post, tools=None, **kw):
        return remote_models.chat("groq", "m1", [{"role": "user", "content": "hi"}], tools, post=post, **kw)

    def test_streams_text_and_sends_what_it_should(self):
        post = Poster(Resp(lines=sse(delta("Hel"), delta("lo"), delta(" there"))))
        tokens = []
        out = self.chat(post, on_token=lambda k, t: tokens.append((k, t)), max_tokens=123)
        self.assertEqual(out, {"content": "Hello there", "tool_calls": None, "streamed": True})
        self.assertEqual(tokens, [("answer", "Hel"), ("answer", "lo"), ("answer", " there")])
        call = post.calls[0]
        self.assertEqual(call["url"], "https://api.groq.com/openai/v1/chat/completions")
        self.assertEqual(call["headers"]["Authorization"], "Bearer gsk_test")
        self.assertEqual((call["json"]["model"], call["json"]["stream"], call["json"]["max_tokens"]), ("m1", True, 123))
        self.assertNotIn("tools", call["json"])

    def test_tool_calls_are_assembled_across_chunks_in_order(self):
        post = Poster(Resp(lines=sse(delta(tool_calls=[tc(1, "get_weather", '{"ci', "b")]), delta(tool_calls=[tc(0, "get_current_datetime", "{}", "a")]),
                                     delta(tool_calls=[tc(1, "", 'ty": "Abuja"}')]))))
        out = self.chat(post, tools=[{"type": "function", "function": {"name": "x"}}])
        self.assertEqual(out["tool_calls"], [{"id": "a", "name": "get_current_datetime", "arguments": "{}"},
                                             {"id": "b", "name": "get_weather", "arguments": '{"city": "Abuja"}'}])
        self.assertFalse(out["streamed"])
        sent = post.calls[0]["json"]
        self.assertEqual((sent["tool_choice"], len(sent["tools"])), ("auto", 1))

    def test_openrouter_sends_its_extra_headers(self):
        post = Poster(Resp(lines=sse(delta("ok"))))
        remote_models.chat("openrouter", "x/y:free", [], post=post)
        self.assertEqual(post.calls[0]["url"], "https://openrouter.ai/api/v1/chat/completions")
        self.assertEqual(post.calls[0]["headers"]["X-Title"], "Faze")
        self.assertIn("HTTP-Referer", post.calls[0]["headers"])

    def test_no_key_asks_for_nothing(self):
        os.environ.pop("GROQ_API_KEY")
        post = Poster()
        with self.assertRaises(RemoteError) as cm:
            self.chat(post)
        self.assertEqual(cm.exception.kind, "auth")
        self.assertIn("GROQ_API_KEY", str(cm.exception))
        self.assertEqual(post.calls, [])

    def test_a_network_failure_marks_us_offline(self):
        with self.assertRaises(RemoteError) as cm:
            self.chat(Poster(requests.ConnectionError("no route")))
        self.assertEqual(cm.exception.kind, "network")
        self.assertIs(net_status.last(), False)

    def test_error_statuses(self):
        cases = [
            (401, '{"error": {"message": "Invalid API Key"}}', "auth", True),
            (429, '{"error": {"message": "Rate limit reached for model"}}', "rate_limit", True),
            (400, '{"error": {"message": "Failed to call a function.", "code": "tool_use_failed"}}', "tool_failed", False),
            (400, '{"error": {"message": "The model `nope` does not exist"}}', "model", True),
            (404, '{"error": {"message": "No endpoints found"}}', "model", True),
            (503, "upstream down", "server", True),
            (400, '{"error": {"message": "messages must be non-empty"}}', "bad_request", False),
        ]
        for status, body, kind, cools in cases:
            remote_models.reset()
            with self.assertRaises(RemoteError) as cm:
                self.chat(Poster(Resp(status=status, text=body)))
            self.assertEqual(cm.exception.kind, kind, (status, body))
            self.assertEqual(not remote_models.available("groq"), cools, (status, kind))

    def test_rate_limit_cooldown_follows_retry_after_and_ends(self):
        clock = [1000.0]
        with self.assertRaises(RemoteError) as cm:
            self.chat(Poster(Resp(status=429, text='{"error": {"message": "slow down"}}', headers={"retry-after": "30"})), now=lambda: clock[0])
        self.assertEqual(cm.exception.retry_after, 30.0)
        self.assertFalse(remote_models.available("groq", now=lambda: clock[0] + 10))
        self.assertEqual(remote_models.note("groq", now=lambda: clock[0] + 10), "rate limited, retry in 20s")
        self.assertTrue(remote_models.available("groq", now=lambda: clock[0] + 31))
        self.assertEqual(remote_models.note("groq", now=lambda: clock[0] + 31), "ready")

    def test_groq_reset_headers_and_the_cap(self):
        with self.assertRaises(RemoteError) as cm:
            self.chat(Poster(Resp(status=429, text="{}", headers={"x-ratelimit-reset-requests": "1m30s"})))
        self.assertEqual(cm.exception.retry_after, 90.0)
        remote_models.reset()
        clock = [0.0]
        with self.assertRaises(RemoteError):
            self.chat(Poster(Resp(status=429, text="{}", headers={"retry-after": "99999"})), now=lambda: clock[0])
        self.assertFalse(remote_models.available("groq", now=lambda: 899))
        self.assertTrue(remote_models.available("groq", now=lambda: 901))

    def test_a_refused_key_is_remembered_for_an_hour(self):
        clock = [0.0]
        with self.assertRaises(RemoteError):
            self.chat(Poster(Resp(status=401, text='{"error": "bad"}')), now=lambda: clock[0])
        self.assertFalse(remote_models.available("groq", now=lambda: 3500))
        self.assertTrue(remote_models.available("groq", now=lambda: 3700))

    def test_a_malformed_tool_call_does_not_cool_the_provider_down(self):
        with self.assertRaises(RemoteError):
            self.chat(Poster(Resp(status=400, text='{"error": {"code": "tool_use_failed", "message": "bad"}}')))
        self.assertTrue(remote_models.available("groq"))

    def test_a_mid_stream_error_object(self):
        with self.assertRaises(RemoteError) as cm:
            self.chat(Poster(Resp(lines=sse(delta("par"), {"error": {"message": "upstream exploded"}}))))
        self.assertEqual(cm.exception.kind, "server")
        self.assertIn("upstream exploded", str(cm.exception))

    def test_a_cut_stream_is_a_network_error(self):
        with self.assertRaises(RemoteError) as cm:
            self.chat(Poster(Resp(lines=["data: " + json.dumps(delta("par")), requests.ConnectionError("reset")])))
        self.assertEqual(cm.exception.kind, "network")

    def test_cancelling_closes_the_stream(self):
        resp = Resp(lines=sse(delta("a"), delta("b")))
        with self.assertRaises(Cancelled):
            self.chat(Poster(resp), cancelled=lambda: True)
        self.assertTrue(resp.closed)

    def test_junk_lines_are_skipped(self):
        out = self.chat(Poster(Resp(lines=["", ": keep-alive", "data: not json", "data: " + json.dumps(delta("fine")), "data: [DONE]"])))
        self.assertEqual(out["content"], "fine")


class TestModelLists(RemoteBase):
    GROQ = {"data": [
        {"id": "llama-3.3-70b-versatile", "context_window": 131072}, {"id": "openai/gpt-oss-120b", "context_window": 131072},
        {"id": "openai/gpt-oss-20b", "context_window": 131072}, {"id": "whisper-large-v3", "context_window": 448},
        {"id": "meta-llama/llama-guard-4-12b", "context_window": 131072}, {"id": "playai-tts", "context_window": 8192},
        {"id": "retired-model", "active": False, "context_window": 99999999}]}
    OPENROUTER = {"data": [
        {"id": "paid/big-model", "context_length": 900000, "pricing": {"prompt": "0.001", "completion": "0.002"}, "supported_parameters": ["tools"]},
        {"id": "free/chatty:free", "context_length": 32000, "pricing": {"prompt": "0", "completion": "0"}, "supported_parameters": ["temperature"]},
        {"id": "meta/llama-3.3-70b-instruct:free", "context_length": 128000, "pricing": {"prompt": "0", "completion": "0"}, "supported_parameters": ["tools", "temperature"]},
        {"id": "other/qwen3-coder:free", "context_length": 262000, "pricing": {"prompt": "0", "completion": "0"}, "supported_parameters": ["tools"]}]}

    def getter(self, data, status=200):
        calls = []

        def get(url, headers=None, timeout=None):
            calls.append((url, headers))
            return Resp(status=status, data=data, text=json.dumps(data))
        get.calls = calls
        return get

    def test_groq_list_drops_what_cannot_chat(self):
        get = self.getter(self.GROQ)
        ids = [m["id"] for m in remote_models.list_models("groq", get=get)]
        self.assertEqual(ids, ["llama-3.3-70b-versatile", "openai/gpt-oss-120b", "openai/gpt-oss-20b"])
        self.assertEqual(get.calls[0][0], "https://api.groq.com/openai/v1/models")
        self.assertEqual(get.calls[0][1]["Authorization"], "Bearer gsk_test")

    def test_openrouter_free_and_tools_flags(self):
        by = {m["id"]: m for m in remote_models.list_models("openrouter", get=self.getter(self.OPENROUTER))}
        self.assertFalse(by["paid/big-model"]["free"])
        self.assertTrue(by["free/chatty:free"]["free"])
        self.assertIs(by["free/chatty:free"]["tools"], False)
        self.assertIs(by["meta/llama-3.3-70b-instruct:free"]["tools"], True)

    def test_lists_are_cached_until_refreshed(self):
        get = self.getter(self.GROQ)
        remote_models.list_models("groq", get=get)
        remote_models.list_models("groq", get=get)
        self.assertEqual(len(get.calls), 1)
        remote_models.list_models("groq", get=get, refresh=True)
        self.assertEqual(len(get.calls), 2)

    def test_list_errors(self):
        with self.assertRaises(RemoteError) as cm:
            remote_models.list_models("groq", get=self.getter({}, status=401))
        self.assertEqual(cm.exception.kind, "auth")
        os.environ.pop("GROQ_API_KEY")
        with self.assertRaises(RemoteError):
            remote_models.list_models("groq", get=self.getter(self.GROQ), refresh=True)

        def down(*a, **k):
            raise requests.ConnectionError("x")
        os.environ["GROQ_API_KEY"] = "k"
        with self.assertRaises(RemoteError) as cm:
            remote_models.list_models("groq", get=down, refresh=True)
        self.assertEqual(cm.exception.kind, "network")

    def test_picks(self):
        groq = remote_models.list_models("groq", get=self.getter(self.GROQ))
        self.assertEqual(remote_models.pick_default("groq", groq), "openai/gpt-oss-120b")
        orr = remote_models.list_models("openrouter", get=self.getter(self.OPENROUTER))
        # free and tool-capable only; llama-3.3 is preferred over qwen3 by the pattern order
        self.assertEqual(remote_models.pick_default("openrouter", orr), "meta/llama-3.3-70b-instruct:free")
        self.assertEqual(remote_models.pick_default("groq", []), "")

    def test_openrouter_without_a_preferred_family_takes_the_largest_free_tool_model(self):
        models = [{"id": "a/x:free", "free": True, "tools": True, "context": 8000}, {"id": "b/y:free", "free": True, "tools": True, "context": 64000},
                  {"id": "c/z:free", "free": True, "tools": False, "context": 900000}, {"id": "d/w", "free": False, "tools": True, "context": 900000}]
        self.assertEqual(remote_models.pick_default("openrouter", models), "b/y:free")

    def test_model_for_prefers_the_setting_then_picks_once(self):
        cfg = config_loader.load_config()
        get = self.getter(self.GROQ)
        cfg["groq"]["model"] = "my/model"
        self.assertEqual(remote_models.model_for("groq", cfg, get=get), "my/model")
        self.assertEqual(get.calls, [])
        cfg["groq"]["model"] = ""
        self.assertEqual(remote_models.model_for("groq", cfg, get=get), "openai/gpt-oss-120b")
        remote_models.model_for("groq", cfg, get=get)
        self.assertEqual(len(get.calls), 1)

    def test_model_for_with_nothing_usable(self):
        with self.assertRaises(RemoteError) as cm:
            remote_models.model_for("groq", config_loader.load_config(), get=self.getter({"data": []}))
        self.assertEqual(cm.exception.kind, "model")
        self.assertIn("groq.model", str(cm.exception))


# ---------------------------------------------------------------- inside the orchestrator

class TestRemoteInTheConversation(Base):
    def setUp(self):
        super().setUp()
        remote_models.reset()
        self.addCleanup(remote_models.reset)
        env = mock.patch.dict(os.environ, {"GROQ_API_KEY": "gsk_test", "OPENROUTER_API_KEY": "sk-or-test"})
        env.start()
        self.addCleanup(env.stop)
        mm = mock.patch("remote_models.model_for", return_value="test-model")
        mm.start()
        self.addCleanup(mm.stop)
        self.chats = []
        self.script = []

        def fake_chat(provider, model, messages, tools=None, **kw):
            self.chats.append({"provider": provider, "model": model, "messages": copy.deepcopy(messages), "tools": tools, "kw": kw})
            item = self.script.pop(0)
            if isinstance(item, Exception):
                raise item
            return item
        patch = mock.patch("remote_models.chat", fake_chat)
        patch.start()
        self.addCleanup(patch.stop)
        self.c = self.convo()
        self.addCleanup(lambda: self.c.handle_command("/model auto"))
        executed = []
        self.executed = executed

        def execute(name, args, layer, extra=None):
            executed.append((name, args, layer))
            return True, "half past four"
        p = mock.patch.object(self.c, "_execute_tool", side_effect=execute)
        self.exec_patch = p.start()
        self.addCleanup(p.stop)

    def text(self, content, streamed=True):
        return {"content": content, "tool_calls": None, "streamed": streamed}

    def calls(self, *calls, content=""):
        return {"content": content, "tool_calls": [{"id": f"id{i}", "name": n, "arguments": json.dumps(a)} for i, (n, a) in enumerate(calls)], "streamed": bool(content)}

    def test_forcing_the_models(self):
        self.assertEqual(FORCEABLE_MODELS, ("qwen", "gemini", "lfm", "groq", "openrouter"))
        self.assertEqual(self.c.handle_command("/model groq"), "Forcing groq until you turn it off")
        self.assertIn("groq", self.c.handle_command("/model"))
        self.assertEqual(self.c.handle_command("/model openrouter 2"), "Forcing openrouter for next 2 turns")

    def test_a_plain_answer_with_the_real_prompt_and_tools(self):
        self.script = [self.text("Hello from Groq.")]
        self.c.handle_command("/model groq")
        reply = self.c.send("hello there")
        self.assertEqual(reply, "Hello from Groq.")
        call = self.chats[0]
        self.assertEqual((call["provider"], call["model"]), ("groq", "test-model"))
        names = [t["function"]["name"] for t in call["tools"]]
        self.assertIn("get_weather", names)
        self.assertIn("search_web", names)
        self.assertEqual(call["messages"][0]["role"], "system")
        self.assertIn("You can call these tools", call["messages"][0]["content"])
        self.assertEqual(call["messages"][-1], {"role": "user", "content": "hello there"})
        self.assertEqual([m["role"] for m in call["messages"]].count("system"), 1)
        self.assertTrue(self.c.last_reply_streamed)

    def test_the_tool_loop_feeds_results_back(self):
        self.script = [self.calls(("get_current_datetime", {})), self.text("It is half past four.")]
        self.c.handle_command("/model groq")
        self.assertEqual(self.c.send("what is the time, roughly?"), "It is half past four.")
        self.assertEqual(self.executed, [("get_current_datetime", {}, "groq")])
        second = self.chats[1]["messages"]
        self.assertEqual(second[-2]["role"], "assistant")
        self.assertEqual(second[-2]["tool_calls"][0], {"id": "id0", "type": "function", "function": {"name": "get_current_datetime", "arguments": "{}"}})
        self.assertEqual(second[-1], {"role": "tool", "tool_call_id": "id0", "content": "half past four"})
        self.assertIsNotNone(self.chats[1]["tools"])

    def test_parallel_calls_each_get_their_result(self):
        self.script = [self.calls(("get_current_datetime", {}), ("get_battery_status", {})), self.text("Both done.")]
        self.c.handle_command("/model groq")
        self.c.send("Check two things for me: the clock reading and the charge level")
        self.assertEqual([e[0] for e in self.executed], ["get_current_datetime", "get_battery_status"])
        tools = [m for m in self.chats[1]["messages"] if m["role"] == "tool"]
        self.assertEqual([m["tool_call_id"] for m in tools], ["id0", "id1"])

    def test_an_identical_repeat_is_not_run_twice(self):
        self.script = [self.calls(("get_current_datetime", {})), self.calls(("get_current_datetime", {})), self.text("Done.")]
        self.c.handle_command("/model groq")
        self.c.send("time please, and think about it")
        self.assertEqual(len(self.executed), 1)
        self.assertEqual(len([m for m in self.chats[2]["messages"] if m["role"] == "tool"]), 2)

    def test_tool_errors_go_back_as_text(self):
        self.exec_patch.side_effect = lambda name, args, layer, extra=None: (False, "no wifi radio")
        self.script = [self.calls(("get_wifi_status", {})), self.text("I couldn't check the connection.")]
        self.c.handle_command("/model groq")
        self.c.send("Is the wireless thing hooked up right now?")
        self.assertEqual(self.chats[1]["messages"][-1]["content"], "Error: no wifi radio")

    def test_the_last_round_has_no_tools_and_a_loop_is_cut(self):
        self.script = [self.calls(("get_current_datetime", {}), content=""), self.calls(("get_battery_status", {}))]
        with self.assertRaises(RemoteError) as cm:
            self.c._call_remote_with_tools("groq", max_rounds=2)
        self.assertIn("kept calling tools", str(cm.exception))
        self.assertIsNotNone(self.chats[0]["tools"])
        self.assertIsNone(self.chats[1]["tools"])

    def test_a_garbled_tool_call_is_retried_then_asked_without_tools(self):
        bad = RemoteError("tool_failed", "the model's tool call was malformed")
        self.script = [bad, self.text("Fine now.")]
        self.assertEqual(self.c._call_remote_with_tools("groq"), "Fine now.")
        self.script = [bad, bad, self.text("Answered without tools.")]
        self.chats.clear()
        self.assertEqual(self.c._call_remote_with_tools("groq"), "Answered without tools.")
        self.assertIsNone(self.chats[2]["tools"])
        self.script = [bad, bad, bad]
        with self.assertRaises(RemoteError):
            self.c._call_remote_with_tools("groq")

    def test_malformed_arguments_become_empty_ones(self):
        self.script = [{"content": "", "tool_calls": [{"id": "x", "name": "get_current_datetime", "arguments": "{not json"}], "streamed": False}, self.text("ok")]
        self.c._call_remote_with_tools("groq")
        self.assertEqual(self.executed[0][1], {})

    def test_text_before_a_tool_call_is_dropped_from_the_stream(self):
        tokens = []
        self.c.on_token = lambda kind, text: tokens.append(kind)
        self.script = [self.calls(("get_current_datetime", {}), content="Let me check. "), self.text("It is half past four.")]
        self.c._call_remote_with_tools("groq")
        self.assertEqual(tokens, ["reset"])

    def test_the_stream_hook_is_handed_to_the_client(self):
        hook = lambda kind, text: None  # noqa: E731
        self.c.on_token = hook
        self.script = [self.text("x")]
        self.c._call_remote_with_tools("openrouter")
        self.assertIs(self.chats[0]["kw"]["on_token"], hook)

    def test_empty_reply_is_an_error(self):
        self.script = [self.text("   ")]
        with self.assertRaises(RemoteError) as cm:
            self.c._call_remote_with_tools("groq")
        self.assertEqual(cm.exception.kind, "empty")

    def test_stop_cancels_the_turn(self):
        self.script = [Cancelled()]
        with self.assertRaises(TurnCancelled):
            self.c._call_remote_with_tools("groq")

    # ---- fallbacks

    def test_a_rate_limited_forced_model_falls_to_the_next_online_one_then_local(self):
        self.c.config["online"]["order"] = "groq,openrouter"
        self.c.handle_command("/model groq")
        self.script = [RemoteError("rate_limit", "rate limited: slow down", 30), self.text("OpenRouter answered.")]
        self.assertEqual(self.c.send("hello again"), "OpenRouter answered.")
        self.assertEqual([c["provider"] for c in self.chats], ["groq", "openrouter"])

    def test_everything_online_failing_falls_back_to_the_local_model(self):
        self.c.config["online"]["order"] = "groq,openrouter"
        self.c.handle_command("/model groq")
        self.llama.script = [{"content": "The phone answered."}]
        self.script = [RemoteError("rate_limit", "rate limited", 30), RemoteError("server", "down")]
        with mock.patch.object(self.c, "_gemini_on", return_value=False):
            self.assertEqual(self.c.send("is anyone there"), "The phone answered.")

    def test_a_missing_key_is_skipped_without_a_request(self):
        os.environ.pop("GROQ_API_KEY")
        self.c.handle_command("/model groq")
        self.llama.script = [{"content": "Local it is."}]
        with mock.patch.object(self.c, "_gemini_on", return_value=False):
            self.assertEqual(self.c.send("no key here"), "Local it is.")
        self.assertEqual(self.chats, [])

    def test_when_nothing_answers_the_reasons_are_listed(self):
        self.c.config["online"]["order"] = "groq"
        self.c.handle_command("/model groq")
        self.c.config["fallback"]["order"] = ""
        self.script = [RemoteError("auth", "the key was refused (401): Invalid API Key")]
        with mock.patch.object(self.c, "_gemini_on", return_value=False):
            reply = self.c.send("hello?")
        self.assertTrue(reply.startswith("[couldn't answer -- groq: the key was refused (401)"), reply)
        self.assertNotIn("RemoteError:", reply)

    def test_gemini_can_be_the_fallback_for_a_forced_online_model(self):
        self.c.config["online"]["order"] = "groq,gemini"
        self.c.handle_command("/model groq")
        self.script = [RemoteError("rate_limit", "rate limited", 30)]
        with mock.patch.object(self.c, "_gemini_on", return_value=True), \
                mock.patch.object(self.c, "_call_gemini_with_tools", return_value="Gemini caught it."):
            self.assertEqual(self.c.send("fall back please"), "Gemini caught it.")

    def test_a_forced_remote_error_without_any_fallback_is_shown_plainly(self):
        self.c.handle_command("/model groq 1")
        self.c.config["fallback"]["order"] = ""
        self.script = [RemoteError("server", "the provider had an error (503): upstream down")]
        with mock.patch.object(self.c, "_gemini_on", return_value=False):
            reply = self.c.send("anything")
        self.assertIn("groq: the provider had an error (503)", reply)

    # ---- online.order and the auto chain

    def test_online_order_parsing(self):
        self.c.config["online"]["order"] = " groq , gemini,bogus,groq,openrouter "
        self.assertEqual(self.c._online_order(), ["groq", "gemini", "openrouter"])
        self.c.config["online"]["order"] = ""
        self.assertEqual(self.c._online_order(), [])

    def test_auto_chain_uses_the_order_and_only_what_is_available(self):
        self.c.config["online"]["order"] = "groq,gemini,openrouter"
        with mock.patch.object(self.c, "_gemini_on", return_value=True):
            tags = [t for t, _ in self.c._auto_chain()]
            self.assertEqual([t for t in tags if t in ("groq", "gemini", "openrouter")], ["groq", "gemini", "openrouter"])
            remote_models.block("groq", "rate_limit", 60)
            os.environ.pop("OPENROUTER_API_KEY")
            tags = [t for t, _ in self.c._auto_chain()]
            self.assertEqual([t for t in tags if t in ("groq", "gemini", "openrouter")], ["gemini"])

    def test_the_default_order_is_unchanged(self):
        self.assertEqual(self.c.config["online"]["order"], "gemini")
        with mock.patch.object(self.c, "_gemini_on", return_value=True):
            self.assertEqual([t for t, _ in self.c._auto_chain()][-1:], ["gemini"])
            self.assertNotIn("groq", [t for t, _ in self.c._auto_chain()])

    def test_auto_mode_reaches_groq_when_asked_to(self):
        self.c.config["online"]["order"] = "groq"
        self.c.config["boot"]["chat_while_booting"] = "wait"
        self.llama.script = [{"content": "unused"}]
        self.script = [self.text("Groq in auto.")]
        with mock.patch.object(self.c.local, "ensure_started"), mock.patch.object(type(self.c.local), "usable", new_callable=mock.PropertyMock, return_value=False), \
                mock.patch.object(self.c, "_gemini_on", return_value=False), \
                mock.patch.object(self.c, "_wait_then_qwen", side_effect=RuntimeError("not ready")):
            self.assertEqual(self.c.send("hello in auto"), "Groq in auto.")

    def test_settings_exist(self):
        for key, kind in (("groq.model", (str, None)), ("openrouter.model", (str, None)), ("online.order", (str, None))):
            self.assertEqual(config_loader.SETTINGS[key], kind)
        cfg = config_loader.load_config()
        self.assertEqual((cfg["groq"], cfg["openrouter"], cfg["online"]), ({"model": ""}, {"model": ""}, {"order": "gemini"}))


# ---------------------------------------------------------------- Tavily and search_web

class TestTavily(RemoteBase):
    GOOD = {"answer": "Python is a programming language.", "results": [
        {"title": "Python.org", "url": "https://python.org", "content": "The  official\nsite " + "x" * 400},
        {"title": "Wikipedia", "url": "https://en.wikipedia.org/wiki/Python", "content": "An encyclopedia entry."}]}

    def post(self, status=200, data=None, boom=None):
        calls = []

        def post(url, headers=None, json=None, timeout=None):
            calls.append((url, headers, json))
            if boom:
                raise boom
            return Resp(status=status, data=data if data is not None else {}, text="{}")
        post.calls = calls
        return post

    def test_formatting_and_request(self):
        post = self.post(data=self.GOOD)
        text = web_search.tavily_search("what is python", "tvly-test", 5, post=post)
        lines = text.splitlines()
        self.assertIn("what is python", lines[0])
        self.assertEqual(lines[1], "Summary: Python is a programming language.")
        self.assertTrue(lines[2].startswith("1. Python.org: The official site xxx"))
        self.assertLessEqual(len(lines[2]), 270)
        self.assertEqual(lines[3], "   https://python.org")
        url, headers, body = post.calls[0]
        self.assertEqual(url, "https://api.tavily.com/search")
        self.assertEqual(headers["Authorization"], "Bearer tvly-test")
        self.assertEqual((body["query"], body["max_results"], body["include_answer"]), ("what is python", 5, True))

    def test_errors_are_plain(self):
        cases = [(self.post(status=401), "refused the key"), (self.post(status=432), "limit reached \\(432\\)"), (self.post(status=429), "limit reached \\(429\\)"),
                 (self.post(status=500), "answered 500"), (self.post(data={"results": []}), "found nothing"),
                 (self.post(boom=requests.ConnectionError("x")), "could not be reached")]
        for post, text in cases:
            with self.assertRaisesRegex(web_search.SearchError, text):
                web_search.tavily_search("q", "k", post=post)
        with self.assertRaisesRegex(web_search.SearchError, "no key"):
            web_search.tavily_search("q", "")

    def test_an_answer_alone_is_enough(self):
        text = web_search.tavily_search("q", "k", post=self.post(data={"answer": "Just this.", "results": []}))
        self.assertIn("Summary: Just this.", text)


class TestSearchWebEngines(RemoteBase):
    def setUp(self):
        super().setUp()
        tool_executor.SEARCH.update(engine="auto", blocked_until=0.0, tavily_blocked_until=0.0)
        self.addCleanup(lambda: tool_executor.SEARCH.update(engine="auto", blocked_until=0.0, tavily_blocked_until=0.0))

    def search(self):
        return tool_executor.search_web({"query": "latest news"})

    def test_auto_uses_tavily_first_when_there_is_a_key(self):
        with mock.patch("web_search.tavily_search", return_value="TAVILY") as t, mock.patch("tool_executor.ask_gemini", side_effect=AssertionError("not needed")):
            self.assertEqual(self.search(), "TAVILY")
        t.assert_called_once_with("latest news", "tvly-test")

    def test_without_a_key_nothing_changes(self):
        os.environ.pop("TAVILY_API_KEY")
        with mock.patch("web_search.tavily_search", side_effect=AssertionError("no key")), \
                mock.patch("tool_executor.ask_gemini", return_value={"type": "text", "content": "GEMINI"}):
            self.assertEqual(self.search(), "GEMINI")

    def test_a_tavily_failure_falls_to_gemini_then_the_web(self):
        with mock.patch("web_search.tavily_search", side_effect=web_search.SearchError("Tavily could not be reached (X)")), \
                mock.patch("tool_executor.ask_gemini", return_value={"type": "text", "content": "GEMINI"}):
            self.assertEqual(self.search(), "GEMINI")
        tool_executor.SEARCH["tavily_blocked_until"] = 0.0
        with mock.patch("web_search.tavily_search", side_effect=web_search.SearchError("Tavily could not be reached (X)")), \
                mock.patch("tool_executor.ask_gemini", side_effect=tool_executor.GeminiError("Gemini API returned 429: quota")), \
                mock.patch("web_search.ddg_search", return_value=[{"title": "T", "snippet": "S", "url": "https://u"}]):
            text = self.search()
        self.assertIn("1. T: S", text)

    def test_a_reached_limit_pauses_tavily(self):
        with mock.patch("web_search.tavily_search", side_effect=web_search.SearchError("Tavily limit reached (432)")) as t, \
                mock.patch("tool_executor.ask_gemini", return_value={"type": "text", "content": "GEMINI"}):
            self.search()
            self.search()
        self.assertEqual(t.call_count, 1)
        self.assertGreater(tool_executor.SEARCH["tavily_blocked_until"], time.monotonic())

    def test_forced_tavily_never_falls_back(self):
        tool_executor.SEARCH["engine"] = "tavily"
        with mock.patch("web_search.tavily_search", side_effect=web_search.SearchError("Tavily refused the key")), \
                mock.patch("tool_executor.ask_gemini", side_effect=AssertionError("no fallback")):
            with self.assertRaisesRegex(tool_executor.ToolExecutionError, "Tavily refused the key"):
                self.search()

    def test_the_other_engines_never_touch_tavily(self):
        for engine in ("gemini", "web"):
            tool_executor.SEARCH["engine"] = engine
            with mock.patch("web_search.tavily_search", side_effect=AssertionError("not for " + engine)), \
                    mock.patch("tool_executor.ask_gemini", return_value={"type": "text", "content": "G"}), \
                    mock.patch("web_search.ddg_search", return_value=[{"title": "T", "snippet": "", "url": "u"}]):
                self.assertTrue(self.search())

    def test_the_setting_accepts_tavily(self):
        self.assertEqual(config_loader.SETTINGS["search.engine"], (str, ("auto", "gemini", "web", "tavily")))
        cfg = config_loader.load_config()
        cfg["search"]["engine"] = "tavily"
        tool_executor.configure(cfg)
        self.assertEqual(tool_executor.SEARCH["engine"], "tavily")


# ---------------------------------------------------------------- status and doctor

class TestProviderStatus(WebBase):
    def setUp(self):
        super().setUp()
        remote_models.reset()
        self.addCleanup(remote_models.reset)
        env = mock.patch.dict(os.environ, {"GROQ_API_KEY": "gsk_test"})
        env.start()
        self.addCleanup(env.stop)
        os.environ.pop("OPENROUTER_API_KEY", None)

    def test_status_describes_each_provider(self):
        p = self.jget("/api/status")[1]["providers"]
        self.assertEqual(p["groq"], {"has_key": True, "ready": True, "note": "ready", "model": ""})
        self.assertFalse(p["openrouter"]["has_key"])
        self.assertIn("OPENROUTER_API_KEY", p["openrouter"]["note"])
        remote_models.block("groq", "rate_limit", 60)
        p = self.jget("/api/status")[1]["providers"]["groq"]
        self.assertEqual((p["has_key"], p["ready"]), (True, False))
        self.assertTrue(p["note"].startswith("rate limited, retry in"))


class TestDoctorProviders(RemoteBase):
    def run_doctor(self, key="k"):
        import actions
        import stt
        with mock.patch.dict(os.environ, {"GEMINI_API_KEY": key}), \
                mock.patch("gemini_client.ask_gemini", return_value={"type": "text", "content": "OK"}), \
                mock.patch("web_search.ddg_search", return_value=[{"title": "t", "snippet": "", "url": "u"}]), \
                mock.patch.object(actions, "youtube_find", return_value=("aaaaaaaaaaa", "v")), \
                mock.patch("tts.synthesize_audio", return_value=(b"x", "audio/wav")), \
                mock.patch.object(stt, "available", return_value=True), \
                mock.patch("live_client.check", return_value=[("websocket", True, "stubbed")]):
            return {n: (s, d) for n, s, d in doctor.run_checks(config_loader.load_config())}

    def test_providers_without_keys_are_skipped(self):
        for name in ("GROQ_API_KEY", "OPENROUTER_API_KEY", "TAVILY_API_KEY"):
            os.environ.pop(name)
        rows = self.run_doctor()
        for title in ("Groq chat", "Groq tool calling", "OpenRouter chat", "OpenRouter tool calling", "Tavily search"):
            self.assertEqual(rows[title][0], "skip", title)
        self.assertIn("GROQ_API_KEY", rows["Groq chat"][1])

    def test_working_providers(self):
        def fake_chat(provider, model, messages, tools=None, **kw):
            if tools:
                return {"content": "", "tool_calls": [{"id": "1", "name": "get_current_datetime", "arguments": "{}"}], "streamed": False}
            return {"content": "OK", "tool_calls": None, "streamed": False}
        with mock.patch("remote_models.model_for", return_value="the-model"), mock.patch("remote_models.chat", fake_chat), \
                mock.patch("web_search.tavily_search", return_value="Web search results\\n1. A\\n   http://a\\n2. B\\n   http://b"):
            rows = self.run_doctor()
        self.assertEqual(rows["Groq chat"][0], "ok")
        self.assertIn("the-model answered", rows["Groq chat"][1])
        self.assertEqual(rows["OpenRouter tool calling"][0], "ok")
        self.assertIn("called get_current_datetime", rows["OpenRouter tool calling"][1])
        self.assertEqual(rows["Tavily search"][0], "ok")

    def test_a_model_that_ignores_the_tool_is_a_warning_and_errors_are_failures(self):
        with mock.patch("remote_models.model_for", return_value="m"), \
                mock.patch("remote_models.chat", return_value={"content": "It is noon.", "tool_calls": None, "streamed": False}):
            rows = self.run_doctor()
        self.assertEqual(rows["Groq tool calling"][0], "warn")
        with mock.patch("remote_models.model_for", return_value="m"), \
                mock.patch("remote_models.chat", side_effect=RemoteError("auth", "the key was refused (401): bad")):
            rows = self.run_doctor()
        self.assertEqual(rows["Groq chat"][0], "fail")
        self.assertIn("refused", rows["Groq chat"][1])

    def test_a_cooling_provider_is_reported_not_called(self):
        remote_models.block("groq", "rate_limit", 60)
        with mock.patch("remote_models.chat", side_effect=AssertionError("cooling down")), mock.patch("remote_models.model_for", return_value="m"):
            rows = self.run_doctor()
        self.assertEqual(rows["Groq chat"][0], "warn")
        self.assertIn("rate limited", rows["Groq chat"][1])


# ---------------------------------------------------------------- the bench

class FakeError(Exception):
    def __init__(self, kind, message="x", retry_after=0):
        super().__init__(message)
        self.kind, self.retry_after = kind, retry_after


def case(cid):
    return next(c for c in bench.CASES if c[0] == cid)


class TestJudge(unittest.TestCase):
    def call(self, name, args):
        return {"content": "", "tool_calls": [{"name": name, "arguments": json.dumps(args) if isinstance(args, dict) else args}]}

    def test_right_tool(self):
        self.assertEqual(bench.judge(case("time"), self.call("get_current_datetime", {})), (True, ""))
        self.assertTrue(bench.judge(case("volume"), self.call("set_volume", {"level": 6}))[0])
        self.assertTrue(bench.judge(case("open-app"), self.call("open_app", {"app": "Calculator"}))[0])
        self.assertTrue(bench.judge(case("weather"), {"content": "", "tool_calls": [{"name": "get_weather", "arguments": {"city": "Abuja"}}]})[0])

    def test_wrong_tool_arguments_or_none(self):
        ok, why = bench.judge(case("time"), self.call("get_battery_status", {}))
        self.assertFalse(ok)
        self.assertIn("called get_battery_status", why)
        ok, why = bench.judge(case("volume"), self.call("set_volume", {"level": 9}))
        self.assertFalse(ok)
        self.assertIn("wrong arguments", why)
        ok, why = bench.judge(case("search-news"), {"content": "It was X.", "tool_calls": None})
        self.assertFalse(ok)
        self.assertIn("without a tool", why)
        ok, why = bench.judge(case("time"), self.call("get_current_datetime", "{broken"))
        self.assertFalse(ok)
        self.assertIn("not valid JSON", why)

    def test_questions_that_need_no_tool(self):
        self.assertEqual(bench.judge(case("none-math"), {"content": "391", "tool_calls": None}), (True, ""))
        self.assertFalse(bench.judge(case("none-math"), self.call("search_web", {"query": "17*23"}))[0])
        self.assertEqual(bench.judge(case("none-joke"), {"content": "  ", "tool_calls": None}), (False, "empty answer"))

    def test_the_case_list_is_sane(self):
        ids = [c[0] for c in bench.CASES]
        self.assertEqual(len(ids), len(set(ids)))
        import tool_schema
        for cid, prompt, expected, check in bench.CASES:
            for name in expected or ():
                self.assertIn(name, tool_schema.TOOL_REGISTRY, cid)
        self.assertGreaterEqual(len([c for c in bench.CASES if c[2] is None]), 3)


class TestRunModel(unittest.TestCase):
    def runner(self, script, **kw):
        clock = [0.0]
        sleeps = []

        def tick():
            clock[0] += 0.5
            return clock[0]
        results = iter(script)

        def ask(prompt, on_first):
            item = next(results)
            if isinstance(item, Exception):
                raise item
            on_first()
            return item
        out = bench.run_model("p/m", ask, cases=kw.pop("cases", bench.CASES[:3]), sleep=sleeps.append, clock=tick, **kw)
        return out, sleeps

    def good(self, name):
        return {"content": "", "tool_calls": [{"name": name, "arguments": "{}"}]}

    def test_scores_and_times(self):
        out, sleeps = self.runner([self.good("get_current_datetime"), self.good("get_battery_status"), self.good("get_wifi_status")], pause=2)
        self.assertEqual((out["passed"], out["total"], out["answered"]), (2, 3, 3))
        self.assertEqual([r["passed"] for r in out["rows"]], [True, True, False])
        self.assertIsNotNone(out["avg_s"])
        self.assertIsNotNone(out["first_token_s"])
        self.assertEqual(sleeps, [2, 2, 2])
        self.assertEqual(out["errors"], {})

    def test_errors_are_counted_and_the_run_goes_on(self):
        out, _ = self.runner([FakeError("server", "boom"), self.good("get_battery_status"), self.good("set_volume")])
        self.assertEqual(out["errors"], {"server": 1})
        self.assertEqual(out["answered"], 2)
        self.assertIn("server: boom", out["rows"][0]["why"])

    def test_a_short_rate_limit_waits_and_retries(self):
        out, sleeps = self.runner([FakeError("rate_limit", "slow", retry_after=3), self.good("get_current_datetime"), self.good("get_battery_status"),
                                   self.good("set_volume")], cases=bench.CASES[:3])
        self.assertEqual(out["errors"].get("rate_limit", 0), 0)
        self.assertEqual(out["answered"], 3)
        self.assertIn(4, sleeps)

    def test_a_long_rate_limit_skips_the_rest(self):
        out, _ = self.runner([FakeError("rate_limit", "slow", retry_after=300)])
        self.assertEqual(out["errors"], {"rate_limit": 1})
        self.assertEqual(out["answered"], 0)
        self.assertEqual([r["why"].startswith("skipped") or r["why"].startswith("rate_limit") for r in out["rows"]], [True, True, True])

    def test_percentile_and_render(self):
        self.assertEqual(bench.percentile([1, 2, 3, 4, 100], 0.95), 100)
        self.assertEqual(bench.percentile([], 0.5), 0.0)
        out, _ = self.runner([self.good("get_current_datetime"), self.good("get_battery_status"), self.good("get_wifi_status")])
        text = bench.render([out, {**out, "model": "q/n", "errors": {"rate_limit": 2}, "avg_s": None, "p95_s": None, "passed": 0}])
        lines = text.splitlines()
        self.assertTrue(lines[0].startswith("model"))
        self.assertIn("p/m", lines[1])
        self.assertIn("2/3", lines[1])
        self.assertIn("66%", lines[1])
        self.assertIn("rate_limit x2", lines[2])
        self.assertIn("p/m missed:", text)
        self.assertIn("volume: called get_wifi_status (wanted set_volume)", text)


class TestAskers(unittest.TestCase):
    def test_remote_asker_sends_the_system_prompt_tools_and_marks_the_first_token(self):
        seen = {}

        def chat(provider, model, messages, tools, on_token=None, max_tokens=0):
            seen.update(provider=provider, model=model, messages=messages, tools=tools, max_tokens=max_tokens)
            on_token("answer", "x")
            return {"content": "x", "tool_calls": None, "streamed": True}
        firsts = []
        ask = bench.remote_asker("groq", "m", "SYSTEM", ["schema"], chat=chat)
        ask("hello", lambda: firsts.append(1))
        self.assertEqual(seen["messages"], [{"role": "system", "content": "SYSTEM"}, {"role": "user", "content": "hello"}])
        self.assertEqual((seen["provider"], seen["model"], seen["tools"], seen["max_tokens"]), ("groq", "m", ["schema"], 400))
        self.assertEqual(firsts, [1])

    def test_gemini_asker_normalizes_both_shapes(self):
        ask = bench.gemini_asker("SYS", ["schema"], ask_gemini=lambda system, msgs, tools_schema=None: {"type": "text", "content": "391"})
        self.assertEqual(ask("17*23", lambda: None), {"content": "391", "tool_calls": None})
        one = {"type": "function_call", "name": "get_battery_status", "args": {}}
        ask = bench.gemini_asker("SYS", [], ask_gemini=lambda *a, **k: one)
        self.assertEqual(ask("battery", lambda: None)["tool_calls"], [{"name": "get_battery_status", "arguments": {}}])
        many = {**one, "calls": [one, {"type": "function_call", "name": "set_volume", "args": {"level": 3}}]}
        ask = bench.gemini_asker("SYS", [], ask_gemini=lambda *a, **k: many)
        self.assertEqual([c["name"] for c in ask("x", lambda: None)["tool_calls"]], ["get_battery_status", "set_volume"])


class TestRankedModels(RemoteBase):
    def test_ranking_follows_the_preferred_families_then_context(self):
        models = [{"id": "zz/other", "free": True, "tools": True, "context": 500000}, {"id": "llama-3.3-70b-versatile", "free": True, "tools": None, "context": 131072},
                  {"id": "openai/gpt-oss-120b", "free": True, "tools": None, "context": 131072}, {"id": "qwen/qwen3-32b", "free": True, "tools": None, "context": 131072}]
        with mock.patch("remote_models.list_models", return_value=models):
            self.assertEqual(bench.ranked_models("groq"), ["openai/gpt-oss-120b", "llama-3.3-70b-versatile", "qwen/qwen3-32b", "zz/other"])

    def test_openrouter_only_ranks_free_models_that_can_use_tools(self):
        models = [{"id": "a/paid", "free": False, "tools": True, "context": 9}, {"id": "b/notools:free", "free": True, "tools": False, "context": 9},
                  {"id": "c/ok:free", "free": True, "tools": True, "context": 9}]
        with mock.patch("remote_models.list_models", return_value=models):
            self.assertEqual(bench.ranked_models("openrouter"), ["c/ok:free"])


class TestBenchMain(RemoteBase):
    def run_main(self, argv, env=None):
        import io
        out_path = os.path.join(tempfile.mkdtemp(prefix="faze-bench-"), "out.json")
        buf = io.StringIO()
        patches = [mock.patch.dict(os.environ, {"FAZE_BENCH_OUT": out_path, **(env or {})}), mock.patch("env_loader.load_env", return_value={}),
                   mock.patch("netfix.install", return_value=True), mock.patch("sys.stdout", buf)]
        for p in patches:
            p.start()
        try:
            rc = bench.main(argv)
        finally:
            mock.patch.stopall()
        return rc, buf.getvalue(), out_path

    def test_no_keys(self):
        for name in ("GROQ_API_KEY", "OPENROUTER_API_KEY"):
            os.environ.pop(name)
        with mock.patch.dict(os.environ, {"GEMINI_API_KEY": ""}):
            os.environ.pop("GEMINI_API_KEY")
            rc, out, _ = self.run_main([])
        self.assertEqual(rc, 1)
        self.assertIn("no keys found", out)

    def test_unknown_argument_prints_the_help(self):
        rc, out, _ = self.run_main(["--bogus"])
        self.assertEqual(rc, 2)
        self.assertIn("faze bench", out.replace("python3 bench.py", "faze bench"))

    def test_list(self):
        with mock.patch("bench.ranked_models", return_value=["a", "b"]):
            rc, out, _ = self.run_main(["--list", "groq", "gemini"])
        self.assertEqual(rc, 0)
        self.assertIn("groq: a, b", out)
        self.assertIn("gemini: ", out)

    def test_a_run_prints_a_table_and_saves_json(self):
        def fake_asker(provider, model, system, schema, chat=None):
            return lambda prompt, on_first: (on_first(), {"content": "ok", "tool_calls": None})[1]
        with mock.patch("bench.ranked_models", return_value=["m1", "m2", "m3"]), mock.patch("bench.remote_asker", fake_asker), mock.patch("bench.CASES", bench.CASES[-4:]):
            rc, out, path = self.run_main(["groq", "--models", "2", "--pause", "0"])
        self.assertEqual(rc, 0)
        self.assertIn("groq/m1", out)
        self.assertIn("groq/m2", out)
        self.assertNotIn("groq/m3", out)
        self.assertIn("saved to", out)
        saved = json.load(open(path))
        self.assertEqual([r["model"] for r in saved["results"]], ["groq/m1", "groq/m2"])
        self.assertEqual(saved["results"][0]["passed"], 4)

    def test_a_single_named_model(self):
        names = []

        def fake_asker(provider, model, system, schema, chat=None):
            names.append(model)
            return lambda prompt, on_first: {"content": "ok", "tool_calls": None}
        with mock.patch("bench.remote_asker", fake_asker), mock.patch("bench.CASES", bench.CASES[-1:]):
            rc, out, _ = self.run_main(["openrouter", "--model", "x/y:free", "--pause", "0"])
        self.assertEqual((rc, names), (0, ["x/y:free"]))

    def test_a_provider_that_cannot_list_models_is_skipped_not_fatal(self):
        with mock.patch("bench.ranked_models", side_effect=RemoteError("auth", "the key was refused (401)")):
            rc, out, _ = self.run_main(["groq"])
        self.assertIn("groq: skipped (the key was refused (401))", out)


class TestBenchScript(p17.TestOverlayButtonCommands):
    def test_bench_runs_in_the_core_folder_with_its_arguments(self):
        core = os.path.join(self.dir, "core")
        os.makedirs(core)
        with open(os.path.join(core, "bench.py"), "w") as f:
            f.write("import sys\nprint('ARGS', sys.argv[1:])\nsys.exit(3)\n")
        self.env["FAZE_CORE"] = core
        out = self.run_svc("bench", "--list", "groq")
        self.assertEqual((out.returncode, out.stdout.strip()), (3, "ARGS ['--list', 'groq']"))

    def test_usage_lists_it(self):
        self.assertIn("bench", self.run_svc("bogus").stdout)


# ---------------------------------------------------------------- the model sheet

@unittest.skipUnless(HAVE_JSDOM, "jsdom not installed")
class TestModelSheetRows(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.res = run_node("ui_smoke.js")

    def test_only_providers_with_a_key_get_a_row(self):
        self.assertNotIn("crash", self.res, self.res.get("crash"))
        self.assertEqual(self.res["errors"], [])
        names = self.res["providerRows"]["names"]
        self.assertEqual(names, ["Auto", "Qwen", "LFM", "Gemini", "Groq"])
        self.assertNotIn("OpenRouter", names)
        self.assertEqual(self.res["providerRows"]["groqSub"], "openai/gpt-oss-120b")

    def test_choosing_it_sends_the_command_and_the_thinking_box_says_it_does_not_apply(self):
        self.assertEqual(self.res["groqCmds"], ["/model groq"])
        self.assertEqual(self.res["groqMode"], "groq")
        self.assertIn("no thinking setting yet", self.res["think"]["hintGroq"])
        self.assertTrue(self.res["think"]["disabledGroq"])


# ---------------------------------------------------------------- the last state comes after the cleanup

class FinalStateSpy:
    """Records, at the moment a closing state is published, whether the microphone hold and the chat lock were already released."""

    def __init__(self, test, listener):
        self.seen = []
        original = test.voice._set

        def spy(gen, **fields):
            if fields.get("state") in ("done", "idle", "error"):
                self.seen.append((fields["state"], list(listener.holds), test.httpd.chat_lock.locked()))
            return original(gen, **fields)
        test.voice._set = spy


class TestClassicFinalState(VoiceBase):
    def test_done_is_published_after_the_hold_and_lock_are_released(self):
        spy = FinalStateSpy(self, self.listener)
        with self.say():
            self.voice.start()
            self.wait_state("done")
        self.assertEqual([x for x in spy.seen if x[0] == "done"], [("done", [True, False], False)])

    def test_so_is_nothing_heard(self):
        spy = FinalStateSpy(self, self.listener)
        self.listener.pcm = None
        self.voice.start()
        self.wait_state("idle")
        self.assertEqual(spy.seen[-1], ("idle", [True, False], False))

    def test_and_an_error(self):
        import stt
        spy = FinalStateSpy(self, self.listener)
        with mock.patch("stt.transcribe_best", side_effect=stt.TranscribeError("no engine")):
            self.voice.start()
            self.wait_state("error")
        self.assertEqual(spy.seen[-1], ("error", [True, False], False))

    def test_pressing_again_at_once_gets_its_own_hold(self):
        with self.say():
            self.voice.start()
            self.wait_state("done")
            self.voice.start()
            self.wait_state("done", seconds=8)
        self.assertEqual(self.listener.holds, [True, False, True, False])


class TestLiveFinalState(p21.LiveBase):
    def test_done_is_published_after_everything_is_released(self):
        spy = FinalStateSpy(self, self.frames)
        self.serve()
        self.voice.start(live=True)
        self.wait_state("done", seconds=15)
        self.assertEqual([x for x in spy.seen if x[0] == "done"], [("done", [True, False], False)])

    def test_so_is_an_early_close(self):
        spy = FinalStateSpy(self, self.frames)
        self.serve(scripted=False, close_after_setup=(1011, "internal error"))
        self.voice.start(live=True)
        self.wait_state("error", seconds=10)
        self.assertEqual(spy.seen[-1], ("error", [True, False], False))


if __name__ == "__main__":
    unittest.main()
