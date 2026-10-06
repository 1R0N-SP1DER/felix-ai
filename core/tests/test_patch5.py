"""
Tests for the installable web app: static assets, security guards, the
JSON API and the streaming chat endpoint. Real HTTP server on an
ephemeral port; fake llama-server; nothing real is started.

    PYTHONPATH=. python3 -m unittest tests.test_patch5 -v
"""

import http.client
import json
import os
import shutil
import socket
import stat
import struct
import subprocess
import sys
import threading
import time
import unittest
from unittest import mock

CORE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, CORE)

from tests.test_patch1 import TMP, FakeLlama, free_port, make_convo  # noqa: E402

import web_server  # noqa: E402
from web_server import FazeServer, get_or_create_token  # noqa: E402
from orchestrator import Conversation  # noqa: E402
from config_loader import load_config  # noqa: E402
from gemini_client import GeminiError  # noqa: E402

TOKEN = "t" * 43


def parse_sse(text):
    events = []
    for block in text.strip().split("\n\n"):
        if not block.strip():
            continue
        event, data = "message", ""
        for line in block.split("\n"):
            if line.startswith("event: "):
                event = line[7:]
            elif line.startswith("data: "):
                data += line[6:]
        events.append((event, json.loads(data)))
    return events


class WebBase(unittest.TestCase):
    def setUp(self):
        os.environ["FAZE_SETTINGS"] = os.path.join(TMP, f"s-{time.time_ns()}.json")
        os.environ.pop("FAZE_GEMINI_DISABLED", None)
        self.llama = FakeLlama()
        self.needle = mock.patch("orchestrator.ask_needle", return_value=None)
        self.needle.start()
        self.convo = make_convo(self.llama.port)
        self.httpd = FazeServer(("127.0.0.1", 0), self.convo, self.convo.config, TOKEN)
        self.port = self.httpd.port
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()

    def tearDown(self):
        self.httpd.shutdown()
        self.httpd.server_close()
        self.llama.close()
        self.needle.stop()
        self.convo.stop_lfm()
        os.environ["FAZE_SETTINGS"] = os.path.join(TMP, "settings-default.json")
        os.environ.pop("FAZE_GEMINI_DISABLED", None)

    def request(self, method, path, body=None, token=TOKEN, headers=None, host=None):
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=20)
        conn.putrequest(method, path, skip_host=host is not None)
        if host is not None:
            conn.putheader("Host", host)
        hdrs = dict(headers or {})
        raw = None
        if body is not None:
            raw = body if isinstance(body, bytes) else json.dumps(body).encode()
            hdrs.setdefault("Content-Type", "application/json")
            hdrs["Content-Length"] = str(len(raw))
        if token:
            hdrs["Authorization"] = "Bearer " + token
        for k, v in hdrs.items():
            conn.putheader(k, v)
        conn.endheaders(raw)
        resp = conn.getresponse()
        data = resp.read()
        conn.close()
        return resp, data

    def jget(self, path, **kw):
        resp, data = self.request("GET", path, **kw)
        return resp.status, json.loads(data)

    def jpost(self, path, body, **kw):
        resp, data = self.request("POST", path, body=body, **kw)
        return resp.status, json.loads(data)

    def chat(self, message):
        resp, data = self.request("POST", "/api/chat", {"message": message})
        self.assertEqual(resp.status, 200, data)
        return parse_sse(data.decode())


class TestStaticAndInstallability(WebBase):
    def test_shell_files(self):
        resp, body = self.request("GET", "/", token=None)
        self.assertEqual(resp.status, 200)
        html = body.decode()
        self.assertIn('rel="manifest"', html)
        self.assertIn('name="viewport"', html)
        self.assertIn("default-src 'self'", resp.getheader("Content-Security-Policy"))
        self.assertEqual(resp.getheader("X-Frame-Options"), "DENY")
        for path, ctype in [("/app.js", "text/javascript"), ("/app.css", "text/css"),
                            ("/sw.js", "text/javascript")]:
            resp, body = self.request("GET", path, token=None)
            self.assertEqual(resp.status, 200, path)
            self.assertTrue(resp.getheader("Content-Type").startswith(ctype), path)
            self.assertGreater(len(body), 100)
        resp, _ = self.request("GET", "/sw.js", token=None)
        self.assertEqual(resp.getheader("Service-Worker-Allowed"), "/")

    def test_manifest_meets_install_criteria(self):
        resp, body = self.request("GET", "/manifest.webmanifest", token=None)
        self.assertEqual(resp.getheader("Content-Type"), "application/manifest+json")
        m = json.loads(body)
        self.assertEqual(m["display"], "standalone")
        self.assertEqual(m["start_url"], "/")
        self.assertTrue(m["name"] and m["short_name"])
        sizes = {(i["sizes"], i["purpose"]) for i in m["icons"]}
        self.assertIn(("192x192", "any"), sizes)
        self.assertIn(("512x512", "any"), sizes)
        self.assertIn(("512x512", "maskable"), sizes)
        for icon in m["icons"]:
            resp, png = self.request("GET", icon["src"], token=None)
            self.assertEqual(resp.status, 200, icon["src"])
            self.assertEqual(png[:8], b"\x89PNG\r\n\x1a\n")
            w, h = struct.unpack(">II", png[16:24])
            self.assertEqual(f"{w}x{h}", icon["sizes"])

    def test_unknown_paths_404_and_no_traversal(self):
        for path in ("/nope", "/../orchestrator.py", "/web/app.js", "/%2e%2e/orchestrator.py"):
            resp, _ = self.request("GET", path, token=None)
            self.assertEqual(resp.status, 404, path)

    def test_service_worker_never_caches_api(self):
        with open(os.path.join(CORE, "web", "sw.js")) as f:
            js = f.read()
        self.assertIn("/api/", js)
        self.assertIn("addEventListener('fetch'", js)


