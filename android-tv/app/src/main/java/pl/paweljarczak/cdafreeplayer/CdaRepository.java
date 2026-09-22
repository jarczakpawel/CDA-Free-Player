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

    public interface SearchListener {
        void onLoading(int page);
        void onPage(ArrayList<Movie> movies, int page, SearchPage stats);
        void onFinished(String why);
        void onError(String error);
        void onVerification(boolean interactive);
    }
    public interface MetadataListener { void onMetadata(MovieMetadata md); void onError(String error); }
    public interface CommentsListener { void onComments(ArrayList<CommentItem> comments); void onError(String error); }
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
            if (cached != null) db.decorateLocalState(cached.movies);
            main.post(() -> {
                if (closed || s != active || s.token.isCancelled()) return;
                if (cached != null) handlePage(s, page, cached);
                else fetchSearchPage(s, page);
            });
        });
    }

    private void fetchSearchPage(SearchSession s, int page) {
        String url = searchUrl(s.query, s.sort, s.duration, page);
        gateway.fetch(url, true, s.token, new CdaGateway.Callback() {
            @Override public void onHtml(String html, boolean via) {
                if (closed || s.token.isCancelled()) return;
                parser.execute(() -> {
                    if (closed || s.token.isCancelled()) return;
                    SearchPage parsed = CdaParser.parseSearch(html);
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

    private static String searchUrl(String q, String sort, String duration, int page) {
        String slug = q.trim().toLowerCase(Locale.ROOT).replaceAll("[\\/ ]+", "_");
        String base = "https://www.cda.pl/video/show/" + Uri.encode(slug, "_") + "/p" + Math.max(1, page);
        return base + "?duration=" + Uri.encode(duration) + "&s=" + Uri.encode(sort);
    }

    public RequestToken loadMetadata(Movie m, boolean allowWeb, MetadataListener listener) {
        MovieMetadata cached = db.getMetadata(m.id);
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
                    MovieMetadata md = mergeMetadata(CdaParser.parseMetadata(html), db.getMetadata(m.id));
                    db.saveMetadata(m.id, md);
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
        ArrayList<CommentItem> cached = db.getComments(m.id);
        if (cached != null) {
            listener.onComments(cached);
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
                    MovieMetadata md = mergeMetadata(CdaParser.parseMetadata(html), db.getMetadata(m.id));
                    if (md.commentCount == null) md.commentCount = comments.size();
                    db.saveComments(m.id, comments);
                    db.saveMetadata(m.id, md);
                    deliver(token, () -> listener.onComments(comments));
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
            Log.i(TAG, "player cache hit id=" + m.id);
            listener.onPlayer(hit.data, hit.metadata);
            return;
        }
        RequestToken token = new RequestToken();
        playerToken = token;
        requests.add(token);
        fetchPlayerAttempt(m, token, listener, false);
    }

    private void fetchPlayerAttempt(Movie m, RequestToken token, PlayerListener listener, boolean forceWeb) {
        long started = android.os.SystemClock.elapsedRealtime();
        Log.i(TAG, "player fetch start id=" + m.id + " forceWeb=" + forceWeb);

        CdaGateway.Callback cb = new CdaGateway.Callback() {
            @Override public void onHtml(String html, boolean via) {
                if (closed || token.isCancelled()) return;
                parser.execute(() -> {
                    if (closed || token.isCancelled()) return;
                    PlayerData parsed = CdaParser.parsePlayerData(html);
                    Log.i(TAG, "player parse id=" + m.id +
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

                    MovieMetadata md = mergeMetadata(CdaParser.parseMetadata(html), db.getMetadata(m.id));
                    if (md.description == null || md.description.isEmpty()) md.description = m.shortDescription == null ? "" : m.shortDescription;
                    if (md.rating == null) md.rating = m.rating;
                    if (md.cdaVotes == null) md.cdaVotes = m.ratingVotes;
                    db.saveMetadata(m.id, md);
                    PlayerData ready = resolved;
                    cachePlayer(m.id, ready, md);
                    deliver(token, () -> { playerToken = null; listener.onPlayer(ready, md); });
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

    public void enterPlaybackMode() {
        playbackMode = true;
        cancelSearch();
        gateway.releaseForPlayback();
    }

    public void exitPlaybackMode() {
        playbackMode = false;
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
