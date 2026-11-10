"""Patch 32: the top bar, model sheet and message box of the app say what the web UI says."""
import os
import re
import unittest

ROOT = os.path.join(os.path.dirname(__file__), "..", "..")
WEB = os.path.join(ROOT, "core", "web")
KT = os.path.join(ROOT, "android", "app", "src", "main", "java", "com", "felix", "faze")


def read(*parts):
    with open(os.path.join(*parts), encoding="utf-8") as f:
        return f.read()


class ModelSheetMatchesTheWebUI(unittest.TestCase):
    def setUp(self):
        self.js = read(WEB, "app.js")
        self.html = read(WEB, "index.html")
        self.kt = read(KT, "ModelSheet.kt")

    def test_names_and_order(self):
        web = re.search(r"const MODEL_NAMES = \{(.*?)\};", self.js).group(1)
        web_names = re.findall(r"(\w+): '([^']+)'", web)
        order = re.search(r"for \(const id of \[([^\]]*)\]", self.js[self.js.index("function renderModelSheet"):]).group(1)
        self.assertEqual(re.findall(r"'(\w+)'", order), [n for n, _ in web_names])
        kt = re.search(r"val names = linkedMapOf\((.*?)\)\n", self.kt, re.S).group(1)
        # the phone lists Offline (the models found on the phone are under it) where the web page has its Qwen and LFM rows
        phone = [("auto", "Auto"), ("offline", "Offline")] + [n for n in web_names if n[0] not in ("auto", "qwen", "lfm")]
        self.assertEqual(phone, re.findall(r'"(\w+)" to "([^"]+)"', kt))

    def test_thinking_levels_and_hints(self):
        web = re.findall(r'<option value="(\w+)">(\w+)</option>', self.html[self.html.index('id="think-level"'):][:500])
        kt = re.search(r"val thinkLevels = listOf\((.*?)\)\n", self.kt, re.S).group(1)
        self.assertEqual(web, re.findall(r'"(\w+)" to "(\w+)"', kt))
        for hint in ["This online model has no thinking setting yet. It still applies when Qwen or Gemini answers.",
                     "LFM cannot think, so this does nothing while LFM is forced. It still applies when Qwen or Gemini answers."]:
            self.assertIn(hint, self.js)
            self.assertIn(hint, self.kt)

    def test_sheet_words(self):
        sheet = read(KT, "ChatChrome.kt")
        hint = "Tap a model to stay on it until you turn it off. Tap the &#10003; or arrow to pick how long instead."
        self.assertIn(hint, self.html)
        self.assertIn("Tap a model to stay on it until you turn it off. Tap the ✓ or arrow to pick how long instead.", sheet)
        for h in ["Model", "Thinking"]:
            self.assertIn("<h3>%s</h3>" % h, self.html)
            self.assertIn('SheetHeading("%s")' % h, sheet)


class ChromeMatchesTheWebUI(unittest.TestCase):
    def setUp(self):
        self.html = read(WEB, "index.html")
        self.chrome = read(KT, "ChatChrome.kt")

    def test_composer_words(self):
        self.assertIn('placeholder="Ask FAZE anything..."', self.html)
        self.assertIn("Ask FAZE anything...", self.chrome)
        # the phone's box is slimmer than the web one: no Attach / Tools / model row under the buttons
        composer = self.chrome[self.chrome.index("fun Composer("):self.chrome.index("private fun ActionSquare")]
        for chip in ['ToolChip(', '"Attach"', '"Tools"']:
            self.assertNotIn(chip, composer)

    def test_plus_menu_items(self):
        for item in ["Voice mode", "Thinking", "Activity log", "New chat", "Settings"]:
            self.assertIn(item, self.html)
            self.assertIn('MenuItem("%s"' % item, self.chrome)

    def test_tools_menu_inserts_are_the_same(self):
        block = self.html[self.html.index('id="tools-menu"'):][:900]
        web = re.findall(r'data-insert="([^"]*)">([^<]+)</button>', block)
        kt = re.search(r"QUICK_INSERTS = listOf\((.*?)\n\)", self.chrome, re.S).group(1)
        self.assertEqual([(label, insert) for insert, label in web], re.findall(r'"([^"]+)" to "([^"]*)"', kt))

    def test_top_bar_glyphs(self):
        for g in ["&#9776;", "&#9678;", "&#9662;", "&#8801;", "&#9632;"]:
            self.assertIn(g, self.html)
        for g in ["☰", "◎", "▾", "≡", "■"]:
            self.assertIn(g, self.chrome)


if __name__ == "__main__":
    unittest.main()