class TestSecurity(WebBase):
    def test_wrong_host_header_refused(self):
        for path in ("/", "/api/status"):
            resp, _ = self.request("GET", path, host="evil.example.com")
            self.assertEqual(resp.status, 421, path)
        resp, _ = self.request("GET", "/", host=f"localhost:{self.port}", token=None)
        self.assertEqual(resp.status, 200)

    def test_api_needs_the_token(self):
        self.assertEqual(self.jget("/api/status", token=None)[0], 401)
        self.assertEqual(self.jget("/api/status", token="x" * 43)[0], 401)
        self.assertEqual(self.jget("/api/settings", token=None)[0], 401)
        self.assertEqual(self.jpost("/api/chat", {"message": "hi"}, token=None)[0], 401)
        self.assertEqual(self.jpost("/api/command", {"line": "/status"}, token="bad")[0], 401)
        self.assertEqual(self.jget("/api/status")[0], 200)

    def test_post_guards(self):
        resp, data = self.request("POST", "/api/command", body=b'{"line": "/status"}',
                                  headers={"Content-Type": "text/plain"})
        self.assertEqual(resp.status, 415)
        resp, data = self.request("POST", "/api/command", body={"line": "/status"},
                                  headers={"Origin": "http://evil.example.com"})
        self.assertEqual(resp.status, 403)
        resp, data = self.request("POST", "/api/command", body={"line": "/status"},
                                  headers={"Origin": f"http://127.0.0.1:{self.port}"})
        self.assertEqual(resp.status, 200)
        resp, _ = self.request("POST", "/api/command", body=b"{not json")
        self.assertEqual(resp.status, 400)
        resp, _ = self.request("POST", "/api/command", body=b"[1]")
        self.assertEqual(resp.status, 400)
        resp, _ = self.request("POST", "/api/command", body=b"x" * (70 * 1024))
        self.assertEqual(resp.status, 413)

    def test_token_file_is_private_and_stable(self):
        path = os.path.join(TMP, "tok", "token")
        with mock.patch.dict(os.environ, {"FAZE_TOKEN_FILE": path}):
            a = get_or_create_token()
            self.assertEqual(stat.S_IMODE(os.stat(path).st_mode), 0o600)
            self.assertEqual(a, get_or_create_token())
            self.assertGreaterEqual(len(a), 40)
            with open(path, "w") as f:
                f.write("short\n")
            self.assertNotEqual(get_or_create_token(), "short")

    def test_comparison_is_constant_time(self):
        import inspect
        self.assertIn("hmac.compare_digest", inspect.getsource(web_server.Handler._authorized))


