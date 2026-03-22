package com.h4ckinto.android.ui.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.h4ckinto.android.R;
import com.h4ckinto.android.ui.BookDetailsActivity;
import com.h4ckinto.android.ui.adapters.LibraryBooksAdapter;
import com.h4ckinto.android.ui.api.BookApiService;
import com.h4ckinto.android.ui.models.Book;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Library Fragment - Browse all books from the backend
 * Shows all books with filter tabs (All, Featured, Top Rated, Most Viewed)
 */
public class LibraryFragment extends Fragment {

    private static final String TAG = "LibraryFragment";

    private TextView tabAll;
    private TextView tabFeatured;
    private TextView tabTopRated;
    private TextView tabMostViewed;
    private TextView booksCount;
    private ProgressBar loadingIndicator;
    private LinearLayout emptyState;
    private RecyclerView libraryBooksRecycler;

    private LibraryBooksAdapter adapter;
    private List<Book> allBooks = new ArrayList<>();
    private List<Book> displayedBooks = new ArrayList<>();
    private String currentTab = "all";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_library, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        initViews(view);
        setupRecyclerView();
        setupTabListeners();
        loadBooksFromApi();
    }

    private void initViews(View view) {
        tabAll = view.findViewById(R.id.tab_all);
        tabFeatured = view.findViewById(R.id.tab_featured);
        tabTopRated = view.findViewById(R.id.tab_top_rated);
        tabMostViewed = view.findViewById(R.id.tab_most_viewed);
        booksCount = view.findViewById(R.id.books_count);
        loadingIndicator = view.findViewById(R.id.loading_indicator);
        emptyState = view.findViewById(R.id.empty_state);
        libraryBooksRecycler = view.findViewById(R.id.library_books_recycler);
    }

    private void setupRecyclerView() {
        LinearLayoutManager layoutManager = new LinearLayoutManager(getContext());
        libraryBooksRecycler.setLayoutManager(layoutManager);

        adapter = new LibraryBooksAdapter(displayedBooks, this::openBookDetails);
        libraryBooksRecycler.setAdapter(adapter);
    }

    private void setupTabListeners() {
        tabAll.setOnClickListener(v -> {
            currentTab = "all";
            selectTab(tabAll);
            filterBooks();
        });
        tabFeatured.setOnClickListener(v -> {
            currentTab = "featured";
            selectTab(tabFeatured);
            filterBooks();
        });
        tabTopRated.setOnClickListener(v -> {
            currentTab = "top_rated";
            selectTab(tabTopRated);
            filterBooks();
        });
        tabMostViewed.setOnClickListener(v -> {
            currentTab = "most_viewed";
            selectTab(tabMostViewed);
            filterBooks();
        });
    }

    private void selectTab(TextView selectedTab) {
        // Reset all tabs
        resetTabStyle(tabAll);
        resetTabStyle(tabFeatured);
        resetTabStyle(tabTopRated);
        resetTabStyle(tabMostViewed);

        // Highlight selected tab
        selectedTab.setBackgroundResource(R.drawable.bg_chip_selected);
        selectedTab.setTextColor(getResources().getColor(R.color.background_dark, null));
    }

    private void resetTabStyle(TextView tab) {
        tab.setBackgroundResource(R.drawable.bg_chip_unselected);
        tab.setTextColor(getResources().getColor(R.color.text_secondary, null));
    }

    private void loadBooksFromApi() {
        Log.d(TAG, "Loading all books from API...");
        showLoading(true);

        BookApiService.getInstance().getBooks(new BookApiService.ApiCallback<List<Book>>() {
            @Override
            public void onSuccess(List<Book> books) {
                Log.d(TAG, "Loaded " + books.size() + " books from API");
                showLoading(false);

                allBooks.clear();
                allBooks.addAll(books);

                filterBooks();
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "Error loading books: " + error);
                showLoading(false);

                if (allBooks.isEmpty()) {
                    showEmpty(true);
                }
                Toast.makeText(getContext(), "خطأ في تحميل الكتب: " + error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void filterBooks() {
        displayedBooks.clear();

        switch (currentTab) {
            case "featured":
                for (Book b : allBooks) {
                    if (b.isFeatured()) {
                        displayedBooks.add(b);
                    }
                }
                break;

            case "top_rated":
                List<Book> ratedBooks = new ArrayList<>(allBooks);
                Collections.sort(ratedBooks, (a, b) -> Float.compare(b.getRating(), a.getRating()));
                displayedBooks.addAll(ratedBooks);
                break;

            case "most_viewed":
                List<Book> viewedBooks = new ArrayList<>(allBooks);
                Collections.sort(viewedBooks, (a, b) -> Integer.compare(b.getViews(), a.getViews()));
                displayedBooks.addAll(viewedBooks);
                break;

            case "all":
            default:
                displayedBooks.addAll(allBooks);
                break;
        }

        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }

        updateBooksCount();
        showEmpty(displayedBooks.isEmpty() && !allBooks.isEmpty());
    }

    private void updateBooksCount() {
        String tabLabel;
        switch (currentTab) {
            case "featured":
                tabLabel = "الكتب المميزة";
                break;
            case "top_rated":
                tabLabel = "الأعلى تقييماً";
                break;
            case "most_viewed":
                tabLabel = "الأكثر مشاهدة";
                break;
            default:
                tabLabel = "جميع الكتب";
                break;
        }
        booksCount.setText(String.format(Locale.getDefault(), "%s (%d)", tabLabel, displayedBooks.size()));
    }

    private void showLoading(boolean show) {
        if (loadingIndicator != null) {
            loadingIndicator.setVisibility(show ? View.VISIBLE : View.GONE);
        }
        if (libraryBooksRecycler != null) {
            libraryBooksRecycler.setVisibility(show ? View.GONE : View.VISIBLE);
        }
    }

    private void showEmpty(boolean show) {
        if (emptyState != null) {
            emptyState.setVisibility(show ? View.VISIBLE : View.GONE);
        }
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

    /**
     * Refresh books from API (can be called externally)
     */
    public void refreshBooks() {
        loadBooksFromApi();
    }
}
