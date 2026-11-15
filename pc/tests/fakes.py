import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class FakeProvider:
    """An OpenAI-style server on localhost. script is a list of (status, pieces, headers) used in turn; the last one repeats."""

    def __init__(self, script):
        self.script = script
        self.requests = []
        outer = self

        class H(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def do_POST(self):
                n = int(self.headers.get("Content-Length", 0))
                body = json.loads(self.rfile.read(n))
                outer.requests.append((self.path, self.headers.get("Authorization"), body))
                i = min(len(outer.requests) - 1, len(outer.script) - 1)
                status, pieces, headers = (outer.script[i] + ({},))[:3] if len(outer.script[i]) == 2 else outer.script[i]
                self.send_response(status)
                for k, v in headers.items():
                    self.send_header(k, v)
                if status != 200:
                    self.send_header("Content-Type", "application/json")
                    self.end_headers()
                    self.wfile.write(json.dumps({"error": {"message": pieces}}).encode())
                    return
                self.send_header("Content-Type", "text/event-stream")
                self.end_headers()
                try:
                    for p in pieces:
                        if p == "__HANG__":
                            time.sleep(30)
                            continue
                        self.wfile.write(("data: %s\n\n" % json.dumps({"choices": [{"delta": {"content": p}}]})).encode())
                        self.wfile.flush()
                    self.wfile.write(b"data: [DONE]\n\n")
                except OSError:
                    pass

        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), H)
        self.url = "http://127.0.0.1:%d" % self.httpd.server_address[1]
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()

    def close(self):
        self.httpd.shutdown()
