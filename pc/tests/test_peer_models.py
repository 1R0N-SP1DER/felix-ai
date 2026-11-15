import os
import tempfile
import unittest

from faze_pc.config import Config
from faze_pc.peer import PeerRequests


class PeerModelsTest(unittest.TestCase):
    def cfg(self):
        return Config(os.path.join(tempfile.mkdtemp(prefix="faze-test-"), "c.json"))

    def test_lists_the_ollama_models(self):
        r = PeerRequests(self.cfg(), lister=lambda: ["llama3.2:3b", "qwen2.5:1.5b"]).handle("p", {"t": "pc_models"})
        self.assertEqual(r, {"t": "pc_models_ok", "models": ["llama3.2:3b", "qwen2.5:1.5b"]})

    def test_ollama_off_is_an_error_reply(self):
        def boom():
            raise OSError("refused")
        r = PeerRequests(self.cfg(), lister=boom).handle("p", {"t": "pc_models"})
        self.assertEqual(r["t"], "error")
        self.assertIn("Ollama", r["message"])

    def test_a_chosen_model_is_used_over_the_default(self):
        seen = []
        p = PeerRequests(self.cfg(), lambda base, key, model, messages, on_token, **kw: (seen.append(model), "x")[1])
        p.handle("p", {"t": "pc_chat", "messages": [{"role": "user", "content": "hi"}], "model": "llama3.2:3b"})
        p.handle("p", {"t": "pc_chat", "messages": [{"role": "user", "content": "hi"}]})
        self.assertEqual(seen, ["llama3.2:3b", p.config.data["ollama_chat_model"]])

    def test_default_lister_reads_the_tags_endpoint(self):
        import http.server, threading, json
        class H(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                body = json.dumps({"models": [{"name": "b:1"}, {"name": "a:2"}]}).encode()
                self.send_response(200); self.end_headers(); self.wfile.write(body)
            def log_message(self, *a): pass
        srv = http.server.HTTPServer(("127.0.0.1", 0), H)
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        c = self.cfg()
        c.data["ollama_url"] = "http://127.0.0.1:%d" % srv.server_port
        try:
            self.assertEqual(PeerRequests(c)._ollama_models(), ["a:2", "b:1"])
        finally:
            srv.shutdown(); srv.server_close()


if __name__ == "__main__":
    unittest.main()
