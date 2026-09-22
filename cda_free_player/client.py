import html as htmlmod
import json
import logging
import os
from logging.handlers import RotatingFileHandler
import re
import threading
import tempfile
import time
from datetime import datetime
from urllib.parse import quote, unquote

import httpx
from bs4 import BeautifulSoup

from .config import BASE, PROFILE_DIR, SESSION_FILE, LOG_FILE


logger = logging.getLogger("cda-free-player")
logger.setLevel(logging.INFO)
if not logger.handlers:
    handler = RotatingFileHandler(
        LOG_FILE,
        maxBytes=5 * 1024 * 1024,
        backupCount=3,
        encoding="utf-8",
    )
    handler.setFormatter(logging.Formatter("%(message)s"))
    logger.addHandler(handler)


def log_event(event, **data):
    logger.info(json.dumps({
        "ts": datetime.now().isoformat(timespec="milliseconds"),
        "event": event,
        **data,
    }, ensure_ascii=False, default=str))




def is_challenge(text, status=200):
    value = (text or "").lower()
    return (
        status in (403, 429, 503)
        or "przeprowadzanie weryfikacji zabezpieczeń" in value
        or "checking if you are not a bot" in value
        or "/cdn-cgi/challenge-platform/" in value
        or "cf-chl-" in value
    )


def video_id(url):
    match = re.search(r"/video/([^/?#]+)", url or "")
    return match.group(1) if match else ""


def clean_description(value):
    if not value:
        return ""

    text = str(value)

    for _ in range(3):
        previous = text
        text = htmlmod.unescape(text)
        if text == previous:
            break

    text = (
        text.replace("\\/", "/")
        .replace("\\r", "")
        .replace("\\n", "\n")
        .replace("\\t", " ")
        .replace("\\u003C", "<")
        .replace("\\u003E", ">")
        .replace("\\u0026", "&")
    )

    if re.search(r"%[0-9A-Fa-f]{2}", text):
        try:
            decoded = unquote(text)
            if decoded:
                text = decoded
        except Exception:
            pass

    text = re.sub(
        r"(?i)<br\s*/?>",
        "\n",
        text,
    )
    text = re.sub(
        r"(?i)</(?:p|div|li|tr|h[1-6])\s*>",
        "\n",
        text,
    )
    text = re.sub(
        r"(?i)<li[^>]*>",
        "• ",
        text,
    )

    soup = BeautifulSoup(
        text,
        "html.parser",
    )

    for node in soup(
        [
            "script",
            "style",
            "noscript",
        ]
    ):
        node.decompose()

    text = soup.get_text(
        "\n",
        strip=True,
    )

    lines = []

    for line in text.splitlines():
        line = re.sub(
            r"[ \t\xa0]+",
            " ",
            line,
        ).strip()

        if not line:
            if lines and lines[-1] != "":
                lines.append("")
            continue

        lines.append(line)

    while lines and lines[-1] == "":
        lines.pop()

    return "\n".join(lines)


def decode_overlib(value):
    if not value:
        return ""

    match = re.search(
        r"overlib\s*\(\s*(['\"])((?:\\.|(?!\1).)*)\1",
        value,
        re.S | re.I,
    )

    if not match:
        return ""

    return clean_description(
        match.group(2),
    )


def tooltip_from_tile(tile):
    for node in [
        tile,
        *tile.find_all(True),
    ]:
        for key in (
            "onmouseover",
            "onmouseenter",
            "data-overlib",
            "data-description",
            "title",
        ):
            value = node.get(key)

            if not value:
                continue

            text = (
                decode_overlib(value)
                if "overlib" in value.lower()
                else clean_description(value)
            )

            if text and len(text) > 20:
                return text

    return ""


def rating_from_tile(tile):
                                           
    for selector in (
        '[itemprop="ratingValue"]',
        '[data-rating]',
        '[data-rate]',
        ".rating",
        ".rate",
        ".rateMedVal",
        ".rating-value",
    ):
        node = tile.select_one(
            selector
        )

        if not node:
            continue

        value = (
            node.get("content")
            or node.get("data-rating")
            or node.get("data-rate")
            or node.get_text(
                " ",
                strip=True,
            )
        )

        match = re.search(
            r"(?<!\d)([0-5](?:[.,]\d{1,2})?)(?!\d)",
            value or "",
        )

        if match:
            rating = float(
                match.group(1)
                .replace(",", ".")
            )

            if 0.0 <= rating <= 5.0:
                return (
                    f"{rating:.1f}"
                )

                                                 
                                              
    text = " ".join(
        tile.stripped_strings
    )

    patterns = (
        r"(?i)(?:ocena|rating|rate)\s*:?\s*([0-5](?:[.,]\d{1,2})?)",
        r"(?<!\d)([0-5](?:[.,]\d{1,2})?)\s*/\s*5(?:\D|$)",
    )

    for pattern in patterns:
        match = re.search(
            pattern,
            text,
        )

        if match:
            rating = float(
                match.group(1)
                .replace(",", ".")
            )

            if 0.0 <= rating <= 5.0:
                return (
                    f"{rating:.1f}"
                )

    return ""


