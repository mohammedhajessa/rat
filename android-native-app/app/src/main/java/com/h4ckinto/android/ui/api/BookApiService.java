package com.h4ckinto.android.ui.api;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.h4ckinto.android.ui.models.Book;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * API Service for fetching books from the book backend
 * All book data is in Arabic
 */
public class BookApiService {
    
    private static final String TAG = "BookApiService";
    
    // Base URL for book API
    public static String BASE_URL = "http://3.136.231.237/book";
    
    private static BookApiService instance;
    private final ExecutorService executor;
    private final Handler mainHandler;
    
    private BookApiService() {
        executor = Executors.newCachedThreadPool();
        mainHandler = new Handler(Looper.getMainLooper());
    }
    
    public static synchronized BookApiService getInstance() {
        if (instance == null) {
            instance = new BookApiService();
        }
        return instance;
    }
    
    public static void setBaseUrl(String url) {
        BASE_URL = url;
        Log.d(TAG, "API Base URL set to: " + url);
    }
    
    public interface ApiCallback<T> {
        void onSuccess(T result);
        void onError(String error);
    }
    
    /**
     * Fetch all books
     */
    public void getBooks(ApiCallback<List<Book>> callback) {
        getBooks(null, null, null, null, callback);
    }
    
    /**
     * Fetch books with filters
     */
    public void getBooks(String category, Boolean featured, String search, Integer limit, ApiCallback<List<Book>> callback) {
        executor.execute(() -> {
            try {
                StringBuilder urlBuilder = new StringBuilder(BASE_URL + "/api/books?");
                
                if (category != null) urlBuilder.append("category=").append(java.net.URLEncoder.encode(category, "UTF-8")).append("&");
                if (featured != null && featured) urlBuilder.append("featured=true&");
                if (search != null) urlBuilder.append("search=").append(java.net.URLEncoder.encode(search, "UTF-8")).append("&");
                if (limit != null) urlBuilder.append("limit=").append(limit);
                
                String response = makeGetRequest(urlBuilder.toString());
                
                if (response != null) {
                    JSONObject json = new JSONObject(response);
                    
                    if (json.getBoolean("success")) {
                        JSONArray booksArray = json.getJSONArray("books");
                        List<Book> books = parseBooksFromJson(booksArray);
                        mainHandler.post(() -> callback.onSuccess(books));
                    } else {
                        mainHandler.post(() -> callback.onError("API error"));
                    }
                } else {
                    mainHandler.post(() -> callback.onError("Network error"));
                }
            } catch (Exception e) {
                Log.e(TAG, "Error fetching books: " + e.getMessage());
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }
    
    /**
     * Fetch featured books
     */
    public void getFeaturedBooks(int limit, ApiCallback<List<Book>> callback) {
        getBooks(null, true, null, limit, callback);
    }
    
    /**
     * Fetch single book by ID
     */
    public void getBook(String bookId, ApiCallback<Book> callback) {
        executor.execute(() -> {
            try {
                String response = makeGetRequest(BASE_URL + "/api/books/" + bookId);
                
                if (response != null) {
                    JSONObject json = new JSONObject(response);
                    
                    if (json.getBoolean("success")) {
                        JSONObject bookJson = json.getJSONObject("book");
                        Book book = parseBookFromJson(bookJson);
                        mainHandler.post(() -> callback.onSuccess(book));
                    } else {
                        mainHandler.post(() -> callback.onError("الكتاب غير موجود"));
                    }
                } else {
                    mainHandler.post(() -> callback.onError("خطأ في الشبكة"));
                }
            } catch (Exception e) {
                Log.e(TAG, "Error fetching book: " + e.getMessage());
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }
    
    /**
     * Fetch all categories
     */
    public void getCategories(ApiCallback<List<Category>> callback) {
        executor.execute(() -> {
            try {
                String response = makeGetRequest(BASE_URL + "/api/categories");
                
                if (response != null) {
                    JSONObject json = new JSONObject(response);
                    
                    if (json.getBoolean("success")) {
                        JSONArray categoriesArray = json.getJSONArray("categories");
                        List<Category> categories = new ArrayList<>();
                        
                        for (int i = 0; i < categoriesArray.length(); i++) {
                            JSONObject catJson = categoriesArray.getJSONObject(i);
                            Category category = new Category(
                                catJson.getString("id"),
                                catJson.getString("name")
                            );
                            categories.add(category);
                        }
                        
                        mainHandler.post(() -> callback.onSuccess(categories));
                    } else {
                        mainHandler.post(() -> callback.onError("API error"));
                    }
                } else {
                    mainHandler.post(() -> callback.onError("خطأ في الشبكة"));
                }
            } catch (Exception e) {
                Log.e(TAG, "Error fetching categories: " + e.getMessage());
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }
    
    /**
     * Search books
     */
    public void searchBooks(String query, ApiCallback<List<Book>> callback) {
        executor.execute(() -> {
            try {
                String encodedQuery = java.net.URLEncoder.encode(query, "UTF-8");
                String response = makeGetRequest(BASE_URL + "/api/search?q=" + encodedQuery);
                
                if (response != null) {
                    JSONObject json = new JSONObject(response);
                    
                    if (json.getBoolean("success")) {
                        JSONArray booksArray = json.getJSONArray("books");
                        List<Book> books = parseBooksFromJson(booksArray);
                        mainHandler.post(() -> callback.onSuccess(books));
                    } else {
                        mainHandler.post(() -> callback.onError("خطأ في البحث"));
                    }
                } else {
                    mainHandler.post(() -> callback.onError("خطأ في الشبكة"));
                }
            } catch (Exception e) {
                Log.e(TAG, "Error searching books: " + e.getMessage());
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }
    
    /**
     * Rate a book
     */
    public void rateBook(String bookId, int rating, ApiCallback<Book> callback) {
        executor.execute(() -> {
            try {
                String body = "{\"rating\":" + rating + "}";
                String response = makePostRequest(BASE_URL + "/api/books/" + bookId + "/rate", body);
                
                if (response != null) {
                    JSONObject json = new JSONObject(response);
                    if (json.getBoolean("success")) {
                        float newRating = (float) json.getDouble("rating");
                        int ratingCount = json.getInt("ratingCount");
                        
                        Book book = new Book(bookId, "", "", newRating, "", 0, false);
                        book.setRatingCount(ratingCount);
                        mainHandler.post(() -> callback.onSuccess(book));
                    } else {
                        mainHandler.post(() -> callback.onError("خطأ في التقييم"));
                    }
                } else {
                    mainHandler.post(() -> callback.onError("خطأ في الشبكة"));
                }
            } catch (Exception e) {
                Log.e(TAG, "Error rating book: " + e.getMessage());
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }
    
    /**
     * Track book download
     */
    public void trackDownload(String bookId) {
        executor.execute(() -> {
            try {
                makePostRequest(BASE_URL + "/api/books/" + bookId + "/download", "");
            } catch (Exception e) {
                Log.e(TAG, "Error tracking download: " + e.getMessage());
            }
        });
    }
    
    // ============ HTTP HELPERS ============
    
    private String makeGetRequest(String urlString) {
        try {
            URL url = new URL(urlString);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            
            int responseCode = connection.getResponseCode();
            
            if (responseCode == HttpURLConnection.HTTP_OK) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
                StringBuilder response = new StringBuilder();
                String line;
                
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();
                
                return response.toString();
            } else {
                Log.e(TAG, "HTTP Error: " + responseCode);
                return null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Network error: " + e.getMessage());
            return null;
        }
    }
    
    private String makePostRequest(String urlString, String body) {
        try {
            URL url = new URL(urlString);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            
            if (body != null && !body.isEmpty()) {
                connection.getOutputStream().write(body.getBytes("UTF-8"));
            }
            
            int responseCode = connection.getResponseCode();
            
            if (responseCode == HttpURLConnection.HTTP_OK) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
                StringBuilder response = new StringBuilder();
                String line;
                
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();
                
                return response.toString();
            } else {
                Log.e(TAG, "HTTP Error: " + responseCode);
                return null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Network error: " + e.getMessage());
            return null;
        }
    }
    
    // ============ JSON PARSING ============
    
    private List<Book> parseBooksFromJson(JSONArray booksArray) throws Exception {
        List<Book> books = new ArrayList<>();
        
        for (int i = 0; i < booksArray.length(); i++) {
            JSONObject bookJson = booksArray.getJSONObject(i);
            books.add(parseBookFromJson(bookJson));
        }
        
        return books;
    }
    
    private Book parseBookFromJson(JSONObject json) throws Exception {
        String title = json.getString("title");
        String author = json.getString("author");
        String genre = json.optString("categoryName", "غير مصنف");
        
        Book book = new Book(
            json.getString("id"),
            title,
            author,
            (float) json.optDouble("rating", 0),
            genre,
            json.optInt("pages", 0),
            json.optBoolean("featured", false)
        );
        
        book.setCategoryId(json.optString("categoryId", "1"));
        book.setRatingCount(json.optInt("ratingCount", 0));
        book.setViews(json.optInt("views", 0));
        book.setDownloads(json.optInt("downloads", 0));
        book.setDescription(json.optString("description", ""));
        
        // Set cover URL
        String coverUrl = json.optString("coverUrl", null);
        if (coverUrl != null && !coverUrl.isEmpty() && !coverUrl.equals("null")) {
            if (coverUrl.startsWith("/")) {
                coverUrl = BASE_URL + coverUrl;
            }
            book.setCoverUrl(coverUrl);
        }
        
        // Set book file URL
        String bookFileUrl = json.optString("bookFileUrl", null);
        if (bookFileUrl != null && !bookFileUrl.isEmpty() && !bookFileUrl.equals("null")) {
            if (bookFileUrl.startsWith("/")) {
                bookFileUrl = BASE_URL + bookFileUrl;
            }
            book.setBookFileUrl(bookFileUrl);
        }
        
        return book;
    }
    
    // ============ INNER CLASSES ============
    
    /**
     * Category model (Arabic only)
     */
    public static class Category {
        private final String id;
        private final String name;
        
        public Category(String id, String name) {
            this.id = id;
            this.name = name;
        }
        
        public String getId() { return id; }
        public String getName() { return name; }
    }
}
