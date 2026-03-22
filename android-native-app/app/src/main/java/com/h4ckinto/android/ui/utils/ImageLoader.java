package com.h4ckinto.android.ui.utils;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Simple image loader with LruCache memory caching.
 * Loads images from URLs into ImageViews without external libraries.
 */
public class ImageLoader {
    
    private static final String TAG = "ImageLoader";
    private static ImageLoader instance;
    
    private final LruCache<String, Bitmap> memoryCache;
    private final ExecutorService executor;
    private final Handler mainHandler;
    
    private ImageLoader() {
        // Use 1/4 of available memory for cache
        int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        int cacheSize = maxMemory / 4;
        
        memoryCache = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                return bitmap.getByteCount() / 1024;
            }
        };
        
        executor = Executors.newFixedThreadPool(4);
        mainHandler = new Handler(Looper.getMainLooper());
    }
    
    public static synchronized ImageLoader getInstance() {
        if (instance == null) {
            instance = new ImageLoader();
        }
        return instance;
    }
    
    /**
     * Load an image from URL into an ImageView
     * 
     * @param url         The image URL
     * @param imageView   The target ImageView
     * @param placeholder Resource ID for placeholder (0 for none)
     */
    public void load(String url, ImageView imageView, int placeholder) {
        if (url == null || url.isEmpty()) {
            if (placeholder != 0) {
                imageView.setImageResource(placeholder);
            }
            return;
        }
        
        // Tag the ImageView with the URL to handle recycling
        imageView.setTag(url);
        
        // Check cache first
        Bitmap cached = memoryCache.get(url);
        if (cached != null) {
            imageView.setImageBitmap(cached);
            return;
        }
        
        // Show placeholder while loading
        if (placeholder != 0) {
            imageView.setImageResource(placeholder);
        }
        
        // Load in background
        WeakReference<ImageView> imageViewRef = new WeakReference<>(imageView);
        
        executor.execute(() -> {
            try {
                Bitmap bitmap = downloadBitmap(url);
                
                if (bitmap != null) {
                    // Add to cache
                    memoryCache.put(url, bitmap);
                    
                    // Set on UI thread
                    mainHandler.post(() -> {
                        ImageView iv = imageViewRef.get();
                        if (iv != null && url.equals(iv.getTag())) {
                            iv.setImageBitmap(bitmap);
                        }
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Error loading image: " + url + " - " + e.getMessage());
            }
        });
    }
    
    /**
     * Load an image from URL into an ImageView (no placeholder)
     */
    public void load(String url, ImageView imageView) {
        load(url, imageView, 0);
    }
    
    private Bitmap downloadBitmap(String urlString) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            connection.setInstanceFollowRedirects(true);
            
            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "HTTP error: " + responseCode + " for " + urlString);
                return null;
            }
            
            InputStream inputStream = connection.getInputStream();
            
            // Decode with inSampleSize for memory efficiency
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.RGB_565; // Less memory
            
            Bitmap bitmap = BitmapFactory.decodeStream(inputStream, null, options);
            inputStream.close();
            
            return bitmap;
        } catch (Exception e) {
            Log.e(TAG, "Download error: " + e.getMessage());
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
    
    /**
     * Clear the memory cache
     */
    public void clearCache() {
        memoryCache.evictAll();
    }
}


