"""
memory_store.py - Persistent SQLite + FTS5 Second Brain & Career Memory for Antigravity.

Stores structured, searchable long-term context across conversations in:
  $HOME/.gemini/memory/assistant.db
and keeps a concise human/agent-readable snapshot updated at:
  $HOME/.gemini/memory/MEMORY_SNAPSHOT.md

Categories:
  - person       : Stakeholders, colleagues, admins, contacts, communication preferences
  - project      : Active school / engineering / personal projects & milestones
  - commitment   : Action items, follow-ups ("waiting on X"), deadlines (`due_at`)
  - career_win   : Brag sheet / accomplishments / impact metrics for evals & resumes
  - preference   : User habits, schedule rules, tone, routines
  - note         : General persistent facts & domain knowledge
"""

import os
import json
import time
import sqlite3
from datetime import datetime

VALID_CATEGORIES = (
    "person",
    "project",
    "commitment",
    "career_win",
    "preference",
    "note",
)

VALID_STATUSES = ("active", "waiting", "completed", "archived")


def _default_home():
    env_home = os.environ.get("HOME", "").strip()
    if env_home:
        return env_home
    prefix = os.environ.get("PREFIX", "").strip()
    if prefix:
        return os.path.dirname(prefix)
    return os.path.expanduser("~")


def memory_dir(home=None):
    base = home or _default_home()
    d = os.path.join(base, ".gemini", "memory")
    os.makedirs(d, exist_ok=True)
    return d


def db_path(home=None):
    return os.path.join(memory_dir(home), "assistant.db")


def snapshot_path(home=None):
    return os.path.join(memory_dir(home), "MEMORY_SNAPSHOT.md")


