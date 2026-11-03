"""Patch 31: the Android screens are ports of the web UI, so the web UI is the reference. These tests keep the two in sync."""
import json
import os
import re
import shutil
import subprocess
import unittest

ROOT = os.path.join(os.path.dirname(__file__), "..", "..")
WEB = os.path.join(ROOT, "core", "web")
KT = os.path.join(ROOT, "android", "app", "src", "main", "java", "com", "felix", "faze")


def read(*parts):
    with open(os.path.join(*parts), encoding="utf-8") as f:
        return f.read()


def node_json(expr):
    node = shutil.which("node")
    if not node:
        raise unittest.SkipTest("node is not installed")
    out = subprocess.run([node, "-e", expr], capture_output=True, text=True, timeout=30)
    if out.returncode != 0:
        raise AssertionError(out.stderr)
    return json.loads(out.stdout)


def kotlin_strings(text):
    return re.findall(r'"((?:[^"\\]|\\.)*)"', text)


class SameDataAsTheWebUI(unittest.TestCase):
    def test_greetings_are_the_same(self):
        src = read(WEB, "app.js")
        g = node_json("const s=require('fs').readFileSync(%r,'utf8');const g=eval('('+s.match(/const GREETINGS = (\\{[\\s\\S]*?\\n  \\});/)[1]+')');console.log(JSON.stringify(g))" % os.path.join(WEB, "app.js"))
        kt = read(KT, "Greetings.kt")
        for slot, lines in g.items():
            block = re.search(r"val %s = listOf\((.*?)\n    \)" % slot, kt, re.S)
            self.assertIsNotNone(block, slot)
            self.assertEqual(lines, [s.replace('\\"', '"') for s in kotlin_strings(block.group(1))], slot)
        self.assertIn("GREETINGS", src)

    def test_accents_are_the_same(self):
        a = node_json("const s=require('fs').readFileSync(%r,'utf8');console.log(JSON.stringify(eval('('+s.match(/const ACCENTS = (\\{[^}]*\\});/)[1]+')')))" % os.path.join(WEB, "app.js"))
        kt = read(KT, "Accents.kt")
        found = re.findall(r'Accent\("(\w+)", 0xFF([0-9A-F]{6}), 0xFF([0-9A-F]{6})\)', kt)
        self.assertEqual([(n, c1.upper()[1:], c2.upper()[1:]) for n, (c1, c2) in a.items()], [(n, x.upper(), y.upper()) for n, x, y in found])

    def test_shape_cycle_and_model_forms_are_the_same(self):
        k = node_json("const K=require(%r);console.log(JSON.stringify({c:K.CYCLE,f:K.MODEL_FORM,s:Object.keys(K.SHAPE_FNS)}))" % os.path.join(WEB, "core.js"))
        kt = read(KT, "CoreShapes.kt")
        cycle = re.search(r"val CYCLE = listOf\((.*?)\)", kt, re.S).group(1)
        self.assertEqual(k["c"], re.findall(r'"(\w+)"', cycle))
        forms = dict(re.findall(r'"(\w+)" to "(\w+)"', re.search(r"val MODEL_FORM = mapOf\((.*?)\n    \)", kt, re.S).group(1)))
        self.assertEqual(k["f"], forms)
        for name in k["s"]:
            self.assertIn('"%s" to {' % name, kt, name)

    def test_design_tokens_are_the_same(self):
        css = read(WEB, "app.css")
        root = re.search(r":root\s*\{(.*?)\}", css, re.S).group(1)
        palette = read(KT, "Theme.kt")
        for var, name in [("--bg-0", "Bg"), ("--bg-1", "Bg1"), ("--bg-2", "Surface"), ("--bg-3", "Surface2"), ("--text", "Text"), ("--text-2", "Dim"),
                          ("--text-3", "Muted"), ("--accent-ink", "AccentInk"), ("--ok", "Ok"), ("--warn", "Warn"), ("--bad", "Bad")]:
            value = re.search(re.escape(var) + r":\s*#([0-9a-fA-F]{6})", root)
            self.assertIsNotNone(value, var)
            self.assertRegex(palette, r"val %s = Color\(0xFF%s\)" % (name, value.group(1).upper()))
        # the two hairline alphas: .13 -> 0x21, .26 -> 0x42 (0.13 * 255 = 33, 0.26 * 255 = 66)
        self.assertIn("--line: rgba(130, 175, 255, .13)", root)
        self.assertIn("--line-2: rgba(130, 175, 255, .26)", root)
        self.assertIn("val Line = Color(0x2182AFFF)", palette)
        self.assertIn("val Line2 = Color(0x4282AFFF)", palette)


