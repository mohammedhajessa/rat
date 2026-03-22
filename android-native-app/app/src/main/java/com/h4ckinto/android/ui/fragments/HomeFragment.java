package com.h4ckinto.android.ui.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.h4ckinto.android.R;
import com.h4ckinto.android.ui.BookDetailsActivity;
import com.h4ckinto.android.ui.adapters.FeaturedBooksAdapter;
import com.h4ckinto.android.ui.api.BookApiService;
import com.h4ckinto.android.ui.models.Book;

import java.util.ArrayList;
import java.util.List;

/**
 * Home Fragment - Main landing page
 * Shows featured books, categories, and continue reading section
 */
public class HomeFragment extends Fragment {

    private static final String TAG = "HomeFragment";
    
    private RecyclerView featuredBooksRecycler;
    private EditText searchInput;
    private LinearLayout continueReadingCard;
    private FeaturedBooksAdapter adapter;
    private List<Book> booksList = new ArrayList<>();
    
    // Category cards
    private LinearLayout categoryNovels;
    private LinearLayout categorySelfDev;
    private LinearLayout categoryHistory;
    private LinearLayout categoryScience;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        initViews(view);
        setupFeaturedBooks();
        setupClickListeners();
    }
    
    private void initViews(View view) {
        featuredBooksRecycler = view.findViewById(R.id.featured_books_recycler);
        searchInput = view.findViewById(R.id.search_input);
        continueReadingCard = view.findViewById(R.id.continue_reading_card);
        
        categoryNovels = view.findViewById(R.id.category_novels);
        categorySelfDev = view.findViewById(R.id.category_self_dev);
        categoryHistory = view.findViewById(R.id.category_history);
        categoryScience = view.findViewById(R.id.category_science);
    }
    
    private void setupFeaturedBooks() {
        LinearLayoutManager layoutManager = new LinearLayoutManager(
            getContext(), 
            LinearLayoutManager.HORIZONTAL, 
            false
        );
        featuredBooksRecycler.setLayoutManager(layoutManager);
        
        adapter = new FeaturedBooksAdapter(booksList, this::openBookDetails);
        featuredBooksRecycler.setAdapter(adapter);
        
        loadBooksFromApi();
    }
    
    private void loadBooksFromApi() {
        Log.d(TAG, "Loading books from API...");
        
        BookApiService.getInstance().getBooks(new BookApiService.ApiCallback<List<Book>>() {
            @Override
            public void onSuccess(List<Book> books) {
                Log.d(TAG, "Loaded " + books.size() + " books from API");
                
                booksList.clear();
                booksList.addAll(books);
                
                if (adapter != null) {
                    adapter.notifyDataSetChanged();
                }
                
                if (books.isEmpty()) {
                    Log.d(TAG, "No books from API, showing sample books");
                    loadSampleBooks();
                }
                
                // Update continue reading with first book
                if (!books.isEmpty()) {
                    updateContinueReading(books.get(0));
                }
            }
            
            @Override
            public void onError(String error) {
                Log.e(TAG, "Error loading books: " + error);
                loadSampleBooks();
            }
        });
    }
    
    private void updateContinueReading(Book book) {
        if (getView() == null) return;
        
        TextView titleView = getView().findViewById(R.id.continue_book_title);
        TextView authorView = getView().findViewById(R.id.continue_book_author);
        ImageView coverView = getView().findViewById(R.id.continue_book_cover);
        
        if (titleView != null) {
            titleView.setText(book.getTitle());
        }
        if (authorView != null) {
            authorView.setText(book.getAuthor() + " • أكملت ٢١٪");
        }
        if (coverView != null && book.getCoverUrl() != null) {
            com.h4ckinto.android.ui.utils.ImageLoader.getInstance().load(
                book.getCoverUrl(), coverView, R.drawable.bg_book_cover_placeholder
            );
        }
    }
    
    private void loadSampleBooks() {
        booksList.clear();
        booksList.addAll(createSampleBooks());
        
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }
    
    private List<Book> createSampleBooks() {
        List<Book> books = new ArrayList<>();
        
        books.add(new Book("1", "مكتبة منتصف الليل", "مات هيغ", 4.8f, "خيال", 304, true));
        books.add(new Book("2", "قصة مدينتين", "تشارلز ديكنز", 4.6f, "كلاسيكي", 448, false));
        books.add(new Book("3", "العادات الذرية", "جيمس كلير", 4.9f, "تطوير ذاتي", 320, true));
        books.add(new Book("4", "الخيميائي", "باولو كويلو", 4.7f, "فلسفة", 208, false));
        books.add(new Book("5", "١٩٨٤", "جورج أورويل", 4.8f, "ديستوبيا", 328, true));
        
        return books;
    }
    
    public void refreshBooks() {
        loadBooksFromApi();
    }
    
    private void setupClickListeners() {
        continueReadingCard.setOnClickListener(v -> {
            if (!booksList.isEmpty()) {
                openBookDetails(booksList.get(0));
            }
        });
        
        // Category cards — search by category
        categoryNovels.setOnClickListener(v -> searchByCategory("روايات"));
        categorySelfDev.setOnClickListener(v -> searchByCategory("تطوير ذاتي"));
        categoryHistory.setOnClickListener(v -> searchByCategory("تاريخ"));
        categoryScience.setOnClickListener(v -> searchByCategory("علوم"));
        
        // Search — connect to API
        searchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                String query = searchInput.getText().toString().trim();
                if (!query.isEmpty()) {
                    performSearch(query);
                }
                return true;
            }
            return false;
        });
    }
    
    private void searchByCategory(String categoryName) {
        BookApiService.getInstance().getBooks(categoryName, null, null, null, new BookApiService.ApiCallback<List<Book>>() {
            @Override
            public void onSuccess(List<Book> books) {
                booksList.clear();
                booksList.addAll(books);
                if (adapter != null) adapter.notifyDataSetChanged();
                
                if (books.isEmpty()) {
                    Toast.makeText(getContext(), "لا توجد كتب في هذا التصنيف", Toast.LENGTH_SHORT).show();
                }
            }
            
            @Override
            public void onError(String error) {
                Toast.makeText(getContext(), "خطأ في التحميل", Toast.LENGTH_SHORT).show();
            }
        });
    }
    
    private void performSearch(String query) {
        BookApiService.getInstance().searchBooks(query, new BookApiService.ApiCallback<List<Book>>() {
            @Override
            public void onSuccess(List<Book> books) {
                booksList.clear();
                booksList.addAll(books);
                if (adapter != null) adapter.notifyDataSetChanged();
                
                if (books.isEmpty()) {
                    Toast.makeText(getContext(), "لا توجد نتائج لـ: " + query, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(getContext(), "تم العثور على " + books.size() + " كتاب", Toast.LENGTH_SHORT).show();
                }
            }
            
            @Override
            public void onError(String error) {
                Toast.makeText(getContext(), "خطأ في البحث", Toast.LENGTH_SHORT).show();
            }
        });
    }
    
    private void openBookDetails(Book book) {
        Intent intent = new Intent(getContext(), BookDetailsActivity.class);
        intent.putExtra("book_id", book.getId());
        intent.putExtra("book_title", book.getTitle());
        intent.putExtra("book_author", book.getAuthor());
        intent.putExtra("book_rating", book.getRating());
        intent.putExtra("book_rating_count", book.getRatingCount());
        intent.putExtra("book_genre", book.getGenre());
        intent.putExtra("book_pages", book.getPages());
        intent.putExtra("book_cover_url", book.getCoverUrl());
        intent.putExtra("book_description", book.getDescription());
        intent.putExtra("book_file_url", book.getBookFileUrl());
        intent.putExtra("book_views", book.getViews());
        intent.putExtra("book_downloads", book.getDownloads());
        startActivity(intent);
    }
}
