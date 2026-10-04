"""
felix-ai Phone Core -- phone_server.py
Minimal HTTP server so the PC (or anything on the LAN) can send Faze a
message and get a real response back, using the phone's actual
orchestrator.py Conversation (memory, tools, routing -- everything).
"""

import json
from http.server import BaseHTTPRequestHandler, HTTPServer

from config_loader import load_config
from memory_store import init_db
from orchestrator import Conversation

PHONE_SERVER_PORT = 8766

init_db()
config = load_config()
convo = Conversation(config)
convo.start_background()


class PhoneServerHandler(BaseHTTPRequestHandler):
    def _send_json(self, payload: dict, status: int = 200):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/":
            self._send_json({"status": "Faze phone server is running"})
        else:
            self._send_json({"error": "not found"}, status=404)

    def do_POST(self):
        if self.path != "/message":
            self._send_json({"error": "not found"}, status=404)
            return

        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length)
        try:
            data = json.loads(raw)
        except json.JSONDecodeError:
            self._send_json({"error": "invalid JSON"}, status=400)
            return

        message = data.get("message", "")
        if not message:
            self._send_json({"error": "missing 'message' field"}, status=400)
            return

        source = data.get("source", "unknown")
        print(f"[phone_server] message from {source}: {message}")

        reply = convo.send(message)
        self._send_json({"reply": reply})

    def log_message(self, format, *args):
        print(f"[phone_server] {self.command} {self.path}")


if __name__ == "__main__":
    print(f"Faze phone server -- listening on port {PHONE_SERVER_PORT}")
    print("Accepts POST /message {\"message\": \"...\", \"source\": \"pc\"}")
    server = HTTPServer(("0.0.0.0", PHONE_SERVER_PORT), PhoneServerHandler)
    server.serve_forever()
