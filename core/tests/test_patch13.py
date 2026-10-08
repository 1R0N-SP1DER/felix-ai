"""
Tools run with Termux's real environment (minus secrets), Android system commands survive the linker's
CFI crash, and Gemini is told to build a tool instead of giving up.

    PYTHONPATH=. python3 -m unittest tests.test_patch13 -v
"""
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

CORE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, CORE)

import actions  # noqa: E402
import agent  # noqa: E402
import doctor  # noqa: E402
from config_loader import load_config  # noqa: E402
from tests.test_patch10 import AgentCase, spec  # noqa: E402

CFI = ("CFI: /system/bin/linker64: runtime error: control flow integrity check failed during vtable dispatch "
       "at 0x76b8a21158 (linker_cfi.cpp:158)")


def proc(out="", code=0, err=""):
    return mock.Mock(stdout=out, stderr=err, returncode=code)


class TestToolEnvironment(AgentCase):
    FAKE = {"PATH": "/p", "LD_PRELOAD": "/termux-exec.so", "LD_LIBRARY_PATH": "/usr/lib", "ANDROID_ART_ROOT": "/apex/art",
            "BOOTCLASSPATH": "/a.jar", "GEMINI_API_KEY": "s", "FAZE_TOKEN": "t", "MY_PASSWORD": "p", "AUTHORIZATION": "a", "HOME": "/real"}

    def test_keeps_the_shell_environment_but_not_secrets(self):
        with mock.patch.dict(os.environ, self.FAKE, clear=True):
            env = agent._clean_env(Path("/work"))
        for kept in ("PATH", "LD_PRELOAD", "LD_LIBRARY_PATH", "ANDROID_ART_ROOT", "BOOTCLASSPATH"):
            self.assertIn(kept, env)
        for gone in ("GEMINI_API_KEY", "FAZE_TOKEN", "MY_PASSWORD", "AUTHORIZATION"):
            self.assertNotIn(gone, env)
        self.assertEqual((env["HOME"], env["TMPDIR"]), ("/work", "/work"))

    def test_a_running_tool_sees_android_variables_and_no_secrets(self):
        info, meta, code = agent.prepare_tool(spec(
            name="show_env", params_json="{}", level="high",
            code="import os\ndef run(args):\n    keys = [k for k in os.environ if 'KEY' in k or 'TOKEN' in k]\n"
                 "    return os.environ.get('ANDROID_ART_ROOT', 'missing') + '|' + (','.join(keys) or 'clean')\n"), True)
        self.store.add_tool(meta, code)
        agent.set_policy(high_tools=True)
        with mock.patch.dict(os.environ, {"ANDROID_ART_ROOT": "/apex/art", "GEMINI_API_KEY": "s", "FAZE_TOKEN": "t"}):
            self.assertEqual(self.store.run_tool("show_env", {}), "/apex/art|clean")


class TestLinkerCrashRetry(unittest.TestCase):
    def setUp(self):
        actions.SETTINGS["root"] = "auto"

    def test_detects_the_crash_text(self):
        self.assertTrue(actions._linker_crash(proc(err=CFI, code=1)))
        self.assertTrue(actions._linker_crash(proc(out='CANNOT LINK EXECUTABLE "app_process"')))
        self.assertFalse(actions._linker_crash(proc(err="Error: Activity not started")))

    def test_retries_once_with_a_clean_environment(self):
        with mock.patch.dict(os.environ, {"LD_PRELOAD": "/x.so", "LD_LIBRARY_PATH": "/l", "PATH": "/p"}, clear=True), \
                mock.patch("subprocess.run", side_effect=[proc(err=CFI, code=1), proc("Starting")]) as run:
            result = actions._run(["am", "start"])
        self.assertEqual(result.stdout, "Starting")
        self.assertIsNone(run.call_args_list[0].kwargs["env"])
        second = run.call_args_list[1].kwargs["env"]
        self.assertEqual(second, {"PATH": "/p"})

    def test_ordinary_failures_are_not_retried(self):
        with mock.patch("subprocess.run", return_value=proc(err="Error: not found", code=1)) as run:
            actions._run(["am", "start"])
        self.assertEqual(run.call_count, 1)

    def test_open_url_survives_the_crash_end_to_end(self):
        with mock.patch.object(actions, "_is_android", return_value=True), \
                mock.patch("subprocess.run", side_effect=[proc(err=CFI, code=1), proc("Starting: Intent")]) as run:
            self.assertEqual(actions.open_url("https://youtu.be/x"), "Opened youtu.be")
        self.assertEqual(run.call_count, 2)

    def test_root_command_clears_the_preload_inside_su(self):
        blocked = proc("Error: Activity not started", 0)
        with mock.patch.object(actions, "_is_android", return_value=True), mock.patch("shutil.which", return_value="/su"), \
                mock.patch("subprocess.run", side_effect=[blocked, proc("Starting")]) as run:
            actions.open_url("https://a.com")
        self.assertTrue(run.call_args_list[1][0][0][2].startswith("unset LD_PRELOAD LD_LIBRARY_PATH; am start"))


class TestAgentInstructions(unittest.TestCase):
    def test_tells_gemini_to_build_a_tool_instead_of_giving_up(self):
        cfg = load_config()
        note = agent.agent_note(cfg)
        for phrase in ("never say you can't", "create_tool", "same name", "quoting the exact error", "Termux", "am start", "CFI", "LD_PRELOAD"):
            self.assertIn(phrase, note)
        self.assertIn("NOT allowed", note)
        cfg["agent"]["high_tools"] = True
        self.assertIn("Higher-level tools are allowed", agent.agent_note(cfg))
        cfg["agent"]["mode"] = "off"
        self.assertEqual(agent.agent_note(cfg), "")


class TestDoctorSystemCheck(unittest.TestCase):
    def check(self, android, result=None):
        with mock.patch.dict(os.environ, {"GEMINI_API_KEY": ""}), mock.patch.object(actions, "_is_android", return_value=android), \
                mock.patch.object(actions, "_run", return_value=result), mock.patch("web_search.ddg_search", return_value=[{"title": "t", "url": "u", "snippet": ""}]), \
                mock.patch.object(actions, "youtube_find", return_value=None):
            return {n: (s, d) for n, s, d in doctor.run_checks(load_config())}["Android system commands"]

    def test_outcomes(self):
        self.assertEqual(self.check(False)[0], "skip")
        self.assertEqual(self.check(True, proc("Activity manager (activity) commands:\n usage: am", 0))[0], "ok")
        status, detail = self.check(True, proc(err=CFI, code=1))
        self.assertEqual(status, "fail")
        self.assertIn("CFI", detail)
        self.assertEqual(self.check(True, proc(err="weird", code=2))[0], "warn")


if __name__ == "__main__":
    unittest.main()
