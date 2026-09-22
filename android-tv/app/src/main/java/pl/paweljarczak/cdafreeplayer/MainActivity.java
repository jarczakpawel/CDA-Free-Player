package pl.paweljarczak.cdafreeplayer;

import android.app.Activity;
import android.app.AlertDialog;
import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.speech.RecognitionListener;
import android.speech.SpeechRecognizer;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

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
    private static final int VOICE_SEARCH_REQUEST = 7301;
    private static final int RECORD_AUDIO_REQUEST = 7302;

    private final Handler h = new Handler(Looper.getMainLooper());
    private CdaRepository repo;
    private ImageLoader images;
    private MovieAdapter adapter;
    private GridLayoutManager gridLayout;
    private LinearLayout navPanel, detailPanel, yearRow;
    private View yearFilterBar;
    private HorizontalScrollView yearScroll;
    private RecyclerView grid;
    private TextView status, resultsTitle, resultCount, loadingText, detailTitle, detailTime, detailDescriptionPreview, voiceStatus;
    private ImageView detailThumb;
    private ImageButton detailFavorite, detailDescription, detailComments, filterButton, voiceSearch;
    private Button browse, recent, favorites, manualSearch, settingsButton, removeCollection;
    private EditText manualQuery;
    private View loadingBar;
    private FrameLayout securityOverlay, voiceOverlay;
    private String sort = "best", duration = "all", query = "lektor 1985";
    private Integer selectedYear = 1985;
    private int initialPages = 0, lastCard = 0;
    private Movie focused;
    private boolean launchedPlayer = false;
    private boolean playerPreparing = false;
    private boolean removeMode = false;
    private String collectionMode = null;
    private UpdateManager updater;
    private SpeechRecognizer speechRecognizer;
    private boolean voiceListening;
    private final ArrayList<Movie> browseItems = new ArrayList<>();
    private String browseTitle = "Lektor 1985";
    private int browseLastCard;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        bind();
        images = new ImageLoader(this);
        updater = new UpdateManager(this);
        repo = new CdaRepository(this, securityOverlay, findViewById(R.id.securityHost));
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
        yearRow = findViewById(R.id.yearRow); yearFilterBar = findViewById(R.id.yearFilterBar); filterButton = findViewById(R.id.filterButton);
        yearScroll = findViewById(R.id.yearScroll); grid = findViewById(R.id.grid); status = findViewById(R.id.status);
        resultsTitle = findViewById(R.id.resultsTitle); resultCount = findViewById(R.id.resultCount);
        loadingBar = findViewById(R.id.loadingBar); loadingText = findViewById(R.id.loadingText);
        detailThumb = findViewById(R.id.detailThumb); detailTitle = findViewById(R.id.detailTitle);
        detailTime = findViewById(R.id.detailTime);
        detailFavorite = findViewById(R.id.detailFavorite); detailDescription = findViewById(R.id.detailDescription);
        detailComments = findViewById(R.id.detailComments); detailDescriptionPreview = findViewById(R.id.detailDescriptionPreview);
        browse = findViewById(R.id.browse); recent = findViewById(R.id.recent); favorites = findViewById(R.id.favorites);
        manualQuery = findViewById(R.id.manualQuery); manualSearch = findViewById(R.id.manualSearch); voiceSearch = findViewById(R.id.voiceSearch);
        settingsButton = findViewById(R.id.settingsButton); removeCollection = findViewById(R.id.removeCollection);
        securityOverlay = findViewById(R.id.securityOverlay); voiceOverlay = findViewById(R.id.voiceOverlay); voiceStatus = findViewById(R.id.voiceStatus);
    }

    private void setupGrid() {
        int cols = getResources().getDisplayMetrics().widthPixels >= 1500 ? 4 : 3;
        gridLayout = new GridLayoutManager(this, cols);
        grid.setLayoutManager(gridLayout);
        grid.setHasFixedSize(true);
        grid.setItemViewCacheSize(cols * 3);
        adapter = new MovieAdapter(images, new MovieAdapter.Listener() {
            @Override public void onFocus(Movie m, int p, View v) {
                lastCard = p;
                if (collectionMode == null) browseLastCard = p;
                showMovie(m);
            }
            @Override public void onClick(Movie m, int p) {
                lastCard = p;
                if (removeMode) removeFromCollection(m); else play(m);
            }
            @Override public void onLeftEdge(Movie m, int p) { lastCard = p; showMovie(m); focusDetailActions(); }
            @Override public void onTopRow(Movie m, int p) {
                lastCard = p;
                if (collectionMode != null) removeCollection.requestFocus();
                else filterButton.requestFocus();
            }
            @Override public void onLastRow(int p) { repo.loadNext(); }
        });
        adapter.setColumns(cols);
        gridLayout.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override public int getSpanSize(int position) { return adapter.isHeader(position) ? cols : 1; }
        });
        grid.setAdapter(adapter);
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView view, int dx, int dy) {
                if (dy > 0 && collectionMode == null && gridLayout.findLastVisibleItemPosition() >= adapter.getItemCount() - cols) repo.loadNext();
            }
        });
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
            b.setOnClickListener(v -> {
                selectedYear = year;
                startSearch("lektor " + year, "Lektor " + year);
            });
            b.setOnFocusChangeListener((v, focused) -> { if (focused) keepYearVisible(v); });
            b.setOnKeyListener((v, key, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
                    filterButton.requestFocus();
                    return true;
                }
                return key == KeyEvent.KEYCODE_DPAD_RIGHT && year == max;
            });
            yearRow.addView(b);
        }
    }

    private void setupFilters() {
        updateFilterDescription();
        filterButton.setOnClickListener(v -> showFilterDialog());
        filterButton.setOnKeyListener((v, key, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (key == KeyEvent.KEYCODE_DPAD_UP) {
                focusYear(selectedYear == null ? DEFAULT_YEAR : selectedYear, false);
                return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (removeCollection.getVisibility() == View.VISIBLE) removeCollection.requestFocus();
                else focusFirstCard();
                return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) return true;
            return false;
        });
    }

    private void showFilterDialog() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(8), dp(24), dp(4));

        TextView sortTitle = new TextView(this);
        sortTitle.setText("Sortowanie");
        sortTitle.setTextColor(Color.WHITE);
        sortTitle.setTextSize(16);
        sortTitle.setPadding(0, dp(8), 0, dp(4));
        root.addView(sortTitle);

        RadioGroup sortGroup = new RadioGroup(this);
        String[][] sorts = {{"Najtrafniejszy", "best"}, {"Najnowsze", "date"}, {"Alfabetycznie", "alf"}};
        for (String[] x : sorts) {
            RadioButton rb = filterRadio(x[0], x[1], x[1].equals(sort));
            sortGroup.addView(rb);
        }
        root.addView(sortGroup);

        TextView durationTitle = new TextView(this);
        durationTitle.setText("Długość");
        durationTitle.setTextColor(Color.WHITE);
        durationTitle.setTextSize(16);
        durationTitle.setPadding(0, dp(12), 0, dp(4));
        root.addView(durationTitle);

        RadioGroup durationGroup = new RadioGroup(this);
        String[][] durations = {{"Każda", "all"}, {"Krótkie <5 min", "krotkie"}, {"Średnie >20 min", "srednie"}, {"Długie >60 min", "dlugie"}};
        for (String[] x : durations) {
            RadioButton rb = filterRadio(x[0], x[1], x[1].equals(duration));
            durationGroup.addView(rb);
        }
        root.addView(durationGroup);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Sortowanie i długość")
                .setView(scroll)
                .setNegativeButton("Anuluj", null)
                .setPositiveButton("Zastosuj", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String newSort = checkedTag(sortGroup, sort);
            String newDuration = checkedTag(durationGroup, duration);
            boolean changed = !newSort.equals(sort) || !newDuration.equals(duration);
            sort = newSort;
            duration = newDuration;
            updateFilterDescription();
            dialog.dismiss();
            if (changed && collectionMode == null) {
                startSearch(query, resultsTitle.getText().toString());
            }
        }));
        dialog.setOnDismissListener(ignored -> filterButton.post(filterButton::requestFocus));
        dialog.show();
    }

    private RadioButton filterRadio(String label, String value, boolean checked) {
        RadioButton rb = new RadioButton(this);
        rb.setText(label);
        rb.setTextColor(Color.WHITE);
        rb.setTextSize(15);
        rb.setId(View.generateViewId());
        rb.setTag(value);
        rb.setChecked(checked);
        rb.setFocusable(true);
        rb.setMinHeight(dp(42));
        return rb;
    }

    private static String checkedTag(RadioGroup group, String fallback) {
        int id = group.getCheckedRadioButtonId();
        if (id == -1) return fallback;
        View v = group.findViewById(id);
        Object tag = v == null ? null : v.getTag();
        return tag == null ? fallback : tag.toString();
    }

    private void updateFilterDescription() {
        String sortLabel = "best".equals(sort) ? "Najtrafniejszy" : "date".equals(sort) ? "Najnowsze" : "Alfabetycznie";
        String durationLabel = "all".equals(duration) ? "Każda długość" : "krotkie".equals(duration) ? "<5 min" : "srednie".equals(duration) ? ">20 min" : ">60 min";
        filterButton.setContentDescription("Filtry: " + sortLabel + ", " + durationLabel);
    }

    private void focusFirstCard() {
        int pos = adapter.firstMoviePosition();
        if (pos == RecyclerView.NO_POSITION) return;
        grid.scrollToPosition(pos);
        grid.post(() -> {
            RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
            if (vh != null) vh.itemView.requestFocus();
        });
    }

    private void setupLeft() {
        browse.setOnClickListener(v -> showBrowse(true));
        recent.setOnClickListener(v -> showCollection(repo.db().recent(), "Ostatnio oglądane", "recent"));
        favorites.setOnClickListener(v -> showCollection(repo.db().favorites(), "Ulubione", "favorites"));
        manualSearch.setOnClickListener(v -> manualSearch());
        voiceSearch.setOnClickListener(v -> startVoiceSearch());
        settingsButton.setOnClickListener(v -> SettingsDialog.show(this, repo.db(), updater, this::refreshCurrentCollection));
        removeCollection.setOnClickListener(v -> toggleRemoveMode());
        manualQuery.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEARCH) { manualSearch(); return true; }
            return false;
        });
        manualQuery.setOnKeyListener((v, key, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN && key == KeyEvent.KEYCODE_DPAD_DOWN) {
                manualSearch.requestFocus();
                return true;
            }
            return false;
        });
        for (View v : new View[]{browse, recent, favorites, settingsButton}) {
            v.setOnKeyListener((x, key, event) -> {
                if (event.getAction() == KeyEvent.ACTION_DOWN && key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (collectionMode != null) removeCollection.requestFocus();
                    else focusYear(selectedYear == null ? DEFAULT_YEAR : selectedYear, false);
                    return true;
                }
                return false;
            });
        }
        manualSearch.setOnKeyListener((v, key, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (key == KeyEvent.KEYCODE_DPAD_DOWN) { voiceSearch.requestFocus(); return true; }
            if (key == KeyEvent.KEYCODE_DPAD_RIGHT) { if (collectionMode != null) removeCollection.requestFocus(); else focusYear(selectedYear == null ? DEFAULT_YEAR : selectedYear, false); return true; }
            return false;
        });
        voiceSearch.setOnKeyListener((v, key, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (key == KeyEvent.KEYCODE_DPAD_UP) { manualSearch.requestFocus(); return true; }
            if (key == KeyEvent.KEYCODE_DPAD_DOWN) { settingsButton.requestFocus(); return true; }
            if (key == KeyEvent.KEYCODE_DPAD_LEFT) { manualSearch.requestFocus(); return true; }
            if (key == KeyEvent.KEYCODE_DPAD_RIGHT) { if (collectionMode != null) removeCollection.requestFocus(); else focusYear(selectedYear == null ? DEFAULT_YEAR : selectedYear, false); return true; }
            return false;
        });
        removeCollection.setOnKeyListener((v, key, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (key == KeyEvent.KEYCODE_DPAD_UP) { focusSectionButton(); return true; }
            if (key == KeyEvent.KEYCODE_DPAD_DOWN) { focusFirstCard(); return true; }
            return key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT;
        });
        detailFavorite.setOnClickListener(v -> {
            if (focused == null) return;
            focused.favorite = repo.db().toggleFavorite(focused);
            updateDetailFavoriteIcon();
            adapter.updateLocalState(focused);
        });
        detailDescription.setOnClickListener(v -> loadDescription());
        detailComments.setOnClickListener(v -> loadComments());

        detailFavorite.setOnKeyListener((v, key, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (key == KeyEvent.KEYCODE_DPAD_RIGHT) { detailDescription.requestFocus(); return true; }
            return false;
        });
        detailDescription.setOnKeyListener((v, key, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (key == KeyEvent.KEYCODE_DPAD_LEFT) { detailFavorite.requestFocus(); return true; }
            if (key == KeyEvent.KEYCODE_DPAD_RIGHT) { detailComments.requestFocus(); return true; }
            return false;
        });
        detailComments.setOnKeyListener((v, key, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (key == KeyEvent.KEYCODE_DPAD_LEFT) { detailDescription.requestFocus(); return true; }
            if (key == KeyEvent.KEYCODE_DPAD_RIGHT) { restoreCard(); return true; }
            return false;
        });
    }

    private void startVoiceSearch() {
        if (voiceListening) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, RECORD_AUDIO_REQUEST);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            startVoiceActivityFallback();
            return;
        }
        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            speechRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) { showVoiceOverlay("Mów…"); }
                @Override public void onBeginningOfSpeech() { showVoiceOverlay("Słucham…"); }
                @Override public void onRmsChanged(float rmsdB) {}
                @Override public void onBufferReceived(byte[] buffer) {}
                @Override public void onEndOfSpeech() { if (voiceListening) showVoiceOverlay("Rozpoznawanie…"); }
                @Override public void onError(int error) {
                    stopVoiceUi();
                    if (error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) recreateSpeechRecognizer();
                    if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                        Toast.makeText(MainActivity.this, "Brak dostępu do mikrofonu", Toast.LENGTH_LONG).show();
                    } else if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        Toast.makeText(MainActivity.this, "Nie rozpoznano mowy", Toast.LENGTH_SHORT).show();
                    } else {
                        startVoiceActivityFallback();
                    }
                }
                @Override public void onResults(Bundle results) {
                    ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    stopVoiceUi();
                    applyVoiceResults(matches);
                }
                @Override public void onPartialResults(Bundle partialResults) {
                    ArrayList<String> matches = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches != null && !matches.isEmpty() && matches.get(0) != null && !matches.get(0).trim().isEmpty())
                        showVoiceOverlay(matches.get(0).trim());
                }
                @Override public void onEvent(int eventType, Bundle params) {}
            });
        }
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag());
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        try {
            voiceListening = true;
            showVoiceOverlay("Mów…");
            speechRecognizer.startListening(intent);
        } catch (RuntimeException e) {
            stopVoiceUi();
            recreateSpeechRecognizer();
            startVoiceActivityFallback();
        }
    }

    private void showVoiceOverlay(String text) {
        voiceStatus.setText(text);
        voiceOverlay.setVisibility(View.VISIBLE);
        voiceOverlay.requestFocus();
    }

    private void stopVoiceUi() {
        voiceListening = false;
        voiceOverlay.setVisibility(View.GONE);
    }

    private void cancelVoiceSearch() {
        if (speechRecognizer != null && voiceListening) speechRecognizer.cancel();
        stopVoiceUi();
        voiceSearch.requestFocus();
    }

    private void recreateSpeechRecognizer() {
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
    }

    private void startVoiceActivityFallback() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag());
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        try {
            startActivityForResult(intent, VOICE_SEARCH_REQUEST);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Brak systemowej usługi rozpoznawania mowy", Toast.LENGTH_LONG).show();
            voiceSearch.requestFocus();
        }
    }

    private void applyVoiceResults(ArrayList<String> results) {
        if (results == null) return;
        for (String value : results) {
            String spoken = value == null ? "" : value.trim();
            if (spoken.isEmpty()) continue;
            manualQuery.setText(spoken);
            manualQuery.setSelection(spoken.length());
            selectedYear = null;
            startSearch(spoken, "Wyniki: " + spoken);
            return;
        }
        voiceSearch.requestFocus();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != RECORD_AUDIO_REQUEST) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) startVoiceSearch();
        else Toast.makeText(this, "Wyszukiwanie głosowe wymaga dostępu do mikrofonu", Toast.LENGTH_LONG).show();
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != VOICE_SEARCH_REQUEST || resultCode != RESULT_OK || data == null) return;
        applyVoiceResults(data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS));
    }

    private void manualSearch() {
        String q = manualQuery.getText().toString().trim();
        if (q.isEmpty()) return;
        selectedYear = null;
        startSearch(q, "Wyniki: " + q);
    }

    private void startSearch(String q, String title) {
        collectionMode = null; removeMode = false; adapter.setRemoveMode(false); adapter.setSectioned(false); removeCollection.setVisibility(View.GONE);
        yearFilterBar.setVisibility(View.VISIBLE);
        browseTitle = title; browseItems.clear(); browseLastCard = 0;
        repo.cancelPlayer(); playerPreparing = false; focused = null; lastCard = 0;
        query = q; initialPages = 0; adapter.setItems(Collections.emptyList());
        resultsTitle.setText(title); resultCount.setText(""); showNav();
        repo.startSearch(q, sort, duration, new CdaRepository.SearchListener() {
            @Override public void onLoading(int page) {
                loadingBar.setVisibility(View.VISIBLE); loadingText.setText("Wczytywanie strony " + page + "…");
                setStatus("Wczytywanie strony " + page + "…");
            }
            @Override public void onPage(ArrayList<Movie> movies, int page, SearchPage stats) {
                int previousCount = adapter.getMovieCount();
                loadingBar.setVisibility(View.GONE); initialPages++; adapter.append(movies);
                browseItems.clear(); browseItems.addAll(adapter.items());
                resultCount.setText(adapter.getMovieCount() + " filmów");
                setStatus("p" + page + ": +" + movies.size() + " • Premium pominięte: " + stats.premium);
                if (adapter.getMovieCount() == previousCount || adapter.getMovieCount() < INITIAL_TARGET && initialPages < INITIAL_MAX_PAGES) h.postDelayed(repo::loadNext, 100);
            }
            @Override public void onFinished(String why) { loadingBar.setVisibility(View.GONE); setStatus(why + " • " + adapter.getMovieCount() + " filmów"); }
            @Override public void onError(String e) { loadingBar.setVisibility(View.GONE); setStatus("Błąd: " + e); }
            @Override public void onVerification(boolean interactive) { setStatus(interactive ? "Weryfikacja zabezpieczeń CDA" : "Weryfikacja w tle…"); }
        });
    }

    private void showCollection(ArrayList<Movie> list, String title, String mode) {
        if (collectionMode == null) {
            browseItems.clear(); browseItems.addAll(adapter.items()); browseTitle = resultsTitle.getText().toString(); browseLastCard = lastCard;
        }
        repo.cancelPlayer(); playerPreparing = false; focused = null; lastCard = 0;
        repo.pauseBrowseSearch();
        loadingBar.setVisibility(View.GONE);
        collectionMode = mode;
        removeMode = false;
        adapter.setRemoveMode(false);
        adapter.setSectioned(true);
        yearFilterBar.setVisibility(View.GONE);
        removeCollection.setVisibility(View.VISIBLE);
        removeCollection.setText("recent".equals(mode) ? "Usuń z oglądanych" : "Usuń z ulubionych");
        adapter.setItems(list); resultsTitle.setText(title); resultCount.setText(adapter.getMovieCount() + " filmów"); showNav();
        lastCard = adapter.firstMoviePosition();
        if (!list.isEmpty()) grid.post(this::focusFirstCard);
    }

    private void showBrowse(boolean focusContent) {
        collectionMode = null; removeMode = false; adapter.setRemoveMode(false); adapter.setSectioned(false);
        removeCollection.setVisibility(View.GONE); yearFilterBar.setVisibility(View.VISIBLE);
        if (browseItems.isEmpty()) {
            startSearch(query, browseTitle);
            if (focusContent) grid.postDelayed(this::focusFirstCard, 180);
            return;
        }
        repo.resumeBrowseSearch();
        adapter.setItems(browseItems); resultsTitle.setText(browseTitle); resultCount.setText(adapter.getMovieCount() + " filmów");
        lastCard = Math.max(0, Math.min(browseLastCard, adapter.getItemCount() - 1));
        showNav();
        if (focusContent) restoreBrowseCard();
    }

    private void restoreBrowseCard() {
        int pos = adapter.isHeader(lastCard) ? adapter.firstMoviePosition() : lastCard;
        if (pos == RecyclerView.NO_POSITION) return;
        grid.scrollToPosition(pos);
        grid.post(() -> {
            RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
            if (vh != null) vh.itemView.requestFocus();
            else focusFirstCard();
        });
    }

    private Button sectionButton() {
        if ("recent".equals(collectionMode)) return recent;
        if ("favorites".equals(collectionMode)) return favorites;
        return browse;
    }

    private void focusSectionButton() { showNav(); sectionButton().requestFocus(); }

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
        focused = m;
        m.title = MovieTitle.clean(m.title, m.duration);
        showDetail();
        detailTitle.setText(m.title);
        images.load(m.imageUrl, detailThumb);
        detailTime.setText(m.duration + (m.positionMs > 0 ? " • oglądano " + format(m.positionMs) : ""));
        detailDescriptionPreview.setText(m.shortDescription == null ? "" : m.shortDescription);
        updateDetailFavoriteIcon();
        updateDetailCommentsDescription(null);
    }

    private void loadDescription() {
        Movie m = focused;
        if (m == null) return;
        MovieMetadata cached = repo.db().getMetadata(m.id);
        if (cached != null && cached.description != null && !cached.description.isEmpty()) {
            TvDialogs.text(this, "Opis", cached.description);
            return;
        }
        setStatus("Wczytywanie pełnego opisu…");
        repo.loadMetadata(m, true, new CdaRepository.MetadataListener() {
            @Override public void onMetadata(MovieMetadata md) {
                String d = md == null || md.description == null || md.description.isEmpty()
                        ? (m.shortDescription == null ? "" : m.shortDescription)
                        : md.description;
                setStatus("Gotowe");
                TvDialogs.text(MainActivity.this, "Opis", d);
            }
            @Override public void onError(String e) {
                setStatus("Błąd: " + e);
                Toast.makeText(MainActivity.this, e, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void loadComments() {
        if (focused == null) return;
        setStatus("Wczytywanie komentarzy…");
        repo.loadComments(focused, new CdaRepository.CommentsListener() {
            @Override public void onComments(ArrayList<CommentItem> comments) {
                updateDetailCommentsDescription(comments.size());
                TvDialogs.comments(MainActivity.this, comments);
                setStatus("Gotowe");
            }
            @Override public void onError(String e) {
                setStatus(e);
                Toast.makeText(MainActivity.this, e, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void updateDetailFavoriteIcon() {
        if (focused == null) return;
        detailFavorite.setImageResource(focused.favorite ? R.drawable.ic_favorite : R.drawable.ic_favorite_border);
        detailFavorite.setContentDescription(focused.favorite ? "Usuń z ulubionych" : "Dodaj do ulubionych");
    }

    private void updateDetailCommentsDescription(Integer count) {
        detailComments.setContentDescription(count == null ? "Komentarze" : "Komentarze, " + count);
    }

    private void play(Movie m) {
        if (playerPreparing) return;
        playerPreparing = true;
        loadingBar.setVisibility(View.GONE);
        setStatus("Przygotowanie filmu…");
        Toast.makeText(this, "Przygotowanie filmu…", Toast.LENGTH_SHORT).show();
        repo.loadPlayer(m, new CdaRepository.PlayerListener() {
            @Override public void onPlayer(PlayerData p, MovieMetadata md) {
                playerPreparing = false;
                long resume = repo.db().resumePosition(m.id);
                repo.enterPlaybackMode();
                images.trimForPlayback();
                launchedPlayer = true;
                Intent i = new Intent(MainActivity.this, PlayerActivity.class);
                m.title = MovieTitle.clean(m.title, m.duration);
                i.putExtra("id", m.id); i.putExtra("title", m.title); i.putExtra("url", m.url);
                i.putExtra("durationText", m.duration); i.putExtra("image", m.imageUrl);
                i.putExtra("dash", p.dash); i.putExtra("hls", p.hls); i.putExtra("direct", p.direct);
                i.putExtra("resolved", p.resolved); i.putExtra("resolvedKind", p.resolvedKind); i.putExtra("resume", resume);
                i.putExtra("description", md.description);
                if (m.rating != null) i.putExtra("rating", m.rating);
                try {
                    startActivity(i);
                    setStatus("Gotowe");
                } catch (RuntimeException e) {
                    launchedPlayer = false;
                    repo.exitPlaybackMode();
                    setStatus("Nie można uruchomić odtwarzacza");
                }
            }
            @Override public void onError(String e) {
                playerPreparing = false;
                setStatus("Błąd: " + e);
                Toast.makeText(MainActivity.this, e, Toast.LENGTH_LONG).show();
            }
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
            if ("recent".equals(collectionMode)) adapter.setItems(repo.db().recent());
            else if ("favorites".equals(collectionMode)) adapter.setItems(repo.db().favorites());
            else {
                repo.db().decorateLocalState(adapter.items());
                adapter.notifyLocalStatesChanged();
            }
            resultCount.setText(adapter.getMovieCount() + " filmów");
            lastCard = Math.max(0, Math.min(lastCard, adapter.getItemCount() - 1));
            focused = adapter.movieAtAdapterPosition(lastCard);
            if (focused != null) {
                showMovie(focused);
                restoreCard();
            }
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (voiceListening || voiceOverlay.getVisibility() == View.VISIBLE) { cancelVoiceSearch(); return; }
        if (playerPreparing) {
            repo.cancelPlayer(); playerPreparing = false; setStatus("Przygotowanie filmu przerwane"); return;
        }
        if (removeMode) {
            removeMode = false; adapter.setRemoveMode(false);
            if (collectionMode != null) removeCollection.setText("recent".equals(collectionMode) ? "Usuń z oglądanych" : "Usuń z ulubionych");
            return;
        }
        if (repo.gateway().webSession().isInteractive()) {
            repo.gateway().webSession().cancelCurrent(); setStatus("Weryfikacja przerwana"); return;
        }
        View f = getCurrentFocus();
        if (f != null && isDescendant(detailPanel, f)) { focusSectionButton(); return; }
        if (f != null && (isDescendant(grid, f) || isDescendant(yearFilterBar, f) || f == removeCollection)) { focusSectionButton(); return; }
        if (f != null && isDescendant(navPanel, f)) {
            if (collectionMode != null) { showBrowse(false); browse.requestFocus(); return; }
            showExitConfirmation();
            return;
        }
        focusSectionButton();
    }

    private void showExitConfirmation() {
        TvDialogs.confirm(this, "Zamknąć aplikację?", "Czy na pewno chcesz zamknąć CDA Free Player?", this::exitCompletely, null);
    }

    private void exitCompletely() {
        if (repo != null) { repo.cancelSearch(); repo.cancelPlayer(); }
        finishAffinity();
        android.os.Process.killProcess(android.os.Process.myPid());
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
    protected void onStop() {
        if (voiceListening && speechRecognizer != null) speechRecognizer.cancel();
        stopVoiceUi();
        if (playerPreparing && !launchedPlayer) {
            repo.cancelPlayer();
            playerPreparing = false;
            setStatus("Przygotowanie filmu przerwane");
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        if (speechRecognizer != null) { speechRecognizer.destroy(); speechRecognizer = null; }
        if (repo != null) repo.shutdown();
        if (images != null) images.shutdown();
        if (updater != null) updater.shutdown();
        super.onDestroy();
    }
}
