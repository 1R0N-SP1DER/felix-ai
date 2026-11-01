import os
import re
import unittest

WF = os.path.join(os.path.dirname(__file__), "..", "..", ".github", "workflows", "android.yml")


def _text():
    with open(WF) as f:
        return f.read()


def _steps(text):
    return [l.strip() for l in text.splitlines() if l.strip() and not l.strip().startswith("#")]


class TestAndroidWorkflow(unittest.TestCase):
    def test_setup_android_action_is_gone(self):
        self.assertNotIn("android-actions/setup-android", "\n".join(_steps(_text())))

    def test_licenses_are_accepted_before_gradle_runs(self):
        t = _text()
        lic = t.index("sdkmanager")
        self.assertIn("--licenses", t)
        self.assertLess(lic, t.index("setup-gradle"))
        self.assertLess(lic, t.index("gradle testDebugUnitTest"))

    def test_license_step_tolerates_sigpipe_from_yes(self):
        line = [l for l in _text().splitlines() if "sdkmanager" in l and not l.strip().startswith("#")][0]
        self.assertTrue(line.rstrip().endswith("|| true"))
        self.assertRegex(line, r"^\s*run: yes \|")

    def test_build_steps_and_artifacts_are_kept(self):
        t = _text()
        for needle in ("gradle testDebugUnitTest", "gradle assembleDebug",
                       "faze-debug-apk", "gradle-version: \"8.11.1\"", "java-version: \"17\""):
            self.assertIn(needle, t)

    def test_no_secrets_in_workflow(self):
        self.assertIsNone(re.search(r"(AIza[0-9A-Za-z_\-]{20,}|gsk_[0-9A-Za-z]{20,}|sk-or-[0-9A-Za-z\-]{20,})", _text()))


if __name__ == "__main__":
    unittest.main()
