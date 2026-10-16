"""
Patch 21: the dedicated live voice mode (faze start --live): Gemini Live over a WebSocket, tested against a fake
Live server (no network, no key). The real Google endpoint needs your phone.

    PYTHONPATH=. python3 -m unittest tests.test_patch21 -v
"""

import base64
import json
import os
import subprocess
import sys
import threading
import time
import unittest
from unittest import mock

CORE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, CORE)

from tests.test_patch16 import VoiceBase, HAVE_JSDOM, run_node  # noqa: E402
import tests.test_patch17 as p17  # noqa: E402  (its fake-service harness is reused by the script tests)

import config_loader  # noqa: E402
import live_client  # noqa: E402
import live_voice  # noqa: E402
import net_status  # noqa: E402
import wake  # noqa: E402
from live_client import LiveConnection, LiveError, PcmPlayer, build_setup, parse_message, scrub  # noqa: E402

try:
    from websockets.sync.client import connect as ws_connect
    from websockets.sync.server import serve
    HAVE_WS = True
except ImportError:
    HAVE_WS = False

ROOT = os.path.dirname(CORE)
AUDIO = base64.b64encode(b"\x01\x00" * 1200).decode()


# ---------------------------------------------------------------- messages and setup (no sockets)

class TestMessages(unittest.TestCase):
    def test_setup_generation_shape(self):
        msg = build_setup("gemini-3.8-live", "be brief", [{"name": "t"}], True, "Kore", "generation")["setup"]
        self.assertEqual(msg["model"], "models/gemini-3.8-live")
        self.assertEqual(msg["generationConfig"]["responseModalities"], ["AUDIO"])
        self.assertEqual(msg["generationConfig"]["speechConfig"]["voiceConfig"]["prebuiltVoiceConfig"]["voiceName"], "Kore")
        self.assertNotIn("responseModalities", msg)
        self.assertEqual(msg["tools"], [{"googleSearch": {}}, {"functionDeclarations": [{"name": "t"}]}])
        self.assertEqual((msg["inputAudioTranscription"], msg["outputAudioTranscription"]), ({}, {}))
        self.assertEqual(msg["systemInstruction"], {"parts": [{"text": "be brief"}]})

    def test_setup_flat_shape_and_optional_tools(self):
        msg = build_setup("models/x", "i", [], False, "Puck", "flat")["setup"]
        self.assertEqual(msg["model"], "models/x")
        self.assertEqual(msg["responseModalities"], ["AUDIO"])
        self.assertNotIn("generationConfig", msg)
        self.assertNotIn("tools", msg)

    def test_parse_message(self):
        msg = {"serverContent": {"modelTurn": {"parts": [{"inlineData": {"data": AUDIO, "mimeType": "audio/pcm;rate=24000"}}, {"text": "hi"}]},
                                 "inputTranscription": {"text": "hello"}, "outputTranscription": {"text": "hi there"},
                                 "interrupted": True, "turnComplete": True},
               "toolCall": {"functionCalls": [{"id": "1", "name": "get_battery_status", "args": {}}, {"name": "x"}]},
               "goAway": {"timeLeft": "30s"}}
        kinds = [e[0] for e in parse_message(msg)]
        self.assertEqual(kinds, ["audio", "text", "in_text", "out_text", "interrupted", "turn_complete", "tool_call", "go_away"])
        events = dict((e[0], e[1:]) for e in parse_message(msg))
        self.assertEqual(events["audio"][0], b"\x01\x00" * 1200)
        self.assertEqual(events["tool_call"][0][1], {"id": "", "name": "x", "args": {}})
        self.assertEqual(events["go_away"], ("30s",))
        self.assertEqual(parse_message({"usageMetadata": {}}), [])
        self.assertEqual(parse_message({"setupComplete": {}}), [])

    def test_the_key_never_shows(self):
        self.assertNotIn("SECRET", scrub("wss://x/ws?key=SECRET&alt=1 failed"))
        self.assertIn("key=...", scrub("wss://x/ws?key=SECRET"))


