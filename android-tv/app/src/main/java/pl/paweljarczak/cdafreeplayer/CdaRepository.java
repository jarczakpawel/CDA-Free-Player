package pl.paweljarczak.cdafreeplayer;

import android.app.Activity;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.FrameLayout;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class CdaRepository {
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
    public interface MetadataObserver { void onMetadata(Movie movie, MovieMetadata metadata); }
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
        CachedPlayer(PlayerData d, MovieMetadata m) { data = d; metadata = m; at = System.currentTimeMillis(); }
    }

    private final CdaDb db;
    private final CdaGateway gateway;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService parser = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cda-parser");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private final ArrayDeque<Movie> enrichQueue = new ArrayDeque<>();
    private final HashSet<String> enrichPending = new HashSet<>();
    private final LinkedHashMap<String, CachedPlayer> playerCache = new LinkedHashMap<String, CachedPlayer>(16, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, CachedPlayer> e) { return size() > PLAYER_CACHE_MAX; }
    };

    private MetadataObserver metadataObserver;
    private VerificationObserver verificationObserver;
    private boolean enrichBusy = false, enrichPaused = false, enrichVerifyBusy = false, playbackMode = false;
    private RequestToken enrichToken, enrichVerifyToken;
    private SearchSession active;

    public CdaRepository(Activity activity, FrameLayout overlay, FrameLayout host) {
        db = new CdaDb(activity);
        gateway = new CdaGateway(activity, overlay, host);
    }

    public CdaDb db() { return db; }
    public CdaGateway gateway() { return gateway; }
    public void setMetadataObserver(MetadataObserver o) { metadataObserver = o; }
    public void setVerificationObserver(VerificationObserver o) { verificationObserver = o; }

    public void cancelSearch() {
        if (active != null) {
            active.token.cancel();
            active.loading = false;
        }
        gateway.webSession().cancelCurrent();
    }

    public void startSearch(String q, String sort, String duration, SearchListener listener) {
        cancelSearch();
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
        if (s == null || s.loading || s.done || s.token.isCancelled() || playbackMode) return;
        if (s.page > 20) {
            s.done = true;
            s.listener.onFinished("Limit 20 stron");
            return;
        }
        final int page = s.page;
        s.loading = true;
        s.listener.onLoading(page);

        SearchPage cached = db.getSearch(s.key, page);
        if (cached != null) {
            parser.execute(() -> {
                db.decorateLocalState(cached.movies);
                main.post(() -> handlePage(s, page, cached));
            });
            return;
        }

        String url = searchUrl(s.query, s.sort, s.duration, page, false);
        fetchSearchAttempt(s, page, url, false, null);
    }

    private void handlePage(SearchSession s, int page, SearchPage p) {
        if (s != active || s.token.isCancelled() || playbackMode) return;
        s.loading = false;
        s.page = page + 1;
        if (p.raw == 0) {
            s.done = true;
            s.listener.onFinished("Koniec wyników");
            return;
        }
        if (p.movies.isEmpty()) {
            main.postDelayed(this::loadNext, 70);
            return;
        }
        s.listener.onPage(p.movies, page, p);
        enqueueEnrichment(p.movies);
    }

    private void fetchSearchAttempt(SearchSession s, int page, String url, boolean forcedP1, SearchPage first) {
        CdaGateway.Callback cb = new CdaGateway.Callback() {
            @Override public void onHtml(String html, boolean via) {
                if (s.token.isCancelled()) return;
                parser.execute(() -> {
                    SearchPage parsed = CdaParser.parseSearch(html);

                    // Desktop already had this recovery and Android did not. CDA can
                    // return an empty first form of /video/show/<query> while /p1 has
                    // the actual catalogue. When page 1 is empty, force a real WebView
                    // fetch of /p1 before accepting "0 films".
                    if (page == 1 && !forcedP1 && parsed.movies.isEmpty()) {
                        String retry = searchUrl(s.query, s.sort, s.duration, page, true);
                        main.post(() -> {
                            if (!s.token.isCancelled() && s == active)
                                fetchSearchAttempt(s, page, retry, true, parsed);
                        });
                        return;
                    }

                    SearchPage chosen = parsed;
                    if (first != null && parsed.movies.isEmpty() && parsed.raw <= first.raw) chosen = first;
                    final SearchPage result = chosen;

                    // Never persist a transient empty bootstrap/interstitial page.
                    if (result.raw > 0 || !result.movies.isEmpty()) db.putSearch(s.key, page, result);
                    db.decorateLocalState(result.movies);
                    main.post(() -> {
                        if (via) resumeEnrichment();
                        handlePage(s, page, result);
                    });
                });
            }

            @Override public void onError(String e) {
                s.loading = false;
                if (!s.token.isCancelled()) s.listener.onError(e);
            }

            @Override public void onChallengeRequired() {}

            @Override public void onVerification(boolean interactive) {
                s.listener.onVerification(interactive);
                if (verificationObserver != null) verificationObserver.onVerification(interactive, false);
            }
        };

        if (forcedP1) gateway.fetchWeb(url, s.token, cb);
        else gateway.fetch(url, true, s.token, cb);
    }

    private static String searchUrl(String q, String sort, String duration, int page, boolean forcePageSuffix) {
        String slug = q.trim().toLowerCase(Locale.ROOT).replaceAll("[\\/ ]+", "_");
        String base = "https://www.cda.pl/video/show/" + Uri.encode(slug, "_");
        if (page > 1 || forcePageSuffix) base += "/p" + page;
        return base + "?duration=" + Uri.encode(duration) + "&s=" + Uri.encode(sort);
    }

    public void loadMetadata(Movie m, boolean allowWeb, MetadataListener listener) {
        MovieMetadata cached = db.getMetadata(m.id);
        if (cached != null && !cached.description.isEmpty()) {
            listener.onMetadata(cached);
            return;
        }
        RequestToken token = new RequestToken();
        gateway.fetch(m.url, allowWeb, token, new CdaGateway.Callback() {
            @Override public void onHtml(String html, boolean via) {
                parser.execute(() -> {
                    MovieMetadata md = CdaParser.parseMetadata(html);
                    PlayerData pd = CdaParser.parsePlayerData(html);
                    db.saveMetadata(m.id, md);
                    if (validPlayer(pd)) cachePlayer(m.id, pd, md);
                    main.post(() -> {
                        if (via) resumeEnrichment();
                        listener.onMetadata(md);
                    });
                });
            }
            @Override public void onError(String e) { listener.onError(e); }
            @Override public void onChallengeRequired() { enrichPaused = true; listener.onError("Weryfikacja zabezpieczeń wymagana"); }
            @Override public void onVerification(boolean interactive) {
                if (verificationObserver != null) verificationObserver.onVerification(interactive, false);
            }
        });
    }

    public void enqueueEnrichment(Collection<Movie> movies) {
        if (playbackMode) return;
        for (Movie m : movies) {
            MovieMetadata md = db.getMetadata(m.id);
            if (md != null && md.rating != null) continue;
            if (enrichPending.add(m.id)) enrichQueue.add(m);
        }
        pumpEnrichment();
    }

    public void resumeEnrichment() {
        if (playbackMode) return;
        enrichPaused = false;
        pumpEnrichment();
    }

    private void pumpEnrichment() {
        if (playbackMode || enrichBusy || enrichPaused || enrichVerifyBusy) return;
        Movie m = enrichQueue.poll();
        if (m == null) return;
        enrichBusy = true;
        enrichToken = new RequestToken();
        gateway.fetch(m.url, false, enrichToken, new CdaGateway.Callback() {
            @Override public void onHtml(String html, boolean via) { parseEnrichment(m, html, 250); }
            @Override public void onError(String e) { finishEnrichment(m, 600); }
            @Override public void onChallengeRequired() {
                enrichBusy = false;
                verifyForEnrichment(m);
            }
            @Override public void onVerification(boolean interactive) {}
        });
    }

    private void verifyForEnrichment(Movie m) {
        if (playbackMode || enrichVerifyBusy) return;
        enrichVerifyBusy = true;
        enrichPaused = true;
        enrichVerifyToken = new RequestToken();
        if (verificationObserver != null) verificationObserver.onVerification(false, true);
        gateway.fetch(m.url, true, enrichVerifyToken, new CdaGateway.Callback() {
            @Override public void onHtml(String html, boolean via) {
                enrichVerifyBusy = false;
                enrichPaused = false;
                parseEnrichment(m, html, 120);
            }
            @Override public void onError(String e) {
                enrichPending.remove(m.id);
                enrichVerifyBusy = false;
                enrichPaused = true;
            }
            @Override public void onChallengeRequired() {}
            @Override public void onVerification(boolean interactive) {
                if (verificationObserver != null) verificationObserver.onVerification(interactive, true);
            }
        });
    }

    private void parseEnrichment(Movie m, String html, long delay) {
        parser.execute(() -> {
            MovieMetadata md = CdaParser.parseMetadata(html);
            db.saveMetadata(m.id, md);
            main.post(() -> {
                if (metadataObserver != null) metadataObserver.onMetadata(m, md);
                finishEnrichment(m, delay);
            });
        });
    }

    private void finishEnrichment(Movie m, long delay) {
        enrichPending.remove(m.id);
        enrichBusy = false;
        enrichToken = null;
        if (!playbackMode && !enrichPaused) main.postDelayed(this::pumpEnrichment, delay);
    }

    public void loadComments(Movie m, CommentsListener listener) {
        ArrayList<CommentItem> cached = db.getComments(m.id);
        if (cached != null) {
            listener.onComments(cached);
            return;
        }
        RequestToken token = new RequestToken();
        gateway.fetch(m.url, true, token, new CdaGateway.Callback() {
            @Override public void onHtml(String html, boolean via) {
                parser.execute(() -> {
                    ArrayList<CommentItem> comments = CdaParser.parseComments(html);
                    db.saveComments(m.id, comments);
                    main.post(() -> {
                        if (via) resumeEnrichment();
                        listener.onComments(comments);
                    });
                });
            }
            @Override public void onError(String e) { listener.onError(e); }
            @Override public void onChallengeRequired() {}
            @Override public void onVerification(boolean interactive) {
                if (verificationObserver != null) verificationObserver.onVerification(interactive, false);
            }
        });
    }

    public void loadPlayer(Movie m, PlayerListener listener) {
        CachedPlayer hit = getCachedPlayer(m.id);
        if (hit != null) {
            listener.onPlayer(hit.data, hit.metadata);
            return;
        }
        RequestToken token = new RequestToken();
        gateway.fetch(m.url, true, token, new CdaGateway.Callback() {
            @Override public void onHtml(String html, boolean via) {
                parser.execute(() -> {
                    PlayerData pd = CdaParser.parsePlayerData(html);
                    MovieMetadata md = CdaParser.parseMetadata(html);
                    db.saveMetadata(m.id, md);
                    if (!validPlayer(pd)) {
                        main.post(() -> listener.onError(playerError(pd)));
                        return;
                    }
                    cachePlayer(m.id, pd, md);
                    main.post(() -> {
                        if (via) resumeEnrichment();
                        listener.onPlayer(pd, md);
                    });
                });
            }
            @Override public void onError(String e) { listener.onError(e); }
            @Override public void onChallengeRequired() {}
            @Override public void onVerification(boolean interactive) {
                if (verificationObserver != null) verificationObserver.onVerification(interactive, false);
            }
        });
    }

    private static boolean validPlayer(PlayerData p) {
        return p != null && !p.premium && (p.type.isEmpty() || "plain".equals(p.type)) && (!p.dash.isEmpty() || !p.hls.isEmpty());
    }

    private static String playerError(PlayerData p) {
        if (p == null) return "Brak danych playera";
        if (p.premium || (!p.type.isEmpty() && !"plain".equals(p.type))) return "Materiał Premium lub niedostępny";
        return "Brak strumienia";
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

    /** Suspend all catalogue work and drop WebView renderer before full-screen video. */
    public void enterPlaybackMode() {
        playbackMode = true;
        cancelSearch();
        enrichPaused = true;
        if (enrichToken != null) enrichToken.cancel();
        if (enrichVerifyToken != null) enrichVerifyToken.cancel();
        gateway.releaseForPlayback();
    }

    public void exitPlaybackMode() {
        if (!playbackMode) return;
        playbackMode = false;
        enrichPaused = false;
        pumpEnrichment();
    }

    public void shutdown() {
        enterPlaybackMode();
        parser.shutdownNow();
        gateway.shutdown();
        db.close();
    }
}
