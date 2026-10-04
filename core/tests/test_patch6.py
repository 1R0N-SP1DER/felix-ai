"""
Tests for names, persistent model choice, thinking toggle, cancel, offline
speech-to-text, the command list and the app's pure JS helpers.

    PYTHONPATH=. python3 -m unittest tests.test_patch6 -v
"""

import json
import os
import shutil
import stat
import struct
import subprocess
import sys
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
from unittest import mock

CORE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, CORE)

from tests.test_patch1 import TMP, FakeLlama, Base, free_port, make_convo  # noqa: E402
from tests.test_patch5 import WebBase, TOKEN  # noqa: E402

import config_loader  # noqa: E402
import identity  # noqa: E402
import orchestrator  # noqa: E402
import prompt_assembler  # noqa: E402
import stt  # noqa: E402
from config_loader import load_config  # noqa: E402
from orchestrator import Conversation, COMMANDS  # noqa: E402
from pronouns import to_third_person  # noqa: E402


def wav(seconds=0.2, rate=16000, channels=1, bits=16):
    n = int(rate * seconds)
    data = b"\x00\x00" * n * channels if bits == 16 else b"\x00" * n * channels
    header = (b"RIFF" + struct.pack("<I", 36 + len(data)) + b"WAVEfmt " + struct.pack("<IHHIIHH", 16, 1, channels, rate,
              rate * channels * bits // 8, channels * bits // 8, bits) + b"data" + struct.pack("<I", len(data)))
    return header + data


def fake_whisper(name, body):
    path = os.path.join(TMP, name)
    with open(path, "w") as f:
        f.write("#!/bin/sh\n" + body + "\n")
    os.chmod(path, os.stat(path).st_mode | stat.S_IEXEC)
    return path


def model_file(name):
    path = os.path.join(TMP, name)
    with open(path, "w"):
        pass
    return path


class SlowLlama:
    """llama-server that streams one token every 0.1s for up to 20s."""

    def __init__(self):
        self.closed_early = threading.Event()
        outer = self

        class H(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def do_POST(self):
                self.rfile.read(int(self.headers["Content-Length"]))
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream")
                self.end_headers()
                try:
                    for _ in range(200):
                        self.wfile.write(b'data: {"choices":[{"delta":{"content":"tok "}}]}\n\n')
                        self.wfile.flush()
                        time.sleep(0.1)
                except (BrokenPipeError, ConnectionResetError, OSError):
                    outer.closed_early.set()

        self.server = HTTPServer(("127.0.0.1", 0), H)
        self.port = self.server.server_address[1]
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def close(self):
        self.server.shutdown()
        self.server.server_close()


class TestNames(Base):
    def tearDown(self):
        prompt_assembler.set_names("Faze", "Felix")
        super().tearDown()

    def test_rename_rebuilds_the_prompt_and_status(self):
        c = self.convo()
        self.assertIn("You are Faze, not Felix", c.messages[0]["content"])
        self.assertEqual(c.handle_command("/settings set assistant.name Nova"), "assistant.name = Nova")
        self.assertEqual(c.handle_command("/settings set assistant.user_name Sam"), "assistant.user_name = Sam")
        system = c.messages[0]["content"]
        self.assertIn("You are Nova, not Sam", system)
        self.assertNotIn("Faze", system)
        self.assertNotIn("Felix", system)
        self.assertEqual(c.config["assistant_name"], "Nova")
        self.assertEqual(c._names(), ("Nova", "Sam"))
        self.assertEqual(load_config()["assistant"], {"name": "Nova", "user_name": "Sam"})  # persisted

    def test_memories_use_the_users_name(self):
        c = self.convo()
        c.handle_command("/settings set assistant.user_name Sam")
        ctx = prompt_assembler.build_turn_context("whats my phone?")
        self.assertIn("FACTS ABOUT SAM", ctx)
        self.assertIn("Sam's phone is a Redmi 10C.", ctx)
        self.assertNotIn("Felix", ctx)

    def test_name_validation(self):
        c = self.convo()
        for bad in ("x" * 25, "<script>", "a;b"):
            self.assertTrue(c.handle_command(f"/settings set assistant.name {bad}").startswith("Setting not changed"), bad)
        with self.assertRaises(ValueError):
            config_loader.set_user_setting("assistant.name", "x" * 25)
        with self.assertRaises(ValueError):
            config_loader.set_user_setting("assistant.name", "bad\nname")
        self.assertEqual(config_loader.set_user_setting("assistant.name", "Zoë-2")[0], "Zoë-2")

    def test_pronoun_rewrite_uses_custom_name(self):
        self.assertEqual(to_third_person("My phone is a Redmi. I like anime.", "Sam"), "Sam's phone is a Redmi. Sam likes anime.")

    def test_identity_guard(self):
        f = identity.fix_identity
        self.assertEqual(f("Yo, I'm Felix. Your phone is a Redmi.", "Faze", "Felix"), "Yo, I'm Faze. Your phone is a Redmi.")
        self.assertEqual(f("my name is felix", "Faze", "Felix"), "my name is Faze")
        self.assertEqual(f("call me Felix", "Faze", "Felix"), "call me Faze")
        for untouched in ("I'm not Felix lol", "Felix's phone is a Redmi", "Hey Felix, what's up", "you're Felix"):
            self.assertEqual(f(untouched, "Faze", "Felix"), untouched)

    def test_model_reply_claiming_to_be_the_user_is_corrected(self):
        self.llama.script = [{"content": "yo I'm Felix, what's good"}]
        c = self.convo()
        self.assertEqual(c.send("who are you"), "yo I'm Faze, what's good")
        self.assertEqual(c.messages[-1]["content"], "yo I'm Faze, what's good")


class TestPersistentModel(Base):
    def test_no_count_persists_and_survives_restart(self):
        c = self.convo()
        self.assertEqual(c.handle_command("/model qwen"), "Forcing qwen until you turn it off")
        self.assertEqual(config_loader.load_user_overrides()["model"]["forced"], "qwen")
        self.assertEqual(c.handle_command("/model"), "Current mode: qwen (until you turn it off)")
        again = Conversation(load_config())
        self.assertEqual((again.forced_model, again.forced_remaining), ("qwen", 0))
        self.assertEqual(again.handle_command("/model auto"), "Switched to auto routing")
        self.assertIsNone(again.forced_model)
        self.assertNotIn("model", config_loader.load_user_overrides())
        self.assertIsNone(Conversation(load_config()).forced_model)

    def test_counted_choice_is_session_only(self):
        c = self.convo()
        c.handle_command("/model gemini 3")
        self.assertNotIn("model", config_loader.load_user_overrides())
        self.assertIsNone(Conversation(load_config()).forced_model)

    def test_counted_choice_returns_to_the_saved_one(self):
        self.llama.script = [{"content": "from qwen"}]
        c = self.convo()
        c.handle_command("/model qwen")
        c.handle_command("/model gemini 1")
        with mock.patch("orchestrator.ask_gemini", return_value={"type": "text", "content": "from gemini"}):
            self.assertEqual(c.send("one"), "from gemini")
        self.assertEqual(c.forced_model, "qwen")
        self.assertEqual(c.send("two"), "from qwen")

    def test_settings_row_changes_the_forced_model(self):
        c = self.convo()
        c.handle_command("/settings set model.forced lfm")
        self.assertEqual(c.forced_model, "lfm")
        c.handle_command("/settings unset model.forced")
        self.assertIsNone(c.forced_model)

    def test_disk_trouble_does_not_break_chat(self):
        c = self.convo()
        with mock.patch("orchestrator.set_user_setting", side_effect=OSError("disk full")):
            self.assertEqual(c.handle_command("/model qwen"), "Forcing qwen until you turn it off")
        self.assertEqual(c.forced_model, "qwen")


class TestThinking(Base):
    def test_on_always_thinks_and_is_saved(self):
        self.llama.script = [{"content": "thought about it"}]
        c = self.convo()
        self.assertIn("Reasoning: on", c.handle_command("/think on"))
        self.assertEqual(config_loader.load_user_overrides()["reasoning"]["mode"], "on")
        self.assertEqual(c.send("hard question"), "thought about it")
        req = self.llama.requests[0]
        self.assertTrue(req["chat_template_kwargs"]["enable_thinking"])
        self.assertEqual(req["max_tokens"], 1500)

    def test_auto_does_not_think_by_default(self):
        self.llama.script = [{"content": "quick"}]
        c = self.convo()
        self.assertEqual(c.handle_command("/think"), "Reasoning: auto")
        c.send("hi there")
        self.assertFalse(self.llama.requests[0]["chat_template_kwargs"]["enable_thinking"])

    def test_off_refuses_the_thinking_tool(self):
        self.llama.script = [{"tool": ("enable_thinking_mode", "{}")}]
        c = self.convo()
        c.handle_command("/think off")
        reply = c.send("prove something")
        self.assertIn("thinking is turned off", reply)
        self.assertEqual(len(self.llama.requests), 1)

    def test_bad_argument(self):
        c = self.convo()
        self.assertEqual(c.handle_command("/think maybe"), "Usage: /think auto|on|off")
        self.assertIn("think: auto", c.handle_command("/status"))


class TestCancel(unittest.TestCase):
    def setUp(self):
        os.environ["FAZE_SETTINGS"] = os.path.join(TMP, f"c-{time.time_ns()}.json")
        self.slow = SlowLlama()
        self.needle = mock.patch("orchestrator.ask_needle", return_value=None)
        self.needle.start()

    def tearDown(self):
        self.needle.stop()
        self.slow.close()

    def test_stop_ends_the_reply_fast_and_does_not_fall_back(self):
        c = make_convo(self.slow.port)
        got = threading.Event()
        c.on_token = lambda kind, text: got.set()
        out = {}
        with mock.patch("orchestrator.ask_gemini") as gemini:
            t = threading.Thread(target=lambda: out.setdefault("r", c.send("write a long story")))
            t.start()
            self.assertTrue(got.wait(10), "no token arrived")
            started = time.time()
            c.cancel()
            t.join(5)
            self.assertFalse(t.is_alive())
            self.assertLess(time.time() - started, 3)
        gemini.assert_not_called()
        self.assertEqual(out["r"], "[stopped]")
        self.assertEqual([m["role"] for m in c.messages], ["system"])
        self.assertTrue(self.slow.closed_early.wait(5), "llama-server was not told to stop")

    def test_next_turn_works_after_a_stop(self):
        c = make_convo(self.slow.port)
        c.cancel()  # a stale cancel must not poison the next message
        with mock.patch("orchestrator.execute_tool", return_value="9% battery, status: FULL"):
            self.assertEqual(c.send("whats my battery"), "Battery is at 9%, full.")


class TestCommandsAndReset(Base):
    def test_help_lists_every_command(self):
        c = self.convo()
        text = c.handle_command("/help")
        for cmd in COMMANDS:
            self.assertIn("/" + cmd["name"], text)
        self.assertEqual(c.commands(), COMMANDS)
        self.assertTrue(c.handle_command("/zzz").startswith("Unknown command /zzz"))

    def test_reset_clears_history_but_keeps_the_prompt(self):
        self.llama.script = [{"content": "hello"}]
        c = self.convo()
        c.send("hi")
        system = c.messages[0]
        self.assertEqual(c.handle_command("/reset"), "Conversation cleared")
        self.assertEqual(c.messages, [system])

    def test_command_list_shape(self):
        names = [c["name"] for c in COMMANDS]
        self.assertEqual(len(names), len(set(names)))
        for c in COMMANDS:
            self.assertTrue(c["description"] and c["usage"].startswith("/" + c["name"]), c)
            for a in c["args"]:
                self.assertTrue(a["value"] and a["description"])


class TestWebAdditions(WebBase):
    def test_status_has_the_new_fields(self):
        code, s = self.jget("/api/status")
        self.assertEqual((s["user_name"], s["think"], s["forced_turns"]), ("Felix", "auto", 0))
        self.assertIn("server", s["stt"])
        self.assertEqual(s["stt"]["pref"], "auto")

    def test_commands_endpoint(self):
        self.assertEqual(self.jget("/api/commands", token=None)[0], 401)
        code, d = self.jget("/api/commands")
        self.assertEqual(code, 200)
        self.assertEqual(d["commands"], COMMANDS)

    def test_cancel_endpoint(self):
        self.assertEqual(self.jpost("/api/cancel", {}, token=None)[0], 401)
        code, d = self.jpost("/api/cancel", {})
        self.assertEqual((code, d), (200, {"ok": True}))
        self.assertTrue(self.convo._cancel.is_set())

    def test_slash_commands_through_the_api(self):
        self.assertEqual(self.jpost("/api/command", {"line": "/think on"})[1]["text"][:13], "Reasoning: on")
        self.assertEqual(self.jget("/api/status")[1]["think"], "on")
        self.assertEqual(self.jpost("/api/command", {"line": "/reset"})[1]["text"], "Conversation cleared")

    def test_disconnect_cancels_the_turn(self):
        import socket as sock
        gate = threading.Event()

        def slow(message):
            gate.wait(10)
            return "reply nobody reads"
        with mock.patch.object(self.convo, "send", side_effect=slow):
            s = sock.create_connection(("127.0.0.1", self.port))
            body = json.dumps({"message": "hi"}).encode()
            s.sendall((f"POST /api/chat HTTP/1.1\r\nHost: 127.0.0.1:{self.port}\r\nAuthorization: Bearer {TOKEN}\r\n"
                       f"Content-Type: application/json\r\nContent-Length: {len(body)}\r\n\r\n").encode() + body)
            time.sleep(0.3)
            s.close()
            gate.set()
            deadline = time.time() + 5
            while self.httpd.chat_lock.locked() and time.time() < deadline:
                time.sleep(0.05)
        self.assertTrue(self.convo._cancel.is_set())


class TestTranscribe(WebBase):
    def setUp(self):
        super().setUp()
        self.good = fake_whisper("whisper-good", 'echo " [BLANK_AUDIO] hello   world "')
        self.model = model_file("ggml-fake.bin")
        self.use(self.good)

    def use(self, binary, model=None):
        self.convo.config["stt"].update({"bin": binary, "model_path": model or self.model})

    def post_audio(self, data, ctype="audio/wav", **kw):
        return self.request("POST", "/api/transcribe", body=data, headers={"Content-Type": ctype}, **kw)

    def test_transcribes_and_cleans_text(self):
        resp, body = self.post_audio(wav())
        self.assertEqual(resp.status, 200, body)
        self.assertEqual(json.loads(body), {"text": "hello world"})
        self.assertTrue(self.jget("/api/status")[1]["stt"]["server"])

    def test_whisper_gets_the_expected_arguments(self):
        log = os.path.join(TMP, "args.txt")
        self.use(fake_whisper("whisper-args", f'echo "$@" > {log}; echo ok'))
        self.post_audio(wav())
        with open(log) as f:
            args = f.read()
        for flag in ("-m " + self.model, "-nt", "-np", "-t 4", "-l en", ".wav"):
            self.assertIn(flag, args)

    def test_security_and_validation(self):
        self.assertEqual(self.post_audio(wav(), token=None)[0].status, 401)
        self.assertEqual(self.post_audio(wav(), ctype="application/json")[0].status, 415)
        resp, _ = self.request("POST", "/api/transcribe", body=wav(),
                               headers={"Content-Type": "audio/wav", "Origin": "http://evil.example.com"})
        self.assertEqual(resp.status, 403)
        self.assertEqual(self.post_audio(b"")[0].status, 413)
        import socket as sock
        s = sock.create_connection(("127.0.0.1", self.port), timeout=10)
        s.sendall((f"POST /api/transcribe HTTP/1.1\r\nHost: 127.0.0.1:{self.port}\r\nAuthorization: Bearer {TOKEN}\r\n"
                   f"Content-Type: audio/wav\r\nContent-Length: {stt.MAX_WAV_BYTES + 1}\r\n\r\n").encode())
        self.assertIn(b" 413 ", s.recv(200).split(b"\r\n")[0])
        s.close()
        for bad, why in ((b"not a wav file at all" * 5, "WAV file"), (wav(rate=44100), "16 kHz mono"),
                         (wav(channels=2), "16 kHz mono")):
            resp, body = self.post_audio(bad)
            self.assertEqual(resp.status, 422)
            self.assertIn(why, json.loads(body)["error"])

    def test_missing_pieces_are_explained(self):
        self.use("/nonexistent/whisper-cli")
        resp, body = self.post_audio(wav())
        self.assertEqual(resp.status, 422)
        self.assertIn("whisper-cli not found", json.loads(body)["error"])
        self.assertFalse(self.jget("/api/status")[1]["stt"]["server"])
        self.assertIn("not found", self.jget("/api/status")[1]["stt"]["reason"])
        self.use(self.good, "/nonexistent/model.bin")
        self.assertIn("model not found", json.loads(self.post_audio(wav())[1])["error"])

    def test_failing_and_slow_whisper(self):
        self.use(fake_whisper("whisper-bad", 'echo "ggml_init failed" >&2; exit 3'))
        resp, body = self.post_audio(wav())
        self.assertEqual(resp.status, 422)
        self.assertIn("code 3", json.loads(body)["error"])
        self.assertIn("ggml_init failed", json.loads(body)["error"])
        self.convo.config["stt"]["timeout"] = 1
        self.use(fake_whisper("whisper-slow", "sleep 5"))
        resp, body = self.post_audio(wav())
        self.assertEqual(resp.status, 422)
        self.assertIn("longer than 1s", json.loads(body)["error"])

    def test_one_clip_at_a_time(self):
        self.use(fake_whisper("whisper-wait", "sleep 1; echo first"))
        out = {}
        t = threading.Thread(target=lambda: out.setdefault("r", self.post_audio(wav())))
        t.start()
        time.sleep(0.4)
        self.assertEqual(self.post_audio(wav())[0].status, 409)
        t.join(10)
        self.assertEqual(out["r"][0].status, 200)

    def test_temp_files_are_removed(self):
        tmp = os.path.join(TMP, "faze-tmp")
        with mock.patch.dict(os.environ, {"FAZE_TMP": tmp}):
            self.post_audio(wav())
        self.assertEqual(os.listdir(tmp), [])

    def test_unit_helpers(self):
        self.assertEqual(stt.clean_text("[BLANK_AUDIO]  hi  (silence) there [MUSIC]"), "hi there")
        stt.check_wav(wav())
        with self.assertRaises(stt.TranscribeError):
            stt.check_wav(wav(bits=8))


@unittest.skipUnless(shutil.which("node"), "node not installed")
class TestAppHelpers(unittest.TestCase):
    def node(self, script):
        out = subprocess.run(["node", "-e", f"const a=require({os.path.join(CORE, 'web', 'app.js')!r});{script}"],
                             capture_output=True, text=True, timeout=30)
        self.assertEqual(out.returncode, 0, out.stderr)
        return json.loads(out.stdout)

    def test_greetings(self):
        res = self.node("""
          const first = (h) => a.greetingFor(h, 'Sam', 'Nova', () => 0);
          const last = (h) => a.greetingFor(h, 'Sam', 'Nova', () => 0.999);
          const all = [];
          for (const h of [3, 8, 14, 19, 23]) for (const r of [0, 0.3, 0.6, 0.99]) all.push(a.greetingFor(h, 'Sam', 'Nova', () => r));
          console.log(JSON.stringify({ first: first(8), last8: last(8), last3: last(3), last14: last(14), last19: last(19), all,
            fallback: a.greetingFor(8, '', '', () => 0), pool: Object.values(a.GREETINGS).flat().length }));""")
        self.assertEqual(res["first"], "Coffee and Nova time?")
        self.assertEqual(res["last8"], "Fresh day, Sam. What comes first?")
        self.assertEqual(res["last3"], "Late-night ideas, Sam?")
        self.assertEqual(res["last14"], "Mid-day check-in, Sam?")
        self.assertEqual(res["last19"], "Evening, Sam. What are we up to?")
        self.assertTrue(all("{" not in g and "undefined" not in g for g in res["all"]))
        self.assertEqual(res["fallback"], "Coffee and Faze time?")
        self.assertGreaterEqual(res["pool"], 20)

    def test_labels(self):
        res = self.node("""console.log(JSON.stringify([a.modelLabel('auto', 0), a.modelLabel('qwen', 0),
          a.modelLabel('gemini', 5), a.modelLabel('lfm', 1), a.stayLabel(0), a.stayLabel(1), a.stayLabel(12)]));""")
        self.assertEqual(res, ["Auto", "Qwen", "Gemini \u00b7 5 left", "LFM \u00b7 1 left",
                               "until I turn it off", "1 message", "12 messages"])

    def test_wav_encoder_matches_what_the_server_accepts(self):
        res = self.node("""
          const w = a.encodeWav(new Float32Array([0, 1, -1, 2, -2, 0.5]), 16000);
          console.log(JSON.stringify({ len: w.length, bytes: Array.from(w) }));""")
        data = bytes(res["bytes"])
        self.assertEqual(res["len"], 44 + 12)
        stt.check_wav(data)  # same validator the server uses
        self.assertEqual(struct.unpack("<6h", data[44:]), (0, 32767, -32768, 32767, -32768, 16383))
        self.assertEqual(struct.unpack("<I", data[40:44])[0], 12)

    def test_resampler(self):
        res = self.node("""
          const input = new Float32Array(4800).fill(0.5);
          const out = a.resampleMean(input, 48000, 16000);
          const ramp = a.resampleMean(Float32Array.from([0, 3, 6, 9, 12, 15]), 48000, 16000);
          console.log(JSON.stringify({ n: out.length, v: out[0], same: a.resampleMean(input, 16000, 16000) === input, ramp: Array.from(ramp) }));""")
        self.assertEqual((res["n"], res["v"], res["same"]), (1600, 0.5, True))
        self.assertEqual(res["ramp"], [3.0, 12.0])

    def test_command_menu_suggestions(self):
        cmds = json.dumps(COMMANDS)
        rows = json.dumps([{"key": "ui.show_trace", "value": True, "choices": None},
                           {"key": "ui.show_model_tag", "value": True, "choices": None},
                           {"key": "boot.mode", "value": "background", "choices": ["background", "blocking"]}])
        res = self.node(f"""
          const c = {cmds}; const s = {rows};
          const q = (t) => a.commandSuggestions(t, c, s).map((r) => [r.label, r.insert, r.complete]);
          console.log(JSON.stringify({{ all: q('/'), mo: q('/mo'), model: q('/model '), mq: q('/model q'), mx: q('/model zzz'),
            set: q('/settings '), keys: q('/settings set ui'), unset: q('/settings unset boot'), enum: q('/settings set boot.mode '),
            nope: q('/zzz'), plain: q('hello'), multi: q('/model\\nx'), desc: q('/clear chat') }}));""")
        self.assertEqual([r[0] for r in res["all"]], ["/" + c["name"] for c in COMMANDS])
        self.assertEqual(res["mo"][0], ["/model", "/model", True])
        self.assertEqual([r[0] for r in res["model"]], ["auto", "qwen", "lfm", "gemini"])
        self.assertEqual(res["mq"], [["qwen", "/model qwen", True]])
        self.assertEqual(res["mx"], [])
        self.assertEqual(res["set"], [["set", "/settings set ", False], ["unset", "/settings unset ", False]])
        self.assertEqual(res["keys"], [["ui.show_trace", "/settings set ui.show_trace ", False],
                                       ["ui.show_model_tag", "/settings set ui.show_model_tag ", False]])
        self.assertEqual(res["unset"], [["boot.mode", "/settings unset boot.mode", True]])
        self.assertEqual(res["enum"], [])
        self.assertEqual((res["nope"], res["plain"], res["multi"]), ([], [], []))

    def test_new_markup_ids_exist_for_every_getelementbyid(self):
        import re
        with open(os.path.join(CORE, "web", "index.html")) as f:
            html = f.read()
        with open(os.path.join(CORE, "web", "app.js")) as f:
            js = f.read()
        ids = set(re.findall(r'id="([\w-]+)"', html))
        used = set(re.findall(r"\$\('([\w-]+)'\)", js))
        self.assertEqual(sorted(used - ids), [])


if __name__ == "__main__":
    unittest.main()
