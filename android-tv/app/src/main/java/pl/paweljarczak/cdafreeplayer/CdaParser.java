package pl.paweljarczak.cdafreeplayer;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CdaParser {
    private static final Pattern VIDEO_ID=Pattern.compile("/video/([^/?#]+)");
    private static final Pattern RATING=Pattern.compile("(?<!\\d)([0-5](?:[.,]\\d{1,2})?)(?!\\d)");
    private static final Pattern CDA_FULL=Pattern.compile("(?i)(?<!\\d)([0-5](?:[.,]\\d{1,2})?)\\s*/\\s*5\\s*Oceny\\s*:\\s*(\\d+(?:[ .]\\d{3})*)");
    private static final Pattern IMDB=Pattern.compile("(?i)IMDb\\s*:\\s*(\\d+(?:[.,]\\d{1,2})?)\\s*/\\s*10\\s*Ilość\\s+głosów\\s*:\\s*(\\d+(?:[ .]\\d{3})*)");
    private CdaParser(){}

    public static SearchPage parseSearch(String html){
        SearchPage page=new SearchPage(); Document doc=Jsoup.parse(html); HashSet<String> seen=new HashSet<>();
        for(Element tile:doc.select("div.video-clip-wrapper")){
            page.raw++; Element a=tile.selectFirst("a.link-title-visit[href]"); if(a==null)continue;
            String href=a.absUrl("href"); if(href.isEmpty())href=a.attr("href"); if(href.startsWith("/"))href="https://www.cda.pl"+href;
            Matcher vm=VIDEO_ID.matcher(href); if(!vm.find()){page.nonVideo++;continue;} String id=vm.group(1); if(!seen.add(id))continue;
            if(isPremium(tile)){page.premium++;continue;}
            Movie m=new Movie(); m.id=id;m.url="https://www.cda.pl/video/"+id;m.title=a.text().trim();
            if(m.title.toLowerCase(Locale.ROOT).contains("premium")){page.premium++;continue;}
            Element img=tile.selectFirst("img.video-clip-image"); if(img!=null){String s=img.hasAttr("src")?img.attr("src"):img.attr("data-src"); if(s.startsWith("//"))s="https:"+s; m.imageUrl=s;}
            Element duration=tile.selectFirst("span.timeElem"); if(duration!=null)m.duration=duration.text().trim();
            m.shortDescription=tooltip(tile); m.rating=ratingFromTile(tile); page.movies.add(m);
        }
        return page;
    }

    private static boolean isPremium(Element tile){
        String text=tile.text().toLowerCase(Locale.ROOT); if(Pattern.compile("(?:^|[\\s|•·:/_-])premium(?:$|[\\s|•·:/_-])").matcher(text).find())return true;
        for(Element e:tile.getAllElements()){
            String cls=e.className().toLowerCase(Locale.ROOT); String id=e.id().toLowerCase(Locale.ROOT); if(cls.contains("premium")||id.contains("premium"))return true;
            for(String attr:new String[]{"data-premium","data-type","data-label","data-badge","aria-label","title"}) if(e.hasAttr(attr)&&e.attr(attr).toLowerCase(Locale.ROOT).contains("premium"))return true;
        }
        return false;
    }

    private static String tooltip(Element tile){
        for(Element e:tile.getAllElements()) for(String attr:new String[]{"data-description","title","onmouseover","onmouseenter"}) if(e.hasAttr(attr)){
            String v=e.attr(attr); if(v.length()<20)continue; Matcher m=Pattern.compile("(?is)overlib\\s*\\(\\s*['\"](.*?)['\"]").matcher(v); if(m.find())v=m.group(1);
            String cleaned=clean(v); if(cleaned.length()>20)return cleaned;
        }
        return "";
    }

    private static Double ratingFromTile(Element tile){
        for(String sel:new String[]{"[itemprop=ratingValue]","[data-rating]","[data-rate]",".rating",".rate",".rateMedVal",".rating-value"}){
            Element e=tile.selectFirst(sel); if(e==null)continue; String v=e.hasAttr("content")?e.attr("content"):e.hasAttr("data-rating")?e.attr("data-rating"):e.text(); Matcher m=RATING.matcher(v); if(m.find()){double d=parseDouble(m.group(1));if(d>=0&&d<=5)return d;}
        }
        Matcher m=Pattern.compile("(?i)(?:ocena|rating|rate)\\s*:?\\s*([0-5](?:[.,]\\d{1,2})?)|(?<!\\d)([0-5](?:[.,]\\d{1,2})?)\\s*/\\s*5").matcher(tile.text());
        if(m.find()){String s=m.group(1)!=null?m.group(1):m.group(2);double d=parseDouble(s);if(d>=0&&d<=5)return d;} return null;
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

    public static PlayerData parsePlayerData(String html){
        Document doc=Jsoup.parse(html); Element el=doc.selectFirst("div[id^=mediaplayer][player_data]"); if(el==null)return null; try{JSONObject root=new JSONObject(el.attr("player_data"));PlayerData p=new PlayerData();p.premium=root.optBoolean("premium",false);JSONObject v=root.optJSONObject("video");if(v==null)return p;p.type=v.optString("type","");p.dash=v.optString("manifest","");p.hls=v.optString("manifest_apple","");p.durationMs=v.optLong("duration",0L)*1000L;return p;}catch(Exception e){return null;}
    }

    private static String clean(String html){if(html==null)return "";String s=html.replace("\\n","\n").replace("\\r","").replace("\\t"," ").replace("\\u003C","<").replace("\\u003E",">");Document d=Jsoup.parseBodyFragment(s);d.select("script,style,noscript,button").remove();String t=d.body().wholeText();return t.replace('\u00a0',' ').replaceAll("[ \\t]+"," ").replaceAll("\\n{3,}","\\n\\n").trim();}
    private static double parseDouble(String s){try{return Double.parseDouble(s.replace(',','.'));}catch(Exception e){return -1;}}
    private static Integer parseInt(String s){if(s==null)return null;String d=s.replaceAll("\\D+","");if(d.isEmpty())return null;try{return Integer.parseInt(d);}catch(Exception e){return null;}}
}