class MemoryStore:
    def __init__(self, home=None):
        self.home = home or _default_home()
        self.path = db_path(self.home)
        self.snap_path = snapshot_path(self.home)
        self._fts_enabled = False
        self._init_schema()

    def _connect(self):
        conn = sqlite3.connect(self.path, timeout=10.0)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA journal_mode=WAL")
        conn.execute("PRAGMA synchronous=NORMAL")
        return conn

    def _init_schema(self):
        with self._connect() as conn:
            conn.execute(
                """
                CREATE TABLE IF NOT EXISTS memories (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    category TEXT NOT NULL,
                    key TEXT NOT NULL,
                    title TEXT NOT NULL,
                    content TEXT NOT NULL,
                    tags TEXT NOT NULL DEFAULT '',
                    status TEXT NOT NULL DEFAULT 'active',
                    due_at TEXT NOT NULL DEFAULT '',
                    metadata_json TEXT NOT NULL DEFAULT '{}',
                    created_at REAL NOT NULL,
                    updated_at REAL NOT NULL,
                    UNIQUE(category, key)
                )
                """
            )
            conn.execute("CREATE INDEX IF NOT EXISTS idx_mem_cat_status ON memories(category, status)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_mem_due ON memories(due_at)")
            try:
                conn.execute(
                    """
                    CREATE VIRTUAL TABLE IF NOT EXISTS memories_fts USING fts5(
                        title,
                        content,
                        tags,
                        category,
                        content='memories',
                        content_rowid='id'
                    )
                    """
                )
                self._fts_enabled = True
            except sqlite3.OperationalError:
                self._fts_enabled = False

    def _rebuild_fts(self, conn):
        if not self._fts_enabled:
            return
        try:
            conn.execute("INSERT INTO memories_fts(memories_fts) VALUES('rebuild')")
        except sqlite3.OperationalError:
            pass

    @staticmethod
    def _slugify(text):
        cleaned = "".join(ch.lower() if ch.isalnum() else "_" for ch in str(text).strip())
        while "__" in cleaned:
            cleaned = cleaned.replace("__", "_")
        return cleaned.strip("_")[:96] or f"item_{int(time.time())}"

    @staticmethod
    def _row_to_dict(row):
        meta = {}
        try:
            meta = json.loads(row["metadata_json"] or "{}")
        except Exception:
            meta = {}
        tags_raw = row["tags"] or ""
        tags = [t.strip() for t in tags_raw.split(",") if t.strip()]
        return {
            "id": row["id"],
            "category": row["category"],
            "key": row["key"],
            "title": row["title"],
            "content": row["content"],
            "tags": tags,
            "status": row["status"],
            "due_at": row["due_at"] or None,
            "metadata": meta,
            "created_at": row["created_at"],
            "updated_at": row["updated_at"],
        }

    def upsert(self, category, title, content, key=None, tags=None, status="active", due_at="", metadata=None):
        cat = str(category or "note").strip().lower()
        if cat not in VALID_CATEGORIES:
            cat = "note"
        st = str(status or "active").strip().lower()
        if st not in VALID_STATUSES:
            st = "active"
        title_s = str(title or "").strip()
        content_s = str(content or "").strip()
        if not title_s:
            return {"ok": False, "error": "title is required"}
        key_s = self._slugify(key or title_s)
        if isinstance(tags, (list, tuple)):
            tags_s = ",".join(str(t).strip() for t in tags if str(t).strip())
        else:
            tags_s = str(tags or "").strip()
        meta_s = json.dumps(metadata if isinstance(metadata, dict) else {}, ensure_ascii=False)
        now = time.time()

        with self._connect() as conn:
            existing = conn.execute(
                "SELECT id, created_at FROM memories WHERE category = ? AND key = ?",
                (cat, key_s),
            ).fetchone()
            if existing:
                conn.execute(
                    """
                    UPDATE memories
                       SET title = ?, content = ?, tags = ?, status = ?, due_at = ?,
                           metadata_json = ?, updated_at = ?
                     WHERE id = ?
                    """,
                    (title_s, content_s, tags_s, st, str(due_at or ""), meta_s, now, existing["id"]),
                )
                row_id = existing["id"]
            else:
                cur = conn.execute(
                    """
                    INSERT INTO memories (
                        category, key, title, content, tags, status, due_at, metadata_json, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (cat, key_s, title_s, content_s, tags_s, st, str(due_at or ""), meta_s, now, now),
                )
                row_id = cur.lastrowid
            self._rebuild_fts(conn)
            saved = conn.execute("SELECT * FROM memories WHERE id = ?", (row_id,)).fetchone()

        self.write_snapshot()
        return {"ok": True, "memory": self._row_to_dict(saved), "snapshot_path": self.snap_path}

    def search(self, query="", category="", status="", limit=25):
        max_rows = max(1, min(int(limit or 25), 200))
        q = str(query or "").strip()
        cat = str(category or "").strip().lower()
        st = str(status or "").strip().lower()

        with self._connect() as conn:
            rows = []
            if q and self._fts_enabled:
                tokens = ["".join(ch for ch in part if ch.isalnum()) for part in q.split()]
                fts_q = " OR ".join(f'"{t}"*' for t in tokens if t)
                if fts_q:
                    sql = """
                        SELECT m.*
                          FROM memories_fts f
                          JOIN memories m ON m.id = f.rowid
                         WHERE memories_fts MATCH ?
                    """
                    params = [fts_q]
                    if cat:
                        sql += " AND m.category = ?"
                        params.append(cat)
                    if st:
                        sql += " AND m.status = ?"
                        params.append(st)
                    sql += " ORDER BY bm25(memories_fts), m.updated_at DESC LIMIT ?"
                    params.append(max_rows)
                    try:
                        rows = conn.execute(sql, params).fetchall()
                    except sqlite3.OperationalError:
                        rows = []

            if not rows:
                sql = "SELECT * FROM memories WHERE 1=1"
                params = []
                if cat:
                    sql += " AND category = ?"
                    params.append(cat)
                if st:
                    sql += " AND status = ?"
                    params.append(st)
                if q:
                    like = f"%{q}%"
                    sql += " AND (title LIKE ? OR content LIKE ? OR tags LIKE ?)"
                    params.extend([like, like, like])
                sql += " ORDER BY updated_at DESC LIMIT ?"
                params.append(max_rows)
                rows = conn.execute(sql, params).fetchall()

        return {
            "ok": True,
            "count": len(rows),
            "memories": [self._row_to_dict(r) for r in rows],
        }

    def update_status(self, mem_id=None, category="", key="", status="completed"):
        st = str(status or "completed").strip().lower()
        if st not in VALID_STATUSES:
            return {"ok": False, "error": f"invalid status: {st}"}
        now = time.time()
        with self._connect() as conn:
            if mem_id is not None:
                cur = conn.execute("UPDATE memories SET status = ?, updated_at = ? WHERE id = ?", (st, now, int(mem_id)))
            else:
                cur = conn.execute(
                    "UPDATE memories SET status = ?, updated_at = ? WHERE category = ? AND key = ?",
                    (st, now, str(category).strip().lower(), self._slugify(key)),
                )
            self._rebuild_fts(conn)
            updated = cur.rowcount
        self.write_snapshot()
        return {"ok": updated > 0, "updated": updated, "status": st}

    def delete(self, mem_id=None, category="", key=""):
        with self._connect() as conn:
            if mem_id is not None:
                cur = conn.execute("DELETE FROM memories WHERE id = ?", (int(mem_id),))
            else:
                cur = conn.execute(
                    "DELETE FROM memories WHERE category = ? AND key = ?",
                    (str(category).strip().lower(), self._slugify(key)),
                )
            self._rebuild_fts(conn)
            deleted = cur.rowcount
        self.write_snapshot()
        return {"ok": deleted > 0, "deleted": deleted}

    def briefing_digest(self):
        today = datetime.now().strftime("%Y-%m-%d")
        with self._connect() as conn:
            commitments = [
                self._row_to_dict(r)
                for r in conn.execute(
                    """
                    SELECT * FROM memories
                     WHERE category = 'commitment' AND status IN ('active', 'waiting')
                     ORDER BY CASE WHEN due_at = '' THEN '9999-99-99' ELSE due_at END ASC, updated_at DESC
                     LIMIT 30
                    """
                ).fetchall()
            ]
            projects = [
                self._row_to_dict(r)
                for r in conn.execute(
                    "SELECT * FROM memories WHERE category = 'project' AND status = 'active' ORDER BY updated_at DESC LIMIT 15"
                ).fetchall()
            ]
            preferences = [
                self._row_to_dict(r)
                for r in conn.execute(
                    "SELECT * FROM memories WHERE category = 'preference' AND status = 'active' ORDER BY updated_at DESC LIMIT 20"
                ).fetchall()
            ]
            people = [
                self._row_to_dict(r)
                for r in conn.execute(
                    "SELECT * FROM memories WHERE category = 'person' AND status = 'active' ORDER BY updated_at DESC LIMIT 25"
                ).fetchall()
            ]
            wins = [
                self._row_to_dict(r)
                for r in conn.execute(
                    "SELECT * FROM memories WHERE category = 'career_win' ORDER BY created_at DESC LIMIT 10"
                ).fetchall()
            ]

        overdue = [c for c in commitments if c.get("due_at") and c["due_at"] <= today]
        return {
            "ok": True,
            "today": today,
            "overdue_or_due_today": overdue,
            "open_commitments": commitments,
            "active_projects": projects,
            "preferences": preferences,
            "key_people": people,
            "recent_career_wins": wins,
            "snapshot_path": self.snap_path,
        }

    def write_snapshot(self):
        digest = self.briefing_digest()
        lines = [
            "# Antigravity Executive & Career Memory Snapshot",
            "",
            f"_Updated: {datetime.now().strftime('%Y-%m-%d %H:%M')}_",
            "",
        ]
        sections = (
            ("Preferences & Working Rules", digest["preferences"]),
            ("Active Projects", digest["active_projects"]),
            ("Open Commitments & Follow-Ups", digest["open_commitments"]),
            ("Key People & Stakeholders", digest["key_people"]),
            ("Career Wins & Brag Sheet", digest["recent_career_wins"]),
        )
        for heading, items in sections:
            lines.append(f"## {heading}")
            if not items:
                lines.append("- *(none recorded yet)*")
            else:
                for it in items:
                    due = f" (Due: {it['due_at']})" if it.get("due_at") else ""
                    st = f" [{it['status']}]" if it.get("status") not in ("active", None) else ""
                    lines.append(f"- **{it['title']}**{st}{due}: {it['content']}")
            lines.append("")

        tmp = self.snap_path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            f.write("\n".join(lines).strip() + "\n")
        os.replace(tmp, self.snap_path)
        return self.snap_path
