"""
felix-ai Core -- local_server.py
Owns the main local llama-server (Qwen): starts it in the background,
warms its cache, restarts it if Android kills it, and reports its state
so the orchestrator can chat through LFM (or Gemini) in the meantime.

States: unmanaged (never started by us; callers just try it) -> down ->
starting -> warming -> ready, or failed.
"""

import os
import subprocess
import threading
import time

import requests

from action_log import log_action

MAX_RESTARTS = 3
RESTART_WINDOW_S = 600


class ServerError(Exception):
    pass


class ServerManager:
    def __init__(self, config: dict, warm_fn):
        self.config = config
        self.warm_fn = warm_fn
        self.state = "unmanaged"
        self.detail = ""
        self.proc = None
        self._boot_started = 0.0
        self._restarts = []
        self._lock = threading.Lock()
        self._thread = None
        self._notices = []

    # ---- helpers -------------------------------------------------------

    @property
    def _port(self) -> int:
        return int(self.config["llm"]["port"])

    def _healthy(self) -> bool:
        try:
            return requests.get(f"http://127.0.0.1:{self._port}/health", timeout=2).status_code == 200
        except requests.exceptions.RequestException:
            return False

    def _notice(self, text: str) -> None:
        with self._lock:
            self._notices.append(text)

    def drain_notices(self) -> list:
        with self._lock:
            out, self._notices = self._notices, []
        return out

    @property
    def usable(self) -> bool:
        """True when requests should go to Qwen."""
        return self.state in ("unmanaged", "ready")

    def status_note(self) -> str:
        """Short text for the per-message tag, '' when all is well."""
        if self.state == "unmanaged" or self.state == "ready":
            return ""
        if self.state in ("starting", "warming"):
            return f"qwen {self.state} {int(time.time() - self._boot_started)}s"
        return f"qwen {self.state}"

    # ---- lifecycle -------------------------------------------------------

    def start(self, block: bool = False) -> None:
        with self._lock:
            if self.state in ("starting", "warming"):
                return
            self.state = "starting"
            self.detail = ""
            self._boot_started = time.time()
            self._thread = threading.Thread(target=self._boot, daemon=True)
            self._thread.start()
        if block:
            self._thread.join()

    def _boot(self) -> None:
        local = self.config["local"]
        try:
            if not self._healthy():
                self._spawn()
                self._wait_healthy(float(local["start_timeout"]))
            if self.config["boot"].get("warmup", True):
                self.state = "warming"
                try:
                    self.warm_fn()
                except Exception as e:  # server is up; first reply may just be slow
                    self._notice(f"[qwen warmup failed ({type(e).__name__}); first reply may be slow]")
                    log_action("qwen", "warmup", {}, f"ERROR {type(e).__name__}: {e}", 0, echo=False)
            self.state = "ready"
            took = int(time.time() - self._boot_started)
            self._notice(f"[qwen ready after {took}s]")
            log_action("qwen", "boot", {}, f"ready after {took}s", took * 1000, echo=False)
        except Exception as e:
            self.state = "failed"
            self.detail = str(e)
            self._notice(f"[qwen failed to start: {e}]")
            log_action("qwen", "boot", {}, f"ERROR {e}", 0, echo=False)

    def _spawn(self) -> None:
        local = self.config["local"]
        model = os.path.expanduser(local["model_path"])
        binary = os.path.expanduser(local["server_bin"])
        if not os.path.isfile(model):
            raise ServerError(f"model not found: {model}")
        if not os.path.isfile(binary):
            raise ServerError(f"llama-server not found: {binary}")
        log_path = os.path.expanduser(local["log"])
        os.makedirs(os.path.dirname(log_path), exist_ok=True)
        cwd = os.path.dirname(binary)
        if cwd.endswith(os.path.join("build", "bin")):
            cwd = os.path.dirname(os.path.dirname(cwd))
        log_file = open(log_path, "ab")
        try:
            self.proc = subprocess.Popen(
                [binary, "-m", model, "--port", str(self._port), "-c", str(local["ctx"]),
                 "-t", str(local["threads"]), "--host", "127.0.0.1"],
                stdout=log_file, stderr=subprocess.STDOUT, cwd=cwd, start_new_session=True)
        finally:
            log_file.close()  # the child keeps its own descriptor

    def _wait_healthy(self, timeout: float) -> None:
        deadline = time.time() + timeout
        while time.time() < deadline:
            code = self.proc.poll()
            if code is not None:
                raise ServerError(f"llama-server exited early (code {code}); see {self.config['local']['log']}")
            if self._healthy():
                return
            time.sleep(0.5)
        raise ServerError(f"llama-server not ready after {int(timeout)}s; see {self.config['local']['log']}")

    def mark_down(self, reason: str) -> None:
        """Called when a request to Qwen failed. Restarts it in the
        background unless it keeps crashing."""
        if self.state != "ready":
            return
        self.state = "down"
        self.detail = reason
        self._notice(f"[qwen went down ({reason})]")
        if not self.config["local"].get("auto_restart", True):
            return
        now = time.time()
        self._restarts = [t for t in self._restarts if now - t < RESTART_WINDOW_S]
        if len(self._restarts) >= MAX_RESTARTS:
            self.state = "failed"
            self.detail = f"crashed {MAX_RESTARTS} times in {RESTART_WINDOW_S // 60} minutes; not restarting"
            self._notice(f"[qwen {self.detail}]")
            return
        self._restarts.append(now)
        self._notice("[restarting qwen in the background]")
        self.start()

    def wait_ready(self, timeout: float = 180.0) -> bool:
        deadline = time.time() + timeout
        while time.time() < deadline:
            if self.usable:
                return True
            if self.state == "failed":
                return False
            time.sleep(0.25)
        return False

    def stop(self) -> None:
        proc, self.proc = self.proc, None
        if proc is None or proc.poll() is not None:
            return
        proc.terminate()
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            proc.kill()
