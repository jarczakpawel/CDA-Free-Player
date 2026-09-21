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
import java.util.List;

public final class MovieAdapter extends RecyclerView.Adapter<MovieAdapter.Holder> {
    private static final String PAYLOAD_METADATA = "metadata";
    private static final String PAYLOAD_LOCAL = "local";

    public interface Listener {
        void onFocus(Movie m, int pos, View v);
        void onClick(Movie m, int pos);
        void onLeftEdge(Movie m, int pos);
        void onLastRow(int pos);
    }

    private final ArrayList<Movie> items = new ArrayList<>();
    private final ImageLoader images;
    private final Listener listener;
    private int columns = 4;
    private boolean removeMode = false;

    public MovieAdapter(ImageLoader images, Listener listener) {
        this.images = images;
        this.listener = listener;
        setHasStableIds(true);
    }

    public void setColumns(int n) { columns = Math.max(1, n); }
    public void setRemoveMode(boolean enabled) { removeMode = enabled; notifyDataSetChanged(); }
    public boolean isRemoveMode() { return removeMode; }
    public ArrayList<Movie> items() { return items; }

    public void setItems(Collection<Movie> movies) {
        items.clear();
        items.addAll(movies);
        notifyDataSetChanged();
    }

    public void append(Collection<Movie> movies) {
        int start = items.size();
        items.addAll(movies);
        notifyItemRangeInserted(start, movies.size());
    }

    public void updateMetadata(String id, MovieMetadata md) {
        for (int i = 0; i < items.size(); i++) {
            Movie m = items.get(i);
            if (m.id.equals(id)) {
                if (md.rating != null) m.rating = md.rating;
                notifyItemChanged(i, PAYLOAD_METADATA);
                return;
            }
        }
    }

    public void updateLocalState(Movie changed) {
        for (int i = 0; i < items.size(); i++) {
            Movie m = items.get(i);
            if (m.id.equals(changed.id)) {
                m.favorite = changed.favorite;
                m.positionMs = changed.positionMs;
                m.mediaDurationMs = changed.mediaDurationMs;
                notifyItemChanged(i, PAYLOAD_LOCAL);
                return;
            }
        }
    }

    public void notifyLocalStatesChanged() {
        if (!items.isEmpty()) notifyItemRangeChanged(0, items.size(), PAYLOAD_LOCAL);
    }

    @Override public long getItemId(int p) { return items.get(p).id.hashCode(); }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup p, int t) {
        View v = LayoutInflater.from(p.getContext()).inflate(R.layout.item_movie, p, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int pos) {
        Movie m = items.get(pos);
        h.root.setBackgroundResource(removeMode ? R.drawable.card_bg_remove : R.drawable.card_bg);
        h.title.setText(m.title);
        h.duration.setText(m.duration);
        h.stars.setRating(m.rating);
        images.load(m.imageUrl, h.image);
        bindLocal(h, m);
        bindListeners(h, m);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int pos, @NonNull List<Object> payloads) {
        if (payloads.isEmpty()) {
            onBindViewHolder(h, pos);
            return;
        }
        Movie m = items.get(pos);
        boolean handled = false;
        for (Object payload : payloads) {
            if (PAYLOAD_METADATA.equals(payload)) {
                h.stars.setRating(m.rating);
                handled = true;
            } else if (PAYLOAD_LOCAL.equals(payload)) {
                bindLocal(h, m);
                handled = true;
            }
        }
        if (!handled) onBindViewHolder(h, pos);
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
            v.animate().cancel();
            v.animate().scaleX(focused ? 1.035f : 1f).scaleY(focused ? 1.035f : 1f).setDuration(75).start();
            if (!focused) return;
            int p = h.getBindingAdapterPosition();
            if (p == RecyclerView.NO_POSITION) return;
            listener.onFocus(m, p, v);
            int lastRow = (getItemCount() - 1) / columns;
            if (p / columns >= lastRow) listener.onLastRow(p);
        });
        h.root.setOnClickListener(v -> {
            int p = h.getBindingAdapterPosition();
            if (p != RecyclerView.NO_POSITION) listener.onClick(m, p);
        });
        h.root.setOnKeyListener((v, key, event) -> {
            int p = h.getBindingAdapterPosition();
            if (p == RecyclerView.NO_POSITION) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN && key == KeyEvent.KEYCODE_DPAD_LEFT && p % columns == 0) {
                listener.onLeftEdge(m, p);
                return true;
            }
            return false;
        });
    }

    @Override public int getItemCount() { return items.size(); }

    static final class Holder extends RecyclerView.ViewHolder {
        final View root;
        final ImageView image;
        final TextView title, duration, favorite;
        final StarRatingView stars;
        final ProgressBar progress;

        Holder(View v) {
            super(v);
            root = v;
            image = v.findViewById(R.id.cardImage);
            title = v.findViewById(R.id.cardTitle);
            duration = v.findViewById(R.id.cardDuration);
            favorite = v.findViewById(R.id.cardFavorite);
            stars = v.findViewById(R.id.cardStars);
            progress = v.findViewById(R.id.cardProgress);
        }
    }
}
