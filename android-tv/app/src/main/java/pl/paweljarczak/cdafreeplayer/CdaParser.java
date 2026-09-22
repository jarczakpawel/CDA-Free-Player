package pl.paweljarczak.cdafreeplayer;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.jsoup.select.Elements;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CdaParser {
    public static final String PLAYER_DATA_PREFIX = "__CDA_PLAYER_DATA__";
    private static final Pattern VIDEO_ID = Pattern.compile("/video/([^/?#]+)");
    private static final Pattern RATING = Pattern.compile("(?<!\\d)([0-5](?:[.,]\\d{1,2})?)(?!\\d)");
    private static final Pattern CDA_FULL = Pattern.compile("(?i)(?<!\\d)([0-5](?:[.,]\\d{1,2})?)\\s*/\\s*5\\s*Oceny\\s*:\\s*(\\d+(?:[ .]\\d{3})*)");
    private static final Pattern IMDB = Pattern.compile("(?i)IMDb\\s*:\\s*(\\d+(?:[.,]\\d{1,2})?)\\s*/\\s*10\\s*Ilość\\s+głosów\\s*:\\s*(\\d+(?:[ .]\\d{3})*)");

    private CdaParser() {}

    public static SearchPage parseSearch(String html) {
        SearchPage page = new SearchPage();
        Document doc = Jsoup.parse(html == null ? "" : html);
        HashSet<String> seen = new HashSet<>();

        Elements tiles = doc.select("div.video-clip-wrapper");
        if (!tiles.isEmpty()) {
            for (Element tile : tiles) {
                page.raw++;
                Element a = tile.selectFirst("a.link-title-visit[href]");
                if (a == null) a = bestVideoAnchor(tile);
                if (a == null) continue;
                addCandidate(page, seen, tile, a);
            }
            return page;
        }

        for (Element a : doc.select("a[href]")) {
            String href = normalizeHref(a.attr("href"));
            Matcher vm = VIDEO_ID.matcher(href);
            if (!vm.find()) continue;
            String id = vm.group(1);
            if (!validVideoId(id) || seen.contains(id)) continue;

            Element tile = closestResultContainer(a);
            String title = candidateTitle(a, tile);
            if (title.isEmpty()) continue; // image-only duplicate; title link may follow

            page.raw++;
            addCandidate(page, seen, tile, a);
        }
        return page;
    }

    private static void addCandidate(SearchPage page, HashSet<String> seen, Element tile, Element a) {
        String href = normalizeHref(a.attr("href"));
        Matcher vm = VIDEO_ID.matcher(href);
        if (!vm.find()) {
            page.nonVideo++;
            return;
        }
        String id = vm.group(1);
        if (!validVideoId(id) || !seen.add(id)) return;

        String title = candidateTitle(a, tile);
        if (title.isEmpty()) return;
        if (isPremium(tile) || title.toLowerCase(Locale.ROOT).contains("premium")) {
            page.premium++;
            return;
        }

        Movie m = new Movie();
        m.id = id;
        m.url = "https://www.cda.pl/video/" + id;
        m.duration = durationFromTile(tile);
        m.title = MovieTitle.clean(title, m.duration);
        m.imageUrl = imageFromTile(tile);
        m.shortDescription = tooltip(tile);
        m.rating = ratingFromTile(tile);
        page.movies.add(m);
    }

    private static Element bestVideoAnchor(Element tile) {
        for (Element a : tile.select("a[href]")) {
            String href = normalizeHref(a.attr("href"));
            Matcher m = VIDEO_ID.matcher(href);
            if (!m.find() || !validVideoId(m.group(1))) continue;
            if (!candidateTitle(a, tile).isEmpty()) return a;
        }
        return null;
    }

    private static boolean validVideoId(String id) {
        if (id == null || id.isEmpty()) return false;
        String x = id.toLowerCase(Locale.ROOT);
        return !x.equals("show") && !x.equals("upload") && !x.equals("add") && !x.equals("search");
    }

    private static String normalizeHref(String href) {
        if (href == null) return "";
        String out = href.trim();
        if (out.startsWith("//")) out = "https:" + out;
        else if (out.startsWith("/")) out = "https://www.cda.pl" + out;
        return out;
    }

    private static Element closestResultContainer(Element a) {
        Element fallback = a.parent() != null ? a.parent() : a;
        Element cur = a.parent();
        for (int depth = 0; cur != null && depth < 7; depth++, cur = cur.parent()) {
            String cls = cur.className().toLowerCase(Locale.ROOT);
            String tag = cur.tagName().toLowerCase(Locale.ROOT);
            boolean cardish = cls.contains("video-clip-wrapper") || cls.contains("video-card") ||
                    cls.contains("video-item") || cls.contains("video-tile") ||
                    cls.contains("clip-item") || cls.contains("clip-wrapper") ||
                    "article".equals(tag) || "li".equals(tag);
            if (cardish) return cur;

            int videoLinks = 0;
            for (Element x : cur.select("a[href]")) {
                Matcher m = VIDEO_ID.matcher(normalizeHref(x.attr("href")));
                if (m.find() && validVideoId(m.group(1))) videoLinks++;
                if (videoLinks > 3) break;
            }
            if (videoLinks > 0 && videoLinks <= 3 && !cur.select("img").isEmpty()) fallback = cur;
        }
        return fallback;
    }

    private static String candidateTitle(Element a, Element tile) {
        String title = a == null ? "" : a.text().trim();
        if (title.isEmpty() && a != null) title = a.attr("title").trim();
        if (title.isEmpty() && a != null) title = a.attr("aria-label").trim();
        if (title.isEmpty() && tile != null) {
            Element t = tile.selectFirst("a.link-title-visit[href], [class*=title] a[href], [class*=title]");
            if (t != null) {
                title = t.text().trim();
                if (title.isEmpty()) title = t.attr("title").trim();
            }
        }
        return title.replaceAll("\\s+", " ").trim();
    }

    private static String imageFromTile(Element tile) {
        if (tile == null) return "";
        Element img = tile.selectFirst("img.video-clip-image, img[data-src], img[data-original], img[src], img[srcset]");
        if (img == null) return "";
        String s = firstNonEmpty(img.attr("data-src"), img.attr("data-original"), img.attr("src"));
        if (s.isEmpty() && img.hasAttr("srcset")) {
            String first = img.attr("srcset").split(",", 2)[0].trim();
            if (!first.isEmpty()) s = first.split("\\s+", 2)[0];
        }
        if (s.startsWith("//")) s = "https:" + s;
        else if (s.startsWith("/")) s = "https://www.cda.pl" + s;
        return s;
    }

    private static String durationFromTile(Element tile) {
        if (tile == null) return "";
        for (String sel : new String[]{"span.timeElem", ".timeElem", "[class*=duration]", "[class*=time]"}) {
            Element e = tile.selectFirst(sel);
            if (e == null) continue;
            String t = e.text().trim();
            if (t.matches(".*\\d{1,2}:\\d{2}.*")) return t;
        }
        return "";
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) if (value != null && !value.trim().isEmpty()) return value.trim();
        return "";
    }

    private static boolean isPremium(Element tile) {
        if (tile == null) return false;
        if (tile.outerHtml().toLowerCase(Locale.ROOT).contains("/vid/premium/")) return true;
        for (Element image : tile.select("img[src],img[data-src],img[data-original],source[src],source[srcset]")) {
            for (String attr : new String[]{"src", "data-src", "data-original", "srcset"}) {
                if (image.hasAttr(attr) && image.attr(attr).toLowerCase(Locale.ROOT).contains("/vid/premium/")) return true;
            }
        }
        String text = tile.text().toLowerCase(Locale.ROOT);
        if (Pattern.compile("(?:^|[\\s|•·:/_-])premium(?:$|[\\s|•·:/_-])").matcher(text).find()) return true;
        for (Element e : tile.getAllElements()) {
            String cls = e.className().toLowerCase(Locale.ROOT);
            String id = e.id().toLowerCase(Locale.ROOT);
            if (cls.contains("premium") || id.contains("premium")) return true;
            if ("true".equalsIgnoreCase(e.attr("data-premium")) || "1".equals(e.attr("data-premium"))) return true;
            for (String attr : new String[]{"data-premium", "data-type", "data-label", "data-badge", "aria-label", "title"}) {
                if (e.hasAttr(attr) && e.attr(attr).toLowerCase(Locale.ROOT).contains("premium")) return true;
            }
        }
        return false;
    }

    private static String tooltip(Element tile) {
        if (tile == null) return "";
        for (Element e : tile.getAllElements()) for (String attr : new String[]{"data-description", "title", "onmouseover", "onmouseenter"}) if (e.hasAttr(attr)) {
            String v = e.attr(attr);
            if (v.length() < 20) continue;
            Matcher m = Pattern.compile("(?is)overlib\\s*\\(\\s*['\"](.*?)['\"]").matcher(v);
            if (m.find()) v = m.group(1);
            String cleaned = clean(v);
            if (cleaned.length() > 20) return cleaned;
        }
        return "";
    }

    private static Double ratingFromTile(Element tile) {
        if (tile == null) return null;
        for (String sel : new String[]{"[itemprop=ratingValue]", "[data-rating]", "[data-rate]", ".rating", ".rate", ".rateMedVal", ".rating-value"}) {
            Element e = tile.selectFirst(sel);
            if (e == null) continue;
            String v = e.hasAttr("content") ? e.attr("content") : e.hasAttr("data-rating") ? e.attr("data-rating") : e.text();
            Matcher m = RATING.matcher(v);
            if (m.find()) {
                double d = parseDouble(m.group(1));
                if (d >= 0 && d <= 5) return d;
            }
        }
        Matcher m = Pattern.compile("(?i)(?:ocena|rating|rate)\\s*:?\\s*([0-5](?:[.,]\\d{1,2})?)|(?<!\\d)([0-5](?:[.,]\\d{1,2})?)\\s*/\\s*5").matcher(tile.text());
        if (m.find()) {
            String s = m.group(1) != null ? m.group(1) : m.group(2);
            double d = parseDouble(s);
            if (d >= 0 && d <= 5) return d;
        }
        return null;
    }

    public static MovieMetadata parseMetadata(String html){
        MovieMetadata md=new MovieMetadata(); Document doc=Jsoup.parse(html); Element d=doc.selectFirst("[itemprop=description]"); if(d==null)d=doc.selectFirst("meta[itemprop=description][content]"); if(d==null)d=doc.selectFirst("meta[property=og:description][content]"); if(d!=null)md.description=clean(d.tagName().equals("meta")?d.attr("content"):d.html());
        String plain=doc.text().replaceAll("\\s+"," "); Matcher c=CDA_FULL.matcher(plain); if(c.find()){md.rating=parseDouble(c.group(1));md.cdaVotes=parseInt(c.group(2));}
        Matcher im=IMDB.matcher(plain); if(im.find()){md.imdbRating=String.format(Locale.US,"%.1f",parseDouble(im.group(1)));md.imdbVotes=parseInt(im.group(2));}
        for(String sel:new String[]{"[data-comments-count]",".comments-count","#comments-count"}){Element e=doc.selectFirst(sel);if(e!=null){Integer x=parseInt(e.hasAttr("data-comments-count")?e.attr("data-comments-count"):e.text());if(x!=null){md.commentCount=x;break;}}}
        if(md.commentCount==null){Element box=doc.selectFirst(".comments-container");if(box==null)box=doc.selectFirst("#cdaComments");if(box!=null){HashSet<String> ids=new HashSet<>();int n=0;for(Element e:box.select(".komentarz.comment,.komentarz,div.comment[id]")){String key=e.id().isEmpty()?e.cssSelector():e.id();if(ids.add(key))n++;}md.commentCount=n;}}
        return md;
    }

    public static java.util.ArrayList<CommentItem> parseComments(String html){
        java.util.ArrayList<CommentItem> out=new java.util.ArrayList<>(); Document doc=Jsoup.parse(html); Element box=doc.selectFirst(".comments-container"); if(box==null)box=doc.selectFirst("#cdaComments"); if(box==null)return out; HashSet<String> seen=new HashSet<>();
        for(Element n:box.select(".komentarz.comment,.komentarz,div.comment[id]")){String key=n.id().isEmpty()?n.cssSelector():n.id();if(!seen.add(key))continue; Element body=n.selectFirst(".tresc,.commentText,.comment-body");if(body==null)continue; CommentItem c=new CommentItem(); Element a=n.selectFirst(".commentHeader .anonim,.commentHeader a,.commentAuthor,.user-name");Element date=n.selectFirst(".commentDate1,.commentDate,time");Element score=n.selectFirst(".commentRate"); c.author=a==null?"anonim":a.text().trim();c.date=date==null?"":date.text().trim();c.score=score==null?"":score.text().trim();c.text=clean(body.html());if(!c.text.isEmpty())out.add(c);if(out.size()>=60)break;}
        return out;
    }

    public static PlayerData parsePlayerData(String html) {
        Document doc = Jsoup.parse(html == null ? "" : html);
        try {
            JSONObject root = findPlayerRoot(html, doc);
            if (root == null) return null;
            PlayerData p = new PlayerData();
            JSONObject v = root.optJSONObject("video");
            if (v == null) return null;
            p.type = v.optString("type", "");
            p.dash = normalizeStreamUrl(v.optString("manifest", ""));
            p.hls = normalizeStreamUrl(v.optString("manifest_apple", ""));
            p.direct = normalizeStreamUrl(v.optString("file", ""));
            p.hash2 = v.optString("hash2", "");
            p.durationMs = Math.max(0L, v.optLong("duration", 0L)) * 1000L;

            p.ts = normalizeTs(v.opt("ts"));
            if (p.ts == null) {
                JSONObject api = root.optJSONObject("api");
                if (api != null) p.ts = normalizeTs(api.opt("ts"));
            }

            JSONObject qualities = v.optJSONObject("qualities");
            if (qualities != null) {
                Iterator<String> keys = qualities.keys();
                while (keys.hasNext()) {
                    String label = keys.next();
                    Object value = qualities.opt(label);
                    if (value != null && value != JSONObject.NULL && !String.valueOf(value).trim().isEmpty())
                        p.qualities.put(label, value);
                }
            }
            return p;
        } catch (Exception e) {
            return null;
        }
    }

    private static JSONObject findPlayerRoot(String html, Document doc) {
        String rawHtml = html == null ? "" : html;

        if (rawHtml.startsWith(PLAYER_DATA_PREFIX)) {
            JSONObject direct = parsePlayerJson(rawHtml.substring(PLAYER_DATA_PREFIX.length()));
            if (direct != null) return direct;
        }

        for (String selector : new String[]{
                "div[id^=mediaplayer][player_data]", "[player_data]",
                "[data-player-data]", "[data-player_data]"}) {
            for (Element el : doc.select(selector)) {
            for (String attr : new String[]{"player_data", "data-player-data", "data-player_data"}) {
                String raw = el.attr(attr);
                if (raw == null || raw.trim().isEmpty()) continue;
                JSONObject parsed = parsePlayerJson(raw);
                if (parsed != null) return parsed;
            }
            }
        }

        for (String marker : new String[]{"player_data", "playerData", "\\\"player_data\\\""}) {
            int from = 0;
            while (from < rawHtml.length()) {
                int at = rawHtml.indexOf(marker, from);
                if (at < 0) break;
                int sep = findAssignmentSeparator(rawHtml, at + marker.length());
                if (sep >= 0) {
                    int brace = skipToObject(rawHtml, sep + 1);
                    if (brace >= 0) {
                        String object = balancedJsonObject(rawHtml, brace);
                        if (object != null) {
                            JSONObject parsed = parsePlayerJson(object);
                            if (parsed != null) return parsed;
                        }
                    }
                }
                from = at + marker.length();
            }
        }
        return null;
    }


    private static JSONObject parsePlayerJson(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.isEmpty()) return null;
        try {
            JSONObject parsed = new JSONObject(value);
            if (parsed.optJSONObject("video") != null) return parsed;
        } catch (Exception ignored) {}
        try {
            String unescaped = Parser.unescapeEntities(value, false).trim();
            if (!unescaped.equals(value)) {
                JSONObject parsed = new JSONObject(unescaped);
                if (parsed.optJSONObject("video") != null) return parsed;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static int findAssignmentSeparator(String s, int from) {
        int limit = Math.min(s.length(), from + 96);
        for (int i = from; i < limit; i++) {
            char c = s.charAt(i);
            if (c == '=' || c == ':') return i;
            if (c == ';' || c == '<' || c == '>') break;
        }
        return -1;
    }

    private static int skipToObject(String s, int from) {
        int limit = Math.min(s.length(), from + 48);
        for (int i = from; i < limit; i++) {
            char c = s.charAt(i);
            if (c == '{') return i;
            if (!Character.isWhitespace(c) && c != '(') return -1;
        }
        return -1;
    }

    private static String balancedJsonObject(String s, int start) {
        int depth = 0;
        boolean quoted = false, escaped = false;
        char quote = 0;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == quote) quoted = false;
                continue;
            }
            if (c == '"' || c == '\'') { quoted = true; quote = c; continue; }
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return s.substring(start, i + 1);
        }
        return null;
    }

    private static Object normalizeTs(Object value) {
        if (value == null || value == JSONObject.NULL) return null;
        if (value instanceof Number) return ((Number) value).longValue();
        String s = String.valueOf(value).trim();
        if (s.isEmpty()) return null;
        int cut = s.indexOf('_');
        String prefix = cut > 0 ? s.substring(0, cut) : s;
        try { return Long.parseLong(prefix); } catch (Exception ignored) { return value; }
    }

    private static String normalizeStreamUrl(String url) {
        if (url == null) return "";
        String out = url.trim();
        if (out.startsWith("//")) return "https:" + out;
        return out.startsWith("https://") || out.startsWith("http://") ? out : "";
    }

    private static String clean(String html){if(html==null)return "";String s=html.replace("\\n","\n").replace("\\r","").replace("\\t"," ").replace("\\u003C","<").replace("\\u003E",">");Document d=Jsoup.parseBodyFragment(s);d.select("script,style,noscript,button").remove();String t=d.body().wholeText();return t.replace('\u00a0',' ').replaceAll("[ \\t]+"," ").replaceAll("\\n{3,}","\\n\\n").trim();}
    private static double parseDouble(String s){try{return Double.parseDouble(s.replace(',','.'));}catch(Exception e){return -1;}}
    private static Integer parseInt(String s){if(s==null)return null;String d=s.replaceAll("\\D+","");if(d.isEmpty())return null;try{return Integer.parseInt(d);}catch(Exception e){return null;}}
}
