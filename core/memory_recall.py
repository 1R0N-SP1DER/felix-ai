import os
import sqlite3
import re
from pathlib import Path

DB_PATH = Path(os.environ.get("FELIX_DB") or (Path(__file__).parent / "felix_ai_memory.db"))

PERSONAL_QUERY = re.compile(
    r"\b(who am i|about me|know about me|my name|what.?s my name|tell me about myself)\b",
    re.IGNORECASE,
)


def get_connection():
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    return conn

def setup_fts():
    conn = get_connection()
    conn.execute("CREATE VIRTUAL TABLE IF NOT EXISTS memories_fts USING fts5(fact, content='memories', content_rowid='id');")
    conn.execute("INSERT INTO memories_fts(memories_fts) VALUES('rebuild');")
    conn.commit()
    conn.close()

def search_memories(user_message, limit=5):
    stopwords = {"the", "a", "an", "is", "are", "my", "me", "i", "you", "what", "whats", "do", "does", "tell", "about", "it", "to", "of", "and", "or", "not", "near"}
    tokens = re.findall(r"[a-z0-9]+", user_message.lower())
    filtered = [f'"{t}"' for t in tokens if t not in stopwords and len(t) >= 3]
    
    conn = get_connection()
    rows = []
    
    if filtered:
        fts_query = " OR ".join(filtered)
        try:
            # FTS5 search
            rows = conn.execute("""
                SELECT m.*
                FROM memories_fts
                JOIN memories m ON m.id = memories_fts.rowid
                WHERE memories_fts MATCH ?
                AND m.active = 1
                ORDER BY rank LIMIT ?
            """, (fts_query, limit)).fetchall()
        except sqlite3.OperationalError:
            pass
            
    # No keyword hit: only fall back for questions about Felix himself
    # ("who am i", "what do you know about me"); never for greetings.
    if not rows and PERSONAL_QUERY.search(user_message):
        try:
            rows = conn.execute("""
                SELECT * FROM memories
                WHERE active = 1 AND category = 'me'
                ORDER BY created_at DESC LIMIT ?
            """, (limit,)).fetchall()
        except sqlite3.OperationalError:
            rows = []

    conn.close()
    return [dict(row) for row in rows]
