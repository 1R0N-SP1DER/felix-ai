"""
Patch 19: the service finds GEMINI_API_KEY itself (~/.felix/env, then export lines in the shell startup files),
so a start from Tasker, a boot script or a root shell is not keyless.

    PYTHONPATH=. python3 -m unittest tests.test_patch19 -v
"""

import os
import re
import sys
import tempfile
import unittest

CORE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, CORE)

import env_loader  # noqa: E402


class TestEnvLoader(unittest.TestCase):
    def setUp(self):
        self.home = tempfile.mkdtemp(prefix="faze-env19-")
        os.makedirs(os.path.join(self.home, ".felix"))
        self.env = {}

    def write(self, rel, text):
        with open(os.path.join(self.home, rel), "w") as f:
            f.write(text)

    def load(self):
        return env_loader.load_env(home=self.home, environ=self.env)

    def test_felix_env_plain_and_export_and_quotes(self):
        for text, expected in (("GEMINI_API_KEY=AQ.plain\n", "AQ.plain"), ("export GEMINI_API_KEY=AQ.exp\n", "AQ.exp"),
                               ('GEMINI_API_KEY="AQ.dq"\n', "AQ.dq"), ("GEMINI_API_KEY='AQ.sq'\r\n", "AQ.sq"),
                               ("# a comment\n\nOTHER=1\nGEMINI_API_KEY=AQ.after # trailing\n", "AQ.after")):
            self.env.clear()
            self.write(".felix/env", text)
            loaded = self.load()
            self.assertEqual(self.env["GEMINI_API_KEY"], expected, text)
            self.assertEqual(loaded, {"GEMINI_API_KEY": os.path.join(self.home, ".felix", "env")})

    def test_the_environment_always_wins(self):
        self.env["GEMINI_API_KEY"] = "AQ.already"
        self.write(".felix/env", "GEMINI_API_KEY=AQ.file\n")
        self.assertEqual(self.load(), {})
        self.assertEqual(self.env["GEMINI_API_KEY"], "AQ.already")

    def test_an_empty_value_in_the_environment_is_filled_in(self):
        self.env["GEMINI_API_KEY"] = ""
        self.write(".felix/env", "GEMINI_API_KEY=AQ.file\n")
        self.load()
        self.assertEqual(self.env["GEMINI_API_KEY"], "AQ.file")

    def test_shell_files_only_count_with_export(self):
        self.write(".bashrc", "GEMINI_API_KEY=AQ.notexported\nalias x=y\n")
        self.assertEqual(self.load(), {})
        self.write(".bashrc", 'alias x=y\nexport GEMINI_API_KEY="AQ.shell"\nexport PATH=$PATH:/x\n')
        loaded = self.load()
        self.assertEqual(self.env["GEMINI_API_KEY"], "AQ.shell")
        self.assertEqual(loaded["GEMINI_API_KEY"], os.path.join(self.home, ".bashrc"))

    def test_felix_env_beats_the_shell_files_and_the_last_line_in_a_file_wins(self):
        self.write(".bashrc", "export GEMINI_API_KEY=AQ.bashrc\n")
        self.write(".profile", "export GEMINI_API_KEY=AQ.profile\n")
        self.load()
        self.assertEqual(self.env["GEMINI_API_KEY"], "AQ.bashrc")  # .bashrc is read before .profile
        self.env.clear()
        self.write(".felix/env", "GEMINI_API_KEY=AQ.old\nGEMINI_API_KEY=AQ.new\n")
        self.load()
        self.assertEqual(self.env["GEMINI_API_KEY"], "AQ.new")

    def test_references_to_other_variables_are_skipped(self):
        self.write(".bashrc", "export GEMINI_API_KEY=$(cat ~/key)\nexport GEMINI_API_KEY=${OTHER}\n")
        self.assertEqual(self.load(), {})
        self.assertNotIn("GEMINI_API_KEY", self.env)

    def test_nothing_there_is_fine(self):
        self.assertEqual(self.load(), {})
        self.assertEqual(env_loader.load_env(home=os.path.join(self.home, "missing"), environ={}), {})

    def test_other_names_are_left_alone(self):
        self.write(".felix/env", "SOMETHING_ELSE=1\nGEMINI_API_KEY=AQ.k\n")
        self.load()
        self.assertEqual(self.env, {"GEMINI_API_KEY": "AQ.k"})

    def test_uses_home_from_the_environment_by_default(self):
        self.write(".felix/env", "GEMINI_API_KEY=AQ.home\n")
        old = os.environ.get("HOME")
        os.environ["HOME"] = self.home
        try:
            self.assertEqual(env_loader.load_env(environ=self.env), {"GEMINI_API_KEY": os.path.join(self.home, ".felix", "env")})
        finally:
            os.environ["HOME"] = old


class TestEntryPointsLoadIt(unittest.TestCase):
    def test_every_entry_point_calls_load_env_first(self):
        for name in ("orchestrator.py", "tts.py", "gemini_client.py"):
            with open(os.path.join(CORE, name)) as f:
                src = f.read()
            block = src[src.index('if __name__ == "__main__":'):]
            self.assertRegex(block, r'if __name__ == "__main__":\n    import env_loader\n    env_loader\.load_env\(\)', name)

    def test_doctor_says_where_to_put_the_key(self):
        import doctor
        from unittest import mock
        from config_loader import load_config
        with mock.patch.dict(os.environ, {}, clear=False):
            os.environ.pop("GEMINI_API_KEY", None)
            rows = doctor.run_checks(load_config())
        text = " ".join(str(r) for r in rows)
        self.assertIn("~/.felix/env", text)


if __name__ == "__main__":
    unittest.main()
