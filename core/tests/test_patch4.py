"""
Tests for background boot, LFM-while-booting, settings and model tags.
Fake llama-server binaries only; nothing real is started.

    PYTHONPATH=. python3 -m unittest tests.test_patch4 -v
"""

import json
import os
import stat
import sys
import time
import unittest
from unittest import mock

CORE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, CORE)

from tests.test_patch1 import TMP, free_port  # noqa: E402  (sets FELIX_DB / FELIX_LOG_DIR)

import config_loader  # noqa: E402
import orchestrator  # noqa: E402
from orchestrator import Conversation, make_prefix_hook  # noqa: E402
from config_loader import load_config  # noqa: E402

SERVER_TEMPLATE = '''#!/usr/bin/env python3
import json, sys, time
from http.server import BaseHTTPRequestHandler, HTTPServer
time.sleep({delay})
port = int(sys.argv[sys.argv.index("--port") + 1])
class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def do_GET(self):
        self.send_response(200); self.end_headers(); self.wfile.write(b"ok")
    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        text = "warm" if body.get("max_tokens") == 1 else {reply!r}
        data = ("data: " + json.dumps({{"choices": [{{"delta": {{"content": text}}}}]}}) + "\\n\\ndata: [DONE]\\n\\n").encode()
        self.send_response(200); self.send_header("Content-Length", str(len(data))); self.end_headers(); self.wfile.write(data)
HTTPServer(("127.0.0.1", port), H).serve_forever()
'''


def make_server(name, reply, delay=0.0, exit_code=None):
    path = os.path.join(TMP, name)
    with open(path, "w") as f:
        if exit_code is not None:
            f.write(f"#!/bin/sh\nexit {exit_code}\n")
        else:
            f.write(SERVER_TEMPLATE.format(reply=reply, delay=delay))
    os.chmod(path, os.stat(path).st_mode | stat.S_IEXEC)
    return path


def model_file(name):
    path = os.path.join(TMP, name)
    open(path, "w").close()
    return path


def wait_for(cond, timeout=15.0):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if cond():
            return True
        time.sleep(0.1)
    return False


class BootBase(unittest.TestCase):
    def setUp(self):
        os.environ["FAZE_SETTINGS"] = os.path.join(TMP, f"b-{time.time_ns()}.json")
        os.environ.pop("FAZE_GEMINI_DISABLED", None)
        self.needle = mock.patch("orchestrator.ask_needle", return_value=None)
        self.needle.start()
        self.routes = []

    def tearDown(self):
        self.needle.stop()
        os.environ.pop("FAZE_GEMINI_DISABLED", None)
        for c in getattr(self, "convos", []):
            c.stop_lfm()
            c.local.stop()

    def convo(self, qwen_delay=1.5, chat="lfm", mode="background", lfm=True, qwen_bin=None, **extra):
        config = load_config()
        port = free_port()
        config["llm"]["port"] = port
        config["boot"].update({"mode": mode, "chat_while_booting": chat})
        config["local"].update({
            "model_path": model_file("qwen.gguf"),
            "server_bin": qwen_bin or make_server("fake-qwen", "qwen says hi", qwen_delay),
            "log": os.path.join(TMP, "q.log"), "start_timeout": 20,
        })
        if lfm:
            config["lfm"].update({
                "model_path": model_file("lfm.gguf"),
                "server_bin": make_server("fake-lfm", "lfm says yo"),
                "port": free_port(), "log": os.path.join(TMP, "l.log"), "start_timeout": 20,
            })
        config.update(extra)
        c = Conversation(config)
        c.on_route = lambda tag, note: self.routes.append((tag, note))
        self.convos = getattr(self, "convos", []) + [c]
        return c


