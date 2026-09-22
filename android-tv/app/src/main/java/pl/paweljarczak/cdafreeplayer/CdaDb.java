package pl.paweljarczak.cdafreeplayer;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public final class CdaDb extends SQLiteOpenHelper {
    private static final int VER = 7;
    private static final long CACHE_MS = 6L * 60 * 60 * 1000;

    public CdaDb(Context c) {
        super(c, "cda-free-player.db", null, VER);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE favorites(id TEXT PRIMARY KEY,title TEXT,url TEXT,duration TEXT,image TEXT,added INTEGER)");
        db.execSQL("CREATE TABLE history(id TEXT PRIMARY KEY,title TEXT,url TEXT,duration TEXT,image TEXT,position INTEGER,media_duration INTEGER,last INTEGER)");
        db.execSQL("CREATE TABLE metadata(id TEXT PRIMARY KEY,description TEXT,rating REAL,cda_votes INTEGER,imdb_rating TEXT,imdb_votes INTEGER,comment_count INTEGER,updated INTEGER)");
        db.execSQL("CREATE TABLE comments(id TEXT PRIMARY KEY,json TEXT,updated INTEGER)");
        db.execSQL("CREATE TABLE search_cache(cache_key TEXT,page INTEGER,json TEXT,created INTEGER,PRIMARY KEY(cache_key,page))");
        db.execSQL("CREATE INDEX IF NOT EXISTS history_last_idx ON history(last DESC)");
        db.execSQL("CREATE INDEX IF NOT EXISTS favorites_added_idx ON favorites(added DESC)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) db.delete("search_cache", null, null);
        if (oldVersion < 3) db.delete("comments", null, null);
        if (oldVersion < 4) db.delete("search_cache", null, null);
        if (oldVersion < 5) db.delete("search_cache", null, null);
        if (oldVersion < 6) {
            db.delete("search_cache", null, null);
            db.delete("metadata", "rating IS NULL", null);
        }
        if (oldVersion < 7) {
            db.delete("search_cache", null, null);
            db.delete("metadata", null, null);
            db.delete("comments", null, null);
        }
    }

    public synchronized void removeHistory(String id) {
        getWritableDatabase().delete("history", "id=?", new String[]{id});
    }

    public synchronized void clearHistory() {
        getWritableDatabase().delete("history", null, null);
    }

    public synchronized void removeFavorite(String id) {
        getWritableDatabase().delete("favorites", "id=?", new String[]{id});
    }

    public synchronized void clearFavorites() {
        getWritableDatabase().delete("favorites", null, null);
    }

    public synchronized boolean isFavorite(String id) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT 1 FROM favorites WHERE id=?", new String[]{id})) {
            return c.moveToFirst();
        }
    }

    public synchronized boolean toggleFavorite(Movie m) {
        SQLiteDatabase db = getWritableDatabase();
        if (isFavorite(m.id)) {
            db.delete("favorites", "id=?", new String[]{m.id});
            m.favorite = false;
            return false;
        }
        ContentValues v = movieValues(m);
        v.put("added", System.currentTimeMillis());
        db.insertWithOnConflict("favorites", null, v, SQLiteDatabase.CONFLICT_REPLACE);
        m.favorite = true;
        return true;
    }

    private ContentValues movieValues(Movie m) {
        ContentValues v = new ContentValues();
        v.put("id", m.id);
        m.title = MovieTitle.clean(m.title, m.duration);
        v.put("title", m.title);
        v.put("url", m.url);
        v.put("duration", m.duration);
        v.put("image", m.imageUrl);
        return v;
    }

    public synchronized void saveHistory(Movie m, long pos, long dur) {
        if (dur <= 0 || pos < 0) return;
        m.positionMs = Math.max(0, pos);
        m.mediaDurationMs = Math.max(0, dur);
        ContentValues v = movieValues(m);
        v.put("position", m.positionMs);
        v.put("media_duration", m.mediaDurationMs);
        v.put("last", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("history", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized long[] historyPosition(String id) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT position,media_duration FROM history WHERE id=?", new String[]{id})) {
            if (c.moveToFirst()) return new long[]{c.getLong(0), c.getLong(1)};
        }
        return new long[]{0, 0};
    }

    public synchronized long resumePosition(String id) {
        long[] x = historyPosition(id);
        if (x[0] <= 5_000) return 0;
        if (x[1] > 0 && x[0] >= (long) (x[1] * 0.95)) return 0;
        return x[0];
    }

    public synchronized ArrayList<Movie> favorites() {
        ArrayList<Movie> out = readMovies("SELECT id,title,url,duration,image,0,0,added FROM favorites ORDER BY added DESC", true);
        decorateLocalState(out);
        for (Movie m : out) m.favorite = true;
        return out;
    }

    public synchronized ArrayList<Movie> recent() {
        ArrayList<Movie> out = readMovies("SELECT id,title,url,duration,image,position,media_duration,last FROM history ORDER BY last DESC LIMIT 100", true);
        decorateLocalState(out);
        return out;
    }

    private ArrayList<Movie> readMovies(String sql, boolean sectionByDay) {
        ArrayList<Movie> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(sql, null)) {
            while (c.moveToNext()) {
                Movie m = new Movie();
                m.id = c.getString(0);
                m.title = c.getString(1);
                m.url = c.getString(2);
                m.duration = c.getString(3);
                m.title = MovieTitle.clean(m.title, m.duration);
                m.imageUrl = c.getString(4);
                m.positionMs = c.getLong(5);
                m.mediaDurationMs = c.getLong(6);
                if (sectionByDay && c.getColumnCount() > 7) m.sectionLabel = dayLabel(c.getLong(7));
                out.add(m);
            }
        }
        return out;
    }

    public synchronized void decorateLocalState(Collection<Movie> movies) {
        if (movies == null || movies.isEmpty()) return;
        if (movies.size() > 900) {
            ArrayList<Movie> list = new ArrayList<>(movies);
            for (int i = 0; i < list.size(); i += 900) decorateLocalState(list.subList(i, Math.min(i + 900, list.size())));
            return;
        }
        HashMap<String, Movie> byId = new HashMap<>();
        for (Movie m : movies) {
            if (m == null || m.id == null || m.id.isEmpty()) continue;
            m.positionMs = 0;
            m.mediaDurationMs = 0;
            m.favorite = false;
            byId.put(m.id, m);
        }
        if (byId.isEmpty()) return;

        String[] args = byId.keySet().toArray(new String[0]);
        String in = placeholders(args.length);
        SQLiteDatabase db = getReadableDatabase();

        try (Cursor c = db.rawQuery("SELECT id,position,media_duration FROM history WHERE id IN (" + in + ")", args)) {
            while (c.moveToNext()) {
                Movie m = byId.get(c.getString(0));
                if (m != null) {
                    m.positionMs = c.getLong(1);
                    m.mediaDurationMs = c.getLong(2);
                }
            }
        }

        try (Cursor c = db.rawQuery("SELECT id FROM favorites WHERE id IN (" + in + ")", args)) {
            while (c.moveToNext()) {
                Movie m = byId.get(c.getString(0));
                if (m != null) m.favorite = true;
            }
        }

    }

    public synchronized void clearCache() {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("search_cache", null, null);
        db.delete("metadata", null, null);
        db.delete("comments", null, null);
        db.execSQL("VACUUM");
    }

    private static String dayLabel(long time) {
        if (time <= 0) return "";
        Calendar now = Calendar.getInstance();
        Calendar day = Calendar.getInstance();
        day.setTimeInMillis(time);
        if (sameDay(now, day)) return "Dzisiaj";
        now.add(Calendar.DAY_OF_YEAR, -1);
        if (sameDay(now, day)) return "Wczoraj";
        String label = new SimpleDateFormat("d MMMM yyyy", new Locale("pl", "PL")).format(new Date(time));
        return label.isEmpty() ? "" : Character.toUpperCase(label.charAt(0)) + label.substring(1);
    }

    private static boolean sameDay(Calendar a, Calendar b) {
        return a.get(Calendar.ERA) == b.get(Calendar.ERA) &&
                a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
                a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    private static String placeholders(int n) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) s.append(',');
            s.append('?');
        }
        return s.toString();
    }

    public synchronized void saveMetadata(String id, MovieMetadata m) {
        ContentValues v = new ContentValues();
        v.put("id", id);
        v.put("description", m.description);
        if (m.rating != null) v.put("rating", m.rating); else v.putNull("rating");
        if (m.cdaVotes != null) v.put("cda_votes", m.cdaVotes); else v.putNull("cda_votes");
        v.put("imdb_rating", m.imdbRating);
        if (m.imdbVotes != null) v.put("imdb_votes", m.imdbVotes); else v.putNull("imdb_votes");
        if (m.commentCount != null) v.put("comment_count", m.commentCount); else v.putNull("comment_count");
        v.put("updated", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("metadata", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized MovieMetadata getMetadata(String id) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT description,rating,cda_votes,imdb_rating,imdb_votes,comment_count FROM metadata WHERE id=?", new String[]{id})) {
            if (!c.moveToFirst()) return null;
            MovieMetadata m = new MovieMetadata();
            m.description = c.getString(0) == null ? "" : c.getString(0);
            if (!c.isNull(1)) m.rating = c.getDouble(1);
            if (!c.isNull(2)) m.cdaVotes = c.getInt(2);
            m.imdbRating = c.getString(3) == null ? "" : c.getString(3);
            if (!c.isNull(4)) m.imdbVotes = c.getInt(4);
            if (!c.isNull(5)) m.commentCount = c.getInt(5);
            return m;
        }
    }

    public synchronized void saveComments(String id, ArrayList<CommentItem> list) {
        JSONArray a = new JSONArray();
        try {
            for (CommentItem c : list) {
                JSONObject o = new JSONObject();
                o.put("author", c.author);
                o.put("date", c.date);
                o.put("score", c.score);
                o.put("text", c.text);
                a.put(o);
            }
        } catch (Exception ignored) {}
        ContentValues v = new ContentValues();
        v.put("id", id);
        v.put("json", a.toString());
        v.put("updated", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("comments", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized ArrayList<CommentItem> getComments(String id) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT json,updated FROM comments WHERE id=?", new String[]{id})) {
            if (!c.moveToFirst() || System.currentTimeMillis() - c.getLong(1) > CACHE_MS) return null;
            return commentsFromJson(c.getString(0));
        } catch (Exception e) {
            return null;
        }
    }

    private ArrayList<CommentItem> commentsFromJson(String s) throws Exception {
        ArrayList<CommentItem> out = new ArrayList<>();
        JSONArray a = new JSONArray(s);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            CommentItem c = new CommentItem();
            c.author = o.optString("author", "anonim");
            c.date = o.optString("date");
            c.score = o.optString("score");
            c.text = o.optString("text");
            out.add(c);
        }
        return out;
    }

    public synchronized void putSearch(String key, int page, SearchPage sp) {
        if (sp == null || (sp.raw <= 0 && sp.movies.isEmpty())) return;
        try {
            JSONObject root = new JSONObject();
            root.put("raw", sp.raw);
            root.put("premium", sp.premium);
            root.put("nonVideo", sp.nonVideo);
            JSONArray a = new JSONArray();
            for (Movie m : sp.movies) a.put(m.toJson());
            root.put("movies", a);
            ContentValues v = new ContentValues();
            v.put("cache_key", key);
            v.put("page", page);
            v.put("json", root.toString());
            v.put("created", System.currentTimeMillis());
            SQLiteDatabase db = getWritableDatabase();
            long cutoff = System.currentTimeMillis() - CACHE_MS;
            db.delete("search_cache", "created<?", new String[]{String.valueOf(cutoff)});
            db.insertWithOnConflict("search_cache", null, v, SQLiteDatabase.CONFLICT_REPLACE);
            db.execSQL("DELETE FROM search_cache WHERE rowid NOT IN (SELECT rowid FROM search_cache ORDER BY created DESC LIMIT 80)");
        } catch (Exception ignored) {}
    }

    public synchronized SearchPage getSearch(String key, int page) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT json,created FROM search_cache WHERE cache_key=? AND page=?", new String[]{key, String.valueOf(page)})) {
            if (!c.moveToFirst() || System.currentTimeMillis() - c.getLong(1) > CACHE_MS) return null;
            JSONObject root = new JSONObject(c.getString(0));
            SearchPage sp = new SearchPage();
            sp.raw = root.optInt("raw");
            sp.premium = root.optInt("premium");
            sp.nonVideo = root.optInt("nonVideo");
            JSONArray a = root.optJSONArray("movies");
            if (a != null) for (int i = 0; i < a.length(); i++) sp.movies.add(Movie.fromJson(a.getJSONObject(i)));
            if (sp.raw <= 0 && sp.movies.isEmpty()) return null;
            return sp;
        } catch (Exception e) {
            return null;
        }
    }
}
