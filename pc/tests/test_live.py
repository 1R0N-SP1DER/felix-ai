import base64
import io
import json
import socket
import threading
import time
import unittest

from faze_pc import live, wsclient
from faze_pc.config import Config
from tests.test_tasks import tmp


def serve_once(handler):
    """A one-connection websocket server on localhost. handler(ws_server_side_socket, recv_frame) runs after the handshake."""
    srv = socket.socket()
    srv.bind(("127.0.0.1", 0))
    srv.listen(1)

    def run():
        conn, _ = srv.accept()
        data = b""
        while b"\r\n\r\n" not in data:
            data += conn.recv(4096)
        key = [l.split(":", 1)[1].strip() for l in data.decode().split("\r\n") if l.lower().startswith("sec-websocket-key")][0]
        conn.sendall(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: %s\r\n\r\n" % wsclient.accept_key(key)).encode())
        c = wsclient.Ws(conn)
        c.buf = data.split(b"\r\n\r\n", 1)[1]
        try:
            handler(c)
        finally:
            conn.close()
            srv.close()

    threading.Thread(target=run, daemon=True).start()
    return srv.getsockname()[1]


class WsTest(unittest.TestCase):
    def test_round_trip_and_fragments_and_ping(self):
        def handler(c):
            op, fin, body = c._frame()  # client frames are masked, _frame unmasks them
            self.assertEqual((op, body), (1, b"hello"))
            c.sock.sendall(wsclient.encode_frame(9, b"p", mask=False))
            c.sock.sendall(wsclient.encode_frame(1, b"ab", mask=False, fin=False))
            c.sock.sendall(wsclient.encode_frame(0, b"cd", mask=False))
            c.sock.sendall(wsclient.encode_frame(2, b"x" * 70000, mask=False))
            c.sock.sendall(wsclient.encode_frame(8, b"\x03\xe8bye", mask=False))
            time.sleep(0.3)

        port = serve_once(handler)
        ws = wsclient.Ws.connect("ws://127.0.0.1:%d/path?k=1" % port, 3)
        ws.send("hello")
        self.assertEqual(ws.recv(2), "abcd")
        self.assertEqual(len(ws.recv(2)), 70000)
        with self.assertRaises(wsclient.WsClosed) as cm:
            ws.recv(2)
        self.assertEqual((cm.exception.code, cm.exception.reason), (1000, "bye"))

    def test_timeout(self):
        port = serve_once(lambda c: time.sleep(0.5))
        ws = wsclient.Ws.connect("ws://127.0.0.1:%d/" % port, 3)
        with self.assertRaises(TimeoutError):
            ws.recv(0.1)
        ws.close()

    def test_bad_handshake(self):
        srv = socket.socket()
        srv.bind(("127.0.0.1", 0))
        srv.listen(1)
        threading.Thread(target=lambda: (lambda c: (c.recv(4096), c.sendall(b"HTTP/1.1 403 Forbidden\r\n\r\n")))(srv.accept()[0]), daemon=True).start()
        with self.assertRaises(wsclient.WsError):
            wsclient.Ws.connect("ws://127.0.0.1:%d/" % srv.getsockname()[1], 3)


class FakeWs:
    def __init__(self, replies, reject=None):
        self.sent, self.replies, self.reject, self.closed = [], list(replies), reject, False

    def send(self, text):
        self.sent.append(json.loads(text))

    def recv(self, timeout=None):
        if self.replies:
            return self.replies.pop(0)
        if self.closed:
            raise wsclient.WsClosed(1000, "")
        time.sleep(timeout or 0.05)
        raise TimeoutError()

    def close(self):
        self.closed = True


class Sink(io.BytesIO):
    def close(self):
        self.kept = self.getvalue()
        super().close()


class FakeProc:
    def __init__(self, data=b""):
        self.stdout = io.BytesIO(data)
        self.stdin = Sink()
        self.terminated = False

    def poll(self):
        return None

    def terminate(self):
        self.terminated = True


def msg(**kw):
    return json.dumps(kw)


class LiveTest(unittest.TestCase):
    def make(self, ws_factory, mic=b"\x01\x00" * 1600 * 3):
        cfg = Config(tmp())
        cfg.data["keys"]["gemini"] = "SECRETKEY"
        events, procs = [], []

        def popen(argv, **kw):
            p = FakeProc(mic if argv[0] in ("pw-record", "arecord", "parecord") else b"")
            procs.append((argv, p))
            return p

        s = live.LiveSession(cfg, events.append, which=lambda n: "/bin/" + n, popen=popen, connect=ws_factory)
        return s, events, procs

    def test_needs_key(self):
        cfg = Config(tmp())
        events = []
        live.LiveSession(cfg, events.append, which=lambda n: None).run()
        self.assertIn("Gemini key", events[0]["message"])

    def test_setup_audio_in_and_out(self):
        audio = base64.b64encode(b"\x10\x00" * 2400).decode()
        ws = FakeWs([msg(setupComplete={}),
                     msg(serverContent={"inputTranscription": {"text": "hi"}}),
                     msg(serverContent={"modelTurn": {"parts": [{"inlineData": {"data": audio}}]}}),
                     msg(serverContent={"outputTranscription": {"text": "hello"}}),
                     msg(serverContent={"turnComplete": True})])
        s, events, procs = self.make(lambda url: ws)
        t = threading.Thread(target=s.run, daemon=True)
        t.start()
        time.sleep(0.8)
        s.stop()
        t.join(3)
        self.assertEqual(ws.sent[0]["setup"]["model"], "models/" + live.DEFAULT_MODEL)
        self.assertTrue(any("realtimeInput" in m for m in ws.sent))
        states = [e["state"] for e in events if e["t"] == "voice_state"]
        self.assertEqual(states[0], "listening")
        self.assertIn("speaking", states)
        self.assertEqual(states[-1], "off")
        self.assertIn({"t": "voice_user_live", "text": "hi"}, events)
        self.assertIn({"t": "voice_said", "text": "hello"}, events)
        player = [p for a, p in procs if a[0] == "pacat"][0]
        self.assertEqual(len(player.stdin.kept), 4800)
        self.assertFalse(any("SECRETKEY" in json.dumps(e) for e in events))

    def test_rejected_shape_falls_back(self):
        calls = []

        def factory(url):
            n = len(calls)
            calls.append(n)
            if n == 0:
                bad = FakeWs([])
                bad.recv = lambda timeout=None: (_ for _ in ()).throw(wsclient.WsClosed(1007, "Unknown name responseModalities"))
                return bad
            return FakeWs([msg(setupComplete={})])

        s, events, _ = self.make(factory)
        s.open()
        self.assertEqual(len(calls), 2)
        self.assertIn("generation", s.rejections[0])
        s.stop()

    def test_refused_setup_message_has_no_key(self):
        def factory(url):
            ws = FakeWs([])
            ws.recv = lambda timeout=None: (_ for _ in ()).throw(wsclient.WsClosed(1008, "quota exceeded key=SECRETKEY"))
            return ws

        s, events, _ = self.make(factory)
        with self.assertRaises(live.LiveError) as cm:
            s.open()
        self.assertNotIn("SECRETKEY", str(cm.exception))

    def test_interrupt_stops_player_and_returns_to_listening(self):
        audio = base64.b64encode(b"\x10\x00" * 100).decode()
        ws = FakeWs([msg(setupComplete={}),
                     msg(serverContent={"modelTurn": {"parts": [{"inlineData": {"data": audio}}]}}),
                     msg(serverContent={"interrupted": True})])
        s, events, _ = self.make(lambda url: ws, mic=b"")
        t = threading.Thread(target=s.run, daemon=True)
        t.start()
        time.sleep(0.5)
        s.stop()
        t.join(3)
        states = [e["state"] for e in events if e["t"] == "voice_state"]
        self.assertEqual(states[:3], ["listening", "speaking", "listening"])

    def test_tool_calls_are_answered_with_an_error(self):
        ws = FakeWs([msg(setupComplete={}), msg(toolCall={"functionCalls": [{"id": "1", "name": "x", "args": {}}]})])
        s, events, _ = self.make(lambda url: ws, mic=b"")
        t = threading.Thread(target=s.run, daemon=True)
        t.start()
        time.sleep(0.5)
        s.stop()
        t.join(3)
        self.assertTrue(any("toolResponse" in m for m in ws.sent))


if __name__ == "__main__":
    unittest.main()
