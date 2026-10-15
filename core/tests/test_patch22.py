"""
Patch 22: Live connects through a socket Faze resolves itself ("[Errno 7] No address associated with hostname" on the
phone), and the launcher no longer loops forever against a stray copy that holds the port: `faze stop` clears strays,
`faze status` names the holder, and a crash loop backs off and says why.

    PYTHONPATH=. python3 -m unittest tests.test_patch22 -v
"""

import os
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from http.server import HTTPServer
from unittest import mock

CORE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ROOT = os.path.dirname(CORE)
sys.path.insert(0, CORE)

import live_client  # noqa: E402
from live_client import LiveConnection, LiveError  # noqa: E402
from tests.test_patch15 import Quiet  # noqa: E402


class FakeSock:
    def __init__(self):
        self.closed, self.connected, self.timeout = False, None, None

    def close(self):
        self.closed = True

    def settimeout(self, t):
        self.timeout = t

    def connect(self, addr):
        self.connected = addr


class TestOpenSocket(unittest.TestCase):
    def test_normal_resolution_is_used_first(self):
        sock = FakeSock()
        with mock.patch("socket.create_connection", return_value=sock) as cc:
            self.assertIs(live_client._open_socket("generativelanguage.googleapis.com", 443, 5.0), sock)
        cc.assert_called_once_with(("generativelanguage.googleapis.com", 443), timeout=5.0)

    def test_falls_back_to_ipv4_when_the_resolver_gives_nothing(self):
        made = []

        def make(family, kind, proto):
            made.append(FakeSock())
            return made[-1]
        with mock.patch("socket.create_connection", side_effect=socket.gaierror(7, "No address associated with hostname")), \
                mock.patch("socket.getaddrinfo", return_value=[(socket.AF_INET, socket.SOCK_STREAM, 6, "", ("142.250.1.2", 443))]) as gai, \
                mock.patch("socket.socket", side_effect=make):
            sock = live_client._open_socket("h.example", 443, 4.0)
        self.assertEqual((sock.connected, sock.timeout), (("142.250.1.2", 443), 4.0))
        self.assertEqual(gai.call_args[0][2], socket.AF_INET)

    def test_a_failed_ipv4_address_tries_the_next_then_gives_up(self):
        socks = []

        class Bad(FakeSock):
            def connect(self, addr):
                socks.append(addr)
                raise OSError("unreachable")
        addrs = [(socket.AF_INET, socket.SOCK_STREAM, 6, "", (ip, 443)) for ip in ("1.1.1.1", "2.2.2.2")]
        with mock.patch("socket.create_connection", side_effect=socket.gaierror(7, "x")), \
                mock.patch("socket.getaddrinfo", return_value=addrs), mock.patch("socket.socket", side_effect=lambda *a: Bad()):
            with self.assertRaisesRegex(OSError, "unreachable"):
                live_client._open_socket("h", 443, 1.0)
        self.assertEqual(socks, [("1.1.1.1", 443), ("2.2.2.2", 443)])

    def test_no_ipv4_address_at_all(self):
        with mock.patch("socket.create_connection", side_effect=socket.gaierror(7, "x")), mock.patch("socket.getaddrinfo", return_value=[]):
            with self.assertRaises(socket.gaierror):
                live_client._open_socket("h", 443, 1.0)


class TestDefaultConnect(unittest.TestCase):
    URL = "wss://generativelanguage.googleapis.com/ws/x?key=SECRET"

    def test_hands_websockets_our_socket_and_turns_the_proxy_off(self):
        sock, seen = FakeSock(), {}

        def ws(url, **kw):
            seen.update(kw, url=url)
            return "connection"
        with mock.patch.object(live_client, "_open_socket", return_value=sock) as op, mock.patch.object(live_client, "_ws_connect", ws):
            self.assertEqual(live_client._default_connect(self.URL, open_timeout=7, max_size=None), "connection")
        op.assert_called_once_with("generativelanguage.googleapis.com", 443, 7)
        self.assertEqual((seen["sock"], seen["proxy"], seen["open_timeout"], seen["max_size"]), (sock, None, 7, None))
        self.assertFalse(sock.closed)

    def test_older_websockets_without_a_proxy_argument(self):
        sock, calls = FakeSock(), []

        def ws(url, **kw):
            calls.append(sorted(kw))
            if "proxy" in kw:
                raise TypeError("unexpected keyword argument 'proxy'")
            return "connection"
        with mock.patch.object(live_client, "_open_socket", return_value=sock), mock.patch.object(live_client, "_ws_connect", ws):
            self.assertEqual(live_client._default_connect(self.URL), "connection")
        self.assertEqual(len(calls), 2)
        self.assertNotIn("proxy", calls[1])

    def test_a_websockets_that_cannot_take_a_socket_is_explained(self):
        sock = FakeSock()
        with mock.patch.object(live_client, "_open_socket", return_value=sock), \
                mock.patch.object(live_client, "_ws_connect", side_effect=TypeError("nope")):
            with self.assertRaisesRegex(LiveError, "pip install -U websockets"):
                live_client._default_connect(self.URL)
        self.assertTrue(sock.closed)

    def test_a_failed_handshake_closes_the_socket(self):
        sock = FakeSock()
        with mock.patch.object(live_client, "_open_socket", return_value=sock), \
                mock.patch.object(live_client, "_ws_connect", side_effect=OSError("handshake failed")):
            with self.assertRaisesRegex(OSError, "handshake failed"):
                live_client._default_connect(self.URL)
        self.assertTrue(sock.closed)

    def test_the_connection_uses_it_by_default_and_names_the_host_in_errors(self):
        with mock.patch.object(live_client, "_default_connect", side_effect=OSError("[Errno 7] No address associated with hostname")):
            with self.assertRaises(LiveError) as cm:
                LiveConnection("SECRETKEY", "m").open("i", [], search=False)
        self.assertIn("could not connect to generativelanguage.googleapis.com", str(cm.exception))
        self.assertIn("Errno 7", str(cm.exception))
        self.assertNotIn("SECRETKEY", str(cm.exception))


