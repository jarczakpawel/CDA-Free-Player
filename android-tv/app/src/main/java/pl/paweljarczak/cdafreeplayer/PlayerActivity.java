package pl.paweljarczak.cdafreeplayer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;

@OptIn(markerClass = UnstableApi.class)
public final class PlayerActivity extends Activity {
    private static final class Source {
        final String url;
        final String kind;
        Source(String u, String k) { url = u; kind = k; }
    }

    private final Handler h = new Handler(Looper.getMainLooper());
    private final ArrayList<Source> sources = new ArrayList<>();
    private PlayerView playerView;
    private ExoPlayer player;
    private View osd, ratingTop, securityOverlay;
    private TextView seekHint, current, remaining, duration, title, ratingExact;
    private SeekBar seek;
    private StarRatingView stars;
    private ImageButton favorite, description, comments;
    private Button quality;
    private CdaRepository repo;
    private CdaDb db;
    private final Movie movie = new Movie();
    private final MovieMetadata metadata = new MovieMetadata();
    private String dash = "", hls = "", direct = "", resolved = "", resolvedKind = "";
    private boolean osdVisible = false, scrubbing = false;
    private long initialResume = 0, lastPeriodicSave = 0;
    private int sourceIndex = 0;
    private Runnable tick, hideOsd, hideHint;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_player);
        bind();
        readIntent();
        repo = new CdaRepository(this, (FrameLayout) securityOverlay, findViewById(R.id.securityHost));
        db = repo.db();
        setupPlayer();
        setupOsd();
        buildSources();
        if (sources.isEmpty()) {
            Toast.makeText(this, "Brak strumienia do odtworzenia", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        prepareSource(0, initialResume);
    }

    private void bind() {
        playerView = findViewById(R.id.playerView); osd = findViewById(R.id.osdPanel); ratingTop = findViewById(R.id.playerRating);
        seekHint = findViewById(R.id.seekHint); seek = findViewById(R.id.seekBar); current = findViewById(R.id.currentTime);
        remaining = findViewById(R.id.remainingTime); duration = findViewById(R.id.durationTime); title = findViewById(R.id.playerTitle);
        stars = findViewById(R.id.playerStars); ratingExact = findViewById(R.id.playerRatingExact); favorite = findViewById(R.id.playerFavorite);
        description = findViewById(R.id.playerDescription); comments = findViewById(R.id.playerComments); quality = findViewById(R.id.playerQuality);
        securityOverlay = findViewById(R.id.securityOverlay);
    }

    private void readIntent() {
        Intent i = getIntent();
        movie.id = safe(i.getStringExtra("id")); movie.title = safe(i.getStringExtra("title")); movie.url = safe(i.getStringExtra("url"));
        movie.duration = safe(i.getStringExtra("durationText")); movie.imageUrl = safe(i.getStringExtra("image"));
        dash = safe(i.getStringExtra("dash")); hls = safe(i.getStringExtra("hls")); direct = safe(i.getStringExtra("direct"));
        resolved = safe(i.getStringExtra("resolved")); resolvedKind = safe(i.getStringExtra("resolvedKind"));
        initialResume = i.getLongExtra("resume", 0);
        metadata.description = safe(i.getStringExtra("description"));
        if (i.hasExtra("rating")) metadata.rating = i.getDoubleExtra("rating", 0);
        if (i.hasExtra("cdaVotes")) metadata.cdaVotes = i.getIntExtra("cdaVotes", 0);
        metadata.imdbRating = safe(i.getStringExtra("imdbRating"));
        if (i.hasExtra("imdbVotes")) metadata.imdbVotes = i.getIntExtra("imdbVotes", 0);
        if (i.hasExtra("commentCount")) metadata.commentCount = i.getIntExtra("commentCount", 0);
    }

    private void setupPlayer() {
        Map<String, String> headers = new HashMap<>();
        String cookie = CookieManager.getInstance().getCookie(movie.url);
        if (cookie != null && !cookie.isEmpty()) headers.put("Cookie", cookie);
        headers.put("Referer", movie.url);

        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(WebSettings.getDefaultUserAgent(this))
                .setDefaultRequestProperties(headers)
                .setConnectTimeoutMs(10_000)
                .setReadTimeoutMs(20_000)
                .setAllowCrossProtocolRedirects(true);
        DefaultDataSource.Factory data = new DefaultDataSource.Factory(this, http);

        DefaultLoadControl load = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(8_000, 30_000, 1_000, 2_000)
                .setBackBuffer(12_000, true)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();

        DefaultRenderersFactory renderers = new DefaultRenderersFactory(this);
        if (Build.VERSION.SDK_INT <= 30) renderers.forceEnableMediaCodecAsynchronousQueueing();

        player = new ExoPlayer.Builder(this, renderers)
                .setLoadControl(load)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(this).setDataSourceFactory(data))
                .build();
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setForceHighestSupportedBitrate(true).build());

        playerView.setUseController(false);
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
        playerView.setKeepContentOnPlayerReset(true);
        playerView.setKeepScreenOn(true);
        playerView.setPlayer(player);
        playerView.requestFocus();

        player.addListener(new Player.Listener() {
            @Override public void onPlayerError(PlaybackException error) {
                long position = Math.max(initialResume, player.getCurrentPosition());
                if (sourceIndex + 1 < sources.size()) {
                    sourceIndex++;
                    prepareSource(sourceIndex, position);
                } else {
                    Toast.makeText(PlayerActivity.this,
                            "Błąd odtwarzania: " + error.getErrorCodeName(), Toast.LENGTH_LONG).show();
                }
            }
            @Override public void onTracksChanged(Tracks tracks) { updateQualityLabel(); }
        });
    }

    private void buildSources() {
        sources.clear();
        HashSet<String> seen = new HashSet<>();
        addSource(seen, resolved, resolvedKind);
        addSource(seen, dash, "dash");
        addSource(seen, hls, "hls");
        addSource(seen, direct, "direct");
    }

    private void addSource(HashSet<String> seen, String url, String kind) {
        if (url == null) return;
        String u = url.trim();
        if (u.isEmpty() || !seen.add(u)) return;
        String k = kind == null ? "" : kind.trim().toLowerCase(Locale.ROOT);
        if (k.isEmpty()) k = inferKind(u);
        sources.add(new Source(u, k));
    }

    private static String inferKind(String url) {
        String clean = url.toLowerCase(Locale.ROOT).split("\\?", 2)[0];
        if (clean.endsWith(".mpd")) return "dash";
        if (clean.endsWith(".m3u8")) return "hls";
        return "direct";
    }

    private void prepareSource(int index, long start) {
        if (index < 0 || index >= sources.size()) return;
        sourceIndex = index;
        Source src = sources.get(index);
        MediaItem.Builder b = new MediaItem.Builder().setUri(Uri.parse(src.url));
        if ("dash".equals(src.kind)) b.setMimeType(MimeTypes.APPLICATION_MPD);
        else if ("hls".equals(src.kind)) b.setMimeType(MimeTypes.APPLICATION_M3U8);
        player.setMediaItem(b.build(), true);
        player.prepare();
        if (start > 5_000) player.seekTo(start);
        player.play();
    }

    private void setupOsd() {
        title.setText(movie.title);
        movie.favorite = db.isFavorite(movie.id);
        updateFavoriteIcon();
        favorite.setOnClickListener(v -> {
            movie.favorite = db.toggleFavorite(movie);
            updateFavoriteIcon();
            resetHide();
        });
        description.setContentDescription("Opis");
        description.setOnClickListener(v -> { TvDialogs.text(this, "Opis", metadata.description); resetHide(); });
        updateCommentsDescription(metadata.commentCount);
        comments.setOnClickListener(v -> loadComments());
        quality.setOnClickListener(v -> showQuality());
        if (metadata.rating != null) {
            ratingTop.setVisibility(View.VISIBLE); stars.setRating(metadata.rating);
            ratingExact.setText(String.format(Locale.US, "%.1f / 5", metadata.rating));
        }
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int p, boolean user) { if (user && scrubbing) updateTimeLabels(p * 1000L, player.getDuration()); }
            @Override public void onStartTrackingTouch(SeekBar b) { scrubbing = true; }
            @Override public void onStopTrackingTouch(SeekBar b) { player.seekTo(b.getProgress() * 1000L); scrubbing = false; resetHide(); }
        });
        seek.setOnKeyListener((v, key, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                seekBy((key == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1) * stepForRepeat(e.getRepeatCount())); return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_DOWN) { favorite.requestFocus(); return true; }
            return false;
        });
        for (View v : new View[]{favorite, description, comments, quality}) {
            v.setOnKeyListener((x, key, e) -> {
                if (e.getAction() == KeyEvent.ACTION_DOWN && key == KeyEvent.KEYCODE_DPAD_UP) { seek.requestFocus(); return true; }
                resetHide(); return false;
            });
        }

        tick = () -> {
            long d = player.getDuration(), p = player.getCurrentPosition();
            if (d > 0 && !scrubbing) {
                seek.setMax((int) Math.min(Integer.MAX_VALUE, d / 1000));
                seek.setProgress((int) Math.min(Integer.MAX_VALUE, p / 1000));
            }
            updateTimeLabels(p, d);
            long now = android.os.SystemClock.elapsedRealtime();
            if (d > 0 && now - lastPeriodicSave >= 30_000) {
                db.saveHistory(movie, p, d);
                lastPeriodicSave = now;
            }
            h.postDelayed(tick, 500);
        };
        h.post(tick);
        hideOsd = this::hideOsd;
        hideHint = () -> seekHint.setVisibility(View.GONE);
    }

    private void updateFavoriteIcon() {
        favorite.setImageResource(movie.favorite ? R.drawable.ic_favorite : R.drawable.ic_favorite_border);
        favorite.setContentDescription(movie.favorite ? "Usuń z ulubionych" : "Dodaj do ulubionych");
    }

    private void updateCommentsDescription(Integer count) {
        comments.setContentDescription(count == null ? "Komentarze" : "Komentarze: " + count);
    }

    private void showOsd() { osdVisible = true; osd.setVisibility(View.VISIBLE); seek.requestFocus(); resetHide(); }
    private void hideOsd() { osdVisible = false; osd.setVisibility(View.GONE); playerView.requestFocus(); }
    private void resetHide() { h.removeCallbacks(hideOsd); h.postDelayed(hideOsd, 7000); }
    private long stepForRepeat(int r) { return r < 3 ? 10_000L : r < 7 ? 30_000L : 60_000L; }

    private void seekBy(long delta) {
        long d = player.getDuration(), now = player.getCurrentPosition(), to = Math.max(0, now + delta);
        if (d > 0) to = Math.min(d, to);
        player.seekTo(to);
        seekHint.setText((delta > 0 ? "+" : "") + (delta / 1000) + " s");
        seekHint.setVisibility(View.VISIBLE); h.removeCallbacks(hideHint); h.postDelayed(hideHint, 900);
        if (osdVisible) resetHide();
    }

    private void updateTimeLabels(long p, long d) {
        current.setText(fmt(p)); duration.setText(d > 0 ? fmt(d) : "--:--");
        remaining.setText(d > 0 ? "zostało " + fmt(Math.max(0, d - p)) : "");
    }

    private static String fmt(long ms) {
        if (ms < 0) return "--:--";
        long s = ms / 1000, hh = s / 3600, mm = (s % 3600) / 60;
        return hh > 0 ? String.format(Locale.US, "%d:%02d:%02d", hh, mm, s % 60) : String.format(Locale.US, "%d:%02d", mm, s % 60);
    }

    private void loadComments() {
        comments.setContentDescription("Komentarze — wczytywanie");
        repo.loadComments(movie, new CdaRepository.CommentsListener() {
            @Override public void onComments(ArrayList<CommentItem> c) { updateCommentsDescription(c.size()); TvDialogs.comments(PlayerActivity.this, c); resetHide(); }
            @Override public void onError(String e) { updateCommentsDescription(metadata.commentCount); Toast.makeText(PlayerActivity.this, e, Toast.LENGTH_LONG).show(); }
        });
    }

    private void showQuality() {
        ArrayList<String> labels = new ArrayList<>(); ArrayList<TrackSelectionOverride> overrides = new ArrayList<>();
        labels.add("Auto — najwyższa obsługiwana"); overrides.add(null);
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) if (g.getType() == C.TRACK_TYPE_VIDEO)
            for (int i = 0; i < g.length; i++) if (g.isTrackSupported(i)) {
                Format f = g.getTrackFormat(i); String label = f.height > 0 ? f.height + "p" : (f.bitrate > 0 ? (f.bitrate / 1000) + " kb/s" : "Video");
                if (!labels.contains(label)) { labels.add(label); overrides.add(new TrackSelectionOverride(g.getMediaTrackGroup(), i)); }
            }
        new AlertDialog.Builder(this).setTitle("Jakość").setItems(labels.toArray(new String[0]), (d, which) -> {
            TrackSelectionParameters.Builder b = player.getTrackSelectionParameters().buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO);
            TrackSelectionOverride override = overrides.get(which);
            if (override == null) b.setForceHighestSupportedBitrate(true);
            else b.setForceHighestSupportedBitrate(false).setOverrideForType(override);
            player.setTrackSelectionParameters(b.build()); quality.setText("Jakość: " + labels.get(which)); resetHide();
        }).show();
    }

    private void updateQualityLabel() {
        int best = 0;
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) if (g.getType() == C.TRACK_TYPE_VIDEO)
            for (int i = 0; i < g.length; i++) if (g.isTrackSelected(i)) best = Math.max(best, g.getTrackFormat(i).height);
        if (best > 0) quality.setText("Jakość: " + best + "p");
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (repo.gateway().webSession().isInteractive()) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                repo.gateway().webSession().cancelCurrent(); return true;
            }
            return super.dispatchKeyEvent(e);
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            int k = e.getKeyCode();
            if (!osdVisible && (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                seekBy((k == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1) * stepForRepeat(e.getRepeatCount())); return true;
            }
            if (k == KeyEvent.KEYCODE_DPAD_UP && !osdVisible) { showOsd(); return true; }
            if ((k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) && !osdVisible) {
                if (player.isPlaying()) player.pause(); else player.play(); return true;
            }
            if (k == KeyEvent.KEYCODE_BACK && osdVisible) { hideOsd(); return true; }
        }
        return playerView.dispatchKeyEvent(e) || super.dispatchKeyEvent(e);
    }

    @Override
    protected void onStop() {
        if (player != null && db != null) db.saveHistory(movie, player.getCurrentPosition(), player.getDuration());
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        if (player != null) {
            if (db != null) db.saveHistory(movie, player.getCurrentPosition(), player.getDuration());
            player.release();
        }
        if (repo != null) repo.shutdown();
        super.onDestroy();
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
