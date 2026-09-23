package pl.paweljarczak.cdafreeplayer;

import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

public final class MovieAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final int TYPE_MOVIE = 0;
    private static final int TYPE_HEADER = 1;
    private static final String PAYLOAD_LOCAL = "local";

    public interface Listener {
        void onFocus(Movie m, int pos, View v);
        void onClick(Movie m, int pos);
        void onLeftEdge(Movie m, int pos);
        void onTopRow(Movie m, int pos);
        void onSectionUp(int targetPos);
        void onLastRow(int pos);
    }

    private final HashSet<String> ids = new HashSet<>();
    private final ArrayList<Movie> movies = new ArrayList<>();
    private final ArrayList<Object> rows = new ArrayList<>();
    private final HashMap<String, Integer> rowById = new HashMap<>();
    private final ImageLoader images;
    private final Listener listener;
    private int columns = 4;
    private boolean removeMode;
    private boolean sectioned;

    public MovieAdapter(ImageLoader images, Listener listener) {
        this.images = images;
        this.listener = listener;
        setHasStableIds(false);
    }

    public void setColumns(int n) { columns = Math.max(1, n); }
    public void setRemoveMode(boolean enabled) { removeMode = enabled; notifyDataSetChanged(); }
    public boolean isRemoveMode() { return removeMode; }
    public ArrayList<Movie> items() { return movies; }
    public int getMovieCount() { return movies.size(); }
    public boolean isHeader(int position) { return position >= 0 && position < rows.size() && rows.get(position) instanceof String; }
    public int firstMoviePosition() {
        for (int i = 0; i < rows.size(); i++) if (rows.get(i) instanceof Movie) return i;
        return RecyclerView.NO_POSITION;
    }
    public Movie movieAtAdapterPosition(int position) {
        if (position < 0 || position >= rows.size() || !(rows.get(position) instanceof Movie)) return null;
        return (Movie) rows.get(position);
    }

    public void setSectioned(boolean enabled) {
        if (sectioned == enabled) return;
        sectioned = enabled;
        rebuildRows();
        notifyDataSetChanged();
    }

    public void setItems(Collection<Movie> source) {
        movies.clear();
        ids.clear();
        if (source != null) for (Movie movie : source) if (movie != null && ids.add(movie.id)) movies.add(movie);
        rebuildRows();
        notifyDataSetChanged();
    }

    public void append(Collection<Movie> source) {
        if (source == null || source.isEmpty()) return;
        int oldRows = rows.size();
        boolean changed = false;
        for (Movie movie : source) if (movie != null && ids.add(movie.id)) {
            movies.add(movie);
            changed = true;
        }
        if (!changed) return;
        rebuildRows();
        if (!sectioned && rows.size() >= oldRows) notifyItemRangeInserted(oldRows, rows.size() - oldRows);
        else notifyDataSetChanged();
    }

    public void updateLocalState(Movie changed) {
        if (changed == null) return;
        for (Movie m : movies) if (m.id.equals(changed.id)) {
            m.favorite = changed.favorite;
            m.positionMs = changed.positionMs;
            m.mediaDurationMs = changed.mediaDurationMs;
            Integer row = rowById.get(m.id);
            if (row != null) notifyItemChanged(row, PAYLOAD_LOCAL);
            return;
        }
    }

    public void notifyLocalStatesChanged() {
        if (!rows.isEmpty()) notifyItemRangeChanged(0, rows.size(), PAYLOAD_LOCAL);
    }

    private void rebuildRows() {
        rows.clear();
        rowById.clear();
        String lastHeader = null;
        for (Movie m : movies) {
            if (sectioned) {
                String header = m.sectionLabel == null ? "" : m.sectionLabel.trim();
                if (!header.isEmpty() && !header.equals(lastHeader)) {
                    rows.add(header);
                    lastHeader = header;
                }
            }
            rowById.put(m.id, rows.size());
            rows.add(m);
        }
    }

    @Override public int getItemViewType(int position) { return isHeader(position) ? TYPE_HEADER : TYPE_MOVIE; }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
        if (type == TYPE_HEADER) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_section_header, parent, false);
            return new HeaderHolder(v);
        }
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_movie, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof HeaderHolder) {
            ((HeaderHolder) holder).title.setText((String) rows.get(position));
            return;
        }
        Holder h = (Holder) holder;
        Movie m = (Movie) rows.get(position);
        h.root.setBackgroundResource(removeMode ? R.drawable.card_bg_remove : R.drawable.card_bg);
        m.title = MovieTitle.clean(m.title, m.duration);
        h.title.setText(m.title);
        h.duration.setText(m.duration);
        images.load(m.imageUrl, h.image);
        bindLocal(h, m);
        bindListeners(h, m);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position, @NonNull List<Object> payloads) {
        if (!(holder instanceof Holder) || payloads.isEmpty()) {
            onBindViewHolder(holder, position);
            return;
        }
        Movie m = (Movie) rows.get(position);
        boolean handled = false;
        for (Object payload : payloads) if (PAYLOAD_LOCAL.equals(payload)) {
            bindLocal((Holder) holder, m);
            handled = true;
        }
        if (!handled) onBindViewHolder(holder, position);
    }

    private static void bindLocal(Holder h, Movie m) {
        h.favorite.setVisibility(m.favorite ? View.VISIBLE : View.GONE);
        if (m.mediaDurationMs > 0 && m.positionMs > 5_000) {
            int pct = (int) Math.min(100, Math.round(m.positionMs * 100.0 / m.mediaDurationMs));
            h.progress.setProgress(pct);
            h.progress.setVisibility(View.VISIBLE);
        } else {
            h.progress.setVisibility(View.GONE);
        }
    }

    private void bindListeners(Holder h, Movie m) {
        h.root.setOnFocusChangeListener((v, focused) -> {
            if (!focused) return;
            int p = h.getBindingAdapterPosition();
            if (p == RecyclerView.NO_POSITION) return;
            listener.onFocus(m, p, v);
            if (!sectioned && p / columns >= (getItemCount() - 1) / columns) listener.onLastRow(p);
        });
        h.root.setOnClickListener(v -> {
            int p = h.getBindingAdapterPosition();
            if (p != RecyclerView.NO_POSITION) listener.onClick(m, p);
        });
        h.root.setOnKeyListener((v, key, event) -> {
            int p = h.getBindingAdapterPosition();
            if (p == RecyclerView.NO_POSITION) return false;
            if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) listener.onClick(m, p);
                return true;
            }
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            int sectionIndex = movieIndexInSection(p);
            if (key == KeyEvent.KEYCODE_DPAD_UP) {
                if (sectioned && sectionIndex < columns) {
                    int target = previousSectionTarget(p, sectionIndex);
                    if (target != RecyclerView.NO_POSITION) listener.onSectionUp(target);
                    else listener.onTopRow(m, p);
                    return true;
                }
                if (!sectioned && p < columns) {
                    listener.onTopRow(m, p);
                    return true;
                }
            }
            if (!sectioned && key == KeyEvent.KEYCODE_DPAD_DOWN && p / columns >= (getItemCount() - 1) / columns) listener.onLastRow(p);
            if (key == KeyEvent.KEYCODE_DPAD_LEFT && sectionIndex % columns == 0) {
                listener.onLeftEdge(m, p);
                return true;
            }
            return false;
        });
    }

    private int movieIndexInSection(int position) {
        if (!sectioned) return position;
        int n = 0;
        for (int i = position - 1; i >= 0; i--) {
            if (rows.get(i) instanceof String) break;
            n++;
        }
        return n;
    }

    private int previousSectionTarget(int position, int column) {
        if (!sectioned) return RecyclerView.NO_POSITION;
        int currentHeader = RecyclerView.NO_POSITION;
        for (int i = position - 1; i >= 0; i--) {
            if (rows.get(i) instanceof String) { currentHeader = i; break; }
        }
        if (currentHeader <= 0) return RecyclerView.NO_POSITION;

        int previousEnd = currentHeader - 1;
        while (previousEnd >= 0 && !(rows.get(previousEnd) instanceof Movie)) previousEnd--;
        if (previousEnd < 0) return RecyclerView.NO_POSITION;

        int previousHeader = RecyclerView.NO_POSITION;
        for (int i = previousEnd - 1; i >= 0; i--) {
            if (rows.get(i) instanceof String) { previousHeader = i; break; }
        }
        int first = previousHeader == RecyclerView.NO_POSITION ? 0 : previousHeader + 1;
        int count = previousEnd - first + 1;
        if (count <= 0) return RecyclerView.NO_POSITION;
        int lastRowSize = count % columns;
        if (lastRowSize == 0) lastRowSize = columns;
        int lastRowStart = previousEnd - lastRowSize + 1;
        return lastRowStart + Math.min(Math.max(0, column), lastRowSize - 1);
    }

    @Override public int getItemCount() { return rows.size(); }

    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        if (holder instanceof Holder) {
            Holder h = (Holder) holder;
            images.cancel(h.image);
        }
        super.onViewRecycled(holder);
    }

    static final class HeaderHolder extends RecyclerView.ViewHolder {
        final TextView title;
        HeaderHolder(View v) {
            super(v);
            title = v.findViewById(R.id.sectionTitle);
        }
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final View root;
        final ImageView image;
        final TextView title, duration, favorite;
        final ProgressBar progress;

        Holder(View v) {
            super(v);
            root = v;
            image = v.findViewById(R.id.cardImage);
            title = v.findViewById(R.id.cardTitle);
            duration = v.findViewById(R.id.cardDuration);
            favorite = v.findViewById(R.id.cardFavorite);
            progress = v.findViewById(R.id.cardProgress);
        }
    }
}
