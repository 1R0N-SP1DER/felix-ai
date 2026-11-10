"""Patch 47: Offline in the model picker, with the .gguf models found on the phone listed under it (llama-server from Termux, run with root)."""
import os
import unittest

ROOT = os.path.join(os.path.dirname(__file__), "..", "..")
KT = os.path.join(ROOT, "android", "app", "src", "main", "java", "com", "felix", "faze")


def read(*p):
    with open(os.path.join(*p), encoding="utf-8") as f:
        return f.read()


class Offline(unittest.TestCase):
    def test_offline_replaces_the_two_not_yet_rows(self):
        s = read(KT, "ModelSheet.kt")
        self.assertIn('"auto" to "Auto", "offline" to "Offline", "gemini"', s)
        self.assertNotIn('"qwen" to "Qwen"', s)
        self.assertNotIn('"lfm" to "LFM"', s)

    def test_server_listens_only_on_this_phone_and_is_small(self):
        s = read(KT, "LocalLlama.kt")
        self.assertIn("--host 127.0.0.1", s)
        self.assertIn("-np 1", s)
        self.assertIn('const val BASE = "http://127.0.0.1:8088/v1"', s)

    def test_plain_http_is_allowed_for_loopback_only(self):
        x = read(ROOT, "android", "app", "src", "main", "res", "xml", "network_security_config.xml")
        self.assertIn('<base-config cleartextTrafficPermitted="false"', x)
        self.assertEqual(x.count("<domain "), 2)
        self.assertIn("127.0.0.1", x)
        self.assertIn("networkSecurityConfig", read(ROOT, "android", "app", "src", "main", "AndroidManifest.xml"))

    def test_offline_never_falls_back_to_online(self):
        self.assertIn('if (selected == "offline") return listOf(', read(KT, "Resilient.kt"))

    def test_models_are_scanned_and_memory_is_given_back(self):
        c = read(KT, "FazeCore.kt")
        self.assertIn("LocalLlama.scanCommand()", c)
        self.assertIn("localServer.stop()", c)
        self.assertIn('"offline" to OfflineChat(', c)

    def test_thinking_is_off_for_offline_models(self):
        self.assertIn('enable_thinking", false', read(KT, "FazeCore.kt"))


if __name__ == "__main__":
    unittest.main()
