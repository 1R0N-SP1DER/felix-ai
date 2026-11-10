import importlib.util
import os
import unittest

ROOT = os.path.join(os.path.dirname(__file__), "..", "..")
spec = importlib.util.spec_from_file_location("bench", os.path.join(ROOT, "scripts", "local_llm_bench.py"))
bench = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bench)


class Bench(unittest.TestCase):
    def test_status_parsing(self):
        t = "Name:\tllama-server\nVmHWM:\t 921600 kB\nVmRSS:\t 819200 kB\nThreads:\t5\n"
        s = bench.parse_status(t)
        self.assertEqual(s["VmHWM"], 900.0)
        self.assertEqual(s["VmRSS"], 800.0)
        self.assertEqual(bench.parse_status("nothing here"), {})

    def test_long_prompt_is_long_and_ends_with_a_question(self):
        m = bench.long_messages(1100)
        self.assertEqual([x["role"] for x in m], ["system", "user"])
        self.assertGreaterEqual(len(m[0]["content"].split()), 1100)
        self.assertLess(len(m[0]["content"].split()), 1300)

    def test_summary_names_the_failure_and_the_numbers(self):
        r = {"model": "x.gguf", "size_mb": 700.0, "free_before_mb": 1500.0, "chat": [], "error": "the server was killed during: long prompt", "log": "a\nb"}
        s = bench.summarize(r)
        self.assertIn("PROBLEM: the server was killed during: long prompt", s)
        ok = {"model": "x.gguf", "size_mb": 700.0, "free_before_mb": 1500.0, "error": None, "load_s": 4.2, "idle_mb": 800.0, "peak_mb": 950.0,
              "short": {"seconds": 2.0, "write_tps": 9.5}, "long": {"prompt_n": 1500, "read_tps": 40.0, "seconds": 38.0},
              "chat": [("hi", {"write_tps": 8.0, "text": "hello"})]}
        s = bench.summarize(ok)
        for part in ["loaded in 4.2 s", "peak 950 MB", "read at 40.0 tokens/s", "writes 9.5 tokens/s", "model (8.0 tokens/s): hello"]:
            self.assertIn(part, s)

    def test_nothing_leaves_the_phone(self):
        src = open(os.path.join(ROOT, "scripts", "local_llm_bench.py")).read()
        self.assertEqual(src.count("http://"), src.count("http://127.0.0.1"))
        self.assertNotIn("https://", src)


if __name__ == "__main__":
    unittest.main()
