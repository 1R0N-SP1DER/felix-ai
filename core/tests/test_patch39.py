"""Patch 39: pick any model each provider offers; memory categories (patch 37's content) are in."""
import os
import unittest

ROOT = os.path.join(os.path.dirname(__file__), "..", "..")
KT = os.path.join(ROOT, "android", "app", "src", "main", "java", "com", "felix", "faze")


def read(*parts):
    with open(os.path.join(*parts), encoding="utf-8") as f:
        return f.read()


class ModelPicker(unittest.TestCase):
    def test_lists_come_from_the_providers_with_keys_in_headers(self):
        src = read(KT, "ModelCatalog.kt")
        self.assertIn('mapOf("x-goog-api-key" to k)', src)
        self.assertIn('mapOf("Authorization" to "Bearer $k")', src)
        self.assertNotIn("?key=", src)
        self.assertIn("pageSize=200", src)

    def test_sheet_offers_the_picker_and_drops_the_groq_only_selector(self):
        chrome = read(KT, "ChatChrome.kt")
        self.assertIn("ModelPicker(core, row.id", chrome)
        self.assertIn("core.pickModel(row.id, id)", chrome)
        self.assertNotIn('SheetHeading("Groq model")', chrome)

    def test_core_loads_off_the_main_thread(self):
        core = read(KT, "FazeCore.kt")
        self.assertIn("fun loadModels(", core)
        self.assertIn("Thread {", core.split("fun loadModels(")[1].split("fun pickModel")[0])

    def test_memory_categories_are_in(self):
        self.assertIn("fun recall(", read(KT, "Memory.kt"))
        self.assertIn('"recall" to Tier.SAFE', read(KT, "Permissions.kt"))
        self.assertIn('"recall": "safe"', read(ROOT, "core", "tests", "test_patch29.py"))
        self.assertIn("draftCat", read(KT, "SettingsScreen.kt"))


if __name__ == "__main__":
    unittest.main()
