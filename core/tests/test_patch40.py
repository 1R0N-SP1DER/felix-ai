"""Patch 40: tools the model writes run in a locked sandbox, and only after you read the code and approve it."""
import os
import re
import unittest

ROOT = os.path.join(os.path.dirname(__file__), "..", "..")
APP = os.path.join(ROOT, "android", "app")
KT = os.path.join(APP, "src", "main", "java", "com", "felix", "faze")


def read(*parts):
    with open(os.path.join(*parts), encoding="utf-8") as f:
        return f.read()


class Sandbox(unittest.TestCase):
    def test_no_java_access_for_tool_code(self):
        src = read(KT, "Sandbox.kt")
        self.assertIn("initSafeStandardObjects()", src)
        self.assertNotIn("initStandardObjects(", src)
        self.assertIn("setClassShutter { false }", src)
        self.assertIn("optimizationLevel = -1", src)
        self.assertIn("observeInstructionCount", src)

    def test_the_only_ways_out_are_the_three_host_functions_plus_declared_powers(self):
        sandbox = read(KT, "Sandbox.kt")
        puts = re.findall(r'putProperty\(scope, "(\w+)"', sandbox)
        self.assertEqual(["http_get", "now", "log", "__args"], puts)
        # patch 42: every other function comes from the Powers table and is defined only when its power was declared
        self.assertEqual(1, len(re.findall(r'scope\.put\(', sandbox)))
        self.assertIn("if (granted) scope.put(fname", sandbox)
        names = re.findall(r'"(\w+)" to "(\w*)"', sandbox[sandbox.index("val functions"):sandbox.index("/** The declared powers")])
        self.assertEqual(["http_post", "shell", "root", "read_file", "write_file", "list_dir", "screenshot", "start_intent", "sleep"], [n for n, _ in names])

    def test_creating_a_tool_is_gated_and_shows_the_code(self):
        self.assertIn('"create_tool" to Tier.CONFIRM', read(KT, "Permissions.kt"))
        self.assertIn('"create_tool" ->', read(KT, "ApprovalText.kt"))
        self.assertIn("THE CODE", read(KT, "ApprovalSheet.kt"))
        self.assertIn("GatedTools(Tools(", read(KT, "FazeCore.kt"))

    def test_rhino_is_pinned(self):
        self.assertRegex(read(APP, "build.gradle.kts"), r'org\.mozilla:rhino:\d+\.\d+\.\d+"')

    def test_settings_lists_and_deletes_saved_tools(self):
        s = read(KT, "SettingsScreen.kt")
        self.assertIn("SavedToolsCard(core)", s)
        self.assertIn("Clear all tools", s)


if __name__ == "__main__":
    unittest.main()