class TestPcmPlayer(unittest.TestCase):
    class Proc:
        def __init__(self, argv):
            self.argv, self.written, self.stdin_closed, self.terminated, self.exited = argv, [], False, False, False
            outer = self

            class In:
                def write(self, b):
                    if outer.stdin_closed:
                        raise ValueError("closed")
                    outer.written.append(b)

                def flush(self):
                    pass

                def close(self):
                    outer.stdin_closed = True
            self.stdin = In()

        def poll(self):
            return 0 if (self.exited or self.terminated) else None

        def terminate(self):
            self.terminated = True

    def player(self, which=lambda n: "/bin/" + n if n == "pacat" else None, override=""):
        self.procs = []

        def popen(argv, **kw):
            self.procs.append(self.Proc(argv))
            return self.procs[-1]
        return PcmPlayer(override, popen=popen, which=which)

    def test_picks_pacat_then_ffplay_then_nothing(self):
        self.assertEqual(self.player().command_line()[0], "pacat")
        self.assertIn("--rate=24000", self.player().command_line())
        self.assertEqual(self.player(which=lambda n: "/bin/ffplay" if n == "ffplay" else None).command_line()[0], "ffplay")
        none = self.player(which=lambda n: None)
        self.assertFalse(none.usable())
        with self.assertRaisesRegex(LiveError, "no audio player"):
            none.write(b"x")

    def test_override_wins(self):
        self.assertEqual(self.player(override="mycmd --raw -x").command_line(), ["mycmd", "--raw", "-x"])

    def test_writes_stream_to_one_process_and_finish_lets_it_drain(self):
        p = self.player()
        p.write(b"aa")
        p.write(b"bb")
        self.assertEqual(len(self.procs), 1)
        self.assertEqual(self.procs[0].written, [b"aa", b"bb"])
        self.assertTrue(p.busy())
        p.finish()
        self.assertTrue(self.procs[0].stdin_closed)
        self.assertFalse(self.procs[0].terminated)  # it plays what is buffered, then exits
        self.procs[0].exited = True
        self.assertFalse(p.busy())
        p.write(b"cc")  # the next turn starts a fresh process
        self.assertEqual(len(self.procs), 2)

    def test_stop_silences_at_once(self):
        p = self.player()
        p.write(b"aa")
        p.stop()
        self.assertTrue(self.procs[0].terminated)
        self.assertFalse(p.busy())
        p.stop()  # harmless twice

    def test_a_dead_player_is_replaced_not_fatal(self):
        p = self.player()
        p.write(b"aa")
        self.procs[0].stdin_closed = True  # pipe gone
        p.write(b"bb")
        self.assertIsNone(p.proc)
        p.write(b"cc")
        self.assertEqual(self.procs[-1].written, [b"cc"])


class TestWakeFrames(unittest.TestCase):
    def test_yields_only_new_frames_until_stopped(self):
        cfg = config_loader.load_config()
        listener = wake.WakeListener(cfg, on_wake=lambda: None)
        listener.state = "listening"
        for i in range(5):  # old audio is not replayed
            listener._ring.append(bytes([i]) * wake.FRAME_BYTES)
            listener._frames += 1
        stop, got = threading.Event(), []

        def reader():
            for frame in listener.frames(stop):
                got.append(frame[0])
        t = threading.Thread(target=reader, daemon=True)
        t.start()
        time.sleep(0.1)
        for i in (10, 11, 12):
            listener._ring.append(bytes([i]) * wake.FRAME_BYTES)
            listener._frames += 1
            time.sleep(0.03)
        time.sleep(0.1)
        stop.set()
        t.join(2)
        self.assertEqual(got, [10, 11, 12])
        self.assertFalse(t.is_alive())

    def test_ends_when_the_stream_ends(self):
        listener = wake.WakeListener(config_loader.load_config(), on_wake=lambda: None)
        listener.state = "off"
        self.assertEqual(list(listener.frames(threading.Event())), [])


# ---------------------------------------------------------------- a fake Live server