class TestApi(WebBase):
    def test_status(self):
        code, s = self.jget("/api/status")
        self.assertEqual(code, 200)
        self.assertEqual(s["name"], "Faze")
        self.assertEqual(s["qwen"]["state"], "unmanaged")
        self.assertEqual(s["mode"], "auto")
        self.assertTrue(s["gemini"])
        self.assertFalse(s["busy"])
        self.assertTrue(s["show_tag"])
        self.assertEqual(s["notices"], [])

    def test_command_endpoint(self):
        code, d = self.jpost("/api/command", {"line": "/model qwen 2"})
        self.assertEqual((code, d["text"]), (200, "Forcing qwen for next 2 turns"))
        self.assertEqual(self.jget("/api/status")[1]["mode"], "qwen")
        code, d = self.jpost("/api/command", {"line": "hello"})
        self.assertEqual(code, 400)

    def test_settings_roundtrip(self):
        code, d = self.jget("/api/settings")
        rows = {r["key"]: r for r in d["settings"]}
        self.assertEqual(rows["boot.chat_while_booting"]["choices"], ["lfm", "gemini", "wait"])
        self.assertEqual(rows["ui.show_model_tag"]["type"], "bool")
        self.assertTrue(rows["local.ctx"]["restart"])
        self.assertFalse(rows["ui.show_model_tag"]["user"])

        code, d = self.jpost("/api/settings", {"key": "ui.show_model_tag", "value": "false"})
        self.assertEqual((code, d["ok"], d["value"], d["restart"]), (200, True, False, False))
        self.assertFalse(self.jget("/api/status")[1]["show_tag"])
        row = [r for r in d["settings"] if r["key"] == "ui.show_model_tag"][0]
        self.assertTrue(row["user"])

        code, d = self.jpost("/api/settings", {"key": "local.ctx", "value": "2048"})
        self.assertTrue(d["restart"])
        code, d = self.jpost("/api/settings", {"key": "ui.show_model_tag", "unset": True})
        self.assertEqual((code, d["removed"]), (200, True))
        self.assertTrue(self.jget("/api/status")[1]["show_tag"])

        for body in ({"key": "bogus", "value": "1"}, {"key": "local.ctx", "value": "abc"},
                     {"key": "boot.mode", "value": "sideways"}):
            code, d = self.jpost("/api/settings", body)
            self.assertEqual(code, 400, body)
            self.assertIn("error", d)

    def test_gemini_toggle_takes_effect_live(self):
        self.jpost("/api/settings", {"key": "gemini_enabled", "value": "false"})
        self.assertFalse(self.jget("/api/status")[1]["gemini"])
        self.jpost("/api/settings", {"key": "gemini_enabled", "unset": True})
        self.assertTrue(self.jget("/api/status")[1]["gemini"])


class TestChatStream(WebBase):
    def test_deterministic_route_streams_route_trace_reply(self):
        with mock.patch("orchestrator.execute_tool", return_value="19% battery, status: CHARGING"):
            events = self.chat("whats my battery")
        names = [e for e, _ in events]
        self.assertEqual(names[0], "route")
        self.assertEqual(events[0][1]["tag"], "regex")
        self.assertIn("trace", names)
        reply = [d for e, d in events if e == "reply"][0]
        self.assertEqual(reply["text"], "Battery is at 19%, charging.")
        self.assertEqual(names[-1], "done")

    def test_model_tokens_stream(self):
        self.llama.script = [{"content": "hello there"}]
        events = self.chat("yo")
        self.assertEqual(events[0], ("route", {"tag": "qwen", "note": ""}))
        tokens = [d for e, d in events if e == "token"]
        self.assertEqual("".join(t["text"] for t in tokens), "hello there")
        self.assertEqual({t["kind"] for t in tokens}, {"answer"})
        self.assertEqual([d for e, d in events if e == "reply"][0]["text"], "hello there")

    def test_fallback_announces_second_route(self):
        convo = make_convo(free_port())
        self.httpd.convo = convo
        with mock.patch("orchestrator.ask_gemini", return_value={"type": "text", "content": "gemini yo"}):
            events = self.chat("yo")
        routes = [d["tag"] for e, d in events if e == "route"]
        self.assertEqual(routes, ["qwen", "gemini"])
        self.assertEqual([d for e, d in events if e == "reply"][0]["text"], "gemini yo")
        convo.stop_lfm()

    def test_errors_become_events_not_crashes(self):
        with mock.patch.object(self.convo, "send", side_effect=RuntimeError("boom")):
            events = self.chat("yo")
        self.assertEqual([e for e, _ in events][-2:], ["error", "done"])
        self.assertIn("boom", [d for e, d in events if e == "error"][0]["message"])
        self.assertEqual(self.jget("/api/status")[1]["busy"], False)

    def test_hooks_are_cleaned_up_after_a_turn(self):
        import action_log
        before = len(action_log._listeners)
        with mock.patch("orchestrator.execute_tool", return_value="1% battery, status: FULL"):
            self.chat("whats my battery")
        self.assertEqual(len(action_log._listeners), before)
        self.assertIsNone(self.convo.on_route)
        self.assertIsNone(self.convo.on_token)

    def test_second_message_while_busy_gets_409(self):
        gate = threading.Event()
        started = threading.Event()

        def slow(message):
            started.set()
            gate.wait(10)
            return "late reply"
        results = {}

        def first():
            results["first"] = self.request("POST", "/api/chat", {"message": "one"})
        with mock.patch.object(self.convo, "send", side_effect=slow):
            t = threading.Thread(target=first)
            t.start()
            self.assertTrue(started.wait(5))
            self.assertTrue(self.jget("/api/status")[1]["busy"])
            code, d = self.jpost("/api/chat", {"message": "two"})
            self.assertEqual(code, 409)
            gate.set()
            t.join(10)
        self.assertEqual(parse_sse(results["first"][1].decode())[-2][1]["text"], "late reply")

    def test_validation(self):
        self.assertEqual(self.jpost("/api/chat", {"message": "  "})[0], 400)
        self.assertEqual(self.jpost("/api/chat", {})[0], 400)
        self.assertEqual(self.jpost("/api/chat", {"message": "/model gemini"})[0], 400)
        self.assertEqual(self.jpost("/api/chat", {"message": "x" * 8001})[0], 413)

    def test_client_disconnect_does_not_wedge_the_server(self):
        gate = threading.Event()

        def slow(message):
            gate.wait(10)
            return "reply nobody reads"
        with mock.patch.object(self.convo, "send", side_effect=slow):
            sock = socket.create_connection(("127.0.0.1", self.port))
            body = json.dumps({"message": "hi"}).encode()
            sock.sendall((f"POST /api/chat HTTP/1.1\r\nHost: 127.0.0.1:{self.port}\r\n"
                          f"Authorization: Bearer {TOKEN}\r\nContent-Type: application/json\r\n"
                          f"Content-Length: {len(body)}\r\n\r\n").encode() + body)
            time.sleep(0.3)
            sock.close()
            gate.set()
            deadline = time.time() + 5
            while self.httpd.chat_lock.locked() and time.time() < deadline:
                time.sleep(0.05)
        self.assertFalse(self.httpd.chat_lock.locked())
        self.assertEqual(self.jget("/api/status")[0], 200)

    def test_pending_notices_arrive_as_events(self):
        self.convo.local._notice("[qwen ready after 40s]")
        with mock.patch("orchestrator.execute_tool", return_value="5% battery, status: FULL"):
            events = self.chat("whats my battery")
        self.assertIn(("notice", {"text": "[qwen ready after 40s]"}), events)