def premium_marker(tile):
    if tile is None:
        return None

                
                                                                               
                                                                           
                                                                     
    text = " ".join(
        tile.stripped_strings
    )

    if re.search(
        r"(?i)(?:^|[\s|•·:/_-])premium(?:$|[\s|•·:/_-])",
        text,
    ):
        return "tile-text"

    for node in [
        tile,
        *tile.find_all(True),
    ]:
        classes = " ".join(
            node.get("class", [])
        ).lower()

        node_id = str(
            node.get("id", "")
        ).lower()

        if str(node.get("data-premium", "")).lower() in ("true", "1"):
            return "data-premium"

        if "premium" in classes:
            return "class"

        if "premium" in node_id:
            return "id"

        for name in (
            "data-premium",
            "data-type",
            "data-label",
            "data-badge",
            "aria-label",
            "title",
        ):
            value = node.get(name)

            if value is None:
                continue

            if re.search(
                r"(?i)(?:^|[\s|•·:/_-])premium(?:$|[\s|•·:/_-])",
                str(value),
            ):
                return f"attr:{name}"

                                                                            
                                                                           
                                                       
        node_classes = set(
            c.lower()
            for c in node.get("class", [])
        )

        badge_like = any(
            marker in " ".join(node_classes)
            for marker in (
                "badge",
                "label",
                "premium",
                "type",
                "quality",
            )
        )

        if badge_like:
            for name in ("href", "src"):
                value = str(
                    node.get(name, "")
                ).lower()

                if "premium" in value:
                    return f"badge-{name}"

    return None


def parse_results(page_html):
    soup = BeautifulSoup(
        page_html,
        "html.parser",
    )

    videos = []
    seen = set()
    raw = premium = nonvideo = 0
    premium_reasons = {}

    for tile in soup.select(
        "div.video-clip-wrapper"
    ):
        raw += 1

        anchor = tile.select_one(
            "a.link-title-visit[href]"
        )

        if not anchor:
            continue

        href = anchor.get(
            "href",
            "",
        )

        if href.startswith("/"):
            href = BASE + href

        vid = video_id(href)

        if not vid:
            nonvideo += 1
            continue

        if vid in seen:
            continue

                                                            
                                                                    
        context = tile

        reason = premium_marker(
            tile,
        )

        if reason:
            premium += 1
            premium_reasons[reason] = (
                premium_reasons.get(
                    reason,
                    0,
                )
                + 1
            )
            continue

        title = anchor.get_text(
            " ",
            strip=True,
        )

        if re.search(
            r"(?i)\bpremium\b",
            title,
        ):
            premium += 1
            premium_reasons[
                "title-fail-closed"
            ] = (
                premium_reasons.get(
                    "title-fail-closed",
                    0,
                )
                + 1
            )
            continue

        seen.add(vid)

        image = tile.select_one(
            "img.video-clip-image"
        )

        image_url = ""

        if image:
            image_url = (
                image.get("data-src")
                or image.get("src")
                or ""
            )

            if image_url.startswith("//"):
                image_url = (
                    "https:"
                    + image_url
                )

        duration = tile.select_one(
            "span.timeElem"
        )

        videos.append({
            "id": vid,
            "title": title,
            "url": (
                f"{BASE}/video/{vid}"
            ),
            "duration": (
                duration.get_text(
                    " ",
                    strip=True,
                )
                if duration
                else ""
            ),
            "image": image_url,
            "short_description": (
                tooltip_from_tile(
                    context,
                )
            ),
            "short_rating": (
                rating_from_tile(
                    context,
                )
            ),
        })

    return videos, {
        "raw": raw,
        "free": len(videos),
        "premium": premium,
        "nonvideo": nonvideo,
        "premium_reasons": premium_reasons,
    }


