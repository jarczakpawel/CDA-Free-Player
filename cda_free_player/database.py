import json
import sqlite3
import threading
import time
from datetime import datetime, date

from .config import DB_FILE, CACHE_TTL


class Database:
    def __init__(self):
        self.db = sqlite3.connect(DB_FILE, check_same_thread=False)
        self.lock = threading.Lock()

        self.db.execute("""
            CREATE TABLE IF NOT EXISTS favorites(
                id TEXT PRIMARY KEY,
                title TEXT,
                url TEXT,
                duration TEXT,
                image TEXT,
                added_at TEXT
            )
        """)

        self.db.execute("""
            CREATE TABLE IF NOT EXISTS history_v6(
                id TEXT PRIMARY KEY,
                title TEXT,
                url TEXT,
                duration TEXT,
                image TEXT,
                position REAL DEFAULT 0,
                media_duration REAL DEFAULT 0,
                last_watched TEXT
            )
        """)

        self.db.execute("""
            CREATE TABLE IF NOT EXISTS metadata_v6(
                id TEXT PRIMARY KEY,
                description TEXT,
                rating TEXT,
                author TEXT,
                date_added TEXT,
                qualities TEXT,
                updated_at TEXT
            )
        """)

        columns = {
            row[1]
            for row in self.db.execute(
                "PRAGMA table_info(metadata_v6)"
            ).fetchall()
        }

        for name, sql_type in (
            ("cda_votes", "INTEGER"),
            ("imdb_rating", "TEXT"),
            ("imdb_votes", "INTEGER"),
            ("comment_count", "INTEGER"),
        ):
            if name not in columns:
                self.db.execute(
                    f"ALTER TABLE metadata_v6 "
                    f"ADD COLUMN {name} {sql_type}"
                )

        self.db.execute("""
            CREATE TABLE IF NOT EXISTS comments_v15(
                id TEXT PRIMARY KEY,
                comments_json TEXT,
                fetched_at REAL
            )
        """)

        self.db.execute("""
            CREATE TABLE IF NOT EXISTS search_pages_v12(
                query TEXT,
                sort_key TEXT,
                duration_key TEXT,
                page INTEGER,
                items_json TEXT,
                fetched_at REAL,
                PRIMARY KEY(query,sort_key,duration_key,page)
            )
        """)

        self.db.commit()

    def is_favorite(self, vid):
        with self.lock:
            return self.db.execute(
                "SELECT 1 FROM favorites WHERE id=?",
                (vid,),
            ).fetchone() is not None

    def toggle_favorite(self, item):
        with self.lock:
            row = self.db.execute(
                "SELECT 1 FROM favorites WHERE id=?",
                (item["id"],),
            ).fetchone()

            if row:
                self.db.execute(
                    "DELETE FROM favorites WHERE id=?",
                    (item["id"],),
                )
                self.db.commit()
                return False

            self.db.execute(
                "INSERT OR REPLACE INTO favorites VALUES(?,?,?,?,?,?)",
                (
                    item["id"],
                    item["title"],
                    item["url"],
                    item["duration"],
                    item["image"],
                    datetime.now().isoformat(timespec="seconds"),
                ),
            )
            self.db.commit()
            return True

    def favorites(self):
        with self.lock:
            rows = self.db.execute(
                "SELECT id,title,url,duration,image,added_at "
                "FROM favorites ORDER BY added_at DESC"
            ).fetchall()

        result = []
        for row in rows:
            item = self._item(row[:5])
            item["section_label"] = self._day_label(row[5])
            result.append(item)
        return result

    def history(self):
        with self.lock:
            rows = self.db.execute(
                "SELECT id,title,url,duration,image,position,media_duration,last_watched "
                "FROM history_v6 ORDER BY last_watched DESC LIMIT 100"
            ).fetchall()

        result = []
        for row in rows:
            item = self._item(row[:5])
            item["position"] = row[5] or 0
            item["media_duration"] = row[6] or 0
            item["section_label"] = self._day_label(row[7])
            result.append(item)

        return result

    @staticmethod
    def _day_label(value):
        if not value:
            return ""
        try:
            day = datetime.fromisoformat(value).date()
        except (TypeError, ValueError):
            return ""
        today = date.today()
        delta = (today - day).days
        if delta == 0:
            return "Dzisiaj"
        if delta == 1:
            return "Wczoraj"
        months = (
            "stycznia", "lutego", "marca", "kwietnia", "maja", "czerwca",
            "lipca", "sierpnia", "września", "października", "listopada", "grudnia"
        )
        return f"{day.day} {months[day.month - 1]} {day.year}"

    def history_position(self, vid):
        with self.lock:
            row = self.db.execute(
                "SELECT position,media_duration FROM history_v6 WHERE id=?",
                (vid,),
            ).fetchone()

        return row if row else (0, 0)

    def save_history(self, item, position=0, media_duration=0):
        with self.lock:
            self.db.execute(
                """
                INSERT INTO history_v6(
                    id,title,url,duration,image,
                    position,media_duration,last_watched
                )
                VALUES(?,?,?,?,?,?,?,?)
                ON CONFLICT(id) DO UPDATE SET
                    title=excluded.title,
                    url=excluded.url,
                    duration=excluded.duration,
                    image=excluded.image,
                    position=excluded.position,
                    media_duration=excluded.media_duration,
                    last_watched=excluded.last_watched
                """,
                (
                    item["id"],
                    item["title"],
                    item["url"],
                    item["duration"],
                    item["image"],
                    float(position or 0),
                    float(media_duration or 0),
                    datetime.now().isoformat(timespec="seconds"),
                ),
            )
            self.db.commit()

    def metadata(self, vid):
        with self.lock:
            row = self.db.execute(
                "SELECT description,rating,author,date_added,qualities,cda_votes,imdb_rating,imdb_votes,comment_count "
                "FROM metadata_v6 WHERE id=?",
                (vid,),
            ).fetchone()

        if not row:
            return None

        try:
            qualities = json.loads(row[4] or "[]")
        except Exception:
            qualities = []

        return {
            "description": row[0] or "",
            "rating": row[1] or "",
            "author": row[2] or "",
            "date_added": row[3] or "",
            "qualities": qualities,
            "cda_votes": row[5] if len(row) > 5 else None,
            "imdb_rating": (row[6] or "") if len(row) > 6 else "",
            "imdb_votes": row[7] if len(row) > 7 else None,
            "comment_count": row[8] if len(row) > 8 else None,
        }

    def save_metadata(self, vid, data):
        with self.lock:
            old = self.db.execute("SELECT qualities FROM metadata_v6 WHERE id=?", (vid,)).fetchone()
            qualities_json = old[0] if old and old[0] else "[]"
            self.db.execute(
                """
                INSERT INTO metadata_v6(
                    id,description,rating,author,date_added,qualities,updated_at,
                    cda_votes,imdb_rating,imdb_votes,comment_count
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(id) DO UPDATE SET
                    description=excluded.description,
                    rating=excluded.rating,
                    author=excluded.author,
                    date_added=excluded.date_added,
                    updated_at=excluded.updated_at,
                    cda_votes=excluded.cda_votes,
                    imdb_rating=excluded.imdb_rating,
                    imdb_votes=excluded.imdb_votes,
                    comment_count=excluded.comment_count
                """,
                (
                    vid,
                    data.get("description", ""),
                    data.get("rating", ""),
                    data.get("author", ""),
                    data.get("date_added", ""),
                    qualities_json,
                    datetime.now().isoformat(timespec="seconds"),
                    data.get("cda_votes"),
                    data.get("imdb_rating", ""),
                    data.get("imdb_votes"),
                    data.get("comment_count"),
                ),
            )
            self.db.commit()


    def search_page(
        self,
        query,
        sort_key,
        duration_key,
        page,
    ):
        with self.lock:
            row = self.db.execute(
                "SELECT items_json,fetched_at FROM search_pages_v12 "
                "WHERE query=? AND sort_key=? AND duration_key=? AND page=?",
                (
                    query,
                    sort_key,
                    duration_key,
                    page,
                ),
            ).fetchone()

        if not row:
            return None

        if time.time() - row[1] > CACHE_TTL:
            return None

        try:
            data = json.loads(row[0])
            if isinstance(data, dict) and "items" in data and "stats" in data:
                return data
            if isinstance(data, list) and data:
                return {"items": data, "stats": {"raw": len(data), "free": len(data), "premium": 0, "nonvideo": 0}}
            return None
        except Exception:
            return None

    def save_search_page(
        self,
        query,
        sort_key,
        duration_key,
        page,
        items,
    ):
        with self.lock:
            self.db.execute(
                "INSERT OR REPLACE INTO search_pages_v12 VALUES(?,?,?,?,?,?)",
                (
                    query,
                    sort_key,
                    duration_key,
                    page,
                    json.dumps(items, ensure_ascii=False),
                    time.time(),
                ),
            )
            self.db.commit()

    def comments(self, vid, max_age=21600):
        with self.lock:
            row = self.db.execute(
                "SELECT comments_json,fetched_at FROM comments_v15 WHERE id=?",
                (vid,),
            ).fetchone()
        if not row or time.time() - float(row[1] or 0) > max_age:
            return None
        try:
            return json.loads(row[0] or "[]")
        except Exception:
            return None

    def save_comments(self, vid, comments):
        with self.lock:
            self.db.execute(
                "INSERT OR REPLACE INTO comments_v15 VALUES(?,?,?)",
                (vid, json.dumps(comments, ensure_ascii=False), time.time()),
            )
            self.db.commit()


    def remove_history(self, vid):
        with self.lock:
            self.db.execute("DELETE FROM history_v6 WHERE id=?", (vid,))
            self.db.commit()

    def clear_history(self):
        with self.lock:
            self.db.execute("DELETE FROM history_v6")
            self.db.commit()

    def remove_favorite(self, vid):
        with self.lock:
            self.db.execute("DELETE FROM favorites WHERE id=?", (vid,))
            self.db.commit()

    def clear_favorites(self):
        with self.lock:
            self.db.execute("DELETE FROM favorites")
            self.db.commit()

    @staticmethod
    def _item(row):
        return {
            "id": row[0],
            "title": row[1],
            "url": row[2],
            "duration": row[3],
            "image": row[4],
            "short_description": "",
            "short_rating": "",
        }