class FakeLive:
    """Speaks just enough of the Live protocol: setup, audio in, a tool call, audio out, turn complete."""

    def __init__(self, reject=(), reject_search=False, hold_speech=0.5, after="wait", scripted=True, close_code=None, close_after_setup=None):
        self.reject, self.reject_search, self.hold_speech, self.after = set(reject), reject_search, hold_speech, after
        self.scripted, self.close_code, self.close_after_setup = scripted, close_code, close_after_setup
        self.setups, self.tool_responses, self.audio_times, self.window = [], [], [], None
        self.texts = []
        self.audio_frames = 0
        self.server = serve(self.handle, "127.0.0.1", 0)
        self.port = self.server.socket.getsockname()[1]
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def connect(self, url, **kw):
        return ws_connect(f"ws://127.0.0.1:{self.port}", open_timeout=5, max_size=None)

    def close(self):
        self.server.shutdown()

    def refuse(self, ws, field):
        ws.close(1007, f"Invalid JSON payload received. Unknown name \"{field}\" at 'setup': Cannot find field.")

    def handle(self, ws):
        setup = json.loads(ws.recv())
        self.setups.append(setup)
        s = setup["setup"]
        if self.close_code:
            ws.close(self.close_code, "API key not valid. Please pass a valid API key. (key=SECRETKEY)")
            return
        if "generation" in self.reject and "generationConfig" in s:
            return self.refuse(ws, "generationConfig")
        if "flat" in self.reject and "responseModalities" in s:
            return self.refuse(ws, "responseModalities")
        if self.reject_search and any("googleSearch" in t for t in s.get("tools", [])):
            return self.refuse(ws, "googleSearch")
        ws.send(json.dumps({"setupComplete": {}}))
        if self.close_after_setup:
            until = time.monotonic() + 0.2  # a real server keeps reading while it decides to hang up
            self.wait_for(ws, lambda: time.monotonic() >= until, seconds=1)
            ws.close(*self.close_after_setup)
            return
        try:
            if self.scripted:
                self.conversation(ws)
            while True:  # then just listen until the client leaves
                self.note(ws.recv(timeout=0.2))
        except TimeoutError:
            pass
        except Exception:
            return
        self.drain(ws)

    def drain(self, ws):
        try:
            while True:
                try:
                    self.note(ws.recv(timeout=0.2))
                except TimeoutError:
                    continue
        except Exception:
            return

    def note(self, raw):
        msg = json.loads(raw)
        if "realtimeInput" in msg and "audio" in msg["realtimeInput"]:
            self.audio_frames += 1
            self.audio_times.append(time.monotonic())
        if "realtimeInput" in msg and "text" in msg["realtimeInput"]:
            self.texts.append(msg["realtimeInput"]["text"])
        if "toolResponse" in msg:
            self.tool_responses.append(msg["toolResponse"]["functionResponses"])
        return msg

    def wait_for(self, ws, cond, seconds=8):
        end = time.monotonic() + seconds
        while time.monotonic() < end and not cond():
            try:
                self.note(ws.recv(timeout=0.1))
            except TimeoutError:
                pass

    def conversation(self, ws):
        self.wait_for(ws, lambda: self.audio_frames >= 5)
        ws.send(json.dumps({"serverContent": {"inputTranscription": {"text": "what time "}}}))
        ws.send(json.dumps({"serverContent": {"inputTranscription": {"text": "is it"}}}))
        ws.send(json.dumps({"toolCall": {"functionCalls": [{"id": "c1", "name": "get_current_datetime", "args": {}}]}}))
        self.wait_for(ws, lambda: bool(self.tool_responses))
        began = time.monotonic()
        ws.send(json.dumps({"serverContent": {"modelTurn": {"parts": [{"inlineData": {"data": AUDIO, "mimeType": "audio/pcm;rate=24000"}}]},
                                              "outputTranscription": {"text": "It is half past four."}}}))
        hold_until = time.monotonic() + self.hold_speech
        self.wait_for(ws, lambda: time.monotonic() >= hold_until, seconds=self.hold_speech + 1)
        self.window = (began, time.monotonic())
        ws.send(json.dumps({"serverContent": {"modelTurn": {"parts": [{"inlineData": {"data": AUDIO, "mimeType": "audio/pcm;rate=24000"}}]}}}))
        if self.after == "interrupt":
            ws.send(json.dumps({"serverContent": {"interrupted": True}}))
            return
        ws.send(json.dumps({"serverContent": {"turnComplete": True}}))
        if self.after == "go_away":
            ws.send(json.dumps({"goAway": {"timeLeft": "20s"}}))
        elif self.after == "close":
            until = time.monotonic() + 0.3
            self.wait_for(ws, lambda: time.monotonic() >= until, seconds=1)
            ws.close(1000, "bye")


