package pl.paweljarczak.cdafreeplayer;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int DEFAULT_YEAR = 1985;
    private static final int INITIAL_TARGET = 12;
    private static final int INITIAL_MAX_PAGES = 4;

    private final Handler h = new Handler(Looper.getMainLooper());
    private CdaRepository repo;
    private ImageLoader images;
    private MovieAdapter adapter;
    private GridLayoutManager gridLayout;
    private LinearLayout navPanel, detailPanel, yearRow, sortRow, durationRow;
    private HorizontalScrollView yearScroll;
    private RecyclerView grid;
    private TextView status, resultsTitle, resultCount, loadingText, detailTitle, detailSources, detailTime, detailDescriptionPreview, detailRatingExact;
    private ImageView detailThumb;
    private StarRatingView detailStars;
    private Button detailFavorite, detailDescription, detailComments, recent, favorites, manualSearch, settingsButton, removeCollection;
    private EditText manualQuery;
    private View loadingBar;
    private FrameLayout securityOverlay;
    private String sort = "best", duration = "all", query = "lektor 1985";
    private Integer selectedYear = 1985;
    private int initialPages = 0, lastCard = 0;
    private Movie focused;
    private MovieMetadata focusedMeta;
    private Runnable metadataTask;
    private boolean launchedPlayer = false;
    private boolean removeMode = false;
    private String collectionMode = null;
    private UpdateManager updater;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        bind();
        images = new ImageLoader(this);
        updater = new UpdateManager(this);
        repo = new CdaRepository(this, securityOverlay, findViewById(R.id.securityHost));
        repo.setMetadataObserver((movie, md) -> {
            adapter.updateMetadata(movie.id, md);
            if (focused != null && focused.id.equals(movie.id)) applyMetadata(focused, md);
        });
        repo.setVerificationObserver((interactive, background) -> setStatus(
                interactive ? "Weryfikacja zabezpieczeń CDA" :
                        background ? "Weryfikacja sesji w tle…" : "Weryfikacja w tle…"));
        setupGrid();
        setupYears();
        setupFilters();
        setupLeft();
        startSearch(query, "Lektor 1985");
        yearRow.postDelayed(() -> focusYear(DEFAULT_YEAR, true), 250);
        h.postDelayed(updater::checkOnStartup, 2200);
    }

    private void bind() {
        navPanel = findViewById(R.id.navPanel); detailPanel = findViewById(R.id.detailPanel);
        yearRow = findViewById(R.id.yearRow); sortRow = findViewById(R.id.sortRow); durationRow = findViewById(R.id.durationRow);
        yearScroll = findViewById(R.id.yearScroll); grid = findViewById(R.id.grid); status = findViewById(R.id.status);
        resultsTitle = findViewById(R.id.resultsTitle); resultCount = findViewById(R.id.resultCount);
        loadingBar = findViewById(R.id.loadingBar); loadingText = findViewById(R.id.loadingText);
        detailThumb = findViewById(R.id.detailThumb); detailTitle = findViewById(R.id.detailTitle);
        detailStars = findViewById(R.id.detailStars); detailRatingExact = findViewById(R.id.detailRatingExact);
        detailSources = findViewById(R.id.detailSources); detailTime = findViewById(R.id.detailTime);
        detailFavorite = findViewById(R.id.detailFavorite); detailDescription = findViewById(R.id.detailDescription);
        detailComments = findViewById(R.id.detailComments); detailDescriptionPreview = findViewById(R.id.detailDescriptionPreview);
        recent = findViewById(R.id.recent); favorites = findViewById(R.id.favorites);
        manualQuery = findViewById(R.id.manualQuery); manualSearch = findViewById(R.id.manualSearch);
        settingsButton = findViewById(R.id.settingsButton); removeCollection = findViewById(R.id.removeCollection);
        securityOverlay = findViewById(R.id.securityOverlay);
    }

    private void setupGrid() {
        int cols = getResources().getDisplayMetrics().widthPixels >= 1500 ? 4 : 3;
        gridLayout = new GridLayoutManager(this, cols);
        grid.setLayoutManager(gridLayout);
        grid.setHasFixedSize(true);
        grid.setItemViewCacheSize(cols * 3);
        adapter = new MovieAdapter(images, new MovieAdapter.Listener() {
            @Override public void onFocus(Movie m, int p, View v) { lastCard = p; showMovie(m); debounceMetadata(m); }
            @Override public void onClick(Movie m, int p) {
                lastCard = p;
                if (removeMode) removeFromCollection(m); else play(m);
            }
            @Override public void onLeftEdge(Movie m, int p) { lastCard = p; showMovie(m); focusDetailActions(); }
            @Override public void onLastRow(int p) { repo.loadNext(); }
        });
        adapter.setColumns(cols);
        grid.setAdapter(adapter);
    }

    private Button tvButton(String text) {
        Button b = new Button(this);
        b.setText(text); b.setTextColor(Color.WHITE); b.setTextSize(13); b.setBackgroundResource(R.drawable.button_bg);
        b.setFocusable(true); b.setMinWidth(dp(76));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(44));
        lp.setMargins(dp(2), dp(2), dp(2), dp(2)); b.setLayoutParams(lp);
        return b;
    }

    private void setupYears() {
        int max = Calendar.getInstance().get(Calendar.YEAR);
        for (int n = 1950; n <= max; n++) {
            int year = n;
            Button b = tvButton(String.valueOf(year));
            b.setTag(year);
            b.setOnClickListener(v -> { selectedYear = year; startSearch("lektor " + year, "Lektor " + year); });
            b.setOnFocusChangeListener((v, focused) -> { if (focused) keepYearVisible(v); });
            yearRow.addView(b);
        }
    }

    private void setupFilters() {
        String[][] sorts = {{"Najtrafniejszy", "best"}, {"Najnowsze", "date"}, {"Alfabetycznie", "alf"}};
        for (String[] x : sorts) {
            Button b = tvButton(x[0]);
            b.setOnClickListener(v -> { sort = x[1]; startSearch(query, resultsTitle.getText().toString()); });
            sortRow.addView(b);
        }
        String[][] durations = {{"Każda", "all"}, {"Krótkie <5 min", "krotkie"}, {"Średnie >20 min", "srednie"}, {"Długie >60 min", "dlugie"}};
        for (String[] x : durations) {
            Button b = tvButton(x[0]);
            b.setOnClickListener(v -> { duration = x[1]; startSearch(query, resultsTitle.getText().toString()); });
            durationRow.addView(b);
        }
    }

    private void setupLeft() {
        recent.setOnClickListener(v -> showCollection(repo.db().recent(), "Ostatnio oglądane", "recent"));
        favorites.setOnClickListener(v -> showCollection(repo.db().favorites(), "Ulubione", "favorites"));
        manualSearch.setOnClickListener(v -> manualSearch());
        settingsButton.setOnClickListener(v -> SettingsDialog.show(this, repo.db(), updater, this::refreshCurrentCollection));
        removeCollection.setOnClickListener(v -> toggleRemoveMode());
        manualQuery.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEARCH) { manualSearch(); return true; }
            return false;
        });
        for (View v : new View[]{recent, favorites, manualSearch, settingsButton}) {
            v.setOnKeyListener((x, key, event) -> {
                if (event.getAction() == KeyEvent.ACTION_DOWN && key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    focusYear(selectedYear == null ? DEFAULT_YEAR : selectedYear, false); return true;
                }
                return false;
            });
        }
        detailFavorite.setOnClickListener(v -> {
            if (focused == null) return;
            boolean favorite = repo.db().toggleFavorite(focused);
            focused.favorite = favorite;
            detailFavorite.setText(favorite ? "♥ Usuń z ulubionych" : "♡ Ulubione");
            adapter.updateLocalState(focused);
        });
        detailDescription.setOnClickListener(v -> {
            if (focused == null) return;
            String d = focusedMeta != null && !focusedMeta.description.isEmpty() ? focusedMeta.description : focused.shortDescription;
            TvDialogs.text(this, "Opis", d);
        });
        detailComments.setOnClickListener(v -> loadComments());
        for (View v : new View[]{detailFavorite, detailDescription, detailComments}) {
            v.setOnKeyListener((x, key, event) -> {
                if (event.getAction() == KeyEvent.ACTION_DOWN && key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    restoreCard(); return true;
                }
                return false;
            });
        }
    }

    private void manualSearch() {
        String q = manualQuery.getText().toString().trim();
        if (q.isEmpty()) return;
        selectedYear = null;
        startSearch(q, "Wyniki: " + q);
    }

    private void startSearch(String q, String title) {
        collectionMode = null; removeMode = false; adapter.setRemoveMode(false); removeCollection.setVisibility(View.GONE);
        query = q; initialPages = 0; adapter.setItems(Collections.emptyList());
        resultsTitle.setText(title); resultCount.setText(""); showNav();
        repo.startSearch(q, sort, duration, new CdaRepository.SearchListener() {
            @Override public void onLoading(int page) {
                loadingBar.setVisibility(View.VISIBLE); loadingText.setText("Wczytywanie strony " + page + "…");
                setStatus("Wczytywanie strony " + page + "…");
            }
            @Override public void onPage(ArrayList<Movie> movies, int page, SearchPage stats) {
                loadingBar.setVisibility(View.GONE); initialPages++; adapter.append(movies);
                resultCount.setText(adapter.getItemCount() + " filmów");
                setStatus("p" + page + ": +" + movies.size() + " • Premium pominięte: " + stats.premium);
                if (adapter.getItemCount() < INITIAL_TARGET && initialPages < INITIAL_MAX_PAGES) h.postDelayed(repo::loadNext, 100);
            }
            @Override public void onFinished(String why) { loadingBar.setVisibility(View.GONE); setStatus(why + " • " + adapter.getItemCount() + " filmów"); }
            @Override public void onError(String e) { loadingBar.setVisibility(View.GONE); setStatus("Błąd: " + e); }
            @Override public void onVerification(boolean interactive) { setStatus(interactive ? "Weryfikacja zabezpieczeń CDA" : "Weryfikacja w tle…"); }
        });
    }

    private void showCollection(ArrayList<Movie> list, String title, String mode) {
        repo.cancelSearch();
        collectionMode = mode;
        removeMode = false;
        adapter.setRemoveMode(false);
        removeCollection.setVisibility(View.VISIBLE);
        removeCollection.setText("recent".equals(mode) ? "Usuń z oglądanych" : "Usuń z ulubionych");
        adapter.setItems(list); resultsTitle.setText(title); resultCount.setText(list.size() + " filmów"); showNav();
        if (!list.isEmpty()) grid.post(() -> {
            grid.scrollToPosition(0);
            grid.post(() -> { RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(0); if (vh != null) vh.itemView.requestFocus(); });
        });
    }

    private void toggleRemoveMode() {
        if (collectionMode == null) return;
        removeMode = !removeMode;
        adapter.setRemoveMode(removeMode);
        removeCollection.setText(removeMode ? "Anuluj usuwanie" :
                ("recent".equals(collectionMode) ? "Usuń z oglądanych" : "Usuń z ulubionych"));
        if (removeMode && adapter.getItemCount() > 0) restoreCard();
    }

    private void removeFromCollection(Movie m) {
        if (!removeMode || collectionMode == null || m == null) return;
        String label = "recent".equals(collectionMode) ? "oglądanych" : "ulubionych";
        TvDialogs.confirm(this, "Potwierdzenie", "Usunąć „" + m.title + "” z " + label + "?", () -> {
            String mode = collectionMode;
            removeMode = false;
            adapter.setRemoveMode(false);
            if ("recent".equals(mode)) {
                repo.db().removeHistory(m.id);
                showCollection(repo.db().recent(), "Ostatnio oglądane", "recent");
            } else {
                repo.db().removeFavorite(m.id);
                showCollection(repo.db().favorites(), "Ulubione", "favorites");
            }
        }, () -> {
            removeMode = false;
            adapter.setRemoveMode(false);
            removeCollection.setText("recent".equals(collectionMode) ? "Usuń z oglądanych" : "Usuń z ulubionych");
        });
    }

    private void refreshCurrentCollection() {
        if ("recent".equals(collectionMode)) {
            showCollection(repo.db().recent(), "Ostatnio oglądane", "recent");
        } else if ("favorites".equals(collectionMode)) {
            showCollection(repo.db().favorites(), "Ulubione", "favorites");
        } else {
            repo.db().decorateLocalState(adapter.items());
            adapter.notifyLocalStatesChanged();
        }
    }

    private void showMovie(Movie m) {
        focused = m; showDetail(); detailTitle.setText(m.title); images.load(m.imageUrl, detailThumb);
        detailTime.setText(m.duration + (m.positionMs > 0 ? " • oglądano " + format(m.positionMs) : ""));
        detailDescriptionPreview.setText(m.shortDescription);
        detailFavorite.setText(m.favorite ? "♥ Usuń z ulubionych" : "♡ Ulubione");
        applyMetadata(m, repo.db().getMetadata(m.id));
    }

    private void debounceMetadata(Movie m) {
        if (metadataTask != null) h.removeCallbacks(metadataTask);
        metadataTask = () -> repo.loadMetadata(m, true, new CdaRepository.MetadataListener() {
            @Override public void onMetadata(MovieMetadata md) {
                if (focused != null && focused.id.equals(m.id)) {
                    applyMetadata(m, md); adapter.updateMetadata(m.id, md); repo.resumeEnrichment();
                }
            }
            @Override public void onError(String e) {}
        });
        h.postDelayed(metadataTask, 400);
    }

    private void applyMetadata(Movie m, MovieMetadata md) {
        focusedMeta = md;
        if (md == null) {
            detailStars.setRating(m.rating);
            detailRatingExact.setText(m.rating == null ? "" : String.format(Locale.US, "%.1f / 5", m.rating));
            detailSources.setText(""); detailComments.setText("Komentarze"); return;
        }
        Double rating = md.rating != null ? md.rating : m.rating;
        detailStars.setRating(rating);
        detailRatingExact.setText(rating == null ? "" : String.format(Locale.US, "%.1f / 5", rating));
        StringBuilder sources = new StringBuilder();
        if (rating != null && md.cdaVotes != null) sources.append("CDA ").append(String.format(Locale.US, "%.1f / 5", rating)).append(" • ").append(md.cdaVotes).append(" ocen");
        if (!md.imdbRating.isEmpty()) {
            if (sources.length() > 0) sources.append('\n');
            sources.append("IMDb ").append(md.imdbRating).append(" / 10");
            if (md.imdbVotes != null) sources.append(" • ").append(md.imdbVotes).append(" głosów");
        }
        detailSources.setText(sources);
        if (!md.description.isEmpty()) detailDescriptionPreview.setText(md.description);
        detailComments.setText(md.commentCount == null ? "Komentarze" : "Komentarze (" + md.commentCount + ")");
    }

    private void loadComments() {
        if (focused == null) return;
        setStatus("Wczytywanie komentarzy…");
        repo.loadComments(focused, new CdaRepository.CommentsListener() {
            @Override public void onComments(ArrayList<CommentItem> comments) {
                detailComments.setText("Komentarze (" + comments.size() + ")"); TvDialogs.comments(MainActivity.this, comments); setStatus("Gotowe");
            }
            @Override public void onError(String e) { setStatus(e); }
        });
    }

    private void play(Movie m) {
        setStatus("Przygotowanie filmu…");
        repo.loadPlayer(m, new CdaRepository.PlayerListener() {
            @Override public void onPlayer(PlayerData p, MovieMetadata md) {
                long resume = repo.db().resumePosition(m.id);
                repo.enterPlaybackMode();
                images.trimForPlayback();
                launchedPlayer = true;
                Intent i = new Intent(MainActivity.this, PlayerActivity.class);
                i.putExtra("id", m.id); i.putExtra("title", m.title); i.putExtra("url", m.url);
                i.putExtra("durationText", m.duration); i.putExtra("image", m.imageUrl);
                i.putExtra("dash", p.dash); i.putExtra("hls", p.hls); i.putExtra("resume", resume);
                i.putExtra("description", md.description);
                if (md.rating != null) i.putExtra("rating", md.rating);
                if (md.cdaVotes != null) i.putExtra("cdaVotes", md.cdaVotes);
                i.putExtra("imdbRating", md.imdbRating);
                if (md.imdbVotes != null) i.putExtra("imdbVotes", md.imdbVotes);
                if (md.commentCount != null) i.putExtra("commentCount", md.commentCount);
                startActivity(i); setStatus("Gotowe");
            }
            @Override public void onError(String e) { setStatus(e); }
        });
    }

    private void focusDetailActions() { showDetail(); detailFavorite.requestFocus(); }

    private void restoreCard() {
        showDetail();
        grid.post(() -> {
            RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(lastCard);
            if (vh != null) vh.itemView.requestFocus();
            else {
                grid.scrollToPosition(lastCard);
                grid.post(() -> { RecyclerView.ViewHolder x = grid.findViewHolderForAdapterPosition(lastCard); if (x != null) x.itemView.requestFocus(); });
            }
        });
    }

    private void showNav() { navPanel.setVisibility(View.VISIBLE); detailPanel.setVisibility(View.GONE); }
    private void showDetail() { navPanel.setVisibility(View.GONE); detailPanel.setVisibility(View.VISIBLE); }

    private void focusYear(int year, boolean center) {
        for (int i = 0; i < yearRow.getChildCount(); i++) {
            View v = yearRow.getChildAt(i);
            if (Integer.valueOf(year).equals(v.getTag())) {
                v.requestFocus();
                int x = center ? Math.max(0, v.getLeft() - (yearScroll.getWidth() - v.getWidth()) / 2) : Math.max(0, v.getLeft() - 12);
                yearScroll.smoothScrollTo(x, 0); break;
            }
        }
    }

    private void keepYearVisible(View v) {
        int l = v.getLeft(), rr = v.getRight(), vl = yearScroll.getScrollX(), vr = vl + yearScroll.getWidth(), margin = 12;
        if (l < vl + margin) yearScroll.smoothScrollTo(Math.max(0, l - margin), 0);
        else if (rr > vr - margin) yearScroll.smoothScrollTo(rr - yearScroll.getWidth() + margin, 0);
    }

    private void setStatus(String text) { status.setText(text); }
    private int dp(int x) { return (int) (x * getResources().getDisplayMetrics().density + .5f); }
    private static String format(long ms) {
        long s = ms / 1000, hh = s / 3600, mm = (s % 3600) / 60;
        return hh > 0 ? String.format(Locale.US, "%d:%02d:%02d", hh, mm, s % 60) : String.format(Locale.US, "%d:%02d", mm, s % 60);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (updater != null) updater.onHostResume();
        if (repo != null && launchedPlayer) {
            launchedPlayer = false;
            repo.exitPlaybackMode();
            repo.db().decorateLocalState(adapter.items());
            adapter.notifyLocalStatesChanged();
            if (lastCard >= 0 && lastCard < adapter.items().size()) {
                focused = adapter.items().get(lastCard);
                showMovie(focused);
            }
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (removeMode) {
            removeMode = false;
            adapter.setRemoveMode(false);
            if (collectionMode != null) {
                removeCollection.setText("recent".equals(collectionMode) ? "Usuń z oglądanych" : "Usuń z ulubionych");
            }
            return;
        }
        if (repo.gateway().webSession().isInteractive()) {
            repo.cancelSearch(); setStatus("Weryfikacja przerwana"); return;
        }
        View f = getCurrentFocus();
        if (f != null && isDescendant(grid, f)) { focusDetailActions(); return; }
        if (f != null && isDescendant(detailPanel, f)) { showNav(); recent.requestFocus(); return; }
        super.onBackPressed();
    }

    private static boolean isDescendant(View parent, View child) {
        View x = child;
        while (x != null) {
            if (x == parent) return true;
            if (!(x.getParent() instanceof View)) break;
            x = (View) x.getParent();
        }
        return false;
    }

    @Override
    protected void onDestroy() {
        if (metadataTask != null) h.removeCallbacks(metadataTask);
        if (repo != null) repo.shutdown();
        if (images != null) images.shutdown();
        if (updater != null) updater.shutdown();
        super.onDestroy();
    }
}
