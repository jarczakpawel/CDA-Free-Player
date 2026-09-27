package pl.paweljarczak.cdafreeplayer;

import java.util.regex.Pattern;

public final class MovieTitle {
    private static final Pattern GENERIC = Pattern.compile("^\\s*(?:\\d{1,2}:)?\\d{1,2}:\\d{2}\\s+(?=\\S)");

    private MovieTitle() {}

    public static String clean(String title, String duration) {
        String value = title == null ? "" : title.replaceAll("\\s+", " ").trim();
        String time = duration == null ? "" : duration.trim();
        if (!time.isEmpty() && value.startsWith(time) && value.length() > time.length()) {
            String rest = value.substring(time.length()).replaceFirst("^[\\s|•·:;,_/\\-–—]+", "").trim();
            if (!rest.isEmpty()) return rest;
        }
        return GENERIC.matcher(value).replaceFirst("").trim();
    }
}
