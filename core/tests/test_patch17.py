"""
Patch 17: `faze voice-stop` and `faze open`, so native Tasker buttons can drive the overlay when the WebView
does not take touches.

    PYTHONPATH=. python3 -m unittest tests.test_patch17 -v
"""

import os
import socket
import subprocess
import sys
import tempfile
import unittest

CORE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ROOT = os.path.dirname(CORE)
sys.path.insert(0, CORE)


class TestOverlayButtonCommands(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp(prefix="faze-svc17-")
        self.script = os.path.join(ROOT, "scripts", "faze_service.sh")
        self.opened = os.path.join(self.dir, "opened.txt")
        self.posted = os.path.join(self.dir, "posted.txt")
        self.write("opener.sh", f'echo "$1" >> "{self.opened}"')
        self.write("http.sh", f'echo "$1 $2 $3" >> "{self.posted}"; echo \'{{"ok": true}}\'')
        sock = socket.socket()
        sock.bind(("127.0.0.1", 0))
        port = sock.getsockname()[1]
        sock.close()
        self.port = port
        self.state = os.path.join(self.dir, "state")
        os.makedirs(self.state)
        self.env = dict(os.environ, FAZE_STATE=self.state, FAZE_LOG=os.path.join(self.dir, "faze.log"), FAZE_PORT=str(port),
                        FAZE_OPEN=os.path.join(self.dir, "opener.sh"), FAZE_HTTP=os.path.join(self.dir, "http.sh"))

    def write(self, name, body):
        path = os.path.join(self.dir, name)
        with open(path, "w") as f:
            f.write("#!/bin/bash\n" + body + "\n")
        os.chmod(path, 0o755)

    def token(self, value="tok123"):
        with open(os.path.join(self.state, "token"), "w") as f:
            f.write(value)

    def run_svc(self, *args):
        return subprocess.run(["bash", self.script, *args], capture_output=True, text=True, env=self.env, timeout=30)

    def test_voice_stop_posts_to_the_running_service(self):
        self.token()
        out = self.run_svc("voice-stop")
        self.assertEqual((out.returncode, out.stdout.strip()), (0, "stopped"), out.stderr)
        with open(self.posted) as f:
            self.assertEqual(f.read().strip(), "/api/voice/stop {} tok123")
        self.assertFalse(os.path.exists(self.opened))

    def test_voice_stop_reports_a_missing_token_or_service(self):
        out = self.run_svc("voice-stop")  # no token yet
        self.assertEqual(out.returncode, 1)
        self.assertIn("could not reach the service", out.stdout)
        self.token()
        self.write("http.sh", "exit 7")
        self.assertEqual(self.run_svc("voice-stop").returncode, 1)

    def test_open_opens_the_app_with_the_token_and_no_voice_mode(self):
        self.token()
        out = self.run_svc("open")
        self.assertEqual((out.returncode, out.stdout.strip()), (0, "opened"), out.stderr)
        with open(self.opened) as f:
            self.assertEqual(f.read().strip(), f"http://localhost:{self.port}/#t=tok123")
        self.assertFalse(os.path.exists(self.posted))

    def test_open_without_a_token_still_opens_the_app(self):
        self.assertEqual(self.run_svc("open").returncode, 0)
        with open(self.opened) as f:
            self.assertEqual(f.read().strip(), f"http://localhost:{self.port}/")

    def test_usage_lists_the_new_commands(self):
        out = self.run_svc("bogus")
        self.assertIn("voice-stop", out.stdout + out.stderr)
        self.assertIn("open", out.stdout + out.stderr)


if __name__ == "__main__":
    unittest.main()