# ---------------------------------------------------------------- the launcher

class ServiceBase(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp(prefix="faze-svc22-")
        self.script = os.path.join(ROOT, "scripts", "faze_service.sh")
        sock = socket.socket()
        sock.bind(("127.0.0.1", 0))
        self.port = sock.getsockname()[1]
        sock.close()
        self.state = os.path.join(self.dir, "state")
        os.makedirs(self.state)
        self.log = os.path.join(self.dir, "faze.log")
        self.token = "tok"
        self.env = dict(os.environ, FAZE_STATE=self.state, FAZE_LOG=self.log, FAZE_PORT=str(self.port), FAZE_RESTART_DELAY="0.05",
                        FAZE_SWEEP_PATTERN="faze-no-such-process-" + str(os.getpid()), FAZE_READY_TIMEOUT="3")
        self.addCleanup(lambda: self.run_svc("stop"))

    def fake_cmd(self, body):
        path = os.path.join(self.dir, "fake_faze.sh")
        with open(path, "w") as f:
            f.write("#!/bin/bash\n" + body + "\n")
        os.chmod(path, 0o755)
        self.env["FAZE_CMD"] = path

    def run_svc(self, *args):
        return subprocess.run(["bash", self.script, *args], capture_output=True, text=True, env=self.env, timeout=30)

    def listen(self):
        httpd = HTTPServer(("127.0.0.1", self.port), Quiet)
        threading.Thread(target=httpd.serve_forever, daemon=True).start()
        self.addCleanup(httpd.server_close)
        self.addCleanup(httpd.shutdown)

    def log_text(self):
        try:
            with open(self.log) as f:
                return f.read()
        except OSError:
            return ""

    def wait_log(self, needle, seconds=6):
        end = time.time() + seconds
        while time.time() < end:
            if needle in self.log_text():
                return True
            time.sleep(0.05)
        return False


class TestStopClearsStrays(ServiceBase):
    def stray(self):
        name = f"orchestrator_stray_{os.getpid()}_{time.time_ns()}.py"
        path = os.path.join(self.dir, name)
        with open(path, "w") as f:
            f.write("sleep 60\n")
        proc = subprocess.Popen(["bash", path])
        self.addCleanup(lambda: proc.poll() is None and proc.kill())
        self.env["FAZE_SWEEP_PATTERN"] = name
        return proc

    def test_stop_kills_a_copy_it_did_not_start(self):
        proc = self.stray()
        out = self.run_svc("stop")
        self.assertEqual(out.stdout.strip(), "stopped (and cleared a stray copy)")
        end = time.time() + 3
        while time.time() < end and proc.poll() is None:
            time.sleep(0.05)
        self.assertIsNotNone(proc.poll())

    def test_stop_also_stops_the_service_and_the_stray(self):
        self.fake_cmd("sleep 60")
        self.assertEqual(self.run_svc("start").returncode, 0)
        proc = self.stray()
        out = self.run_svc("stop")
        self.assertIn("stopped", out.stdout)
        time.sleep(0.6)
        self.assertIsNotNone(proc.poll())
        self.assertEqual(self.run_svc("status").stdout.strip(), "not running")

    def test_nothing_to_stop(self):
        self.assertEqual(self.run_svc("stop").stdout.strip(), "not running")

    def test_a_plain_stop_says_just_stopped(self):
        self.fake_cmd("sleep 60")
        self.run_svc("start")
        self.assertEqual(self.run_svc("stop").stdout.strip(), "stopped")

    def test_restart_clears_strays_too(self):
        proc = self.stray()
        self.fake_cmd("sleep 60")
        out = self.run_svc("restart")
        self.assertEqual(out.returncode, 0, out.stderr)
        time.sleep(0.6)
        self.assertIsNotNone(proc.poll())
        self.assertTrue(self.run_svc("status").stdout.startswith("running"))


class TestSweepNeverTouchesTheRealAppInATestRun(ServiceBase):
    def fake_pkill(self):
        log = os.path.join(self.dir, "pkill.txt")
        bindir = os.path.join(self.dir, "bin")
        os.makedirs(bindir, exist_ok=True)
        path = os.path.join(bindir, "pkill")
        with open(path, "w") as f:
            f.write(f'#!/bin/bash\necho "$@" >> "{log}"\nexit 1\n')
        os.chmod(path, 0o755)
        self.env["PATH"] = bindir + os.pathsep + self.env["PATH"]
        return log

    def test_with_faze_cmd_set_and_no_pattern_nothing_is_swept(self):
        log = self.fake_pkill()
        self.env.pop("FAZE_SWEEP_PATTERN")
        self.fake_cmd("sleep 60")
        self.run_svc("start")
        self.run_svc("stop")
        with open(log) as f:
            calls = f.read().splitlines()
        self.assertTrue(calls, "stop still stops the service's own children")
        self.assertFalse([c for c in calls if c.startswith("-f")], calls)

    def test_in_real_use_the_app_is_swept_by_default(self):
        log = self.fake_pkill()
        self.env.pop("FAZE_SWEEP_PATTERN")
        self.env.pop("FAZE_CMD", None)
        self.run_svc("stop")
        with open(log) as f:
            self.assertIn("-f python.*orchestrator\\.py", f.read())


class TestStatusNamesTheHolder(ServiceBase):
    def test_something_else_answering_on_the_port(self):
        self.listen()
        out = self.run_svc("status")
        self.assertEqual(out.returncode, 1)
        self.assertIn(f"answering on port {self.port}", out.stdout)
        self.assertIn(f"held by pid {os.getpid()}", out.stdout)
        self.assertIn("faze stop", out.stdout)

    def test_nothing_on_the_port(self):
        out = self.run_svc("status")
        self.assertEqual((out.returncode, out.stdout.strip()), (1, "not running"))

    def test_a_running_service_is_still_reported_plainly(self):
        self.fake_cmd("sleep 60")
        self.run_svc("start")
        self.assertTrue(self.run_svc("status").stdout.startswith("running (pid "))


class TestCrashLoopGuard(ServiceBase):
    def test_a_loop_of_instant_exits_backs_off_and_says_why(self):
        self.listen()  # the stray that holds the port
        self.fake_cmd("echo \"[web] can't listen on 127.0.0.1:%s (Address already in use)\"; exit 0" % self.port)
        self.env.update(FAZE_LOOP_LIMIT="3", FAZE_LOOP_SLOW="30")
        self.run_svc("start")
        self.assertTrue(self.wait_log("keeps exiting"))
        time.sleep(0.5)
        text = self.log_text()
        self.assertEqual(text.count("keeps exiting"), 1)
        self.assertIn(f"port {self.port} is held by pid {os.getpid()}", text)
        self.assertIn("Run: faze stop", text)
        self.assertIn("restarting in 30s", text)
        self.assertEqual(text.count("[service] faze exited"), 3)  # then it waits, instead of spinning every few seconds

    def test_without_a_holder_the_message_still_appears(self):
        self.fake_cmd("exit 1")
        self.env.update(FAZE_LOOP_LIMIT="2", FAZE_LOOP_SLOW="30")
        self.run_svc("start")
        self.assertTrue(self.wait_log("keeps exiting"))
        self.assertNotIn("is held by", self.log_text())

    def test_a_service_that_ran_a_while_is_not_a_crash_loop(self):
        self.fake_cmd("sleep 1.2")
        self.env.update(FAZE_LOOP_LIMIT="2", FAZE_QUICK_EXIT="1", FAZE_RESTART_DELAY="0.05")
        self.run_svc("start")
        self.assertTrue(self.wait_log("restarting in 0.05s", seconds=4))
        self.assertTrue(self.wait_log("restarting in 0.05s", seconds=4) and self.log_text().count("[service] faze exited") >= 1)
        time.sleep(2.6)
        self.assertNotIn("keeps exiting", self.log_text())
        self.assertGreaterEqual(self.log_text().count("[service] faze exited"), 2)

    def test_stop_still_ends_a_backed_off_loop(self):
        self.fake_cmd("exit 0")
        self.env.update(FAZE_LOOP_LIMIT="2", FAZE_LOOP_SLOW="30")
        self.run_svc("start")
        self.assertTrue(self.wait_log("keeps exiting"))
        self.run_svc("stop")
        time.sleep(0.5)
        self.assertEqual(self.run_svc("status").stdout.strip(), "not running")


if __name__ == "__main__":
    unittest.main()
