"""Patch 48: fast routes (built-in rules, shortcuts learned from successes, Needle) so the model only says what was done."""
import os
import unittest

ROOT = os.path.join(os.path.dirname(__file__), "..", "..")
KT = os.path.join(ROOT, "android", "app", "src", "main", "java", "com", "felix", "faze")


def read(n):
    with open(os.path.join(KT, n), encoding="utf-8") as f:
        return f.read()


class FastRoutes(unittest.TestCase):
    def test_order_is_builtin_then_learned_then_needle(self):
        s = read("FastRoute.kt")
        body = s[s.index("fun route(message: String, schema: JSONArray)"):][:700]
        self.assertLess(body.index("BuiltInRoutes.route"), body.index("learnedRoute("))
        self.assertLess(body.index("learnedRoute("), body.index("needle?.ask"))

    def test_only_plain_safe_tools_are_learned(self):
        s = read("FastRoute.kt")
        for tool in ["tap", "write_clipboard", "read_clipboard", "type_text", "read_screen", "look_at_screen", "create_tool"]:
            self.assertNotIn('"%s"' % tool, s[s.index("val LEARNABLE"):][:300])
        self.assertIn("canLearn(tool)", s)
        self.assertIn('{ PermissionPolicy.tier(it) == Tier.SAFE }', read("FazeCore.kt"))

    def test_needle_is_loopback_and_validated(self):
        s = read("FastRoute.kt")
        self.assertIn("http://127.0.0.1:8765/complete", s)
        self.assertIn("MIN_CONFIDENCE = 0.5", s)
        self.assertIn("fits(name, args, schema)", s)

    def test_learned_file_keeps_words_never_replies(self):
        s = read("FastRoute.kt")
        store = s[s.index("class LearnedRoutes"):s.index("class NeedleClient")]
        self.assertNotIn("result", store.lower())
        self.assertIn("learned_routes.json", read("FazeCore.kt"))

    def test_agent_uses_the_router_and_learns_only_from_one_good_call(self):
        a = read("Agent.kt")
        self.assertIn("router?.route(userText", a)
        self.assertIn("executed.size == 1 && executed[0].third", a)
        self.assertIn("PermissionPolicy.DECLINED", a)
        self.assertIn("router = router", read("FazeCore.kt"))

    def test_settings_can_switch_it_off_and_forget(self):
        u = read("SettingsScreen.kt")
        self.assertIn("core.learned.clear()", u)
        self.assertIn("core.settings.fastRoutes", u)
        self.assertIn("core.settings.needleOn", u)


if __name__ == "__main__":
    unittest.main()
