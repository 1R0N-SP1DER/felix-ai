"""Patch 41: exporting the Termux memory for the app, and the app's import."""
import json
import os
import sqlite3
import subprocess
import sys
import tempfile
import unittest

ROOT = os.path.join(os.path.dirname(__file__), "..", "..")
SCRIPT = os.path.join(ROOT, "scripts", "export_memory_for_app.py")


def make_db(path):
    conn = sqlite3.connect(path)
    conn.execute("CREATE TABLE memories (id INTEGER PRIMARY KEY AUTOINCREMENT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL, fact TEXT NOT NULL, source TEXT NOT NULL, confidence REAL NOT NULL, active INTEGER NOT NULL DEFAULT 1, category TEXT NOT NULL DEFAULT 'me')")
    rows = [("Likes tea", "me", 1), ("Builds Faze", "work", 1), ("Deleted fact", "me", 0), ("  ", "me", 1), ("Lives in Lagos", "", 1)]
    for fact, cat, active in rows:
        conn.execute("INSERT INTO memories (created_at, updated_at, fact, source, confidence, active, category) VALUES ('t','t',?,'stated',1.0,?,?)", (fact, active, cat))
    conn.commit()
    conn.close()


class Exporter(unittest.TestCase):
    def test_exports_only_active_facts_with_categories(self):
        with tempfile.TemporaryDirectory() as d:
            db, out = os.path.join(d, "m.db"), os.path.join(d, "sub", "out.json")
            make_db(db)
            r = subprocess.run([sys.executable, SCRIPT, db, out], capture_output=True, text=True)
            self.assertEqual(0, r.returncode, r.stderr)
            data = json.load(open(out, encoding="utf-8"))
            self.assertEqual("faze-memory-v1", data["format"])
            self.assertEqual([("Likes tea", "me"), ("Builds Faze", "work"), ("Lives in Lagos", "me")], [(n["text"], n["category"]) for n in data["notes"]])
            self.assertIn("Exported 3 notes in 2 categories", r.stdout)

    def test_the_database_is_opened_read_only(self):
        self.assertIn("mode=ro", open(SCRIPT, encoding="utf-8").read())

    def test_a_missing_database_is_a_clear_message(self):
        r = subprocess.run([sys.executable, SCRIPT, "/nonexistent/x.db", "/tmp/never.json"], capture_output=True, text=True)
        self.assertNotEqual(0, r.returncode)
        self.assertIn("No database at", r.stderr)

    def test_the_app_reads_the_same_format(self):
        kt = open(os.path.join(ROOT, "android", "app", "src", "main", "java", "com", "felix", "faze", "Memory.kt"), encoding="utf-8").read()
        self.assertIn('getJSONArray("notes")', kt)
        self.assertIn('o!!.optString("category"', kt)


if __name__ == "__main__":
    unittest.main()