class FakePlayer:
    def __init__(self, *a, **k):
        FakePlayer.instances.append(self)
        self.writes, self.finished_at, self.stopped, self.all_writes = [], None, 0, []
        self.command = ["fake"]

    instances = []

    def usable(self):
        return FakePlayer.ok

    ok = True

    def write(self, pcm):
        self.writes.append(pcm)
        self.all_writes.append(pcm)
        self.finished_at = None

    def finish(self):
        self.finished_at = time.monotonic()

    def busy(self):
        if not self.writes:
            return False
        return self.finished_at is None or time.monotonic() - self.finished_at < 0.15

    def stop(self):
        self.stopped += 1
        self.writes = []
        self.finished_at = None


class FrameListener:
    """The wake listener, as far as live mode sees it: a stream of 30 ms frames, hold(), _live()."""

    def __init__(self, live=True):
        self.live, self.holds = live, []

    def hold(self, on):
        self.holds.append(on)

    def _live(self):
        return self.live

    def frames(self, stop):
        while not stop.is_set() and self.live:
            yield b"\x01\x02" * 480
            time.sleep(0.01)

    def capture_utterance(self, **kw):
        return b"\x01\x02" * 480


@unittest.skipUnless(HAVE_WS, "websockets not installed")
class TestLiveConnection(unittest.TestCase):
    def setUp(self):
        self.servers = []
        self.addCleanup(lambda: [s.close() for s in self.servers])

    def fake(self, **kw):
        f = FakeLive(scripted=False, **kw)
        self.servers.append(f)
        return f

    def open(self, fake, search=True):
        conn = LiveConnection("AQ.test", "gemini-3.8-live", connect=fake.connect)
        conn.open("be brief", [{"name": "t"}], search=search, voice="Kore")
        self.addCleanup(conn.close)
        return conn

    def test_first_shape_works(self):
        fake = self.fake()
        conn = self.open(fake)
        self.assertEqual((conn.shape, conn.search, len(fake.setups)), ("generation", True, 1))

    def test_falls_back_to_the_flat_shape(self):
        fake = self.fake(reject=["generation"])
        conn = self.open(fake)
        self.assertEqual((conn.shape, conn.search, len(fake.setups)), ("flat", True, 2))
        self.assertIn("generationConfig", conn.rejections[0])

    def test_falls_back_to_no_search_in_the_working_shape(self):
        fake = self.fake(reject_search=True)
        conn = self.open(fake)
        self.assertEqual((conn.shape, conn.search), ("generation", False))
        self.assertEqual(len(fake.setups), 3)  # generation+search, flat+search, generation without
        self.assertNotIn("googleSearch", json.dumps(fake.setups[-1]))

    def test_everything_refused_lists_every_reason(self):
        fake = self.fake(reject=["generation", "flat"])
        conn = LiveConnection("AQ.test", "m", connect=fake.connect)
        with self.assertRaises(LiveError) as cm:
            conn.open("i", [], search=True)
        text = str(cm.exception)
        self.assertIn("setup was refused", text)
        self.assertIn("generation", text)
        self.assertIn("flat", text)
        self.assertEqual(len(fake.setups), 4)

    def test_an_auth_failure_is_not_retried_and_never_shows_the_key(self):
        fake = self.fake(close_code=1008)
        conn = LiveConnection("SECRETKEY", "m", connect=fake.connect)
        with self.assertRaises(LiveError) as cm:
            conn.open("i", [], search=False)
        self.assertEqual(len(fake.setups), 1)
        self.assertIn("API key not valid", str(cm.exception))
        self.assertNotIn("SECRETKEY", str(cm.exception))

    def test_unreachable_server(self):
        def refuse(url, **kw):
            raise OSError("connection refused: wss://x?key=SECRETKEY")
        conn = LiveConnection("SECRETKEY", "m", connect=refuse)
        with self.assertRaises(LiveError) as cm:
            conn.open("i", [], search=False)
        self.assertIn("could not connect to generativelanguage.googleapis.com", str(cm.exception))
        self.assertNotIn("SECRETKEY", str(cm.exception))

    def test_missing_package_is_explained(self):
        with mock.patch.object(live_client, "_ws_connect", None):
            with self.assertRaisesRegex(LiveError, "pip install websockets"):
                LiveConnection("k", "m").open("i", [])
            self.assertFalse(live_client.available())

    def test_traffic_formats(self):
        fake = self.fake()
        conn = self.open(fake)
        conn.send_audio(b"\x05\x06" * 10)
        conn.send_tool_responses([{"id": "1", "name": "a", "ok": True, "text": "fine"}, {"id": "2", "name": "b", "ok": False, "text": "bad"}])
        time.sleep(0.3)
        self.assertEqual(fake.audio_frames, 1)
        self.assertEqual(fake.tool_responses[0], [{"id": "1", "name": "a", "response": {"result": "fine"}},
                                                  {"id": "2", "name": "b", "response": {"error": "bad"}}])

    def test_poll_events_timeouts_and_close(self):
        fake = self.fake()
        conn = self.open(fake)
        self.assertEqual(conn.poll(0.05), [])
        conn.close()
        self.assertEqual(conn.poll(0.05)[0][0], "closed")


