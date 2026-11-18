import io
import json
import os
import tempfile
import unittest

from faze_pc import modelsheet
from faze_pc.config import Config


class Resp(io.BytesIO):
    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False


class SheetTest(unittest.TestCase):
    def cfg(self, **kw):
        c = Config(os.path.join(tempfile.mkdtemp(), "c.json"))
        c.data.update(kw)
        return c

    def test_local_models_are_listed_sorted_with_sizes(self):
        body = json.dumps({"models": [{"name": "qwen3.5:4b", "size": 2_600_000_000}, {"name": "lfm2.5:1.2b", "size": 800_000_000}]}).encode()
        ok, models = modelsheet.ollama_models(self.cfg(), opener=lambda url, timeout: Resp(body))
        self.assertTrue(ok)
        self.assertEqual([m["name"] for m in models], ["lfm2.5:1.2b", "qwen3.5:4b"])
        self.assertEqual(models[1]["gb"], 2.6)

    def test_ollama_not_running_is_a_normal_answer(self):
        def boom(url, timeout):
            raise OSError("refused")
        self.assertEqual(modelsheet.ollama_models(self.cfg(), opener=boom), (False, []))
        self.assertEqual(modelsheet.ollama_models(self.cfg(), opener=lambda u, timeout: Resp(b"junk")), (False, []))

    def test_rows_follow_the_phone_order_and_mark_the_chosen_one(self):
        c = self.cfg(provider="groq")
        c.data["keys"]["groq"] = "k"
        rows = modelsheet.rows(c, True, [{"name": "qwen3.5:4b", "gb": 2.6}])
        self.assertEqual([r["id"] for r in rows], ["auto", "ollama", "gemini", "groq", "openrouter", "nvidia", "custom"])
        self.assertEqual([r["id"] for r in rows if r["chosen"]], ["groq"])
        by = {r["id"]: r for r in rows}
        self.assertEqual(by["groq"]["dot"], "ok")
        self.assertEqual(by["gemini"]["dot"], "bad")
        self.assertIn("1 installed", by["ollama"]["sub"])
        self.assertEqual(modelsheet.rows(c, False, [])[1]["sub"], "Ollama is not running on this PC")

    def test_picking_sets_the_provider_and_the_local_model(self):
        c = self.cfg()
        modelsheet.pick(c, "ollama", "lfm2.5:1.2b")
        self.assertEqual((c.data["provider"], c.data["ollama_chat_model"], c.data["ollama_deep_model"]), ("ollama", "lfm2.5:1.2b", "lfm2.5:1.2b"))
        modelsheet.pick(c, "auto")
        self.assertEqual(c.data["provider"], "auto")
        self.assertEqual(c.data["ollama_chat_model"], "lfm2.5:1.2b")  # picking Auto leaves the local choice alone
        with self.assertRaises(ValueError):
            modelsheet.pick(c, "nope")

    def test_thinking_levels(self):
        c = self.cfg()
        modelsheet.set_effort(c, "high")
        self.assertEqual(c.data["effort"], "high")
        with self.assertRaises(ValueError):
            modelsheet.set_effort(c, "extreme")


if __name__ == "__main__":
    unittest.main()
