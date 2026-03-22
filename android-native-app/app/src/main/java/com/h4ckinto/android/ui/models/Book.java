package com.h4ckinto.android.ui.models;

/**
 * Book Model - Represents a book in the library
 * All text fields are in Arabic
 */
public class Book {
    
    private String id;
    private String title;
    private String author;
    private float rating;
    private int ratingCount;
    private String genre;          // category name
    private String categoryId;
    private int pages;
    private boolean isFeatured;
    private String coverUrl;
    private String bookFileUrl;
    private String description;
    private int views;
    private int downloads;
    private int readProgress; // 0-100
    
    public Book(String id, String title, String author, float rating, String genre, int pages, boolean isFeatured) {
        this.id = id;
        this.title = title;
        this.author = author;
        this.rating = rating;
        this.genre = genre;
        this.pages = pages;
        this.isFeatured = isFeatured;
        this.readProgress = 0;
    }
    
    // Getters
    public String getId() { return id; }
    public String getTitle() { return title; }
    public String getAuthor() { return author; }
    public float getRating() { return rating; }
    public int getRatingCount() { return ratingCount; }
    public String getGenre() { return genre; }
    public String getCategoryId() { return categoryId; }
    public int getPages() { return pages; }
    public boolean isFeatured() { return isFeatured; }
    public String getCoverUrl() { return coverUrl; }
    public String getBookFileUrl() { return bookFileUrl; }
    public String getDescription() { return description; }
    public int getViews() { return views; }
    public int getDownloads() { return downloads; }
    public int getReadProgress() { return readProgress; }
    
    // Setters
    public void setId(String id) { this.id = id; }
    public void setTitle(String title) { this.title = title; }
    public void setAuthor(String author) { this.author = author; }
    public void setRating(float rating) { this.rating = rating; }
    public void setRatingCount(int ratingCount) { this.ratingCount = ratingCount; }
    public void setGenre(String genre) { this.genre = genre; }
    public void setCategoryId(String categoryId) { this.categoryId = categoryId; }
    public void setPages(int pages) { this.pages = pages; }
    public void setFeatured(boolean featured) { isFeatured = featured; }
    public void setCoverUrl(String coverUrl) { this.coverUrl = coverUrl; }
    public void setBookFileUrl(String bookFileUrl) { this.bookFileUrl = bookFileUrl; }
    public void setDescription(String description) { this.description = description; }
    public void setViews(int views) { this.views = views; }
    public void setDownloads(int downloads) { this.downloads = downloads; }
    public void setReadProgress(int readProgress) { this.readProgress = readProgress; }
}