# ---------------------------------------------------------------- the whole mode

@unittest.skipUnless(HAVE_WS, "websockets not installed")
class LiveBase(VoiceBase):
    def setUp(self):
        super().setUp()
        self.fakes = []
        self.addCleanup(lambda: [f.close() for f in self.fakes])
        net_status.mark_online()
        self.addCleanup(net_status.reset)
        env = mock.patch.dict(os.environ, {"GEMINI_API_KEY": "AQ.test"})
        env.start()
        self.addCleanup(env.stop)
        FakePlayer.instances, FakePlayer.ok = [], True
        for target, value in (("live_voice.PcmPlayer", FakePlayer),):
            p = mock.patch(target, value)
            p.start()
            self.addCleanup(p.stop)
        self.frames = FrameListener()
        self.httpd.wake = self.frames
        self.executed = []

        def execute(name, args, layer, extra=None):
            self.executed.append((name, args, layer))
            return True, "half past four"
        p = mock.patch.object(self.convo, "_execute_tool", side_effect=execute)
        p.start()
        self.addCleanup(p.stop)
        self.convo.config["live"].update(idle_seconds=0.5)

    def serve(self, **kw):
        fake = FakeLive(**kw)
        self.fakes.append(fake)
        p = mock.patch.object(live_client, "_default_connect", fake.connect)
        p.start()
        self.addCleanup(p.stop)
        return fake

    def collapse(self):
        seen = self.states()
        return [x for i, x in enumerate(seen) if i == 0 or x != seen[i - 1]]