class TestBackgroundBoot(BootBase):
    def test_lfm_chats_while_qwen_boots_then_qwen_takes_over(self):
        c = self.convo(qwen_delay=2.5)
        c.start_background()
        self.assertFalse(c.local.usable)
        self.assertTrue(wait_for(lambda: c._healthy(f"http://127.0.0.1:{c.config['lfm']['port']}/health")))
        self.assertEqual(c.send("yo"), "lfm says yo")
        tag, note = self.routes[-1]
        self.assertEqual(tag, "lfm")
        self.assertRegex(note, r"qwen (starting|warming) \d+s")
        self.assertTrue(wait_for(lambda: c.local.usable))
        self.assertEqual(c.send("yo again"), "qwen says hi")
        self.assertEqual(self.routes[-1], ("qwen", ""))
        self.assertIsNone(c.lfm_process)  # LFM released once Qwen took over
        self.assertTrue(any("qwen ready" in n for n in c.drain_notices()))

    def test_gemini_chats_while_booting_when_configured(self):
        c = self.convo(qwen_delay=2.5, chat="gemini", lfm=False)
        c.start_background()
        with mock.patch("orchestrator.ask_gemini", return_value={"type": "text", "content": "gemini yo"}):
            self.assertEqual(c.send("yo"), "gemini yo")
        self.assertEqual(self.routes[-1][0], "gemini")

    def test_wait_mode_blocks_until_qwen_is_ready(self):
        c = self.convo(qwen_delay=2.0, chat="wait", lfm=False)
        c.start_background()
        started = time.time()
        self.assertEqual(c.send("yo"), "qwen says hi")
        self.assertGreater(time.time() - started, 1.0)
        self.assertEqual(self.routes[0][0], "qwen")

    def test_blocking_mode_returns_only_when_ready(self):
        c = self.convo(qwen_delay=1.5, mode="blocking", lfm=False)
        c.start_background()
        self.assertEqual(c.local.state, "ready")

    def test_nothing_available_says_so(self):
        c = self.convo(qwen_delay=3.0, chat="lfm", lfm=False, gemini_enabled=False)
        c.start_background()
        reply = c.send("yo")
        self.assertIn("no backend available", reply)
        self.assertIn("qwen", reply)

    def test_qwen_killed_mid_session_lfm_covers_then_it_restarts(self):
        c = self.convo(qwen_delay=0.3)
        c.start_background()
        self.assertTrue(wait_for(lambda: c.local.state == "ready"))
        self.assertEqual(c.send("yo"), "qwen says hi")
        c.local.proc.kill()
        c.local.proc.wait()
        reply = c.send("yo")  # qwen refuses -> mark_down -> LFM answers
        self.assertEqual(reply, "lfm says yo")
        self.assertEqual([t for t, _ in self.routes[-2:]], ["qwen", "lfm"])
        notices = " ".join(c.drain_notices())
        self.assertIn("went down", notices)
        self.assertIn("restarting qwen", notices)
        self.assertTrue(wait_for(lambda: c.local.state == "ready"))
        self.assertEqual(c.send("yo"), "qwen says hi")

    def test_crash_loop_stops_restarting(self):
        c = self.convo(qwen_delay=0.1, lfm=False)
        c.start_background()
        self.assertTrue(wait_for(lambda: c.local.state == "ready"))
        for _ in range(4):
            if c.local.proc:
                c.local.proc.kill()
                c.local.proc.wait()
            c.local.state = "ready" if c.local.state != "failed" else "failed"
            with mock.patch("orchestrator.ask_gemini", return_value={"type": "text", "content": "g"}):
                c.send("yo")
            wait_for(lambda: c.local.state in ("ready", "failed"), 10)
        self.assertEqual(c.local.state, "failed")
        self.assertIn("crashed 3 times", c.local.detail)

    def test_missing_model_or_binary_fails_loudly(self):
        c = self.convo(lfm=False)
        c.config["local"]["model_path"] = "/nonexistent/model.gguf"
        c.local.start(block=True)
        self.assertEqual(c.local.state, "failed")
        self.assertIn("model not found", c.local.detail)
        self.assertTrue(any("failed to start" in n for n in c.drain_notices()))

    def test_server_that_exits_immediately_is_reported(self):
        c = self.convo(lfm=False, qwen_bin=make_server("dead", "", exit_code=3))
        c.local.start(block=True)
        self.assertEqual(c.local.state, "failed")
        self.assertIn("exited early (code 3)", c.local.detail)

    def test_attaches_to_a_server_that_is_already_running(self):
        c = self.convo(qwen_delay=0.1, lfm=False)
        c.start_background()
        self.assertTrue(wait_for(lambda: c.local.state == "ready"))
        second = Conversation(c.config)  # same port: nothing new should be spawned
        second.start_background()
        self.assertTrue(wait_for(lambda: second.local.state == "ready"))
        self.assertIsNone(second.local.proc)
        self.convos.append(second)


