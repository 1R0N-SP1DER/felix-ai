"""
Patch 24: IPv4-first name lookups (the any-family lookup stalled ~5 s on the phone), websockets used the supported way,
`faze say` to type to Faze from anywhere (a Tasker input dialog, the shell), a typing mode in the overlay, and the new
provider keys picked up from ~/.felix/env.

    PYTHONPATH=. python3 -m unittest tests.test_patch24 -v
"""

import json
import os
import socket
import subprocess
import sys
import tempfile
import threading
import unittest
from http.server import HTTPServer
from unittest import mock

CORE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, CORE)

import tests.test_patch17 as p17  # noqa: E402
from tests.test_patch16 import HAVE_JSDOM, run_node  # noqa: E402
from tests.test_patch15 import Quiet  # noqa: E402

import config_loader  # noqa: E402
import env_loader  # noqa: E402
import live_client  # noqa: E402
import netfix  # noqa: E402


# ---------------------------------------------------------------- IPv4-first lookups

class TestNetfix(unittest.TestCase):
    def setUp(self):
        self.addCleanup(netfix.uninstall)
        self.calls = []
        self.answers = {}

        def spy(host, port, family=0, type=0, proto=0, flags=0):
            self.calls.append(family)
            answer = self.answers.get(family, [(family or socket.AF_INET6, 1, 6, "", ("::1", port))])
            if isinstance(answer, Exception):
                raise answer
            return answer
        patch = mock.patch.object(netfix, "_original", spy)
        patch.start()
        self.addCleanup(patch.stop)

    def test_any_family_asks_for_ipv4_first(self):
        self.answers[socket.AF_INET] = [(socket.AF_INET, 1, 6, "", ("1.2.3.4", 443))]
        self.assertEqual(netfix._ipv4_first("h", 443)[0][4], ("1.2.3.4", 443))
        self.assertEqual(self.calls, [socket.AF_INET])  # the slow any-family lookup was never made

    def test_falls_back_when_ipv4_finds_nothing_or_fails(self):
        self.answers[socket.AF_INET] = socket.gaierror(7, "No address associated with hostname")
        self.answers[socket.AF_UNSPEC] = [(socket.AF_INET6, 1, 6, "", ("2001:db8::1", 443, 0, 0))]
        self.assertEqual(netfix._ipv4_first("ipv6only.example", 443)[0][4][0], "2001:db8::1")
        self.assertEqual(self.calls, [socket.AF_INET, socket.AF_UNSPEC])
        self.calls.clear()
        self.answers[socket.AF_INET] = []
        netfix._ipv4_first("h", 443)
        self.assertEqual(self.calls, [socket.AF_INET, socket.AF_UNSPEC])

    def test_a_failure_in_both_is_raised(self):
        self.answers[socket.AF_INET] = socket.gaierror(7, "x")
        self.answers[socket.AF_UNSPEC] = socket.gaierror(7, "x")
        with self.assertRaises(socket.gaierror):
            netfix._ipv4_first("nowhere.example", 443)

    def test_an_explicit_family_is_respected(self):
        netfix._ipv4_first("h", 443, socket.AF_INET6)
        self.assertEqual(self.calls, [socket.AF_INET6])

    def test_extra_arguments_are_passed_through(self):
        seen = []
        with mock.patch.object(netfix, "_original", lambda *a: seen.append(a) or [(2, 1, 6, "", ("1.1.1.1", 80))]):
            netfix._ipv4_first("h", 80, 0, socket.SOCK_STREAM, 6, 0)
        self.assertEqual(seen, [("h", 80, socket.AF_INET, socket.SOCK_STREAM, 6, 0)])

    def test_install_replaces_getaddrinfo_and_uninstall_restores_it(self):
        self.assertFalse(netfix.installed())
        self.assertTrue(netfix.install())
        self.assertIs(socket.getaddrinfo, netfix._ipv4_first)
        self.assertTrue(netfix.installed())
        self.assertTrue(netfix.install())  # twice is fine
        self.assertIs(socket.getaddrinfo, netfix._ipv4_first)
        netfix.uninstall()
        self.assertIsNot(socket.getaddrinfo, netfix._ipv4_first)
        self.assertFalse(netfix.installed())

    def test_the_setting_turns_it_off(self):
        self.assertTrue(netfix.install(config_loader.load_config()))
        self.assertFalse(netfix.install({"net": {"ipv4_first": False}}))
        self.assertFalse(netfix.installed())

    def test_the_setting_exists_and_defaults_on(self):
        self.assertEqual(config_loader.SETTINGS["net.ipv4_first"], (bool, None))
        self.assertEqual(config_loader.load_config()["net"], {"check": True, "ipv4_first": True})

    def test_every_entry_point_installs_it(self):
        for name in ("orchestrator.py", "tts.py", "gemini_client.py", "live_client.py"):
            with open(os.path.join(CORE, name)) as f:
                src = f.read()
            self.assertIn("netfix.install(", src, name)

    def test_real_lookups_still_work(self):
        netfix.uninstall()
        mock.patch.stopall()
        netfix.install()
        try:
            self.assertTrue(socket.getaddrinfo("localhost", 80, 0, socket.SOCK_STREAM))
            self.assertTrue(socket.getaddrinfo("127.0.0.1", 80, socket.AF_UNSPEC, socket.SOCK_STREAM))
        finally:
            netfix.uninstall()