class TestLiveConversation(LiveBase):
    def test_a_full_conversation(self):
        fake = self.serve()
        started = self.voice.start(live=True)
        self.assertTrue(started["started"])
        self.assertEqual(started["engine"], "live")
        done = self.wait_state("done", seconds=15)
        self.assertEqual(self.collapse(), ["listening", "thinking", "speaking", "listening", "done"])
        self.assertEqual((done["transcript"], done["reply"]), ("what time is it", "It is half past four."))
        self.assertEqual(done["note"], "Conversation ended (idle)")
        self.assertEqual(done["engine"], "live")
        self.assertEqual(self.executed, [("get_current_datetime", {}, "live")])
        self.assertEqual(fake.tool_responses[0], [{"id": "c1", "name": "get_current_datetime", "response": {"result": "half past four"}}])
        player = FakePlayer.instances[0]
        self.assertEqual(len(player.all_writes), 2)
        self.assertEqual(player.all_writes[0], b"\x01\x00" * 1200)
        t = done["timings"]
        self.assertEqual(t["turns"], 1)
        self.assertGreaterEqual(t["connect_ms"], 0)
        self.assertIn("first_audio_ms", t)
        self.assertEqual(self.listener_holds(), [True, False])
        self.assertFalse(self.httpd.chat_lock.locked())

    def listener_holds(self):
        return self.frames.holds

    def test_what_the_server_was_asked_for(self):
        fake = self.serve()
        self.voice.start(live=True)
        self.wait_state("done", seconds=15)
        s = fake.setups[0]["setup"]
        self.assertEqual(s["model"], "models/gemini-3.8-live")
        self.assertEqual(s["generationConfig"]["speechConfig"]["voiceConfig"]["prebuiltVoiceConfig"]["voiceName"], "Kore")
        self.assertIn("speaking aloud", s["systemInstruction"]["parts"][0]["text"])
        names = [d["name"] for t in s["tools"] for d in t.get("functionDeclarations", [])]
        self.assertTrue(any("googleSearch" in t for t in s["tools"]))
        self.assertIn("get_weather", names)
        self.assertIn("get_current_datetime", names)
        for banned in ("listen", "create_tool", "search_web"):
            self.assertNotIn(banned, names)

    def test_search_off_keeps_the_search_tool_and_drops_google_search(self):
        self.convo.config["live"]["search"] = False
        fake = self.serve()
        self.voice.start(live=True)
        self.wait_state("done", seconds=15)
        tools = fake.setups[0]["setup"]["tools"]
        self.assertFalse(any("googleSearch" in t for t in tools))
        self.assertIn("search_web", [d["name"] for t in tools for d in t["functionDeclarations"]])

    def test_the_microphone_is_muted_while_gemini_speaks(self):
        fake = self.serve(hold_speech=0.8)
        self.voice.start(live=True)
        self.wait_state("done", seconds=15)
        began, ended = fake.window
        inside = [t for t in fake.audio_times if began + 0.1 < t < ended]
        self.assertLessEqual(len(inside), 3)  # nothing but frames already in flight
        before = [t for t in fake.audio_times if t < began]
        self.assertGreaterEqual(len(before), 5)

    def test_barge_in_keeps_the_microphone_open(self):
        self.convo.config["live"]["barge_in"] = True
        fake = self.serve(hold_speech=0.8)
        self.voice.start(live=True)
        self.wait_state("done", seconds=15)
        began, ended = fake.window
        inside = [t for t in fake.audio_times if began + 0.1 < t < ended]
        self.assertGreaterEqual(len(inside), 10)

    def test_an_interruption_silences_the_player_and_goes_back_to_listening(self):
        self.serve(after="interrupt")
        self.voice.start(live=True)
        self.wait_state("listening", seconds=15)
        end = time.time() + 8
        while time.time() < end and not FakePlayer.instances[0].stopped:
            time.sleep(0.02)
        self.assertGreaterEqual(FakePlayer.instances[0].stopped, 1)
        self.voice.stop()

    def test_go_away_ends_it_after_the_turn(self):
        self.serve(after="go_away")
        self.voice.start(live=True)
        done = self.wait_state("done", seconds=15)
        self.assertEqual(done["note"], "Live session is ending soon")

    def test_the_server_closing_after_a_turn_is_a_normal_end(self):
        self.serve(after="close")
        self.voice.start(live=True)
        done = self.wait_state("done", seconds=15)
        self.assertEqual(done["note"], "Live session closed")

    def test_the_server_closing_before_anything_is_an_error(self):
        self.serve(scripted=False, close_after_setup=(1011, "internal error"))
        self.voice.start(live=True)
        err = self.wait_state("error", seconds=10)
        self.assertIn("the Live session closed", err["error"])
        self.assertIn("internal error", err["error"])
        self.assertEqual(self.frames.holds, [True, False])
        self.assertFalse(self.httpd.chat_lock.locked())

    def test_nobody_speaking_ends_it(self):
        self.serve(scripted=False)
        self.convo.config["live"]["idle_seconds"] = 0.4
        self.voice.start(live=True)
        done = self.wait_state("done", seconds=10)
        self.assertEqual(done["note"], "Conversation ended (nobody spoke)")

    def test_stop_ends_it_and_releases_everything(self):
        self.serve(scripted=False)
        self.convo.config["live"]["idle_seconds"] = 30
        self.voice.start(live=True)
        self.wait_state("listening")
        time.sleep(0.3)
        s = self.voice.stop()
        self.assertEqual((s["state"], s["note"]), ("idle", "Stopped"))
        end = time.time() + 5
        while time.time() < end and (self.httpd.chat_lock.locked() or self.frames.holds != [True, False]):
            time.sleep(0.05)
        self.assertFalse(self.httpd.chat_lock.locked())
        self.assertEqual(self.frames.holds, [True, False])
        self.assertGreaterEqual(FakePlayer.instances[0].stopped, 1)

    def test_the_session_time_limit(self):
        self.serve(scripted=False)
        self.convo.config["live"].update(max_seconds=0.5, idle_seconds=30)
        self.voice.start(live=True)
        self.assertEqual(self.wait_state("done", seconds=10)["note"], "Live session time limit reached")

    def test_a_busy_chat_is_an_error_not_a_fallback(self):
        self.serve(scripted=False)
        self.httpd.chat_lock.acquire()
        try:
            self.voice.start(live=True)
            self.assertIn("still answering", self.wait_state("error")["error"])
        finally:
            self.httpd.chat_lock.release()

    def test_the_classic_voice_is_untouched(self):
        fake = self.serve()
        with mock.patch.object(self.convo, "send", return_value="Half past four, if you must know."):
            self.voice.start()
            self.wait_state("speaking", "done")
        self.assertEqual(fake.setups, [])
        self.assertEqual(self.voice.state()["engine"], "classic")


