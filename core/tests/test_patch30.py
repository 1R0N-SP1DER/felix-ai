"""Patch 30: overlay, volume-key service, wake engines, quiet notification, fixed root commands."""
import os
import re
import subprocess
import unittest

ROOT = os.path.join(os.path.dirname(__file__), "..", "..")
APP = os.path.join(ROOT, "android", "app")
SRC = os.path.join(APP, "src", "main")
KT = os.path.join(SRC, "java", "com", "felix", "faze")


def read(*parts):
    with open(os.path.join(*parts), encoding="utf-8") as f:
        return f.read()


class VolumeKeyService(unittest.TestCase):
    def setUp(self):
        self.manifest = read(SRC, "AndroidManifest.xml")
        self.config = read(SRC, "res", "xml", "accessibility_config.xml")

    def test_service_is_declared_with_the_binding_permission(self):
        self.assertRegex(self.manifest, r'<service[^>]*android:name="\.FazeAccessibilityService"')
        self.assertIn('android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"', self.manifest)
        self.assertIn("android.accessibilityservice.AccessibilityService", self.manifest)
        self.assertIn("@xml/accessibility_config", self.manifest)

    def test_it_asks_for_key_events_and_nothing_more(self):
        self.assertIn('android:canRequestFilterKeyEvents="true"', self.config)
        self.assertIn("flagRequestFilterKeyEvents", self.config)
        # no screen reading: it must not ask to retrieve window content or perform gestures
        self.assertNotIn("canRetrieveWindowContent", self.config)
        self.assertNotIn("canPerformGestures", self.config)

    def test_overlay_permission_and_window_type(self):
        self.assertIn("android.permission.SYSTEM_ALERT_WINDOW", self.manifest)
        overlay = read(KT, "Overlay.kt")
        self.assertIn("TYPE_APPLICATION_OVERLAY", overlay)
        self.assertIn("FLAG_BLUR_BEHIND", overlay)  # darkened, blurred background (patch 31)
        self.assertIn("blurBehindRadius", overlay)
        self.assertIn("0x99000000", overlay)  # the dark scrim works even where blur is unsupported
        self.assertIn("FLAG_NOT_FOCUSABLE", overlay)

    def test_only_volume_keys_are_looked_at(self):
        svc = read(KT, "FazeAccessibilityService.kt")
        self.assertIn("KEYCODE_VOLUME_UP", svc)
        self.assertIn("KEYCODE_VOLUME_DOWN", svc)
        self.assertNotIn("rootInActiveWindow", svc)
        self.assertNotIn("getRootInActiveWindow", svc)

    def test_the_chord_key_codes_match_android(self):
        wake = read(KT, "Wake.kt")
        self.assertIn("VOLUME_UP = 24", wake)
        self.assertIn("VOLUME_DOWN = 25", wake)


class QuietNotification(unittest.TestCase):
    def test_lowest_importance_and_own_icon(self):
        svc = read(KT, "ListenService.kt")
        self.assertIn("IMPORTANCE_MIN", svc)
        self.assertIn("R.drawable.ic_stat_faze", svc)
        self.assertNotIn("ic_btn_speak_now", svc)  # the blue system microphone
        self.assertIn('deleteNotificationChannel("listen")', svc)
        self.assertTrue(os.path.isfile(os.path.join(SRC, "res", "drawable", "ic_stat_faze.xml")))


class FixedRootCommands(unittest.TestCase):
    def test_exactly_three_fixed_device_config_commands(self):
        src = read(KT, "RootCommands.kt")
        cmds = re.findall(r'const val \w+ = "(cmd [^"]+)"', src)
        self.assertEqual(3, len(cmds))
        for c in cmds:
            self.assertTrue(c.startswith("cmd device_config "), c)
            self.assertIn(" privacy camera_mic_icons_enabled", c)
            self.assertNotRegex(c, r"[;&|`$<>]")

    def test_su_is_started_in_one_place_and_checks_the_list(self):
        users = []
        for f in os.listdir(KT):
            if f.endswith(".kt") and re.search(r'ProcessBuilder\("su"|exec\(arrayOf\("su"', read(KT, f)):
                users.append(f)
        self.assertEqual(["WakeEngines.kt"], users)
        engines = read(KT, "WakeEngines.kt")
        self.assertLess(engines.index("RootCommands.isAllowed(command)"), engines.index('ProcessBuilder("su"'))

    def test_the_dot_option_is_off_by_default(self):
        settings = read(KT, "Settings.kt")
        self.assertRegex(settings, r'getBoolean\("hide_mic_dot", false\)')


class WakeEnginesAndGradle(unittest.TestCase):
    def setUp(self):
        self.gradle = read(APP, "build.gradle.kts")

    def test_picovoice_is_gone(self):
        # Picovoice was dropped in patch 31: no dependency, no code, no settings
        self.assertNotIn("picovoice", self.gradle.lower())
        for base, _d, files in os.walk(os.path.join(APP, "src")):
            for f in files:
                if f.endswith((".kt", ".xml")):
                    self.assertNotIn("porcupine", read(base, f).lower(), f)

    def test_accesskey_is_not_in_the_sources(self):
        # a Picovoice AccessKey is a long base64 string; none may be committed
        for base, _d, files in os.walk(os.path.join(APP, "src")):
            for f in files:
                if f.endswith((".kt", ".xml")):
                    text = read(base, f)
                    self.assertIsNone(re.search(r'AccessKey\s*=\s*"[A-Za-z0-9+/=]{30,}"', text), f)

    def test_vosk_path_uses_the_strict_check(self):
        engines = read(KT, "WakeEngines.kt")
        self.assertIn("WakeVerifier.accept", engines)
        self.assertIn("strictGrammarJson", engines)
        self.assertIn("setWords(true)", engines)


class EnableScript(unittest.TestCase):
    PATH = os.path.join(ROOT, "scripts", "faze_enable_android.sh")

    def test_clears_termux_preload_and_uses_system_binaries(self):
        text = read(self.PATH)
        self.assertIn("unset LD_PRELOAD", text)
        self.assertIn("/system/bin/appops set", text)
        self.assertIn("SYSTEM_ALERT_WINDOW", text)
        self.assertIn("enabled_accessibility_services", text)
        self.assertNotIn("rm -", text)

    def test_script_is_valid_shell(self):
        r = subprocess.run(["sh", "-n", self.PATH], capture_output=True, text=True)
        self.assertEqual(0, r.returncode, r.stderr)

    def test_it_does_not_duplicate_the_service_entry(self):
        self.assertIn('*"$SVC"*) echo "accessibility service already enabled"', read(self.PATH))


if __name__ == "__main__":
    unittest.main()
