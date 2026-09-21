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
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;
import androidx.media3.common.util.UnstableApi;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

@OptIn(markerClass = UnstableApi.class)
public final class PlayerActivity extends Activity {
    private final Handler h = new Handler(Looper.getMainLooper());
    private PlayerView playerView;
    private ExoPlayer player;
    private View osd, ratingTop, securityOverlay;
    private TextView seekHint, current, remaining, duration, title, ratingExact;
    private SeekBar seek;
    private StarRatingView stars;
    private Button favorite, description, comments, quality;
    private CdaRepository repo;
    private CdaDb db;
    private final Movie movie = new Movie();
    private final MovieMetadata metadata = new MovieMetadata();
    private String dash = "", hls = "";
    private boolean usingHls = false, osdVisible = false, scrubbing = false;
    private long initialResume = 0, lastPeriodicSave = 0;
    private Runnable tick, hideOsd, hideHint;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_player);
        bind(); readIntent();
        repo = new CdaRepository(this, (FrameLayout) securityOverlay, findViewById(R.id.securityHost));
        db = repo.db();
        setupPlayer(); setupOsd(); prepare(false, initialResume);
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
        movie.id = i.getStringExtra("id"); movie.title = i.getStringExtra("title"); movie.url = i.getStringExtra("url");
        movie.duration = i.getStringExtra("durationText"); movie.imageUrl = i.getStringExtra("image");
        dash = i.getStringExtra("dash"); hls = i.getStringExtra("hls"); initialResume = i.getLongExtra("resume", 0);
        metadata.description = i.getStringExtra("description"); if (metadata.description == null) metadata.description = "";
        if (i.hasExtra("rating")) metadata.rating = i.getDoubleExtra("rating", 0);
        if (i.hasExtra("cdaVotes")) metadata.cdaVotes = i.getIntExtra("cdaVotes", 0);
        metadata.imdbRating = i.getStringExtra("imdbRating"); if (metadata.imdbRating == null) metadata.imdbRating = "";
        if (i.hasExtra("imdbVotes")) metadata.imdbVotes = i.getIntExtra("imdbVotes", 0);
        if (i.hasExtra("commentCount")) metadata.commentCount = i.getIntExtra("commentCount", 0);
    }

    private void setupPlayer() {
        Map<String, String> headers = new HashMap<>();
        String cookie = CookieManager.getInstance().getCookie(movie.url);
        if (cookie != null) headers.put("Cookie", cookie);
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
        // Android 9-11 devices can benefit from async MediaCodec queueing when
        // decoders otherwise stutter under load. This is particularly relevant
        // for the Android TV 9 performance floor.
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
                if (!usingHls && hls != null && !hls.isEmpty()) {
                    long position = player.getCurrentPosition();
                    usingHls = true;
                    prepare(true, position);
                } else {
                    Toast.makeText(PlayerActivity.this, "Błąd odtwarzania: " + error.getErrorCodeName(), Toast.LENGTH_LONG).show();
                }
            }
            @Override public void onTracksChanged(Tracks tracks) { updateQualityLabel(); }
        });
    }

    private void prepare(boolean useHls, long start) {
        String url = useHls ? hls : dash;
        if (url == null || url.isEmpty()) { url = useHls ? dash : hls; useHls = !useHls; }
        if (url == null || url.isEmpty()) return;
        usingHls = useHls;
        MediaItem item = new MediaItem.Builder().setUri(Uri.parse(url))
                .setMimeType(useHls ? MimeTypes.APPLICATION_M3U8 : MimeTypes.APPLICATION_MPD).build();
        player.setMediaItem(item);
        player.prepare();
        if (start > 5_000) player.seekTo(start);
        player.play();
    }

    private void setupOsd() {
        title.setText(movie.title);
        movie.favorite = db.isFavorite(movie.id);
        favorite.setText(movie.favorite ? "♥ Usuń z ulubionych" : "♡ Ulubione");
        favorite.setOnClickListener(v -> {
            movie.favorite = db.toggleFavorite(movie);
            favorite.setText(movie.favorite ? "♥ Usuń z ulubionych" : "♡ Ulubione"); resetHide();
        });
        description.setOnClickListener(v -> { TvDialogs.text(this, "Opis", metadata.description); resetHide(); });
        comments.setText(metadata.commentCount == null ? "Komentarze" : "Komentarze (" + metadata.commentCount + ")");
        comments.setOnClickListener(v -> loadComments()); quality.setOnClickListener(v -> showQuality());
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
        comments.setText("Komentarze…");
        repo.loadComments(movie, new CdaRepository.CommentsListener() {
            @Override public void onComments(ArrayList<CommentItem> c) { comments.setText("Komentarze (" + c.size() + ")"); TvDialogs.comments(PlayerActivity.this, c); resetHide(); }
            @Override public void onError(String e) { comments.setText("Komentarze"); Toast.makeText(PlayerActivity.this, e, Toast.LENGTH_LONG).show(); }
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
        if (player != null) db.saveHistory(movie, player.getCurrentPosition(), player.getDuration());
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        if (player != null) {
            db.saveHistory(movie, player.getCurrentPosition(), player.getDuration());
            player.release();
        }
        if (repo != null) repo.shutdown();
        super.onDestroy();
    }
}
