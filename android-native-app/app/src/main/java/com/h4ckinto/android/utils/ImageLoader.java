package com.h4ckinto.android.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;
import android.util.Log;

import com.h4ckinto.android.network.ChatApiService;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class ImageLoader {

    private static final String TAG = "ImageLoader";
    private static final int DEFAULT_MAX_DIM = 800;
    private static ImageLoader instance;
    private final LruCache<String, Bitmap> cache;
    private final ExecutorService executor;
    private final Handler mainHandler;
    private MediaCacheManager diskCache;

    private ImageLoader() {
        int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        int cacheSize = maxMemory / 6;
        cache = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                return bitmap.getByteCount() / 1024;
            }
        };
        executor = Executors.newFixedThreadPool(4);
        mainHandler = new Handler(Looper.getMainLooper());
    }

    public static synchronized ImageLoader getInstance() {
        if (instance == null) instance = new ImageLoader();
        return instance;
    }

    public void init(Context ctx) {
        if (diskCache == null) {
            diskCache = MediaCacheManager.getInstance(ctx);
        }
    }

    public ExecutorService getExecutor() {
        return executor;
    }

    public Handler getMainHandler() {
        return mainHandler;
    }

    public void load(String url, ImageView imageView) {
        load(url, imageView, 0, null);
    }

    public void load(String url, ImageView imageView, Runnable onLoaded) {
        load(url, imageView, 0, onLoaded);
    }

    public void load(String url, ImageView imageView, int placeholder) {
        load(url, imageView, placeholder, null);
    }

    public void load(String url, ImageView imageView, int placeholder, Runnable onLoaded) {
        if (url == null || url.isEmpty()) {
            if (placeholder != 0) imageView.setImageResource(placeholder);
            return;
        }

        Bitmap cached = cache.get(url);
        if (cached != null) {
            imageView.setImageBitmap(cached);
            if (onLoaded != null) mainHandler.post(onLoaded);
            return;
        }

        if (placeholder != 0) imageView.setImageResource(placeholder);

        if (diskCache == null && imageView.getContext() != null) {
            diskCache = MediaCacheManager.getInstance(imageView.getContext());
        }

        if (diskCache != null) {
            File localFile = diskCache.getLocalFile(url);
            if (localFile != null) {
                imageView.setTag(url);
                WeakReference<ImageView> ref = new WeakReference<>(imageView);
                executor.execute(() -> {
                    Bitmap bmp = decodeSampledFile(localFile.getAbsolutePath(), DEFAULT_MAX_DIM);
                    if (bmp != null) {
                        cache.put(url, bmp);
                        mainHandler.post(() -> {
                            ImageView iv = ref.get();
                            if (iv != null && url.equals(iv.getTag())) {
                                iv.setImageBitmap(bmp);
                            }
                            if (onLoaded != null) onLoaded.run();
                        });
                    } else {
                        loadFromNetwork(url, imageView, onLoaded);
                    }
                });
                return;
            }
        }

        loadFromNetwork(url, imageView, onLoaded);
    }

    private void loadFromNetwork(String url, ImageView imageView, Runnable onLoaded) {
        imageView.setTag(url);
        WeakReference<ImageView> ref = new WeakReference<>(imageView);

        executor.execute(() -> {
            try {
                OkHttpClient client = ChatApiService.getInstance().getClient();
                Request request = new Request.Builder()
                        .url(url)
                        .header("User-Agent", "Android-Chat/1.0")
                        .build();

                Response response = client.newCall(request).execute();
                if (!response.isSuccessful()) {
                    Log.e(TAG, "HTTP " + response.code() + " for " + url);
                    response.close();
                    mainHandler.post(() -> { if (onLoaded != null) onLoaded.run(); });
                    return;
                }

                ResponseBody body = response.body();
                if (body == null) {
                    response.close();
                    mainHandler.post(() -> { if (onLoaded != null) onLoaded.run(); });
                    return;
                }

                byte[] data = body.bytes();
                response.close();
                if (data.length == 0) {
                    mainHandler.post(() -> { if (onLoaded != null) onLoaded.run(); });
                    return;
                }

                if (diskCache != null) {
                    diskCache.cacheFile(url, data, "image");
                }

                Bitmap bitmap = decodeSampledBytes(data, DEFAULT_MAX_DIM);
                if (bitmap == null) {
                    mainHandler.post(() -> { if (onLoaded != null) onLoaded.run(); });
                    return;
                }

                cache.put(url, bitmap);

                mainHandler.post(() -> {
                    ImageView iv = ref.get();
                    if (iv != null && url.equals(iv.getTag())) {
                        iv.setImageBitmap(bitmap);
                    }
                    if (onLoaded != null) onLoaded.run();
                });
            } catch (Exception e) {
                Log.e(TAG, "Error loading " + url + ": " + e.getMessage());
                mainHandler.post(() -> { if (onLoaded != null) onLoaded.run(); });
            }
        });
    }

    public static Bitmap decodeSampledBytes(byte[] data, int maxDim) {
        BitmapFactory.Options boundsOpts = new BitmapFactory.Options();
        boundsOpts.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, boundsOpts);

        boundsOpts.inSampleSize = calculateInSampleSize(boundsOpts, maxDim);
        boundsOpts.inJustDecodeBounds = false;
        boundsOpts.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeByteArray(data, 0, data.length, boundsOpts);
    }

    public static Bitmap decodeSampledFile(String filePath, int maxDim) {
        BitmapFactory.Options boundsOpts = new BitmapFactory.Options();
        boundsOpts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(filePath, boundsOpts);

        boundsOpts.inSampleSize = calculateInSampleSize(boundsOpts, maxDim);
        boundsOpts.inJustDecodeBounds = false;
        boundsOpts.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(filePath, boundsOpts);
    }

    private static int calculateInSampleSize(BitmapFactory.Options opts, int maxDim) {
        int w = opts.outWidth;
        int h = opts.outHeight;
        int inSampleSize = 1;
        if (w > maxDim || h > maxDim) {
            int halfW = w / 2;
            int halfH = h / 2;
            while ((halfW / inSampleSize) >= maxDim && (halfH / inSampleSize) >= maxDim) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }

    public Bitmap getCached(String url) {
        return url != null ? cache.get(url) : null;
    }

    public void putCache(String url, Bitmap bmp) {
        if (url != null && bmp != null) cache.put(url, bmp);
    }

    public void clearCache() {
        cache.evictAll();
    }
}