def parse_player_data(page_html):
    text = page_html or ""
    candidates = []
    if text.startswith("__CDA_PLAYER_DATA__"):
        candidates.append(text[len("__CDA_PLAYER_DATA__"):])
    else:
        soup = BeautifulSoup(text, "html.parser")
        for node in soup.select("[player_data],[data-player-data],[data-player_data]"):
            candidates.extend(node.get(key) for key in ("player_data", "data-player-data", "data-player_data") if node.get(key))
        decoder = json.JSONDecoder()
        for match in re.finditer(r'(?:player_data|playerData)\s*[=:]\s*(\{)', text):
            try:
                data, _ = decoder.raw_decode(text[match.start(1):])
                if isinstance(data, dict) and isinstance(data.get("video"), dict):
                    candidates.append(json.dumps(data))
            except ValueError:
                pass
    for raw in candidates:
        for value in (raw, htmlmod.unescape(raw)):
            try:
                data = json.loads(value)
                if isinstance(data, dict) and isinstance(data.get("video"), dict):
                    return data
            except (ValueError, TypeError):
                pass
    return None


def _integer_from_text(value):
    if value is None:
        return None
    digits = re.sub(r"\D+", "", str(value))
    if not digits:
        return None
    try:
        return int(digits)
    except Exception:
        return None


def parse_metadata(page_html):
    soup = BeautifulSoup(
        page_html,
        "html.parser",
    )

    description = ""

    for selector, attr in (
        (
            '[itemprop="description"]',
            None,
        ),
        (
            'meta[itemprop="description"][content]',
            "content",
        ),
        (
            'meta[property="og:description"][content]',
            "content",
        ),
    ):
        node = soup.select_one(
            selector
        )

        if not node:
            continue

        description = (
            node.get(
                attr,
                "",
            )
            if attr
            else str(node)
        )

        description = clean_description(
            description
        )

        if description:
            break

    plain = re.sub(
        r"\s+",
        " ",
        soup.get_text(
            " ",
            strip=True,
        ),
    )

    rating = ""
    cda_votes = None
    imdb_rating = ""
    imdb_votes = None

    cda_match = re.search(
        r"(?i)(?<!\d)"
        r"([0-5](?:[.,]\d{1,2})?)"
        r"\s*/\s*5"
        r"\s*Oceny\s*:\s*"
        r"(\d+(?:[ .]\d{3})*)",
        plain,
    )

    if cda_match:
        value_num = float(
            cda_match.group(1)
            .replace(",", ".")
        )
        rating = f"{value_num:.1f}"
        cda_votes = _integer_from_text(
            cda_match.group(2)
        )

    if not rating:
        for selector, attr in (
            (
                'meta[itemprop="ratingValue"][content]',
                "content",
            ),
            (
                '[itemprop="ratingValue"]',
                None,
            ),
            (
                '[data-rating]',
                "data-rating",
            ),
            (
                'span.rating',
                None,
            ),
        ):
            node = soup.select_one(
                selector
            )

            if not node:
                continue

            value = (
                node.get(
                    attr,
                    "",
                )
                if attr
                else node.get_text(
                    " ",
                    strip=True,
                )
            )

            match = re.search(
                r"(?<!\d)"
                r"([0-5](?:[.,]\d{1,2})?)"
                r"(?!\d)",
                value or "",
            )

            if match:
                value_num = float(
                    match.group(1)
                    .replace(",", ".")
                )

                if (
                    0
                    <= value_num
                    <= 5
                ):
                    rating = (
                        f"{value_num:.1f}"
                    )
                    break

    imdb_match = re.search(
        r"(?i)IMDb\s*:\s*"
        r"(\d+(?:[.,]\d{1,2})?)"
        r"\s*/\s*10"
        r"\s*Ilość\s+głosów\s*:\s*"
        r"(\d+(?:[ .]\d{3})*)",
        plain,
    )

    if imdb_match:
        value_num = float(
            imdb_match.group(1)
            .replace(",", ".")
        )

        if (
            0
            <= value_num
            <= 10
        ):
            imdb_rating = (
                f"{value_num:.1f}"
            )

        imdb_votes = _integer_from_text(
            imdb_match.group(2)
        )

                                                                  
                                                                    
    comment_count = None

    for selector in (
        "[data-comments-count]",
        ".comments-count",
        "#comments-count",
    ):
        node = soup.select_one(
            selector
        )

        if not node:
            continue

        raw = (
            node.get(
                "data-comments-count"
            )
            or node.get_text(
                " ",
                strip=True,
            )
        )

        count = _integer_from_text(
            raw
        )

        if count is not None:
            comment_count = count
            break

    if comment_count is None:
        container = soup.select_one(
            ".comments-container"
        )

        if container is None:
            root = soup.select_one(
                "#cdaComments"
            )

            if root is not None:
                container = (
                    root.select_one(
                        ".comments-container"
                    )
                    or root
                )

        if container is not None:
            seen = set()
            count = 0

            for node in container.select(
                ".komentarz.comment, "
                ".komentarz, "
                "div.comment[id]"
            ):
                ident = (
                    node.get("id")
                    or str(id(node))
                )

                if ident in seen:
                    continue

                seen.add(ident)
                count += 1

            comment_count = count

    author = ""

    for selector in (
        '[itemprop="author"] [itemprop="name"]',
        'span.color-link-primary',
        'a[itemprop="author"]',
    ):
        node = soup.select_one(
            selector
        )

        if node:
            author = (
                node.get("content")
                or node.get_text(
                    " ",
                    strip=True,
                )
            )

            if author:
                break

    date_added = ""

    for selector, attr in (
        (
            'meta[itemprop="uploadDate"][content]',
            "content",
        ),
        (
            'time[itemprop="uploadDate"][datetime]',
            "datetime",
        ),
        (
            'meta[property="video:release_date"][content]',
            "content",
        ),
    ):
        node = soup.select_one(
            selector
        )

        if node:
            date_added = node.get(
                attr,
                "",
            ).strip()

            if date_added:
                break

    pdata = parse_player_data(
        page_html
    )

    return {
        "description": description,
        "rating": rating,
        "cda_votes": cda_votes,
        "imdb_rating": imdb_rating,
        "imdb_votes": imdb_votes,
        "comment_count": comment_count,
        "author": author,
        "date_added": date_added,
    }, pdata


