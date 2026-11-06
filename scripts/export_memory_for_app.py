#!/usr/bin/env python3
"""Export the facts in the Termux memory database to a file the Android app can import.

Usage (in Termux):  python3 ~/felix-ai/scripts/export_memory_for_app.py [database] [output]
Reads only active rows of the `memories` table; writes JSON the app's Settings > Agent > Memory > Import reads.
The file stays on your phone; nothing is uploaded.
"""
import json
import os
import sqlite3
import sys
from pathlib import Path

DEFAULT_DB = Path(os.environ.get("FELIX_DB") or (Path(__file__).resolve().parent.parent / "core" / "felix_ai_memory.db"))
DEFAULT_OUT = Path.home() / "storage" / "downloads" / "faze_memory_export.json"


def export(db_path, out_path):
    db_path, out_path = Path(db_path), Path(out_path)
    if not db_path.is_file():
        raise SystemExit("No database at %s (pass its path as the first argument)" % db_path)
    conn = sqlite3.connect("file:%s?mode=ro" % db_path, uri=True)
    try:
        rows = conn.execute("SELECT fact, category, created_at FROM memories WHERE active = 1 ORDER BY id").fetchall()
    finally:
        conn.close()
    notes = [{"text": (fact or "").strip(), "category": (category or "me").strip() or "me", "created": created or ""} for fact, category, created in rows if (fact or "").strip()]
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps({"format": "faze-memory-v1", "notes": notes}, ensure_ascii=False, indent=1), encoding="utf-8")
    cats = {}
    for n in notes:
        cats[n["category"]] = cats.get(n["category"], 0) + 1
    return len(notes), cats


if __name__ == "__main__":
    db = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_DB
    out = sys.argv[2] if len(sys.argv) > 2 else DEFAULT_OUT
    count, cats = export(db, out)
    print("Exported %d notes in %d categories to %s" % (count, len(cats), out))
    for name, n in sorted(cats.items()):
        print("  %-20s %d" % (name, n))
