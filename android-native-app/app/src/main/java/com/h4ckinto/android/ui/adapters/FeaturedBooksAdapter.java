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

/**
 * Adapter for Featured Books RecyclerView
 * Displays books in a horizontal scrolling list
 */
public class FeaturedBooksAdapter extends RecyclerView.Adapter<FeaturedBooksAdapter.BookViewHolder> {
    
    private List<Book> books;
    private OnBookClickListener listener;
    
    public interface OnBookClickListener {
        void onBookClick(Book book);
    }
    
    public FeaturedBooksAdapter(List<Book> books, OnBookClickListener listener) {
        this.books = books;
        this.listener = listener;
    }
    
    @NonNull
    @Override
    public BookViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_book_featured, parent, false);
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
        
        private ImageView bookCover;
        private TextView bookTitle;
        private TextView bookAuthor;
        private TextView bookBadge;
        
        // Placeholder colors for books without covers
        private static final int[] PLACEHOLDER_COLORS = {
            0xFF1E3A5F, // Dark blue
            0xFF2D4A3F, // Dark green
            0xFF4A2D4A, // Dark purple
            0xFF4A3D2D, // Dark brown
            0xFF2D3A4A  // Dark teal
        };
        
        public BookViewHolder(@NonNull View itemView) {
            super(itemView);
            
            bookCover = itemView.findViewById(R.id.book_cover);
            bookTitle = itemView.findViewById(R.id.book_title);
            bookAuthor = itemView.findViewById(R.id.book_author);
            bookBadge = itemView.findViewById(R.id.book_badge);
        }
        
        public void bind(Book book, OnBookClickListener listener) {
            bookTitle.setText(book.getTitle());
            bookAuthor.setText(book.getAuthor());
            
            // Show featured badge if applicable
            if (book.isFeatured()) {
                bookBadge.setVisibility(View.VISIBLE);
                bookBadge.setText("مميز");
            } else {
                bookBadge.setVisibility(View.GONE);
            }
            
            // Load book cover image
            String coverUrl = book.getCoverUrl();
            if (coverUrl != null && !coverUrl.isEmpty()) {
                // Load cover from URL
                bookCover.setBackgroundColor(0x00000000); // Clear background
                ImageLoader.getInstance().load(coverUrl, bookCover, R.drawable.bg_book_cover_placeholder);
            } else {
                // No cover URL - show colored placeholder
                bookCover.setImageDrawable(null);
                int colorIndex = getAdapterPosition() >= 0 ? getAdapterPosition() % PLACEHOLDER_COLORS.length : 0;
                bookCover.setBackgroundColor(PLACEHOLDER_COLORS[colorIndex]);
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
