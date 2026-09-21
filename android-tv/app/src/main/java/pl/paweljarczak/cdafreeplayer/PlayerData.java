package pl.paweljarczak.cdafreeplayer;

import java.util.LinkedHashMap;
import java.util.Map;

public final class PlayerData {
    public boolean premium = false;
    public String type = "";
    public String dash = "";
    public String hls = "";
    public String direct = "";
    public String resolved = "";
    public String resolvedKind = "";
    public String hash2 = "";
    public long durationMs = 0;
    public Object ts = null;
    public final LinkedHashMap<String, Object> qualities = new LinkedHashMap<>();

    public boolean hasPlayableSource() {
        return !resolved.isEmpty() || !dash.isEmpty() || !hls.isEmpty() || !direct.isEmpty();
    }

    public boolean canResolveQuality() {
        return ts != null && ts != org.json.JSONObject.NULL && !String.valueOf(ts).isEmpty() && !hash2.isEmpty() && !qualities.isEmpty();
    }

    public PlayerData copy() {
        PlayerData p = new PlayerData();
        p.premium = premium;
        p.type = type;
        p.dash = dash;
        p.hls = hls;
        p.direct = direct;
        p.resolved = resolved;
        p.resolvedKind = resolvedKind;
        p.hash2 = hash2;
        p.durationMs = durationMs;
        p.ts = ts;
        for (Map.Entry<String, Object> e : qualities.entrySet()) p.qualities.put(e.getKey(), e.getValue());
        return p;
    }
}
