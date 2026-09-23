package pl.paweljarczak.cdafreeplayer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.CookieManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.AudioAttributes;
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
    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(UiScale.wrap(newBase));
    }

    private static final class Source {
        final String url;
        final String kind;
        Source(String u, String k) { url = u; kind = k; }
    }

    private final Handler h = new Handler(Looper.getMainLooper());
    private final ArrayList<Source> sources = new ArrayList<>();
    private PlayerView playerView;
    private ExoPlayer player;
    private View osd, ratingTop, securityOverlay, contentLoadingOverlay;
    private TextView seekHint, current, remaining, duration, title, ratingExact, contentLoadingText;
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
    private boolean resumePlaying = true, readyToSave, fatalError, contentLoading, resumeAfterVerification;
    private RequestToken contentToken;
    private View contentReturnFocus;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_player);
        bind();
        readIntent();
        repo = new CdaRepository(this, (FrameLayout) securityOverlay, findViewById(R.id.securityHost));
        repo.setPlaybackContext(true);
        repo.setVerificationObserver((interactive, background) -> {
            if (interactive) {
                if (player != null && player.getPlayWhenReady()) {
                    resumeAfterVerification = true;
                    player.pause();
                }
            } else if (resumeAfterVerification) {
                resumeAfterVerification = false;
                if (player != null) player.play();
            }
        });
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
        contentLoadingOverlay = findViewById(R.id.contentLoadingOverlay); contentLoadingText = findViewById(R.id.contentLoadingText);
        securityOverlay = findViewById(R.id.securityOverlay);
    }

    private void readIntent() {
        Intent i = getIntent();
        movie.id = safe(i.getStringExtra("id")); movie.title = safe(i.getStringExtra("title")); movie.url = safe(i.getStringExtra("url"));
        movie.duration = safe(i.getStringExtra("durationText")); movie.title = MovieTitle.clean(movie.title, movie.duration); movie.imageUrl = safe(i.getStringExtra("image"));
        movie.shortDescription = safe(i.getStringExtra("shortDescription"));
        dash = safe(i.getStringExtra("dash")); hls = safe(i.getStringExtra("hls")); direct = safe(i.getStringExtra("direct"));
        resolved = safe(i.getStringExtra("resolved")); resolvedKind = safe(i.getStringExtra("resolvedKind"));
        initialResume = Math.max(0, i.getLongExtra("resume", 0));
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
                .setUserAgent(CdaBrowserIdentity.userAgent(this))
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
        renderers.setEnableDecoderFallback(true);

        player = new ExoPlayer.Builder(this, renderers)
                .setLoadControl(load)
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                .setHandleAudioBecomingNoisy(true)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(this).setDataSourceFactory(data))
                .build();
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setForceHighestSupportedBitrate(true).build());

        playerView.setUseController(false);
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
        playerView.setKeepContentOnPlayerReset(true);
        playerView.setKeepScreenOn(false);
        playerView.setPlayer(player);
        playerView.requestFocus();

        player.addListener(new Player.Listener() {
            @Override public void onPlayerError(PlaybackException error) {
                if (player == null) return;
                saveProgress();
                Log.w("CDAFP", "Media3 error=" + error.getErrorCodeName() + "; source=" + sourceIndex);
                long position = readyToSave ? Math.max(0, player.getCurrentPosition()) : initialResume;
                if (sourceIndex + 1 < sources.size()) {
                    sourceIndex++;
                    prepareSource(sourceIndex, position);
                } else {
                    fatalError = true;
                    playerView.setKeepScreenOn(false);
                    Toast.makeText(PlayerActivity.this,
                            "Błąd odtwarzania: " + error.getErrorCodeName(), Toast.LENGTH_LONG).show();
                }
            }
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY || state == Player.STATE_ENDED) readyToSave = true;
                if (state == Player.STATE_ENDED) saveProgress();
                playerView.setKeepScreenOn(player != null && player.getPlayWhenReady() &&
                        (state == Player.STATE_BUFFERING || state == Player.STATE_READY));
            }
            @Override public void onPlayWhenReadyChanged(boolean play, int reason) {
                playerView.setKeepScreenOn(play && player != null && !fatalError &&
                        (player.getPlaybackState() == Player.STATE_READY || player.getPlaybackState() == Player.STATE_BUFFERING));
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
        initialResume = Math.max(0, start);
        readyToSave = false;
        fatalError = false;
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO).build());
        Source src = sources.get(index);
        MediaItem.Builder b = new MediaItem.Builder().setUri(Uri.parse(src.url));
        if ("dash".equals(src.kind)) b.setMimeType(MimeTypes.APPLICATION_MPD);
        else if ("hls".equals(src.kind)) b.setMimeType(MimeTypes.APPLICATION_M3U8);
        player.setMediaItem(b.build(), Math.max(0, start));
        player.prepare();
        player.setPlayWhenReady(resumePlaying);
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
        description.setOnClickListener(v -> loadDescription());
        updateCommentsDescription(metadata.commentCount);
        comments.setOnClickListener(v -> loadComments());
        quality.setOnClickListener(v -> showQuality());
        updateRatingDisplay();
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int p, boolean user) { if (user && scrubbing) updateTimeLabels(p * 1000L, player.getDuration()); }
            @Override public void onStartTrackingTouch(SeekBar b) { scrubbing = true; h.removeCallbacks(hideOsd); }
            @Override public void onStopTrackingTouch(SeekBar b) { player.seekTo(b.getProgress() * 1000L); scrubbing = false; resetHide(); }
        });
        seek.setOnKeyListener((v, key, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) {
                if (e.getRepeatCount() == 0) {
                    if (player.getPlayWhenReady()) player.pause(); else player.play();
                }
                resetHide();
                return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                seekBy((key == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1) * stepForRepeat(e.getRepeatCount())); return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_DOWN) { favorite.requestFocus(); return true; }
            return false;
        });
        for (View v : new View[]{favorite, description, comments, quality}) {
            v.setOnKeyListener((x, key, e) -> {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (key == KeyEvent.KEYCODE_DPAD_UP) { seek.requestFocus(); return true; }
                if (key == KeyEvent.KEYCODE_DPAD_DOWN) { hideOsd(); return true; }
                resetHide(); return false;
            });
        }

        tick = () -> {
            if (player == null) return;
            long d = player.getDuration(), p = player.getCurrentPosition();
            if (d > 0 && !scrubbing) {
                seek.setMax((int) Math.min(Integer.MAX_VALUE, d / 1000));
                seek.setProgress((int) Math.min(Integer.MAX_VALUE, p / 1000));
            }
            updateTimeLabels(p, d);
            long now = android.os.SystemClock.elapsedRealtime();
            if (d > 0 && now - lastPeriodicSave >= 5_000) {
                saveProgress();
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

    private void updateRatingDisplay() {
        if (metadata.rating == null) {
            stars.setRating(null);
            ratingTop.setVisibility(View.GONE);
            return;
        }
        stars.setRating(metadata.rating);
        String text = String.format(Locale.US, "%.1f / 5", metadata.rating);
        if (metadata.cdaVotes != null && metadata.cdaVotes > 0) text += " • " + metadata.cdaVotes + " ocen";
        ratingExact.setText(text);
        ratingTop.setVisibility(osdVisible ? View.VISIBLE : View.GONE);
    }

    private void showOsd() { osdVisible = true; osd.setVisibility(View.VISIBLE); updateRatingDisplay(); seek.requestFocus(); resetHide(); }
    private void hideOsd() { osdVisible = false; osd.setVisibility(View.GONE); ratingTop.setVisibility(View.GONE); playerView.requestFocus(); }
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


    private void loadDescription() {
        if (metadata.rating != null && metadata.description != null && !metadata.description.isEmpty()) {
            TvDialogs.text(this, "Opis", metadata.description, metadata);
            resetHide();
            return;
        }
        MovieMetadata cached = repo.sessionMetadata(movie.id);
        if (cached != null && cached.description != null && !cached.description.isEmpty()) {
            applyMetadata(cached);
            TvDialogs.text(this, "Opis", metadata.description, metadata);
            resetHide();
            return;
        }
        beginContentLoad("Wczytywanie opisu…", description);
        description.setContentDescription("Opis — wczytywanie");
        contentToken = repo.loadMetadata(movie, true, new CdaRepository.MetadataListener() {
            @Override public void onMetadata(MovieMetadata md) {
                applyMetadata(md);
                description.setContentDescription("Opis");
                endContentLoad();
                TvDialogs.text(PlayerActivity.this, "Opis", metadata.description, metadata);
                resetHide();
            }
            @Override public void onError(String e) {
                description.setContentDescription("Opis");
                endContentLoad();
                Toast.makeText(PlayerActivity.this, e, Toast.LENGTH_LONG).show();
                resetHide();
            }
        });
    }

    private void loadComments() {
        beginContentLoad("Wczytywanie komentarzy…", comments);
        comments.setContentDescription("Komentarze — wczytywanie");
        contentToken = repo.loadComments(movie, new CdaRepository.CommentsListener() {
            @Override public void onComments(ArrayList<CommentItem> c, MovieMetadata md) {
                applyMetadata(md);
                updateCommentsDescription(c.size());
                endContentLoad();
                TvDialogs.comments(PlayerActivity.this, c, metadata);
                resetHide();
            }
            @Override public void onError(String e) {
                updateCommentsDescription(metadata.commentCount);
                endContentLoad();
                Toast.makeText(PlayerActivity.this, e, Toast.LENGTH_LONG).show();
                resetHide();
            }
        });
    }

    private void applyMetadata(MovieMetadata md) {
        if (md == null) return;
        if (md.description != null && !md.description.isEmpty()) metadata.description = md.description;
        if (md.rating != null) metadata.rating = md.rating;
        if (md.cdaVotes != null) metadata.cdaVotes = md.cdaVotes;
        if (md.imdbRating != null && !md.imdbRating.isEmpty()) metadata.imdbRating = md.imdbRating;
        if (md.imdbVotes != null) metadata.imdbVotes = md.imdbVotes;
        if (md.commentCount != null) metadata.commentCount = md.commentCount;
        updateRatingDisplay();
        updateCommentsDescription(metadata.commentCount);
    }

    private void beginContentLoad(String text, View returnFocus) {
        if (contentLoading) return;
        contentLoading = true;
        contentReturnFocus = returnFocus;
        h.removeCallbacks(hideOsd);
        contentLoadingText.setText(text);
        contentLoadingOverlay.setVisibility(View.VISIBLE);
        contentLoadingOverlay.requestFocus();
    }

    private void endContentLoad() {
        contentToken = null;
        contentLoading = false;
        contentLoadingOverlay.setVisibility(View.GONE);
        View focus = contentReturnFocus;
        contentReturnFocus = null;
        if (osdVisible && focus != null) focus.requestFocus();
    }

    private void cancelContentLoad() {
        RequestToken token = contentToken;
        contentToken = null;
        if (token != null) repo.cancel(token);
        contentLoading = false;
        contentLoadingOverlay.setVisibility(View.GONE);
        View focus = contentReturnFocus;
        contentReturnFocus = null;
        description.setContentDescription("Opis");
        updateCommentsDescription(metadata.commentCount);
        if (osdVisible && focus != null) focus.requestFocus();
        resetHide();
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
        if (player == null) return super.dispatchKeyEvent(e);
        if (contentLoading) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getKeyCode() == KeyEvent.KEYCODE_BACK) cancelContentLoad();
            return true;
        }
        if (repo.gateway().webSession().isInteractive()) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                repo.gateway().webSession().cancelCurrent(); return true;
            }
            return super.dispatchKeyEvent(e);
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            int k = e.getKeyCode();
            if (k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE && osdVisible) {
                if (e.getRepeatCount() == 0) {
                    if (player.getPlayWhenReady()) player.pause(); else player.play();
                }
                return true;
            }
            if (!osdVisible && (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                seekBy((k == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1) * stepForRepeat(e.getRepeatCount())); return true;
            }
            if (k == KeyEvent.KEYCODE_DPAD_UP && !osdVisible) { showOsd(); return true; }
            if ((k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) && !osdVisible) {
                if (e.getRepeatCount() == 0) {
                    if (player.getPlayWhenReady()) player.pause();
                    else { if (player.getPlaybackState() == Player.STATE_ENDED) player.seekTo(0); player.play(); }
                }
                return true;
            }
            if (k == KeyEvent.KEYCODE_MEDIA_PLAY) { player.play(); return true; }
            if (k == KeyEvent.KEYCODE_MEDIA_PAUSE) { player.pause(); return true; }
            if (k == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || k == KeyEvent.KEYCODE_MEDIA_REWIND) {
                seekBy((k == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ? 1 : -1) * stepForRepeat(e.getRepeatCount())); return true;
            }
            if (k == KeyEvent.KEYCODE_BACK && osdVisible) { hideOsd(); return true; }
        }
        return super.dispatchKeyEvent(e);
    }

    private void saveProgress() {
        if (player != null && db != null && readyToSave && player.getDuration() > 0) {
            db.saveHistory(movie, player.getCurrentPosition(), player.getDuration());
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (repo != null && player == null && !sources.isEmpty()) {
            setupPlayer();
            prepareSource(sourceIndex, initialResume);
            h.post(tick);
            seekHint.setVisibility(View.GONE);
            if (osdVisible) resetHide();
        }
    }

    @Override
    protected void onStop() {
        if (contentLoading) cancelContentLoad();
        h.removeCallbacksAndMessages(null);
        if (player != null) {
            saveProgress();
            if (readyToSave) initialResume = Math.max(0, player.getCurrentPosition());
            resumePlaying = player.getPlayWhenReady();
            playerView.setPlayer(null);
            playerView.setKeepScreenOn(false);
            player.release();
            player = null;
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        if (player != null) {
            saveProgress();
            player.release();
            player = null;
        }
        if (repo != null) repo.shutdown();
        super.onDestroy();
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
