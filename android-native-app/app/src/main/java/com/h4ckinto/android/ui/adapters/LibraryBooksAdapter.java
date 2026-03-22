package com.h4ckinto.android.ui.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.h4ckinto.android.R;
import com.h4ckinto.android.ui.models.Book;
import com.h4ckinto.android.ui.utils.ImageLoader;

import java.util.List;
import java.util.Locale;

/**
 * Adapter for Library Books RecyclerView
 * Displays books in a vertical list with cover, title, author, genre, rating
 */
public class LibraryBooksAdapter extends RecyclerView.Adapter<LibraryBooksAdapter.BookViewHolder> {

    private List<Book> books;
    private OnBookClickListener listener;

    public interface OnBookClickListener {
        void onBookClick(Book book);
    }

    public LibraryBooksAdapter(List<Book> books, OnBookClickListener listener) {
        this.books = books;
        this.listener = listener;
    }

    @NonNull
    @Override
    public BookViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_book_library, parent, false);
        return new BookViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull BookViewHolder holder, int position) {
        Book book = books.get(position);
        holder.bind(book, listener);
    }

    @Override
    public int getItemCount() {
        return books != null ? books.size() : 0;
    }

    static class BookViewHolder extends RecyclerView.ViewHolder {

        private final ImageView bookCover;
        private final TextView bookTitle;
        private final TextView bookAuthor;
        private final TextView bookGenre;
        private final TextView bookPages;
        private final TextView bookRating;
        private final TextView bookViews;

        public BookViewHolder(@NonNull View itemView) {
            super(itemView);
            bookCover = itemView.findViewById(R.id.book_cover);
            bookTitle = itemView.findViewById(R.id.book_title);
            bookAuthor = itemView.findViewById(R.id.book_author);
            bookGenre = itemView.findViewById(R.id.book_genre);
            bookPages = itemView.findViewById(R.id.book_pages);
            bookRating = itemView.findViewById(R.id.book_rating);
            bookViews = itemView.findViewById(R.id.book_views);
        }

        public void bind(Book book, OnBookClickListener listener) {
            bookTitle.setText(book.getTitle());
            bookAuthor.setText(book.getAuthor());

            // Genre
            String genre = book.getGenre();
            if (genre != null && !genre.isEmpty()) {
                bookGenre.setText(genre);
                bookGenre.setVisibility(View.VISIBLE);
            } else {
                bookGenre.setVisibility(View.GONE);
            }

            // Pages
            if (book.getPages() > 0) {
                bookPages.setText(String.format(Locale.getDefault(), "%d صفحة", book.getPages()));
                bookPages.setVisibility(View.VISIBLE);
            } else {
                bookPages.setVisibility(View.GONE);
            }

            // Rating
            if (book.getRating() > 0) {
                bookRating.setText(String.format(Locale.ENGLISH, "⭐ %.1f", book.getRating()));
            } else {
                bookRating.setText("⭐ --");
            }

            // Views
            if (book.getViews() > 0) {
                bookViews.setText(String.format(Locale.getDefault(), "👁 %d", book.getViews()));
                bookViews.setVisibility(View.VISIBLE);
            } else {
                bookViews.setVisibility(View.GONE);
            }

            // Cover image
            String coverUrl = book.getCoverUrl();
            if (coverUrl != null && !coverUrl.isEmpty()) {
                ImageLoader.getInstance().load(coverUrl, bookCover, R.drawable.bg_book_cover_placeholder);
            } else {
                bookCover.setImageDrawable(null);
                int[] colors = {0xFF1E3A5F, 0xFF2D4A3F, 0xFF4A2D4A, 0xFF4A3D2D, 0xFF2D3A4A};
                int colorIndex = getAdapterPosition() >= 0 ? getAdapterPosition() % colors.length : 0;
                bookCover.setBackgroundColor(colors[colorIndex]);
            }

            // Click listener
            itemView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onBookClick(book);
                }
            });
        }
    }

    public void updateBooks(List<Book> newBooks) {
        this.books = newBooks;
        notifyDataSetChanged();
    }
}


