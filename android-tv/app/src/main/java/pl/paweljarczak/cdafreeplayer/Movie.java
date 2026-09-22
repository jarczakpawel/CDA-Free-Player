package pl.paweljarczak.cdafreeplayer;

import org.json.JSONObject;

public final class Movie {
    public String id = "", title = "", url = "", duration = "", imageUrl = "", shortDescription = "", sectionLabel = "";
    public Double rating = null;
    public long positionMs = 0, mediaDurationMs = 0;
    public boolean favorite = false;

    public JSONObject toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("title", title);
            o.put("url", url);
            o.put("duration", duration);
            o.put("image", imageUrl);
            o.put("short", shortDescription);
            if (rating != null) o.put("rating", rating);
            return o;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public static Movie fromJson(JSONObject o) {
        Movie m = new Movie();
        m.id = o.optString("id");
        m.title = o.optString("title");
        m.url = o.optString("url");
        m.duration = o.optString("duration");
        m.title = MovieTitle.clean(m.title, m.duration);
        m.imageUrl = o.optString("image");
        m.shortDescription = o.optString("short");
        if (o.has("rating")) m.rating = o.optDouble("rating");
        return m;
    }
}
