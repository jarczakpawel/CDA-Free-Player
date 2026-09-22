package pl.paweljarczak.cdafreeplayer;

import android.app.Activity;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class CdaRepository {
    private static final String TAG = "CDAFP";
    private static final boolean TRACE = false;

    public interface SearchListener {
        void onLoading(int page);
        void onPage(ArrayList<Movie> movies, int page, SearchPage stats);
        void onFinished(String why);
        void onError(String error);
        void onVerification(boolean interactive);
    }
    public interface MetadataListener { void onMetadata(MovieMetadata md); void onError(String error); }
    public interface CommentsListener { void onComments(ArrayList<CommentItem> comments, MovieMetadata metadata); void onError(String error); }
    public interface PlayerListener { void onPlayer(PlayerData data, MovieMetadata metadata); void onError(String error); }
    public interface VerificationObserver { void onVerification(boolean interactive, boolean background); }

    private static final long PLAYER_CACHE_MS = 4L * 60 * 1000;
    private static final int PLAYER_CACHE_MAX = 12;

    private static final class SearchSession {
        String query, sort, duration, key;
        int page = 1;
        boolean loading = false, done = false;
        RequestToken token = new RequestToken();
        SearchListener listener;
    }

    private static final class CachedPlayer {
        final PlayerData data;
        final MovieMetadata metadata;
        final long at;
        CachedPlayer(PlayerData d, MovieMetadata m) {
            data = d; metadata = m; at = System.currentTimeMillis();
        }
    }

    private final CdaDb db;
    private final CdaGateway gateway;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService parser = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cda-parser");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private final LinkedHashMap<String, CachedPlayer> playerCache =
            new LinkedHashMap<String, CachedPlayer>(16, .75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, CachedPlayer> e) {
                    return size() > PLAYER_CACHE_MAX;
                }
            };
    private final LinkedHashMap<String, MovieMetadata> metadataCache =
            new LinkedHashMap<String, MovieMetadata>(32, .75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, MovieMetadata> e) {
                    return size() > 24;
                }
            };
    private final LinkedHashMap<String, ArrayList<CommentItem>> commentsCache =
            new LinkedHashMap<String, ArrayList<CommentItem>>(16, .75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, ArrayList<CommentItem>> e) {
                    return size() > 12;
                }
            };

    private VerificationObserver verificationObserver;
    private boolean playbackMode = false;
    private SearchSession active, suspended, pausedBrowse;
    private RequestToken playerToken;
    private volatile boolean closed;
    private final Set<RequestToken> requests = new HashSet<>();

    public CdaRepository(Activity activity, FrameLayout overlay, FrameLayout host) {
        db = new CdaDb(activity);
        gateway = new CdaGateway(activity, overlay, host);
    }

    public CdaDb db() { return db; }
    public CdaGateway gateway() { return gateway; }
    public void setVerificationObserver(VerificationObserver o) { verificationObserver = o; }

    public void cancelSearch() {
        SearchSession s = active;
        active = null;
        if (s != null) {
            s.token.cancel();
            s.loading = false;
            gateway.webSession().cancel(s.token);
        }
    }

    public void pauseBrowseSearch() {
        SearchSession s = active;
        if (s == null) return;
        active = null;
        s.token.cancel();
        gateway.webSession().cancel(s.token);
        SearchSession copy = new SearchSession();
        copy.query = s.query; copy.sort = s.sort; copy.duration = s.duration; copy.key = s.key;
        copy.page = s.page; copy.done = s.done; copy.listener = s.listener;
        pausedBrowse = copy;
    }

    public void resumeBrowseSearch() {
        if (closed || active != null || pausedBrowse == null) return;
        active = pausedBrowse;
        pausedBrowse = null;
    }

    public void startSearch(String q, String sort, String duration, SearchListener listener) {
        cancelSearch();
        pausedBrowse = null;
        SearchSession s = new SearchSession();
        s.query = q.trim();
        s.sort = sort;
        s.duration = duration;
        s.key = s.query + "|" + sort + "|" + duration;
        s.listener = listener;
        active = s;
        loadNext();
    }

    public void loadNext() {
        SearchSession s = active;
        if (closed || s == null || s.loading || s.done || s.token.isCancelled() || playbackMode) return;
        if (s.page > 20) {
            s.done = true;
            s.listener.onFinished("Limit 20 stron");
            return;
        }
        final int page = s.page;
        s.loading = true;
        s.listener.onLoading(page);

        parser.execute(() -> {
            if (closed || s.token.isCancelled()) return;
            SearchPage cached = db.getSearch(s.key, page);
            if (cached != null) {
                db.decorateLocalState(cached.movies);
                logCatalogPage(page, "cache", null, cached);
            }
            main.post(() -> {
                if (closed || s != active || s.token.isCancelled()) return;
                if (cached != null) handlePage(s, page, cached);
                else fetchSearchPage(s, page);
            });
        });
    }

    private void fetchSearchPage(SearchSession s, int page) {
        String url = searchUrl(s.query, s.sort, s.duration, page);
        gateway.fetchCatalog(url, s.token, new CdaGateway.Callback() {
            @Override public void onHtml(String html, boolean via) {
                if (closed || s.token.isCancelled()) return;
                parser.execute(() -> {
                    if (closed || s.token.isCancelled()) return;
                    SearchPage parsed = CdaParser.parseSearch(html);
                    logCatalogPage(page, via ? "webview" : "http", html, parsed);
                    if (parsed.raw > 0 || !parsed.movies.isEmpty()) db.putSearch(s.key, page, parsed);
                    db.decorateLocalState(parsed.movies);
                    main.post(() -> handlePage(s, page, parsed));
                });
            }

            @Override public void onError(String e) {
                s.loading = false;
                if (!closed && s == active && !s.token.isCancelled()) s.listener.onError(e);
            }

            @Override public void onChallengeRequired() {}

            @Override public void onVerification(boolean interactive) {
                if (closed || s != active || s.token.isCancelled()) return;
                s.listener.onVerification(interactive);
                notifyVerification(interactive, false);
            }
        });
    }

    private void handlePage(SearchSession s, int page, SearchPage p) {
        if (closed || s != active || s.token.isCancelled() || playbackMode) return;
        s.loading = false;
        s.page = page + 1;
        if (p.raw == 0) {
            s.done = true;
            s.listener.onFinished("Koniec wyników");
            return;
        }
        if (p.movies.isEmpty()) {
            main.postDelayed(() -> { if (s == active) loadNext(); }, 70);
            return;
        }
        s.listener.onPage(p.movies, page, p);
    }

    private static void logCatalogPage(int page, String source, String html, SearchPage parsed) {
        if (!TRACE) return;
        int ratings = 0, votes = 0, shorts = 0;
        for (Movie m : parsed.movies) {
            if (m.rating != null) ratings++;
            if (m.ratingVotes != null) votes++;
            if (m.shortDescription != null && !m.shortDescription.isEmpty()) shorts++;
        }
        String lower = html == null ? "" : html.toLowerCase(Locale.ROOT);
        if (TRACE) Log.i(TAG, "catalog parse page=" + page +
                " via=" + source +
                " bytes=" + (html == null ? 0 : html.length()) +
                " raw=" + parsed.raw +
                " free=" + parsed.movies.size() +
                " ratings=" + ratings +
                " votes=" + votes +
                " short=" + shorts +
                " markers=" + markerCount(lower, "ratingvalue") + "/" +
                markerCount(lower, "data-rating") + "/" +
                markerCount(lower, "data-rate") + "/" +
                markerCount(lower, "aggregaterating") + "/" +
                markerCount(lower, "ratemedval"));
    }

    private static void logMetadata(String id, String source, String html, MovieMetadata md) {
        if (!TRACE) return;
        String lower = html == null ? "" : html.toLowerCase(Locale.ROOT);
        if (TRACE) Log.i(TAG, "metadata parse id=" + id +
                " via=" + source +
                " bytes=" + (html == null ? 0 : html.length()) +
                " rating=" + md.rating +
                " votes=" + md.cdaVotes +
                " imdb=" + md.imdbRating +
                " desc=" + (md.description == null ? 0 : md.description.length()) +
                " markers=" + markerCount(lower, "ratingvalue") + "/" +
                markerCount(lower, "data-rating") + "/" +
                markerCount(lower, "data-rate") + "/" +
                markerCount(lower, "aggregaterating") + "/" +
                markerCount(lower, "ratemedval"));
    }

    private static int markerCount(String text, String needle) {
        if (text == null || text.isEmpty() || needle == null || needle.isEmpty()) return 0;
        int count = 0, from = 0;
        while ((from = text.indexOf(needle, from)) >= 0) { count++; from += needle.length(); }
        return count;
    }

    private static String searchUrl(String q, String sort, String duration, int page) {
        String slug = q.trim().toLowerCase(Locale.ROOT).replaceAll("[\\/ ]+", "_");
        String base = "https://www.cda.pl/video/show/" + Uri.encode(slug, "_") + "/p" + Math.max(1, page);
        return base + "?duration=" + Uri.encode(duration) + "&s=" + Uri.encode(sort);
    }

    public RequestToken loadMetadata(Movie m, boolean allowWeb, MetadataListener listener) {
        MovieMetadata cached = sessionMetadata(m.id);
        if (cached != null && cached.description != null && !cached.description.isEmpty()) {
            listener.onMetadata(cached);
            return null;
        }
        RequestToken token = new RequestToken();
        requests.add(token);
        gateway.fetch(m.url, allowWeb, token, new CdaGateway.Callback() {
            @Override public void onHtml(String html, boolean via) {
                if (closed || token.isCancelled()) return;
                parser.execute(() -> {
                    if (closed || token.isCancelled()) return;
                    MovieMetadata parsed = CdaParser.parseMetadata(html);
                    ArrayList<CommentItem> comments = CdaParser.parseComments(html);
                    logMetadata(m.id, via ? "webview" : "http", html, parsed);
                    MovieMetadata md = mergeMetadata(parsed, sessionMetadata(m.id));
                    Integer parsedCommentCount = md.commentCount;
                    if (parsedCommentCount == null && !comments.isEmpty()) md.commentCount = comments.size();
                    putSessionMetadata(m.id, md);
                    if (!comments.isEmpty() || parsedCommentCount != null) {
                        synchronized (commentsCache) { commentsCache.put(m.id, new ArrayList<>(comments)); }
                    }
                    deliver(token, () -> listener.onMetadata(md));
                });
            }
            @Override public void onError(String e) { deliver(token, () -> listener.onError(e)); }
            @Override public void onChallengeRequired() { deliver(token, () -> listener.onError("Weryfikacja zabezpieczeń wymagana")); }
            @Override public void onVerification(boolean interactive) { notifyVerification(interactive, false); }
        });
        return token;
    }

    public RequestToken loadComments(Movie m, CommentsListener listener) {
        ArrayList<CommentItem> cached;
        synchronized (commentsCache) { cached = commentsCache.get(m.id); }
        MovieMetadata cachedMetadata = sessionMetadata(m.id);
        if (cached != null && cachedMetadata != null) {
            listener.onComments(new ArrayList<>(cached), cachedMetadata);
            return null;
        }
        RequestToken token = new RequestToken();
        requests.add(token);
        gateway.fetch(m.url, true, token, new CdaGateway.Callback() {
            @Override public void onHtml(String html, boolean via) {
                if (closed || token.isCancelled()) return;
                parser.execute(() -> {
                    if (closed || token.isCancelled()) return;
                    ArrayList<CommentItem> comments = CdaParser.parseComments(html);
                    MovieMetadata md = mergeMetadata(CdaParser.parseMetadata(html), sessionMetadata(m.id));
                    logMetadata(m.id, via ? "webview" : "http", html, md);
                    if (md.commentCount == null) md.commentCount = comments.size();
                    putSessionMetadata(m.id, md);
                    synchronized (commentsCache) { commentsCache.put(m.id, new ArrayList<>(comments)); }
                    deliver(token, () -> listener.onComments(comments, md));
                });
            }
            @Override public void onError(String e) { deliver(token, () -> listener.onError(e)); }
            @Override public void onChallengeRequired() { deliver(token, () -> listener.onError("Weryfikacja zabezpieczeń wymagana")); }
            @Override public void onVerification(boolean interactive) { notifyVerification(interactive, false); }
        });
        return token;
    }

    public void cancel(RequestToken token) {
        if (token == null) return;
        token.cancel();
        requests.remove(token);
        gateway.webSession().cancel(token);
    }

    public void loadPlayer(Movie m, PlayerListener listener) {
        cancelPlayer();
        SearchSession previous = active;
        cancelSearch();
        suspended = previous;
        CachedPlayer hit = getCachedPlayer(m.id);
        if (hit != null && validPlayer(hit.data)) {
            if (TRACE) Log.i(TAG, "player cache hit id=" + m.id);
            MovieMetadata md = mergeMetadata(hit.metadata, sessionMetadata(m.id));
            listener.onPlayer(hit.data, md);
            return;
        }
        RequestToken token = new RequestToken();
        playerToken = token;
        requests.add(token);
        fetchPlayerAttempt(m, token, listener, false);
    }

    private void fetchPlayerAttempt(Movie m, RequestToken token, PlayerListener listener, boolean forceWeb) {
        long started = android.os.SystemClock.elapsedRealtime();
        if (TRACE) Log.i(TAG, "player fetch start id=" + m.id + " forceWeb=" + forceWeb);

        CdaGateway.Callback cb = new CdaGateway.Callback() {
            @Override public void onHtml(String html, boolean via) {
                if (closed || token.isCancelled()) return;
                parser.execute(() -> {
                    if (closed || token.isCancelled()) return;
                    PlayerData parsed = CdaParser.parsePlayerData(html);
                    if (TRACE) Log.i(TAG, "player parse id=" + m.id +
                            " via=" + (via ? "webview" : "http") +
                            " bytes=" + (html == null ? 0 : html.length()) +
                            " found=" + (parsed != null) +
                            " playable=" + (parsed != null && parsed.hasPlayableSource()) +
                            " ms=" + (android.os.SystemClock.elapsedRealtime() - started));

                    PlayerData resolved = parsed;
                    if (resolved != null) {
                        try {
                            resolved = gateway.resolvePlayer(m, resolved, token);
                        } catch (InterruptedException ignored) {
                            return;
                        } catch (Exception e) {
                            Log.w(TAG, "videoGetLink resolver failed id=" + m.id + " " + e.getClass().getSimpleName());
                        }
                    }

                    if (resolved == null || !resolved.hasPlayableSource()) {
                        if (!forceWeb) {
                            main.post(() -> { if (!closed && !token.isCancelled()) fetchPlayerAttempt(m, token, listener, true); });
                        } else {
                            PlayerData finalParsed = resolved;
                            playerFailure(token, listener, playerError(finalParsed));
                        }
                        return;
                    }

                    MovieMetadata md = new MovieMetadata();
                    md.description = m.shortDescription == null ? "" : m.shortDescription;
                    MovieMetadata explicit = sessionMetadata(m.id);
                    if (explicit != null) md = mergeMetadata(md, explicit);
                    PlayerData ready = resolved;
                    MovieMetadata readyMetadata = md;
                    cachePlayer(m.id, ready, readyMetadata);
                    deliver(token, () -> { playerToken = null; listener.onPlayer(ready, readyMetadata); });
                });
            }

            @Override public void onError(String e) {
                if (closed || token.isCancelled()) return;
                Log.w(TAG, "player fetch error id=" + m.id + " forceWeb=" + forceWeb + " " + e);
                if (!forceWeb) fetchPlayerAttempt(m, token, listener, true);
                else playerFailure(token, listener, "Nie udało się przygotować odtwarzania: " + e);
            }

            @Override public void onChallengeRequired() {
                if (!forceWeb) fetchPlayerAttempt(m, token, listener, true);
            }

            @Override public void onVerification(boolean interactive) {
                notifyVerification(interactive, false);
            }
        };

        if (forceWeb) gateway.fetchPlayerWeb(m.url, token, cb);
        else gateway.fetch(m.url, true, token, cb);
    }

    private void deliver(RequestToken token, Runnable callback) {
        main.post(() -> {
            requests.remove(token);
            if (!closed && !token.isCancelled()) callback.run();
        });
    }

    private void playerFailure(RequestToken token, PlayerListener listener, String error) {
        deliver(token, () -> {
            playerToken = null;
            restoreSearch();
            listener.onError(error);
        });
    }

    public boolean isSearchLoading() { return active != null && active.loading; }

    public void cancelPlayer() {
        if (playerToken != null) {
            RequestToken token = playerToken;
            playerToken = null;
            token.cancel();
            requests.remove(token);
            gateway.webSession().cancel(token);
        }
        restoreSearch();
    }

    private void restoreSearch() {
        if (suspended == null || closed) return;
        SearchSession old = suspended;
        suspended = null;
        SearchSession next = new SearchSession();
        next.query = old.query; next.sort = old.sort; next.duration = old.duration;
        next.key = old.key; next.page = old.page; next.done = old.done; next.listener = old.listener;
        active = next;
    }

    private static boolean validPlayer(PlayerData p) {
        return p != null && p.hasPlayableSource();
    }

    private static String playerError(PlayerData p) {
        if (p == null) return "Brak danych playera CDA (WebView)";
        if (p.canResolveQuality()) return "CDA nie zwróciło działającego linku do strumienia";
        return "Brak darmowego strumienia w danych playera";
    }

    private synchronized void cachePlayer(String id, PlayerData p, MovieMetadata md) {
        playerCache.put(id, new CachedPlayer(p, md));
    }

    private synchronized CachedPlayer getCachedPlayer(String id) {
        CachedPlayer c = playerCache.get(id);
        if (c == null) return null;
        if (System.currentTimeMillis() - c.at > PLAYER_CACHE_MS) {
            playerCache.remove(id);
            return null;
        }
        return c;
    }

    public MovieMetadata sessionMetadata(String id) {
        synchronized (metadataCache) { return metadataCache.get(id); }
    }

    private void putSessionMetadata(String id, MovieMetadata metadata) {
        if (id == null || metadata == null) return;
        synchronized (metadataCache) { metadataCache.put(id, metadata); }
    }

    public void clearTransientCaches() {
        synchronized (metadataCache) { metadataCache.clear(); }
        synchronized (commentsCache) { commentsCache.clear(); }
        synchronized (playerCache) { playerCache.clear(); }
    }

    private static MovieMetadata mergeMetadata(MovieMetadata fresh, MovieMetadata old) {
        if (fresh == null) fresh = new MovieMetadata();
        if (old == null) return fresh;
        if (fresh.description == null || fresh.description.isEmpty()) fresh.description = old.description;
        if (fresh.rating == null) fresh.rating = old.rating;
        if (fresh.cdaVotes == null) fresh.cdaVotes = old.cdaVotes;
        if (fresh.imdbRating == null || fresh.imdbRating.isEmpty()) fresh.imdbRating = old.imdbRating;
        if (fresh.imdbVotes == null) fresh.imdbVotes = old.imdbVotes;
        if (fresh.commentCount == null) fresh.commentCount = old.commentCount;
        return fresh;
    }

    private void notifyVerification(boolean interactive, boolean background) {
        if (verificationObserver != null) verificationObserver.onVerification(interactive, background);
    }

    public void setPlaybackContext(boolean active) {
        gateway.setPlaybackContext(active);
    }

    public void enterPlaybackMode() {
        playbackMode = true;
        cancelSearch();
        gateway.setPlaybackContext(true);
        gateway.releaseForPlayback();
    }

    public void exitPlaybackMode() {
        playbackMode = false;
        gateway.setPlaybackContext(false);
        restoreSearch();
    }

    public void shutdown() {
        closed = true;
        playbackMode = true;
        if (active != null) active.token.cancel();
        if (pausedBrowse != null) pausedBrowse.token.cancel();
        for (RequestToken token : requests) token.cancel();
        requests.clear();
        main.removeCallbacksAndMessages(null);
        gateway.shutdown();
        parser.execute(db::close);
        parser.shutdown();
    }
}
