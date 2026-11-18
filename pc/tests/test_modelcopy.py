import base64
import os
import tempfile
import unittest

from faze_pc import modelcopy
from faze_pc.config import Config


class Phone:
    """Answers like the phone: a list, a staged copy it serves in pieces, and an unstage."""

    def __init__(self, files, fail_stage=False, cut_at=None):
        self.files, self.fail_stage, self.cut_at = files, fail_stage, cut_at
        self.sent = []

    def request(self, pid, msg, timeout=15):
        self.sent.append(msg["t"])
        t = msg["t"]
        if t == "models_list":
            return {"t": "models_list_ok", "models": [{"path": "/sdcard/Download/" + n, "name": n, "size": len(b)} for n, b in self.files.items()]}
        if t == "models_stage":
            if self.fail_stage:
                return {"t": "error", "message": "not enough space"}
            n = msg["path"].rsplit("/", 1)[1]
            return {"t": "models_stage_ok", "root": "Models", "path": n, "size": len(self.files[n])}
        if t == "fs_read":
            b = self.files[msg["path"]]
            o, ln = msg["offset"], msg["len"]
            if self.cut_at is not None and o >= self.cut_at:
                return {"t": "error", "message": "link dropped"}
            piece = b[o:o + ln]
            return {"t": "fs_read_ok", "data": base64.b64encode(piece).decode(), "size": len(b), "eof": o + len(piece) >= len(b)}
        return {"t": "models_unstage_ok"}


class CopyTest(unittest.TestCase):
    def make(self, files, have=(), tools=("ollama",), rc=0, **kw):
        self.dir = tempfile.mkdtemp()
        self.cfg = Config(os.path.join(self.dir, "c.json"))
        self.phone = Phone(files, **kw)
        self.says, self.cmds = [], []

        def runner(argv, timeout=0):
            self.cmds.append(argv)
            self.mf = open(argv[-1]).read() if argv[:2] == ["ollama", "create"] else ""
            return rc, "boom" if rc else "ok"

        mc = modelcopy.ModelCopy(self.cfg, self.phone.request, "p1", lambda *a: self.says.append(a), lambda: set(have), dest=os.path.join(self.dir, "m"),
                                 which=lambda n: "/bin/" + n if n in tools else None, runner=runner)
        return mc

    def test_names_follow_the_file_names(self):
        self.assertEqual(modelcopy.ollama_name("Qwen3.5-4B-Q4_K_M.gguf"), "qwen3.5:4b-q4_k_m")
        self.assertEqual(modelcopy.ollama_name("LFM2.5-1.2B-Q8_0.gguf"), "lfm2.5:1.2b-q8_0")
        self.assertEqual(modelcopy.ollama_name("tiny.gguf"), "tiny:latest")
        self.assertEqual(modelcopy.ollama_name("we ird (1).gguf"), "we:ird-1")

    def test_copies_each_model_creates_it_in_ollama_and_cleans_up(self):
        big = bytes(range(256)) * 5000  # more than one piece
        mc = self.make({"Qwen3.5-4B-Q4_K_M.gguf": big, "LFM2.5-1.2B-Q8_0.gguf": b"abc" * 100})
        mc.go()
        creates = [c for c in self.cmds if c[:2] == ["ollama", "create"]]
        self.assertEqual(sorted(c[2] for c in creates), ["lfm2.5:1.2b-q8_0", "qwen3.5:4b-q4_k_m"])
        self.assertIn("<|im_start|>", self.mf)
        self.assertEqual(self.phone.sent.count("models_unstage"), 2)
        self.assertEqual(os.listdir(os.path.join(self.dir, "m")), [])  # nothing left behind
        self.assertEqual(self.cfg.data["ollama_chat_model"], "lfm2.5:1.2b-q8_0")
        self.assertEqual(self.cfg.data["ollama_deep_model"], "qwen3.5:4b-q4_k_m")
        self.assertTrue(self.says[-1][2] and self.says[-1][3])

    def test_the_downloaded_bytes_are_exact(self):
        big = os.urandom(1_300_000)
        seen = {}

        def runner(argv, timeout=0):
            if argv[:2] == ["ollama", "create"]:
                path = open(argv[-1]).read().split("\n")[0][5:]
                seen["data"] = open(path, "rb").read()
            return 0, ""

        mc = self.make({"A-1b.gguf": big})
        mc.run = runner
        mc.go()
        self.assertEqual(seen["data"], big)

    def test_models_already_in_ollama_are_skipped(self):
        mc = self.make({"Qwen3.5-4B.gguf": b"x" * 10}, have={"qwen3.5:4b"})
        mc.go()
        self.assertEqual([c for c in self.cmds if c[:2] == ["ollama", "create"]], [])
        self.assertIn("already on this PC", self.says[-1][0])
        self.assertTrue(self.says[-1][3])

    def test_no_ollama_says_how_to_get_it(self):
        mc = self.make({"a.gguf": b"x"}, tools=())
        mc.go()
        self.assertIn("install.sh", self.says[-1][0])
        self.assertFalse(self.says[-1][3])

    def test_a_refusal_from_the_phone_is_reported_and_unstaged_is_not_needed(self):
        mc = self.make({"a.gguf": b"x"}, fail_stage=True)
        mc.go()
        self.assertIn("not enough space", self.says[-1][0])
        self.assertNotIn("models_unstage", self.phone.sent)

    def test_a_dropped_link_stops_with_a_clear_message_and_still_unstages(self):
        mc = self.make({"a.gguf": os.urandom(1_300_000)}, cut_at=modelcopy.CHUNK)
        mc.go()
        self.assertIn("stopped at", self.says[-1][0])
        self.assertFalse(self.says[-1][3])
        self.assertIn("models_unstage", self.phone.sent)
        self.assertEqual([c for c in self.cmds if c[:2] == ["ollama", "create"]], [])

    def test_an_ollama_failure_is_reported(self):
        mc = self.make({"a-1b.gguf": b"x" * 50}, rc=1)
        mc.go()
        self.assertIn("could not set up", self.says[-1][0])
        self.assertFalse(self.says[-1][3])


if __name__ == "__main__":
    unittest.main()