# ---------------------------------------------------------------- live_client

class TestWebsocketsUse(unittest.TestCase):
    def test_a_context_manager_is_entered_by_hand_and_its_connection_returned(self):
        events = []

        class Manager:
            def __enter__(self):
                events.append("enter")
                return "the-connection"

            def __exit__(self, *a):
                events.append("exit")
        sock = type("S", (), {"close": lambda self: events.append("sock closed")})()
        with mock.patch.object(live_client, "_open_socket", return_value=sock), mock.patch.object(live_client, "_ws_connect", lambda *a, **k: Manager()):
            self.assertEqual(live_client._default_connect("wss://h/x?key=k"), "the-connection")
        self.assertEqual(events, ["enter"])  # still open: it lives for the whole conversation

    def test_a_plain_connection_is_returned_as_it_is(self):
        sock = type("S", (), {"close": lambda self: None})()
        with mock.patch.object(live_client, "_open_socket", return_value=sock), mock.patch.object(live_client, "_ws_connect", lambda *a, **k: "plain"):
            self.assertEqual(live_client._default_connect("wss://h/x"), "plain")

    def test_check_looks_names_up_at_call_time(self):
        seen = []

        def lookup(host, port, family, kind):
            seen.append(family)
            return [(2, 1, 6, "", ("142.250.1.2", 443))]
        peer = type("P", (), {"getpeername": lambda s: ("142.250.1.2", 443), "close": lambda s: None})()
        with mock.patch("socket.getaddrinfo", lookup), mock.patch.object(live_client, "_open_socket", return_value=peer), \
                mock.patch.object(live_client.PcmPlayer, "command_line", lambda self: ["pacat"]):
            steps = live_client.check(config_loader.load_config(), key="", connect=lambda *a, **k: None)
        self.assertEqual(seen, [0, socket.AF_INET])
        self.assertTrue(dict((n, ok) for n, ok, _ in steps)["dns"])


# ---------------------------------------------------------------- provider keys

class TestProviderKeys(unittest.TestCase):
    def test_new_keys_are_found_and_the_environment_still_wins(self):
        home = tempfile.mkdtemp(prefix="faze-env24-")
        os.makedirs(os.path.join(home, ".felix"))
        with open(os.path.join(home, ".felix", "env"), "w") as f:
            f.write("GEMINI_API_KEY=AQ.g\nGROQ_API_KEY=gsk_x\nexport OPENROUTER_API_KEY='sk-or-y'\nTAVILY_API_KEY=\"tvly-z\"\nUNRELATED=1\n")
        environ = {"GROQ_API_KEY": "already"}
        loaded = env_loader.load_env(home=home, environ=environ)
        self.assertEqual(environ, {"GROQ_API_KEY": "already", "GEMINI_API_KEY": "AQ.g", "OPENROUTER_API_KEY": "sk-or-y", "TAVILY_API_KEY": "tvly-z"})
        self.assertEqual(sorted(loaded), ["GEMINI_API_KEY", "OPENROUTER_API_KEY", "TAVILY_API_KEY"])

    def test_the_names(self):
        for name in ("GEMINI_API_KEY", "GROQ_API_KEY", "OPENROUTER_API_KEY", "TAVILY_API_KEY", "CEREBRAS_API_KEY", "MISTRAL_API_KEY", "ANTHROPIC_API_KEY"):
            self.assertIn(name, env_loader.NAMES)


