package com.h4ckinto.android.ui;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.h4ckinto.android.R;
import com.h4ckinto.android.ui.adapters.FeaturedBooksAdapter;
import com.h4ckinto.android.ui.api.BookApiService;
import com.h4ckinto.android.ui.models.Book;
import com.h4ckinto.android.ui.utils.ImageLoader;

import java.util.ArrayList;
import java.util.List;

/**
 * Book Details Activity
 * Shows comprehensive book information with beautiful UI
 */
public class BookDetailsActivity extends AppCompatActivity {

    private static final String TAG = "BookDetailsActivity";
    
    private ImageView btnBack;
    private ImageView btnShare;
    private ImageView btnMore;
    private ImageView bookCover;
    private TextView bookTitle;
    private TextView bookAuthor;
    private TextView ratingValue;
    private TextView ratingCount;
    private TextView bookDescription;
    private TextView btnReadMore;
    private FrameLayout btnBookmark;
    private Button btnStartReading;
    private RecyclerView similarBooksRecycler;
    
    // Book data from intent
    private String currentBookId;
    private String currentBookTitle;
    private String currentBookAuthor;
    private float currentBookRating;
    private int currentBookRatingCount;
    private String currentBookGenre;
    private int currentBookPages;
    private String currentCoverUrl;
    private String currentDescription;
    private String currentBookFileUrl;
    private int currentViews;
    private int currentDownloads;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        setupStatusBar();
        setContentView(R.layout.activity_book_details);
        
        getBookDataFromIntent();
        initViews();
        populateBookData();
        setupSimilarBooks();
        setupClickListeners();
        
