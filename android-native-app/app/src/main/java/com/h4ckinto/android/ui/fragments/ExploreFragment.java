package com.h4ckinto.android.ui.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
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
 * Explore Fragment - Discover new books
 * Shows categories, new releases, top authors, and recommendations
 */
public class ExploreFragment extends Fragment {

    private static final String TAG = "ExploreFragment";
    
    private EditText searchInput;
    private RecyclerView newReleasesRecycler;
    private RecyclerView recommendedRecycler;
    private FeaturedBooksAdapter newReleasesAdapter;
    private FeaturedBooksAdapter recommendedAdapter;
    private List<Book> newReleasesList = new ArrayList<>();
    private List<Book> recommendedList = new ArrayList<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_explore, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        initViews(view);
        setupRecyclerViews();
        setupSearch();
        loadBooksFromApi();
    }
    
    private void initViews(View view) {
        searchInput = view.findViewById(R.id.search_input);
        newReleasesRecycler = view.findViewById(R.id.new_releases_recycler);
        recommendedRecycler = view.findViewById(R.id.recommended_recycler);
    }
    
    private void setupRecyclerViews() {
        // New Releases
        newReleasesAdapter = new FeaturedBooksAdapter(newReleasesList, this::openBookDetails);
        setupHorizontalRecycler(newReleasesRecycler, newReleasesAdapter);
        
        // Recommended
        recommendedAdapter = new FeaturedBooksAdapter(recommendedList, this::openBookDetails);
        setupHorizontalRecycler(recommendedRecycler, recommendedAdapter);
    }
    
    private void setupHorizontalRecycler(RecyclerView recyclerView, FeaturedBooksAdapter adapter) {
        LinearLayoutManager layoutManager = new LinearLayoutManager(
            getContext(),
            LinearLayoutManager.HORIZONTAL,
            false
        );
        recyclerView.setLayoutManager(layoutManager);
        recyclerView.setAdapter(adapter);
    }
    
    private void loadBooksFromApi() {
        BookApiService.getInstance().getBooks(new BookApiService.ApiCallback<List<Book>>() {
            @Override
            public void onSuccess(List<Book> books) {
                Log.d(TAG, "Loaded " + books.size() + " books from API");
                
                newReleasesList.clear();
                recommendedList.clear();
                
                // Split books between the two sections
                for (int i = 0; i < books.size(); i++) {
                    if (i % 2 == 0) {
                        newReleasesList.add(books.get(i));
                    } else {
                        recommendedList.add(books.get(i));
                    }
                }
                
                if (newReleasesList.isEmpty()) {
                    newReleasesList.addAll(createNewReleases());
                }
                if (recommendedList.isEmpty()) {
                    recommendedList.addAll(createRecommendedBooks());
                }
                
                if (newReleasesAdapter != null) newReleasesAdapter.updateBooks(newReleasesList);
                if (recommendedAdapter != null) recommendedAdapter.updateBooks(recommendedList);
            }
            
            @Override
            public void onError(String error) {
                Log.e(TAG, "Error loading books: " + error);
                newReleasesList.addAll(createNewReleases());
                recommendedList.addAll(createRecommendedBooks());
                if (newReleasesAdapter != null) newReleasesAdapter.updateBooks(newReleasesList);
                if (recommendedAdapter != null) recommendedAdapter.updateBooks(recommendedList);
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
    
    private List<Book> createNewReleases() {
        List<Book> books = new ArrayList<>();
        
        books.add(new Book("10", "الغريب", "ألبير كامو", 4.5f, "فلسفة", 159, true));
        books.add(new Book("11", "مئة عام من العزلة", "غابرييل غارسيا ماركيز", 4.7f, "رواية", 417, false));
        books.add(new Book("12", "أرض السافلين", "أحمد خالد مصطفى", 4.3f, "رعب", 520, true));
        books.add(new Book("13", "الأسود يليق بك", "أحلام مستغانمي", 4.4f, "رواية", 368, false));
        
        return books;
    }
    
    private List<Book> createRecommendedBooks() {
        List<Book> books = new ArrayList<>();
        
        books.add(new Book("20", "فن اللامبالاة", "مارك مانسون", 4.2f, "تطوير ذاتي", 224, false));
        books.add(new Book("21", "قواعد العشق الأربعون", "إليف شافاق", 4.6f, "روحاني", 432, true));
        books.add(new Book("22", "ساق البامبو", "سعود السنعوسي", 4.5f, "رواية", 396, false));
        books.add(new Book("23", "في قلبي أنثى عبرية", "خولة حمدي", 4.1f, "رواية", 448, true));
        
        return books;
    }
    
    private void setupSearch() {
        searchInput.setOnEditorActionListener((v, actionId, event) -> {
            String query = searchInput.getText().toString().trim();
            if (!query.isEmpty()) {
                Toast.makeText(getContext(), getString(R.string.search_for, query), Toast.LENGTH_SHORT).show();
                performSearch(query);
            }
            return true;
        });
    }

    private void performSearch(String query) {
        BookApiService.getInstance().searchBooks(query, new BookApiService.ApiCallback<List<Book>>() {
            @Override
            public void onSuccess(List<Book> books) {
                Log.d(TAG, "Search results for '" + query + "': " + books.size() + " books");
                
                // Show results in both recyclers
                newReleasesList.clear();
                recommendedList.clear();
                
                for (int i = 0; i < books.size(); i++) {
                    if (i % 2 == 0) {
                        newReleasesList.add(books.get(i));
                    } else {
                        recommendedList.add(books.get(i));
                    }
                }
                
                if (newReleasesAdapter != null) newReleasesAdapter.updateBooks(newReleasesList);
                if (recommendedAdapter != null) recommendedAdapter.updateBooks(recommendedList);
                
                if (books.isEmpty()) {
                    Toast.makeText(getContext(), getString(R.string.search_no_results, query), Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(getContext(), getString(R.string.search_found, books.size()), Toast.LENGTH_SHORT).show();
                }
            }
            
            @Override
            public void onError(String error) {
                Log.e(TAG, "Search error: " + error);
                Toast.makeText(getContext(), getString(R.string.search_error) + ": " + error, Toast.LENGTH_SHORT).show();
            }
        });
    }
}
