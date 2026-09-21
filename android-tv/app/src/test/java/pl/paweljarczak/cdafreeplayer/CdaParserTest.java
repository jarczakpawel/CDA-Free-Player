package pl.paweljarczak.cdafreeplayer;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class CdaParserTest {
    @Test
    public void parsesDesktopSearchCard() {
        String html = "<html><body>" +
                "<div class='video-clip-wrapper'>" +
                "<a class='link-title-visit' href='/video/abc123'>Film testowy</a>" +
                "<img class='video-clip-image' data-src='//img.example/a.jpg'>" +
                "<span class='timeElem'>01:23:45</span>" +
                "</div></body></html>";
        SearchPage page = CdaParser.parseSearch(html);
        assertEquals(1, page.raw);
        assertEquals(1, page.movies.size());
        assertEquals("abc123", page.movies.get(0).id);
        assertEquals("Film testowy", page.movies.get(0).title);
    }

    @Test
    public void parsesMobileSearchCardFallback() {
        String html = "<html><body>" +
                "<article class='video-card'>" +
                "<a href='/video/xyz789'><img data-src='/thumb.jpg'></a>" +
                "<h3 class='video-title'><a href='/video/xyz789'>Film mobilny</a></h3>" +
                "<span class='duration'>42:01</span>" +
                "</article></body></html>";
        SearchPage page = CdaParser.parseSearch(html);
        assertEquals(1, page.raw);
        assertEquals(1, page.movies.size());
        assertEquals("xyz789", page.movies.get(0).id);
        assertEquals("Film mobilny", page.movies.get(0).title);
    }

    @Test
    public void premiumDetectionStaysCardLocal() {
        String html = "<html><body>" +
                "<div class='video-clip-wrapper'><span class='premium-badge'>Premium</span>" +
                "<a class='link-title-visit' href='/video/prem1'>Premium film</a></div>" +
                "<div class='video-clip-wrapper'>" +
                "<a class='link-title-visit' href='/video/free2'>Darmowy film</a></div>" +
                "</body></html>";
        SearchPage page = CdaParser.parseSearch(html);
        assertEquals(2, page.raw);
        assertEquals(1, page.premium);
        assertEquals(1, page.movies.size());
        assertEquals("free2", page.movies.get(0).id);
    }
}
