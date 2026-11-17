"""Talking to the daemon from a window or the command line."""
import json
import os
import socket
import subprocess
import sys
import time

from .daemon import socket_path


class Client:
    def __init__(self, path=None):
        self.path = path or socket_path()
        self.sock = socket.socket(socket.AF_UNIX)
        self.sock.connect(self.path)
        self.file = self.sock.makefile("rwb")

    def send(self, obj):
        self.file.write((json.dumps(obj) + "\n").encode())
        self.file.flush()

    def events(self):
        for raw in self.file:
            try:
                yield json.loads(raw)
            except ValueError:
                continue

    def request(self, obj, until=("state", "chat", "history", "ok", "pong", "done", "error")):
        self.send(obj)
        for ev in self.events():
            if ev.get("t") in until:
                return ev

    def close(self):
        try:
            self.file.close()
            self.sock.close()
        except OSError:
            pass


def running(path=None):
    try:
        Client(path).close()
        return True
    except OSError:
        return False


def start_daemon(path=None, wait=5.0):
    """Starts the daemon in the background if it is not running, and waits until it answers."""
    if running(path):
        return True
    subprocess.Popen([sys.executable, "-m", "faze_pc.daemon"], stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
    end = time.time() + wait
    while time.time() < end:
        if running(path):
            return True
        time.sleep(0.1)
    return False