# ---------------------------------------------------------------- faze say

class TestSayScript(p17.TestOverlayButtonCommands):
    """The fake-service harness from patch 17; only the tests below run (its own are inherited and still pass)."""

    def up(self, reply='{"ok": true, "started": true}'):
        self.token()
        with open(os.path.join(self.dir, "fake_faze.sh"), "w") as f:
            f.write("#!/bin/bash\nsleep 60\n")
        os.chmod(os.path.join(self.dir, "fake_faze.sh"), 0o755)
        httpd = HTTPServer(("127.0.0.1", self.port), Quiet)
        threading.Thread(target=httpd.serve_forever, daemon=True).start()
        self.addCleanup(httpd.shutdown)
        self.addCleanup(httpd.server_close)
        reply_file = os.path.join(self.dir, "reply.txt")
        with open(reply_file, "w") as f:
            f.write(reply)
        self.write("http.sh", f'echo "$1 $2 $3" >> "{self.posted}"; cat "{reply_file}"')
        self.env.update(FAZE_CMD=os.path.join(self.dir, "fake_faze.sh"), FAZE_RESTART_DELAY="0.2", FAZE_READY_TIMEOUT="5")
        self.addCleanup(lambda: self.run_svc("stop"))

    def posted_call(self):
        with open(self.posted) as f:
            line = f.read().strip().splitlines()[-1]
        path, rest = line.split(" ", 1)
        body, token = rest.rsplit(" ", 1)
        return path, json.loads(body), token

    def test_say_sends_the_text_and_asks_for_the_overlay(self):
        self.up()
        out = self.run_svc("say", "what", "is", "the", "time")
        self.assertEqual((out.returncode, out.stdout.strip().splitlines()[-1]), (0, "sent"), out.stderr)
        self.assertEqual(self.posted_call(), ("/api/voice/say", {"text": "what is the time", "live": False, "overlay": True}, "tok123"))
        self.assertFalse(os.path.exists(self.opened))

    def test_say_live_and_the_queued_reply(self):
        self.up(reply='{"ok": true, "started": false, "queued": true}')
        out = self.run_svc("say", "--live", "hello")
        self.assertEqual(out.stdout.strip().splitlines()[-1], "sent to the live conversation")
        self.assertEqual(self.posted_call()[1], {"text": "hello", "live": True, "overlay": True})

    def test_quotes_and_unicode_survive(self):
        self.up()
        text = 'it\'s "fine" \u00e9 \\ and $HOME `x`'
        self.run_svc("say", text)
        self.assertEqual(self.posted_call()[1]["text"], text)

    def test_nothing_to_say(self):
        self.up()
        out = self.run_svc("say")
        self.assertEqual(out.returncode, 2)
        self.assertIn("usage: faze say", out.stdout)
        self.assertFalse(os.path.exists(self.posted))
        self.assertEqual(self.run_svc("say", "--live").returncode, 2)

    def test_a_failed_send_is_reported(self):
        self.up()
        self.write("http.sh", "exit 7")
        out = self.run_svc("say", "hello")
        self.assertEqual(out.returncode, 1)
        self.assertIn("could not send", out.stdout)

    def test_usage_lists_it(self):
        self.assertIn("say <text>", self.run_svc("bogus").stdout)


@unittest.skipUnless(HAVE_JSDOM, "jsdom not installed")
class TestOverlayTypingMode(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.res = run_node("overlay_smoke.js")

    def test_focus_moves_the_card_out_of_the_keyboard_s_way(self):
        self.assertNotIn("crash", self.res, self.res.get("crash"))
        self.assertEqual(self.res["errors"], [])
        self.assertTrue(self.res["typingOnFocus"])
        self.assertFalse(self.res["typingOnBlur"])

    def test_the_stylesheet_defines_typing_mode(self):
        with open(os.path.join(CORE, "web", "overlay.css")) as f:
            css = f.read()
        self.assertIn("body.typing { align-items: flex-start;", css)
        self.assertIn("body.typing #actions { display: none; }", css)


if __name__ == "__main__":
    unittest.main()
