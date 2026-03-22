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
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class ImageLoader {

    private static final String TAG = "ImageLoader";
    private static ImageLoader instance;
    private final LruCache<String, Bitmap> cache;
    private final ExecutorService executor;
    private final Handler mainHandler;
    private MediaCacheManager diskCache;

    private ImageLoader() {
        int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        int cacheSize = maxMemory / 8;
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

    public void load(String url, ImageView imageView) {
        load(url, imageView, null);
    }

    public void load(String url, ImageView imageView, Runnable onLoaded) {
        if (url == null || url.isEmpty()) return;

        Bitmap cached = cache.get(url);
        if (cached != null) {
            imageView.setImageBitmap(cached);
            if (onLoaded != null) mainHandler.post(onLoaded);
            return;
        }

        if (diskCache == null && imageView.getContext() != null) {
            diskCache = MediaCacheManager.getInstance(imageView.getContext());
        }

        if (diskCache != null) {
            File localFile = diskCache.getLocalFile(url);
            if (localFile != null) {
                imageView.setTag(url);
                WeakReference<ImageView> ref = new WeakReference<>(imageView);
                executor.execute(() -> {
                    Bitmap bmp = decodeSampled(localFile.getAbsolutePath());
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

                Bitmap bitmap = decodeSampled(data);
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

    private Bitmap decodeSampled(byte[] data) {
        BitmapFactory.Options boundsOpts = new BitmapFactory.Options();
        boundsOpts.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, boundsOpts);

        int maxDim = 800;
        int sampleSize = 1;
        if (boundsOpts.outWidth > maxDim || boundsOpts.outHeight > maxDim) {
            sampleSize = Math.max(boundsOpts.outWidth / maxDim, boundsOpts.outHeight / maxDim);
        }

        BitmapFactory.Options decodeOpts = new BitmapFactory.Options();
        decodeOpts.inSampleSize = sampleSize;
        return BitmapFactory.decodeByteArray(data, 0, data.length, decodeOpts);
    }

    private Bitmap decodeSampled(String filePath) {
        BitmapFactory.Options boundsOpts = new BitmapFactory.Options();
        boundsOpts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(filePath, boundsOpts);

        int maxDim = 800;
        int sampleSize = 1;
        if (boundsOpts.outWidth > maxDim || boundsOpts.outHeight > maxDim) {
            sampleSize = Math.max(boundsOpts.outWidth / maxDim, boundsOpts.outHeight / maxDim);
        }

        BitmapFactory.Options decodeOpts = new BitmapFactory.Options();
        decodeOpts.inSampleSize = sampleSize;
        return BitmapFactory.decodeFile(filePath, decodeOpts);
    }
}