class SameWordsAsTheWebUI(unittest.TestCase):
    def setUp(self):
        self.html = read(WEB, "index.html")

    def test_home_screen(self):
        home = read(KT, "HomeScreen.kt")
        self.assertIn("What are we building?", self.html)
        self.assertIn("What are we building?", home)
        self.assertIn("Ask anything. Build anything.", home)
        for insert, label in re.findall(r'<button type="button" data-insert="([^"]*)">([^<]+)</button>', self.html[self.html.index('id="starters"'):self.html.index('id="starters"') + 600]):
            self.assertIn('"%s" to "%s"' % (label, insert), home)

    def test_sidebar_labels(self):
        side = read(KT, "Sidebar.kt")
        for text in ["New chat", "Search chats", "Tools", "Actions", "Open app", "Play on YouTube", "Open link", "Settings", "FAZE"]:
            self.assertIn(text, self.html, text)
            self.assertIn(text, side, text)
        for group in ["Today", "Yesterday", "Previous 7 days", "Older"]:
            self.assertIn(group, read(KT, "ChatGroups.kt"))
            self.assertIn("'%s'" % group, read(WEB, "app.js"))

    def test_settings_tabs_and_section_titles(self):
        settings = read(KT, "SettingsScreen.kt")
        for tab in ["General", "Voice", "Agent", "Advanced"]:
            self.assertIn('>%s</button>' % tab, self.html)
            self.assertIn('"%s"' % tab, settings)
        for title in ["Look", "Wake word", "Voice", "Approval mode", "Phone control", "Saved tools", "Saved tasks", "FAZE", "Diagnostics"]:
            self.assertIn("<h3>%s</h3>" % title, self.html, title)
            self.assertIn('SettingsCard("%s")' % title, settings, title)
        for label in ["Accent", "Text size", "Font", "Animations", "Greeting line", "Speak replies aloud", "Voice engine", "Browse voices", "Device voice",
                      "Offline voices only", "Speed", "Pitch", "Test voice", "Mic button", "Mic engine", "Phrase", "Allow higher-level tools",
                      "Clear all tools", "Clear all tasks", "Open activity log"]:
            self.assertIn(label, self.html, label)
            self.assertIn(label, settings, label)


class FixesFromTesting(unittest.TestCase):
    def test_overlay_is_dimmed_and_blurred(self):
        overlay = read(KT, "Overlay.kt")
        self.assertIn("0x99000000", overlay)
        self.assertIn("FLAG_BLUR_BEHIND", overlay)
        self.assertIn("blurBehindRadius", overlay)
        self.assertIn("MATCH_PARENT,\n            WindowManager.LayoutParams.MATCH_PARENT", overlay)  # the window covers the screen

    def test_volume_keys_are_swallowed_and_replayed(self):
        svc = read(KT, "FazeAccessibilityService.kt")
        self.assertIn("adjustSuggestedStreamVolume", svc)
        self.assertIn("FLAG_SHOW_UI", svc)
        self.assertIn("detector.poll(", svc)
        self.assertIn("return result.consume", svc)

    def test_picovoice_is_gone_everywhere(self):
        for base, _d, files in os.walk(os.path.join(ROOT, "android")):
            for f in files:
                if f.endswith((".kt", ".kts", ".xml", ".md")):
                    text = read(base, f).lower()
                    if f == "README.md":
                        self.assertNotIn("porcupine", text.replace("picovoice was tried in patch 30 and dropped", "").replace("(picovoice was tried", ""), f)
                    else:
                        self.assertNotIn("porcupine", text, f)
                        self.assertNotIn("picovoice", text, f)


if __name__ == "__main__":
    unittest.main()
