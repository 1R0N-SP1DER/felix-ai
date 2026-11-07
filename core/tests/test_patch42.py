"""Patch 42: tools can declare real powers; the person approves the code and the powers once; undeclared powers do not exist."""
import os
import re
import unittest

ROOT = os.path.join(os.path.dirname(__file__), "..", "..")
APP = os.path.join(ROOT, "android", "app")
KT = os.path.join(APP, "src", "main", "java", "com", "felix", "faze")
TESTS = os.path.join(APP, "src", "test", "java", "com", "felix", "faze")


def read(*parts):
    with open(os.path.join(*parts), encoding="utf-8") as f:
        return f.read()


class Powers(unittest.TestCase):
    def test_the_six_powers_and_their_functions(self):
        s = read(KT, "Sandbox.kt")
        self.assertIn('val all = listOf("network", "shell", "root", "files", "screenshot", "intent")', s)
        for fn, power in [("http_post", "network"), ("shell", "shell"), ("root", "root"), ("read_file", "files"),
                          ("write_file", "files"), ("list_dir", "files"), ("screenshot", "screenshot"), ("start_intent", "intent"), ("sleep", "")]:
            self.assertIn('"%s" to "%s"' % (fn, power), s)

    def test_powered_tools_get_a_longer_limit_and_network_lifts_the_site_list_only(self):
        s = read(KT, "Sandbox.kt")
        self.assertIn("poweredMs: Long = 30_000", s)
        self.assertIn('anyHost = "network" in powers', s)
        self.assertIn("if (!anyHost && allowed.none", s)
        self.assertIn('host == "localhost"', s)  # local addresses stay blocked even with the network power

    def test_host_work_is_done_by_one_android_class(self):
        p = read(KT, "Powers.kt")
        for fn in ["shell", "root", "http", "readFile", "writeFile", "listDir", "screenshot", "intent"]:
            self.assertRegex(p, r"override fun %s\(" % fn)
        self.assertIn("screencap -p", p)
        self.assertIn("power = AndroidPowers(app)", read(KT, "FazeCore.kt"))

    def test_the_declaration_and_the_code_are_shown_for_approval(self):
        perm = read(KT, "Permissions.kt")
        self.assertIn('Powers: ${powersOf(args)', perm)
        self.assertIn("(powers: $it)", perm)
        text = read(KT, "ApprovalText.kt")
        self.assertIn('summary.contains("(powers: ")', text)
        self.assertIn('"create_tool" to Tier.CONFIRM', perm)
        self.assertIn('Param("powers"', read(KT, "Tools.kt"))

    def test_saved_tools_run_without_per_call_prompts(self):
        self.assertIn("if (userTools.has(name)) Tier.SAFE else null", read(KT, "FazeCore.kt"))

    def test_the_global_switch_is_real_and_on_by_default(self):
        self.assertRegex(read(KT, "Settings.kt"), r'getBoolean\("allow_powered", true\)')
        s = read(KT, "SettingsScreen.kt")
        self.assertIn("core.settings.allowPowered = it", s)
        self.assertNotRegex(s, r'"Allow higher-level tools"[^\n]*soon = true')
        self.assertIn("{ settings.allowPowered }", read(KT, "FazeCore.kt"))
        u = read(KT, "UserTools.kt")
        for needle in ["return POWERS_OFF", "ToolResult(false, POWERS_OFF)"]:
            self.assertIn(needle, u)

    def test_the_old_tools_file_still_loads(self):
        u = read(KT, "UserTools.kt")
        self.assertIn('o.optJSONArray("powers") ?: JSONArray()', u)

    def test_kotlin_tests_exist_for_the_new_behaviour(self):
        t = read(TESTS, "PowersTest.kt")
        for name in ["undeclaredPowersDoNotExist", "networkLiftsTheSiteList", "poweredToolsAreRefusedWhenTheSwitchIsOff"]:
            self.assertIn("fun %s" % name, t)

    def test_all_files_access_is_declared_and_has_a_settings_row(self):
        self.assertIn("android.permission.MANAGE_EXTERNAL_STORAGE", read(APP, "src", "main", "AndroidManifest.xml"))
        s = read(KT, "SettingsScreen.kt")
        self.assertIn("ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION", s)
        self.assertIn("Access to all files", s)

    def test_no_keys_in_the_new_files(self):
        for f in ["Powers.kt", "Sandbox.kt", "UserTools.kt"]:
            self.assertNotRegex(read(KT, f), r"gsk_[A-Za-z0-9]{10,}|AIza[A-Za-z0-9_\-]{20,}")


if __name__ == "__main__":
    unittest.main()