def parse_comments(page_html, limit=60):
    soup = BeautifulSoup(page_html, "html.parser")
    container = soup.select_one(".comments-container")
    if container is None:
        root = soup.select_one("#cdaComments")
        if root is not None:
            container = root.select_one(".comments-container") or root
    if container is None:
        return []
    result = []
    seen = set()
    nodes = container.select(".komentarz.comment, .komentarz, div.comment[id]")
    for node in nodes:
        ident = node.get("id") or str(id(node))
        if ident in seen:
            continue
        seen.add(ident)
        author_node = node.select_one(".commentHeader .anonim, .commentHeader a, .commentAuthor, .user-name")
        date_node = node.select_one(".commentDate1, .commentDate, time")
        rate_node = node.select_one(".commentRate")
        text_node = node.select_one(".tresc, .commentText, .comment-body")
        if text_node is None:
            continue
        clone = BeautifulSoup(str(text_node), "html.parser")
        for bad in clone.select(".ansComment, script, style, .reply, button"):
            bad.decompose()
        text = clean_description(str(clone))
        if not text:
            continue
        result.append({
            "author": clean_description(str(author_node)) if author_node else "anonim",
            "date": date_node.get_text(" ", strip=True) if date_node else "",
            "rate": (
                rate_node.get_text(
                    " ",
                    strip=True,
                )
                if rate_node
                else ""
            ),
            "text": text,
        })
        if len(result) >= limit:
            break
    return result


class SearchCancelled(Exception):
    pass


