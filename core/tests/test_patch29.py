"""Patch 29: the Android app's approval policy matches the Termux one, and the manifest/gradle changes for always-on listening."""
import os
import re
import unittest

ROOT = os.path.join(os.path.dirname(__file__), "..", "..")
ANDROID = os.path.join(ROOT, "android", "app")


def read(*parts):
    with open(os.path.join(*parts), encoding="utf-8") as f:
        return f.read()


class ApprovalPolicyParity(unittest.TestCase):
    def test_kotlin_tiers_match_python_for_every_android_tool(self):
        from permission_policy import PERMISSION_REGISTRY
        kt = read(ANDROID, "src", "main", "java", "com", "felix", "faze", "Permissions.kt")
        kotlin = dict((m.group(1), m.group(2)) for m in re.finditer(r'"(\w+)" to Tier\.(\w+)', kt))
        self.assertGreaterEqual(len(kotlin), 12)
        android_only = {"remember": "confirm", "recall": "safe", "create_tool": "confirm"}  # tools that exist only in the app (memory notes)
        for name, tier in kotlin.items():
            if name in android_only:
                self.assertEqual(android_only[name], tier.lower(), name)
                continue
            self.assertIn(name, PERMISSION_REGISTRY, name)
            self.assertEqual(PERMISSION_REGISTRY[name], tier.lower(), name)

    def test_every_tool_the_app_offers_has_a_tier(self):
        tools = read(ANDROID, "src", "main", "java", "com", "felix", "faze", "Tools.kt")
        perms = read(ANDROID, "src", "main", "java", "com", "felix", "faze", "Permissions.kt")
        offered = set(re.findall(r'tool\("(\w+)"', tools))
        self.assertGreaterEqual(len(offered), 12)
        for name in offered:
            self.assertIn('"%s" to Tier.' % name, perms, name)

    def test_the_gate_is_what_the_app_actually_uses(self):
        core = read(ANDROID, "src", "main", "java", "com", "felix", "faze", "FazeCore.kt")
        self.assertIn("GatedTools(Tools(", core)
        self.assertNotIn("Agent(client, Tools(", core)  # no path that skips the gate


class ManifestAndGradle(unittest.TestCase):
    def setUp(self):
        self.manifest = read(ANDROID, "src", "main", "AndroidManifest.xml")
        self.gradle = read(ANDROID, "build.gradle.kts")

    def test_microphone_foreground_service_is_declared_properly(self):
        self.assertIn("android.permission.FOREGROUND_SERVICE_MICROPHONE", self.manifest)
        self.assertIn("android.permission.FOREGROUND_SERVICE\"", self.manifest)
        self.assertRegex(self.manifest, r'<service[^>]*android:name="\.ListenService"[^>]*foregroundServiceType="microphone"')
        self.assertRegex(self.manifest, r'<service[^>]*android:exported="false"')

    def test_no_boot_receiver(self):
        # Android 15 refuses a microphone service started from boot; it starts from the visible app instead
        self.assertNotIn("BOOT_COMPLETED", self.manifest)

    def test_application_class_and_no_cleartext(self):
        self.assertIn('android:name=".FazeApp"', self.manifest)
        self.assertIn('android:usesCleartextTraffic="false"', self.manifest)

    def test_wake_library_is_pinned_and_native_parts_limited(self):
        self.assertRegex(self.gradle, r'com\.alphacephei:vosk-android:\d+\.\d+\.\d+"')
        self.assertRegex(self.gradle, r'net\.java\.dev\.jna:jna:\d+\.\d+\.\d+@aar')
        self.assertIn('abiFilters += listOf("arm64-v8a")', self.gradle)
        self.assertNotRegex(self.gradle, r'(latest|\+")')

    def test_model_comes_over_https_only(self):
        src = read(ANDROID, "src", "main", "java", "com", "felix", "faze", "ModelStore.kt")
        self.assertRegex(src, r'MODEL_URL = "https://')

    def test_no_key_like_strings_in_the_android_sources(self):
        pat = re.compile(r"(gsk_[A-Za-z0-9]{20,}|AIza[A-Za-z0-9_-]{30,}|tvly-[A-Za-z0-9]{20,}|sk-or-[A-Za-z0-9-]{20,})")
        for base, _dirs, files in os.walk(os.path.join(ANDROID, "src")):
            for f in files:
                if f.endswith((".kt", ".xml")):
                    self.assertIsNone(pat.search(read(base, f)), f)


if __name__ == "__main__":
    unittest.main()