        // Also fetch fresh data from API if we have an ID
        if (currentBookId != null) {
            fetchBookFromApi();
        }
    }
    
    private void setupStatusBar() {
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        );
        
        View decorView = getWindow().getDecorView();
        WindowInsetsControllerCompat controller = new WindowInsetsControllerCompat(getWindow(), decorView);
        controller.setAppearanceLightStatusBars(false);
        controller.setAppearanceLightNavigationBars(false);
    }
    
    private void getBookDataFromIntent() {
        currentBookId = getIntent().getStringExtra("book_id");
        currentBookTitle = getIntent().getStringExtra("book_title");
        currentBookAuthor = getIntent().getStringExtra("book_author");
        currentBookRating = getIntent().getFloatExtra("book_rating", 0f);
        currentBookRatingCount = getIntent().getIntExtra("book_rating_count", 0);
        currentBookGenre = getIntent().getStringExtra("book_genre");
        currentBookPages = getIntent().getIntExtra("book_pages", 0);
        currentCoverUrl = getIntent().getStringExtra("book_cover_url");
        currentDescription = getIntent().getStringExtra("book_description");
        currentBookFileUrl = getIntent().getStringExtra("book_file_url");
        currentViews = getIntent().getIntExtra("book_views", 0);
        currentDownloads = getIntent().getIntExtra("book_downloads", 0);
        
        // Default values if null
        if (currentBookTitle == null) currentBookTitle = "كتاب";
        if (currentBookAuthor == null) currentBookAuthor = "مؤلف";
        if (currentBookGenre == null) currentBookGenre = "غير مصنف";
    }
    
    private void fetchBookFromApi() {
        BookApiService.getInstance().getBook(currentBookId, new BookApiService.ApiCallback<Book>() {
            @Override
            public void onSuccess(Book book) {
                // Update with fresh API data
                currentBookTitle = book.getTitle();
                currentBookAuthor = book.getAuthor();
                currentBookRating = book.getRating();
                currentBookRatingCount = book.getRatingCount();
                currentBookGenre = book.getGenre();
                currentBookPages = book.getPages();
                currentViews = book.getViews();
                currentDownloads = book.getDownloads();
                
                if (book.getCoverUrl() != null) currentCoverUrl = book.getCoverUrl();
                if (book.getDescription() != null && !book.getDescription().isEmpty()) {
                    currentDescription = book.getDescription();
                }
                if (book.getBookFileUrl() != null) currentBookFileUrl = book.getBookFileUrl();
                
                populateBookData();
            }
            
            @Override
            public void onError(String error) {
                Log.e(TAG, "Error fetching book details: " + error);
            }
        });
    }
    
    private void initViews() {
        btnBack = findViewById(R.id.btn_back);
        btnShare = findViewById(R.id.btn_share);
        btnMore = findViewById(R.id.btn_more);
        bookCover = findViewById(R.id.book_cover);
        bookTitle = findViewById(R.id.book_title);
        bookAuthor = findViewById(R.id.book_author);
        ratingValue = findViewById(R.id.rating_value);
        ratingCount = findViewById(R.id.rating_count);
        bookDescription = findViewById(R.id.book_description);
        btnReadMore = findViewById(R.id.btn_read_more);
        btnBookmark = findViewById(R.id.btn_bookmark);
        btnStartReading = findViewById(R.id.btn_start_reading);
        similarBooksRecycler = findViewById(R.id.similar_books_recycler);
    }
    
    private void populateBookData() {
        bookTitle.setText(currentBookTitle);
        bookAuthor.setText(currentBookAuthor);
        
        // Rating
        if (currentBookRating > 0) {
            ratingValue.setText(String.format("%.1f", currentBookRating));
        } else {
            ratingValue.setText("--");
        }
        
        // Rating count
        if (currentBookRatingCount > 0) {
            ratingCount.setText("(" + formatArabicNumber(currentBookRatingCount) + " مراجعة)");
        } else {
            ratingCount.setText("(لا توجد مراجعات)");
        }
        
        // Load cover image
        if (currentCoverUrl != null && !currentCoverUrl.isEmpty()) {
            ImageLoader.getInstance().load(currentCoverUrl, bookCover, R.drawable.bg_book_cover_placeholder);
        }
        
        // Set description
        if (currentDescription != null && !currentDescription.isEmpty()) {
            bookDescription.setText(currentDescription);
        } else {
            bookDescription.setText("لا يوجد وصف متاح لهذا الكتاب.");
        }
    }
    
    private String formatArabicNumber(int number) {
        if (number >= 1000) {
            return String.format("%.1f", number / 1000.0) + " ألف";
        }
        return String.valueOf(number);
    }
    
    private void setupSimilarBooks() {
        BookApiService.getInstance().getBooks(new BookApiService.ApiCallback<List<Book>>() {
            @Override
            public void onSuccess(List<Book> books) {
                List<Book> filtered = new ArrayList<>();
                for (Book b : books) {
                    if (!b.getId().equals(currentBookId)) {
                        filtered.add(b);
                        if (filtered.size() >= 5) break;
                    }
                }
                
                if (filtered.isEmpty()) {
                    filtered = createSimilarBooks();
                }
                
                setupSimilarBooksRecycler(filtered);
            }
            
            @Override
            public void onError(String error) {
                setupSimilarBooksRecycler(createSimilarBooks());
            }
        });
    }
    
    private void setupSimilarBooksRecycler(List<Book> books) {
        LinearLayoutManager layoutManager = new LinearLayoutManager(
            this, LinearLayoutManager.HORIZONTAL, false
        );
        similarBooksRecycler.setLayoutManager(layoutManager);
        
        FeaturedBooksAdapter adapter = new FeaturedBooksAdapter(books, book -> {
            Intent intent = new Intent(BookDetailsActivity.this, BookDetailsActivity.class);
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
        });
        
        similarBooksRecycler.setAdapter(adapter);
    }
    
    private List<Book> createSimilarBooks() {
        List<Book> books = new ArrayList<>();
        books.add(new Book("s1", "الإنسان يبحث عن معنى", "فيكتور فرانكل", 4.7f, "فلسفة", 184, false));
        books.add(new Book("s2", "رسائل من تحت الأرض", "فيودور دوستويفسكي", 4.4f, "كلاسيكي", 136, true));
        books.add(new Book("s3", "الجريمة والعقاب", "فيودور دوستويفسكي", 4.8f, "كلاسيكي", 671, false));
        return books;
    }
    
    private void setupClickListeners() {
        btnBack.setOnClickListener(v -> finish());
        
        btnShare.setOnClickListener(v -> {
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, currentBookTitle);
            shareIntent.putExtra(Intent.EXTRA_TEXT, "أنصحك بقراءة: " + currentBookTitle + " - " + currentBookAuthor);
            startActivity(Intent.createChooser(shareIntent, "مشاركة الكتاب"));
        });
        
        btnMore.setOnClickListener(v -> showRatingDialog());
        
        btnReadMore.setOnClickListener(v -> {
            if (bookDescription.getMaxLines() == 8) {
                bookDescription.setMaxLines(Integer.MAX_VALUE);
                btnReadMore.setText("عرض أقل");
            } else {
                bookDescription.setMaxLines(8);
                btnReadMore.setText("عرض المزيد");
            }
        });
        
        btnBookmark.setOnClickListener(v -> {
            Toast.makeText(this, "تمت الإضافة للمفضلة", Toast.LENGTH_SHORT).show();
        });
        
        btnStartReading.setOnClickListener(v -> {
            if (currentBookFileUrl != null && !currentBookFileUrl.isEmpty()) {
                // Track download
                BookApiService.getInstance().trackDownload(currentBookId);
                
                Intent intent = new Intent(BookDetailsActivity.this, BookReaderViewActivity.class);
                intent.putExtra("book_title", currentBookTitle);
                intent.putExtra("book_file_url", currentBookFileUrl);
                startActivity(intent);
            } else {
                Toast.makeText(this, "ملف الكتاب غير متوفر حالياً", Toast.LENGTH_SHORT).show();
            }
        });
    }
    
    private void showRatingDialog() {
        if (currentBookId == null) return;
        
        final String[] ratings = {"⭐", "⭐⭐", "⭐⭐⭐", "⭐⭐⭐⭐", "⭐⭐⭐⭐⭐"};
        
        new AlertDialog.Builder(this, R.style.DarkAlertDialog)
            .setTitle("تقييم الكتاب")
            .setItems(ratings, (dialog, which) -> {
                int rating = which + 1;
                BookApiService.getInstance().rateBook(currentBookId, rating, new BookApiService.ApiCallback<Book>() {
                    @Override
                    public void onSuccess(Book result) {
                        currentBookRating = result.getRating();
                        currentBookRatingCount = result.getRatingCount();
                        ratingValue.setText(String.format("%.1f", currentBookRating));
                        ratingCount.setText("(" + formatArabicNumber(currentBookRatingCount) + " مراجعة)");
                        Toast.makeText(BookDetailsActivity.this, "شكراً لتقييمك!", Toast.LENGTH_SHORT).show();
                    }
                    
                    @Override
                    public void onError(String error) {
                        Toast.makeText(BookDetailsActivity.this, "خطأ في التقييم", Toast.LENGTH_SHORT).show();
                    }
                });
            })
            .show();
    }
}