class CdaClient:
    def __init__(self, events):
        self.events = events
                                                                        
                                                                            
                                                         
        self.browser_lock = threading.Lock()
        self._native_webview = None

    def native_webview(self):
        with self.browser_lock:
            if self._native_webview is None:
                from .native_webview import NativeWebViewSession
                self._native_webview = NativeWebViewSession(self.events)
            return self._native_webview

    def load_session(self):
        if not SESSION_FILE.exists():
            return None

        try:
            session = json.loads(SESSION_FILE.read_text(encoding="utf-8"))
            if not isinstance(session, dict) or not isinstance(session.get("user_agent"), str) or not isinstance(session.get("cookies"), list):
                return None
            return session
        except Exception as exc:
            log_event("session_load_error", error=str(exc))
            return None

    def save_session(self, cookies, user_agent):
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=SESSION_FILE.parent, delete=False) as out:
            temporary = out.name
            json.dump({"user_agent": user_agent, "cookies": cookies}, out, ensure_ascii=False)
        try:
            os.replace(temporary, SESSION_FILE)
        finally:
            if os.path.exists(temporary): os.unlink(temporary)
        log_event(
            "session_saved",
            cookie_names=sorted({c.get("name", "") for c in cookies}),
        )

    def http_client(self):
        session = self.load_session()
        if not session:
            return None

        client = httpx.Client(
            headers={
                "User-Agent": session["user_agent"],
                "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                "Accept-Language": "pl-PL,pl;q=0.9,en;q=0.7",
                "Upgrade-Insecure-Requests": "1",
            },
            follow_redirects=True,
            timeout=20.0,
            http2=True,
        )

        for cookie in session["cookies"]:
            client.cookies.set(
                cookie["name"],
                cookie["value"],
                domain=cookie.get("domain") or ".cda.pl",
                path=cookie.get("path") or "/",
            )

        return client

    @staticmethod
    def _check_cancel(cancel_event):
        if (
            cancel_event is not None
            and cancel_event.is_set()
        ):
            raise SearchCancelled(
                "Wyszukiwanie anulowane."
            )

    def get_html(
        self,
        url,
        allow_browser,
        cancel_event=None,
        expect_player=False,
    ):
        self._check_cancel(
            cancel_event
        )

        client = self.http_client()

        if client:
            started = time.monotonic()

            try:
                self._check_cancel(
                    cancel_event
                )

                response = client.get(
                    url
                )

                self._check_cancel(
                    cancel_event
                )

                text = response.text

                log_event(
                    "http",
                    url=url,
                    final_url=str(
                        response.url
                    ),
                    status=response.status_code,
                    ms=round(
                        (
                            time.monotonic()
                            - started
                        )
                        * 1000
                    ),
                    bytes=len(
                        response.content
                    ),
                    challenge=is_challenge(
                        text,
                        response.status_code,
                    ),
                    cf_ray=response.headers.get(
                        "cf-ray"
                    ),
                )

                if not is_challenge(
                    text,
                    response.status_code,
                ):
                    response.raise_for_status()
                    if not expect_player or parse_player_data(text):
                        return text, "http"

                self.events.put((
                    "security_verification",
                    ("start" if allow_browser else "required"),
                    url,
                ))

            finally:
                client.close()

        self._check_cancel(
            cancel_event
        )

        if not allow_browser:
            return (
                None,
                "challenge",
            )

                                                                    
        try:
            native = self.native_webview().fetch(url, cancel_event, expect_player)
            cookies = native.get("cookies", [])
            user_agent = native.get("user_agent", "")
            if user_agent:
                self.save_session(cookies, user_agent)
            self.events.put(("security_verification", "done", url))
            return native.get("html", ""), "native-webview"
        except SearchCancelled:
            raise
        except Exception as exc:
            log_event("native_webview_error", url=url, error=str(exc))
            raise RuntimeError(
                "Weryfikacja CDA wymaga WebView, ale nie udało się go uruchomić: "
                f"{exc}"
            )


    def search_url(
        self,
        query,
        sort_key,
        duration_key,
        page,
    ):
        slug = re.sub(
            r"[\\/ ]+",
            "_",
            query.strip(),
        ).lower()

                                                                  
                                                                            
                                                                    
        base = (
            f"{BASE}/video/show/"
            f"{quote(slug, safe='_')}"
            f"/p{page}"
        )

        return (
            f"{base}?duration={quote(duration_key)}"
            f"&s={quote(sort_key)}"
        )

    def post_video_get_link(self, item, pdata, quality_value):
        video = pdata.get("video", {})
        ts = video.get("ts")
        if ts is None:
            ts = (pdata.get("api") or {}).get("ts")
        if isinstance(ts, str):
            try: ts = int(ts.split("_", 1)[0])
            except ValueError: pass

        hash2 = video.get("hash2")

        if ts is None or not hash2:
            return None

        client = self.http_client()
        if not client:
            return None

        payload = {
            "jsonrpc": "2.0",
            "method": "videoGetLink",
            "params": [
                item["id"],
                quality_value,
                ts,
                hash2,
                {},
            ],
            "id": 2,
        }

        try:
            response = client.post(
                item["url"],
                json=payload,
                headers={
                    "Content-Type": "application/json",
                    "X-Requested-With": "XMLHttpRequest",
                    "Referer": item["url"],
                },
            )

            log_event(
                "video_get_link",
                id=item["id"],
                status=response.status_code,
            )

            if response.status_code != 200:
                return None

            data = response.json()
            result = data.get("result") or {}
            if result.get("status") not in (None, "ok"):
                return None

            return result.get("resp")
        except Exception as exc:
            log_event(
                "video_get_link_error",
                id=item["id"],
                error=str(exc),
            )
            return None
        finally:
            client.close()
