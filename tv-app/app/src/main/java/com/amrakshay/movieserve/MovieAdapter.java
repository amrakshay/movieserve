package com.amrakshay.movieserve;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/** The list: folder headings interleaved with films. */
class MovieAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    interface OnMovieSelected {
        void onMovieSelected(Library.Item item);
    }

    private final List<Library.Row> rows = new ArrayList<>();
    private final OnMovieSelected listener;

    MovieAdapter(OnMovieSelected listener) {
        this.listener = listener;
    }

    void submit(List<Library.Row> next) {
        rows.clear();
        rows.addAll(next);
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        return rows.get(position).kind;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == Library.Row.HEADER) {
            return new HeaderHolder(inflater.inflate(R.layout.item_header, parent, false));
        }
        return new MovieHolder(inflater.inflate(R.layout.item_movie, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Library.Row row = rows.get(position);
        if (holder instanceof HeaderHolder) {
            ((HeaderHolder) holder).title.setText(row.title);
            return;
        }
        MovieHolder movie = (MovieHolder) holder;
        movie.title.setText(row.title);
        movie.size.setText(row.item.readableSize());
        movie.itemView.setOnClickListener(v -> listener.onMovieSelected(row.item));
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    static class HeaderHolder extends RecyclerView.ViewHolder {
        final TextView title;

        HeaderHolder(View view) {
            super(view);
            title = view.findViewById(R.id.headerTitle);
        }
    }

    static class MovieHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView size;

        MovieHolder(View view) {
            super(view);
            title = view.findViewById(R.id.movieTitle);
            size = view.findViewById(R.id.movieSize);
            // The row grows slightly when the remote lands on it, so the focused item is obvious
            // from across a room.
            view.setOnFocusChangeListener((v, focused) -> {
                float scale = focused ? 1.03f : 1f;
                v.animate().scaleX(scale).scaleY(scale).setDuration(120).start();
            });
        }
    }
}