class TestTagsAndStatus(BootBase):
    def test_every_route_announces_itself(self):
        c = self.convo(qwen_delay=0.1, lfm=False)
        with mock.patch("orchestrator.execute_tool", return_value="19% battery, status: CHARGING"):
            c.send("whats my battery")
            c.send("ask gemini who is x")
        self.assertEqual([t for t, _ in self.routes], ["regex", "intent"])
        c.handle_command("/model gemini 1")
        with mock.patch("orchestrator.ask_gemini", return_value={"type": "text", "content": "g"}):
            c.send("hello")
        self.assertEqual(self.routes[-1], ("gemini", "forced"))

    def test_prefix_hook_output(self):
        import io
        from contextlib import redirect_stdout
        buf = io.StringIO()
        with redirect_stdout(buf):
            state, hook = make_prefix_hook("Faze", True)
            hook("lfm", "qwen warming 12s")
            hook("gemini", "")
        self.assertEqual(buf.getvalue(), "Faze [lfm | qwen warming 12s]: \nFaze [gemini]: ")
        buf = io.StringIO()
        with redirect_stdout(buf):
            _, hook = make_prefix_hook("Faze", False)
            hook("qwen", "")
        self.assertEqual(buf.getvalue(), "")

    def test_status_command(self):
        c = self.convo(qwen_delay=0.1, lfm=True)
        self.assertEqual(c.handle_command("/status"), "qwen: running (not managed) | lfm: idle | gemini: on | mode: auto | think: auto")
        c.start_background()
        self.assertTrue(wait_for(lambda: c.local.state == "ready"))
        c.handle_command("/model qwen")
        self.assertIn("qwen: ready", c.handle_command("/status"))
        self.assertIn("mode: qwen", c.handle_command("/status"))


class TestSettings(unittest.TestCase):
    def setUp(self):
        self.path = os.path.join(TMP, f"settings-{time.time_ns()}.json")
        os.environ["FAZE_SETTINGS"] = self.path
        os.environ.pop("FAZE_GEMINI_DISABLED", None)

    def tearDown(self):
        os.environ["FAZE_SETTINGS"] = os.path.join(TMP, "settings-default.json")
        os.environ.pop("FAZE_GEMINI_DISABLED", None)

    def test_defaults_then_user_layer(self):
        cfg = load_config()
        self.assertEqual(cfg["boot"]["mode"], "background")
        self.assertEqual(cfg["boot"]["chat_while_booting"], "lfm")
        self.assertEqual(cfg["llm"]["port"], 8080)  # profile.json layer still present
        with open(self.path, "w") as f:
            json.dump({"boot": {"chat_while_booting": "gemini"}}, f)
        cfg = load_config()
        self.assertEqual(cfg["boot"]["chat_while_booting"], "gemini")
        self.assertEqual(cfg["boot"]["mode"], "background")  # untouched siblings survive

    def test_set_validates_and_persists(self):
        self.assertEqual(config_loader.set_user_setting("ui.show_model_tag", "off"), (False, False))
        self.assertEqual(config_loader.set_user_setting("local.ctx", "2048"), (2048, True))
        self.assertEqual(config_loader.set_user_setting("boot.mode", "Blocking"), ("blocking", True))
        with open(self.path) as f:
            saved = json.load(f)
        self.assertEqual(saved, {"boot": {"mode": "blocking"}, "local": {"ctx": 2048}, "ui": {"show_model_tag": False}})
        for key, bad in [("bogus.key", "1"), ("local.ctx", "abc"), ("local.ctx", "0"),
                         ("boot.mode", "sideways"), ("gemini_enabled", "maybe")]:
            with self.assertRaises(ValueError, msg=f"{key}={bad}"):
                config_loader.set_user_setting(key, bad)

    def test_corrupt_file_is_a_clear_error(self):
        with open(self.path, "w") as f:
            f.write("{not json")
        with self.assertRaises(ValueError) as cm:
            load_config()
        self.assertIn("not valid JSON", str(cm.exception))
        self.assertIn(self.path, str(cm.exception))

    def test_unset_removes_the_override(self):
        config_loader.set_user_setting("local.threads", "2")
        self.assertTrue(config_loader.unset_user_setting("local.threads"))
        self.assertFalse(config_loader.unset_user_setting("local.threads"))
        self.assertEqual(load_config()["local"]["threads"], 4)

    def test_settings_command_end_to_end(self):
        needle = mock.patch("orchestrator.ask_needle", return_value=None)
        needle.start()
        self.addCleanup(needle.stop)
        c = Conversation(load_config())
        listing = c.handle_command("/settings")
        self.assertIn("boot.chat_while_booting = lfm", listing)
        self.assertEqual(c.handle_command("/settings set gemini_enabled false"), "gemini_enabled = False")
        self.assertEqual(os.environ.get("FAZE_GEMINI_DISABLED"), "1")
        self.assertFalse(c._gemini_on())
        self.assertIn("(user)", c.handle_command("/settings"))
        self.assertEqual(c.handle_command("/settings set local.ctx 2048"),
                         "local.ctx = 2048 (takes effect after restart)")
        self.assertEqual(c.handle_command("/settings unset gemini_enabled"), "gemini_enabled reset to default")
        self.assertTrue(c._gemini_on())
        self.assertNotIn("FAZE_GEMINI_DISABLED", os.environ)
        self.assertTrue(c.handle_command("/settings set bogus 1").startswith("Setting not changed: unknown setting"))
        self.assertTrue(c.handle_command("/settings set local.ctx").startswith("Usage:"))


if __name__ == "__main__":
    unittest.main()