class TestLiveFallsBack(LiveBase):
    """When Live cannot start, nothing was said yet, so the classic voice takes over and says why."""

    def classic_finishes(self):
        with mock.patch.object(self.convo, "send", return_value="Half past four, if you must know."):
            self.voice.start(live=True)
            done = self.wait_state("done", seconds=10)
        self.assertEqual(done["engine"], "classic")
        self.assertIn("Live is unavailable", done["note"])
        self.assertEqual(done["reply"], "Half past four, if you must know.")
        return done

    def test_no_key(self):
        self.serve()
        os.environ.pop("GEMINI_API_KEY")
        self.assertIn("no GEMINI_API_KEY", self.classic_finishes()["note"])

    def test_offline(self):
        self.serve()
        net_status.mark_offline()
        self.assertIn("offline", self.classic_finishes()["note"])

    def test_websockets_missing(self):
        with mock.patch.object(live_client, "_ws_connect", None):
            self.assertIn("pip install websockets", self.classic_finishes()["note"])

    def test_no_wake_listener(self):
        self.serve()
        self.httpd.wake = None
        with mock.patch("device.record_pcm", return_value=b"\x05\x06" * 800):
            self.assertIn("wake listener", self.classic_finishes()["note"])

    def test_no_audio_player(self):
        self.serve()
        FakePlayer.ok = False
        self.assertIn("no audio player", self.classic_finishes()["note"])

    def test_setup_refused(self):
        self.serve(reject=["generation", "flat"])
        note = self.classic_finishes()["note"]
        self.assertIn("setup was refused", note)

    def test_the_failed_attempt_leaves_nothing_held(self):
        self.serve(close_code=1008)
        self.classic_finishes()
        self.assertFalse(self.httpd.chat_lock.locked())


class TestLiveEndpointAndScript(LiveBase):
    def test_endpoint_starts_a_live_conversation(self):
        self.serve(scripted=False)
        self.convo.config["live"]["idle_seconds"] = 30
        code, d = self.jpost("/api/voice/start", {"live": True})
        self.assertEqual((code, d["started"], d["engine"]), (200, True, "live"))
        self.assertEqual(self.jget("/api/voice/state")[1]["engine"], "live")
        self.voice.stop()

    def test_settings_exist(self):
        cfg = config_loader.load_config()["live"]
        self.assertEqual(cfg, {"model": "gemini-3.8-live", "barge_in": False, "search": True, "idle_seconds": 15, "max_seconds": 600, "player": ""})
        for key in ("live.model", "live.barge_in", "live.search", "live.idle_seconds", "live.max_seconds", "live.player"):
            self.assertIn(key, config_loader.SETTINGS)