@unittest.skipUnless(shutil.which("node"), "node not installed")
class TestBrowserCode(unittest.TestCase):
    def node(self, script):
        out = subprocess.run(["node", "-e", f"const a=require({os.path.join(CORE, 'web', 'app.js')!r});{script}"],
                             capture_output=True, text=True, timeout=30)
        self.assertEqual(out.returncode, 0, out.stderr)
        return json.loads(out.stdout)

    def test_syntax(self):
        for f in ("app.js", "sw.js"):
            r = subprocess.run(["node", "--check", os.path.join(CORE, "web", f)], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stderr)

    def test_markdown_is_escaped_and_rendered(self):
        html = self.node("console.log(JSON.stringify(a.renderMarkdown("
                         "'<script>alert(1)</script> **bold** `x<y`\\n* one\\n* two\\n### Head\\n```\\n<b>raw</b>\\n```')))")
        self.assertNotIn("<script>", html)
        self.assertIn("&lt;script&gt;", html)
        self.assertIn("<strong>bold</strong>", html)
        self.assertIn("<code>x&lt;y</code>", html)
        self.assertIn("<ul><li>one</li><li>two</li></ul>", html)
        self.assertIn('<div class="h">Head</div>', html)
        self.assertIn("<pre><code>&lt;b&gt;raw&lt;/b&gt;</code></pre>", html)

    def test_sse_parser_handles_split_chunks(self):
        res = self.node("""
          const stream = 'event: route\\ndata: {"tag":"qwen"}\\n\\nevent: token\\ndata: {"te';
          const first = a.parseSSE(stream);
          const second = a.parseSSE(first.rest + 'xt":"hi"}\\n\\n');
          console.log(JSON.stringify([first.events.length, first.events[0], second.events[0]]));""")
        self.assertEqual(res[0], 1)
        self.assertEqual(res[1], {"event": "route", "data": {"tag": "qwen"}})
        self.assertEqual(res[2], {"event": "token", "data": {"text": "hi"}})

    def test_speech_text_is_clean(self):
        res = self.node("""console.log(JSON.stringify([
          a.stripForSpeech('**Hi** there \\ud83d\\ude0a see https://x.io/a `code` now'),
          a.stripForSpeech('```\\nbig block\\n``` done'),
          a.stripForSpeech('Sentence one. '.repeat(100)).length]));""")
        self.assertEqual(res[0], "Hi there see link code now")
        self.assertEqual(res[1], "code omitted. done")
        self.assertLessEqual(res[2], 600)


if __name__ == "__main__":
    unittest.main()
