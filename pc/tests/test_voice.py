import base64
import json
import os
import struct
import tempfile
import unittest

from faze_pc import voice as v
from faze_pc.config import Config


class FakeProc:
    def __init__(self, argv, **kw):
        self.argv, self.signals, self.killed, self.done = argv, [], False, False
        # a recorder writes its file; a player just plays
        if argv and argv[0] in ("pw-record", "parecord", "arecord"):
            path = argv[-1]
            with open(path, "wb") as f:
                f.write(b"RIFF" + b"x" * 5000)

    def send_signal(self, s):
        self.signals.append(s)

    def wait(self, timeout=None):
        self.done = True

    def terminate(self):
        self.killed = True

    def kill(self):
        self.killed = True


class VoiceTest(unittest.TestCase):
    def cfg(self, **keys):
        c = Config(os.path.join(tempfile.mkdtemp(prefix="faze-test-"), "c.json"))
        c.data["keys"].update(keys)
        return c

    def voice(self, have=("pw-record", "pw-play"), post=None, **keys):
        self.procs = []
        def popen(argv, **kw):
            p = FakeProc(argv)
            self.procs.append(p)
            return p
        self.posts = []
        def fake_post(url, headers, body, timeout=40):
            self.posts.append((url, headers, body))
            return post(url, headers, body)
        return v.Voice(self.cfg(**keys), which=lambda n: "/usr/bin/" + n if n in have else None, popen=popen, post=fake_post)

    def test_wav_header_is_valid(self):
        w = v.wav_from_pcm(b"\x00\x00" * 100)
        self.assertEqual(w[:4], b"RIFF")
        self.assertEqual(struct.unpack("<I", w[40:44])[0], 200)
        self.assertEqual(struct.unpack("<I", w[24:28])[0], 24000)

    def test_multipart_has_fields_and_file(self):
        body, ctype = v.multipart({"model": "m"}, "file", "a.wav", b"DATA", "audio/wav")
        b = ctype.split("boundary=")[1].encode()
        self.assertIn(b"name=\"model\"\r\n\r\nm", body)
        self.assertIn(b"filename=\"a.wav\"", body)
        self.assertTrue(body.endswith(b"--" + b + b"--\r\n"))

    def test_records_then_transcribes_with_groq(self):
        voice = self.voice(post=lambda u, h, b: json.dumps({"text": " open firefox "}).encode(), groq="gsk_x")
        voice.start_listening()
        self.assertEqual(self.procs[0].argv[0], "pw-record")
        self.assertEqual(voice.stop_listening(), "open firefox")
        self.assertIn("audio/transcriptions", self.posts[0][0])
        self.assertEqual(self.posts[0][1]["Authorization"], "Bearer gsk_x")
        self.assertTrue(self.procs[0].signals)  # asked to finish the file
        self.assertFalse(os.path.exists(self.procs[0].argv[-1]))

    def test_gemini_transcription_when_no_groq_key(self):
        reply = {"candidates": [{"content": {"parts": [{"text": "hello there"}]}}]}
        voice = self.voice(post=lambda u, h, b: json.dumps(reply).encode(), gemini="AIza")
        voice.start_listening()
        self.assertEqual(voice.stop_listening(), "hello there")
        self.assertIn("generateContent", self.posts[0][0])
        self.assertEqual(json.loads(self.posts[0][2])["contents"][0]["parts"][1]["inline_data"]["mime_type"], "audio/wav")

    def test_no_key_and_no_recorder_say_what_is_missing(self):
        voice = self.voice()
        voice.start_listening()
        with self.assertRaises(v.VoiceError) as e:
            voice.stop_listening()
        self.assertIn("key", str(e.exception))
        with self.assertRaises(v.VoiceError) as e:
            self.voice(have=()).start_listening()
        self.assertIn("recorder", str(e.exception))

    def test_silence_returns_nothing_without_a_request(self):
        voice = self.voice(groq="x")
        voice.start_listening()
        open(self.procs[0].argv[-1], "wb").write(b"RIFF")
        self.assertEqual(voice.stop_listening(), "")
        self.assertEqual(self.posts, [])

    def test_cancel_removes_the_recording(self):
        voice = self.voice()
        voice.start_listening()
        path = self.procs[0].argv[-1]
        voice.cancel_listening()
        self.assertFalse(os.path.exists(path))

    def test_gemini_voice_plays_through_the_player(self):
        pcm = b"\x01\x00" * 50
        answer = {"steps": [{"type": "model_output", "content": [{"type": "audio", "data": base64.b64encode(pcm).decode()}]}]}
        voice = self.voice(post=lambda u, h, b: json.dumps(answer).encode(), gemini="AIza")
        self.assertEqual(voice.speak("hi there"), "gemini")
        sent = json.loads(self.posts[0][2])
        self.assertEqual(sent["generation_config"]["speech_config"][0]["voice"], "Kore")
        self.assertEqual(self.procs[0].argv[0], "pw-play")

    def test_falls_back_to_a_system_voice_when_gemini_fails(self):
        def boom(u, h, b):
            raise v.VoiceError("generativelanguage.googleapis.com answered 429")
        voice = self.voice(have=("pw-play", "espeak-ng"), post=boom, gemini="AIza")
        self.assertEqual(voice.speak("hello"), "system")
        self.assertEqual(self.procs[0].argv, ["espeak-ng", "hello"])

    def test_nothing_to_speak_with_says_so(self):
        voice = self.voice(have=(), gemini="")
        with self.assertRaises(v.VoiceError):
            voice.speak("hello")

    def test_stop_speaking_ends_the_player(self):
        voice = self.voice(have=("espeak-ng",))
        class Slow(FakeProc):
            def wait(self, timeout=None):
                voice.stop_speaking()
        voice.popen = lambda argv, **kw: (self.procs.append(Slow(argv)), self.procs[-1])[1]
        voice.speak("long text")
        self.assertTrue(self.procs[-1].killed)

    def test_audio_is_taken_from_the_last_model_step(self):
        body = json.dumps({"steps": [{"type": "model_output", "content": [{"type": "audio", "data": "AAA"}]}, {"type": "model_output", "content": [{"type": "audio", "data": "BBB"}]}]})
        self.assertEqual(v.gemini_audio(body), "BBB")
        self.assertIsNone(v.gemini_audio("not json"))


if __name__ == "__main__":
    unittest.main()