class TestLiveScript(p17.TestOverlayButtonCommands):
    """Reuses the fake-service harness from patch 17 (its own tests are not repeated: only the ones below run)."""

    def test_start_live_posts_the_live_flag_and_does_not_open_the_app(self):
        self.token()
        with open(os.path.join(self.dir, "fake_faze.sh"), "w") as f:
            f.write("#!/bin/bash\nsleep 60\n")
        os.chmod(os.path.join(self.dir, "fake_faze.sh"), 0o755)
        import socket
        from http.server import HTTPServer
        from tests.test_patch15 import Quiet
        httpd = HTTPServer(("127.0.0.1", self.port), Quiet)
        threading.Thread(target=httpd.serve_forever, daemon=True).start()
        self.addCleanup(httpd.shutdown)
        self.addCleanup(httpd.server_close)
        with open(os.path.join(self.dir, "reply.txt"), "w") as f:
            f.write('{"ok": true, "started": true}')
        self.write("http.sh", f'echo "$1 $2 $3" >> "{self.posted}"; cat "{os.path.join(self.dir, "reply.txt")}"')
        self.env.update(FAZE_CMD=os.path.join(self.dir, "fake_faze.sh"), FAZE_RESTART_DELAY="0.2", FAZE_READY_TIMEOUT="5")
        self.addCleanup(lambda: self.run_svc("stop"))
        out = self.run_svc("start", "--live")
        self.assertEqual(out.returncode, 0, out.stderr)
        self.assertIn("live: listening", out.stdout)
        with open(self.posted) as f:
            self.assertEqual(f.read().strip(), '/api/voice/start {"overlay": true, "live": true} tok123')
        self.assertFalse(os.path.exists(self.opened))
        self.assertEqual(self.run_svc("live").returncode, 0)

    def fake_am(self, exit_code=0):
        log = os.path.join(self.dir, "am.txt")
        bindir = os.path.join(self.dir, "bin")
        os.makedirs(bindir, exist_ok=True)
        path = os.path.join(bindir, "am")
        with open(path, "w") as f:
            f.write(f'#!/bin/bash\necho "$@" >> "{log}"\nexit {exit_code}\n')
        os.chmod(path, 0o755)
        self.env["PATH"] = bindir + os.pathsep + self.env["PATH"]
        return log

    def test_close_stops_and_asks_tasker_even_when_the_service_is_down(self):
        self.token()
        log = self.fake_am()
        self.write("http.sh", "exit 7")  # the service cannot be reached
        out = self.run_svc("close")
        self.assertEqual((out.returncode, out.stdout.strip()), (0, "closed"), out.stderr)
        with open(log) as f:
            self.assertEqual(f.read().strip(), "broadcast -a net.dinglisch.android.tasker.ACTION_TASK --es task_name Faze Overlay Close")

    def test_close_also_stops_the_exchange_when_the_service_is_up(self):
        self.token()
        self.fake_am()
        out = self.run_svc("close")
        self.assertEqual(out.returncode, 0, out.stderr)
        with open(self.posted) as f:
            self.assertEqual(f.read().strip(), '/api/voice/stop {"close": true} tok123')

    def test_close_task_name_can_be_changed_and_a_missing_tasker_is_reported(self):
        self.token()
        log = self.fake_am()
        self.env["FAZE_CLOSE_TASK"] = "My Close"
        self.run_svc("close")
        with open(log) as f:
            self.assertTrue(f.read().strip().endswith("task_name My Close"))
        self.fake_am(exit_code=1)
        out = self.run_svc("close")
        self.assertEqual(out.returncode, 1)
        self.assertIn("could not reach Tasker", out.stdout)

    def test_overlay_link_for_live(self):
        self.token()
        self.assertEqual(self.run_svc("overlay", "--live").stdout.strip(), f"http://localhost:{self.port}/overlay#t=tok123&auto&live")
        self.assertEqual(self.run_svc("overlay").stdout.strip(), f"http://localhost:{self.port}/overlay#t=tok123&auto")

    def test_modes_stay_exclusive(self):
        for args in (("start", "--live", "--voice"), ("start", "--app", "--live")):
            out = self.run_svc(*args)
            self.assertEqual(out.returncode, 2, args)
            self.assertIn("--live", out.stdout)


@unittest.skipUnless(HAVE_JSDOM, "jsdom not installed")
class TestOverlayLive(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.res = run_node("overlay_smoke.js")

    def test_badge_and_again_follow_the_engine(self):
        r = self.res
        self.assertNotIn("crash", r, r.get("crash"))
        self.assertEqual(r["errors"], [])
        self.assertTrue(r["classicBadgeHidden"])
        self.assertFalse(r["liveBadgeHidden"])
        self.assertEqual(r["liveAgainBody"], {"live": True})

    def test_the_live_link_starts_live_and_keeps_its_flags(self):
        r = self.res["liveAuto"]
        self.assertEqual(r["bodies"], [{"live": True}])
        self.assertFalse(r["badgeHidden"])
        self.assertEqual(r["hash"], "#auto&live")


if __name__ == "__main__":
    unittest.main()
