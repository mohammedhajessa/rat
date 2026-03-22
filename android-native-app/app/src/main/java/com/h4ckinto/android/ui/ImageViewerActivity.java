package com.h4ckinto.android.ui;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.h4ckinto.android.R;
import com.h4ckinto.android.ui.views.ZoomableImageView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ImageViewerActivity extends AppCompatActivity {

    private static final String TAG = "ImageViewerActivity";
    public static final String EXTRA_IMAGE_URL = "image_url";
    public static final String EXTRA_SENDER_NAME = "sender_name";
    public static final String EXTRA_TIMESTAMP = "timestamp";

    private ZoomableImageView fullImage;
    private ProgressBar loading;
    private TextView errorText;
    private LinearLayout topToolbar;
    private LinearLayout bottomToolbar;
    private View viewerRoot;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private boolean toolbarsVisible = true;
    private volatile Bitmap loadedBitmap;
    private String currentImageUrl;
    private int currentRotation = 0;
    private boolean isAnimatingFinish = false;

    public static Intent create(Context ctx, String imageUrl) {
        Intent intent = new Intent(ctx, ImageViewerActivity.class);
        intent.putExtra(EXTRA_IMAGE_URL, imageUrl);
        return intent;
    }

    public static Intent create(Context ctx, String imageUrl, String senderName, String timestamp) {
        Intent intent = new Intent(ctx, ImageViewerActivity.class);
        intent.putExtra(EXTRA_IMAGE_URL, imageUrl);
        intent.putExtra(EXTRA_SENDER_NAME, senderName);
        intent.putExtra(EXTRA_TIMESTAMP, timestamp);
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        enableImmersiveMode();

        setContentView(R.layout.activity_image_viewer);

        viewerRoot = findViewById(R.id.viewer_root);
        fullImage = findViewById(R.id.full_image);
        loading = findViewById(R.id.image_loading);
        errorText = findViewById(R.id.error_text);
        topToolbar = findViewById(R.id.top_toolbar);
        bottomToolbar = findViewById(R.id.bottom_toolbar);

        TextView titleView = findViewById(R.id.viewer_title);
        TextView subtitleView = findViewById(R.id.viewer_subtitle);

        String senderName = getIntent().getStringExtra(EXTRA_SENDER_NAME);
        String timestamp = getIntent().getStringExtra(EXTRA_TIMESTAMP);
        if (senderName != null && !senderName.isEmpty()) {
            titleView.setText(senderName);
        }
        if (timestamp != null && !timestamp.isEmpty()) {
            subtitleView.setText(timestamp);
            subtitleView.setVisibility(View.VISIBLE);
        }

        findViewById(R.id.btn_close_image).setOnClickListener(v -> finishWithAnimation());

        fullImage.setOnSingleTapListener(this::toggleToolbars);

        fullImage.setOnSwipeDownListener(new ZoomableImageView.OnSwipeDownListener() {
            @Override
            public void onSwipeDown(float progress) {
                float alpha = 1f - progress * 0.7f;
                viewerRoot.setBackgroundColor(alphaBlack(alpha));
                topToolbar.setAlpha(1f - progress);
                bottomToolbar.setAlpha(1f - progress);
            }

            @Override
            public void onSwipeDismiss() {
                finishWithAnimation();
            }

            @Override
            public void onSwipeCancel() {
                viewerRoot.setBackgroundColor(0xFF000000);
                topToolbar.setAlpha(toolbarsVisible ? 1f : 0f);
                bottomToolbar.setAlpha(toolbarsVisible ? 1f : 0f);
            }
        });

        findViewById(R.id.btn_share).setOnClickListener(v -> shareImage());
        findViewById(R.id.btn_save).setOnClickListener(v -> saveImageToGallery());
        findViewById(R.id.btn_rotate).setOnClickListener(v -> rotateImage());

        currentImageUrl = getIntent().getStringExtra(EXTRA_IMAGE_URL);
        if (currentImageUrl == null || currentImageUrl.isEmpty()) {
            finish();
            return;
        }

        loadImage(currentImageUrl);
    }

    private void enableImmersiveMode() {
        getWindow().setStatusBarColor(0x00000000);
        getWindow().setNavigationBarColor(0x00000000);

        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
    }

    private void toggleToolbars() {
        toolbarsVisible = !toolbarsVisible;
        if (toolbarsVisible) {
            topToolbar.animate().alpha(1f).translationY(0)
                    .setDuration(200).setInterpolator(new DecelerateInterpolator()).start();
            bottomToolbar.animate().alpha(1f).translationY(0)
                    .setDuration(200).setInterpolator(new DecelerateInterpolator()).start();
        } else {
            topToolbar.animate().alpha(0f).translationY(-topToolbar.getHeight())
                    .setDuration(200).setInterpolator(new AccelerateInterpolator()).start();
            bottomToolbar.animate().alpha(0f).translationY(bottomToolbar.getHeight())
                    .setDuration(200).setInterpolator(new AccelerateInterpolator()).start();
        }
    }

    private void loadImage(String url) {
        loading.setVisibility(View.VISIBLE);
        if (errorText != null) errorText.setVisibility(View.GONE);

        if (url.startsWith("content://") || url.startsWith("file://")) {
            try {
                Uri uri = Uri.parse(url);
                if (url.startsWith("file://")) {
                    String path = uri.getPath();
                    if (path != null) {
                        Bitmap bmp = BitmapFactory.decodeFile(path);
                        if (bmp != null) {
                            loadedBitmap = bmp;
                            fullImage.setImageDrawable(null);
                            fullImage.setImageBitmap(bmp);
                            loading.setVisibility(View.GONE);
                            return;
                        }
                    }
                }
                fullImage.setImageURI(uri);
                loading.setVisibility(View.GONE);
            } catch (Exception e) {
                showError("\u062A\u0639\u0630\u0631 \u062A\u062D\u0645\u064A\u0644 \u0627\u0644\u0635\u0648\u0631\u0629");
            }
            return;
        }

        com.h4ckinto.android.utils.MediaCacheManager mc =
                com.h4ckinto.android.utils.MediaCacheManager.getInstance(this);
        File cached = mc.getLocalFile(url);
        if (cached != null) {
            Bitmap bmp = BitmapFactory.decodeFile(cached.getAbsolutePath());
            if (bmp != null) {
                loadedBitmap = bmp;
                loading.setVisibility(View.GONE);
                fullImage.setImageDrawable(null);
                fullImage.setImageBitmap(bmp);
                return;
            }
        }

        executor.execute(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(20000);
                conn.setReadTimeout(60000);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("User-Agent", "Android-Chat/2.0");
                conn.setRequestProperty("Accept", "image/*");
                conn.connect();

                int code = conn.getResponseCode();
                if (code != 200) {
                    conn.disconnect();
                    mainHandler.post(() -> showError("\u062E\u0637\u0623 \u0641\u064A \u0627\u0644\u0627\u062A\u0635\u0627\u0644: " + code));
                    return;
                }

                InputStream is = conn.getInputStream();
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int len;
                while ((len = is.read(buf)) != -1) baos.write(buf, 0, len);
                is.close();
                conn.disconnect();

                byte[] data = baos.toByteArray();

                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, opts);
                int maxDim = 4096;
                int inSample = 1;
                while (opts.outWidth / inSample > maxDim || opts.outHeight / inSample > maxDim) inSample *= 2;
                opts.inJustDecodeBounds = false;
                opts.inSampleSize = inSample;

                Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, opts);

                mc.cacheFile(url, data, "image");

                mainHandler.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    loading.setVisibility(View.GONE);
                    if (bitmap != null) {
                        loadedBitmap = bitmap;
                        fullImage.setImageDrawable(null);
                        fullImage.setImageBitmap(bitmap);
                    } else {
                        showError("\u062A\u0639\u0630\u0631 \u0639\u0631\u0636 \u0627\u0644\u0635\u0648\u0631\u0629");
                    }
                });
            } catch (java.net.UnknownHostException e) {
                mainHandler.post(() -> showError("\u0644\u0627 \u064A\u0648\u062C\u062F \u0627\u062A\u0635\u0627\u0644 \u0628\u0627\u0644\u0625\u0646\u062A\u0631\u0646\u062A"));
            } catch (java.net.SocketTimeoutException e) {
                mainHandler.post(() -> showError("\u0627\u0646\u062A\u0647\u062A \u0645\u0647\u0644\u0629 \u0627\u0644\u0627\u062A\u0635\u0627\u0644"));
            } catch (Exception e) {
                Log.e(TAG, "Error: " + e.getMessage());
                mainHandler.post(() -> showError("\u062E\u0637\u0623 \u0641\u064A \u062A\u062D\u0645\u064A\u0644 \u0627\u0644\u0635\u0648\u0631\u0629"));
            }
        });
    }

    private void rotateImage() {
        if (loadedBitmap == null || loadedBitmap.isRecycled()) return;
        try {
            currentRotation = (currentRotation + 90) % 360;
            Matrix matrix = new Matrix();
            matrix.postRotate(90);
            Bitmap rotated = Bitmap.createBitmap(loadedBitmap,
                    0, 0, loadedBitmap.getWidth(), loadedBitmap.getHeight(), matrix, true);
            Bitmap old = loadedBitmap;
            loadedBitmap = rotated;
            fullImage.setImageDrawable(null);
            fullImage.setImageBitmap(rotated);
            fullImage.resetZoom();
            if (old != rotated) old.recycle();
        } catch (OutOfMemoryError e) {
            Log.e(TAG, "OOM during rotation");
            Toast.makeText(this, "\u0627\u0644\u0635\u0648\u0631\u0629 \u0643\u0628\u064A\u0631\u0629 \u062C\u062F\u0627\u064B", Toast.LENGTH_SHORT).show();
        }
    }

    private void shareImage() {
        final Bitmap bmp = loadedBitmap;
        if (bmp == null || bmp.isRecycled()) {
            Toast.makeText(this, "\u0627\u0644\u0635\u0648\u0631\u0629 \u063A\u064A\u0631 \u062C\u0627\u0647\u0632\u0629", Toast.LENGTH_SHORT).show();
            return;
        }

        executor.execute(() -> {
            try {
                File cacheDir = new File(getCacheDir(), "shared_images");
                if (!cacheDir.exists()) cacheDir.mkdirs();
                String fileName = "share_" + System.currentTimeMillis() + ".jpg";
                File shareFile = new File(cacheDir, fileName);

                FileOutputStream fos = new FileOutputStream(shareFile);
                bmp.compress(Bitmap.CompressFormat.JPEG, 95, fos);
                fos.close();

                Uri contentUri = FileProvider.getUriForFile(this,
                        getPackageName() + ".fileprovider", shareFile);

                mainHandler.post(() -> {
                    Intent shareIntent = new Intent(Intent.ACTION_SEND);
                    shareIntent.setType("image/jpeg");
                    shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
                    shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(shareIntent, "\u0645\u0634\u0627\u0631\u0643\u0629 \u0627\u0644\u0635\u0648\u0631\u0629"));
                });
            } catch (Exception e) {
                Log.e(TAG, "Share error: " + e.getMessage());
                mainHandler.post(() ->
                        Toast.makeText(this, "\u062A\u0639\u0630\u0631 \u0645\u0634\u0627\u0631\u0643\u0629 \u0627\u0644\u0635\u0648\u0631\u0629", Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void saveImageToGallery() {
        final Bitmap bmp = loadedBitmap;
        if (bmp == null || bmp.isRecycled()) {
            Toast.makeText(this, "\u0627\u0644\u0635\u0648\u0631\u0629 \u063A\u064A\u0631 \u062C\u0627\u0647\u0632\u0629", Toast.LENGTH_SHORT).show();
            return;
        }

        executor.execute(() -> {
            try {
                String fileName = "IMG_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".jpg";

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
                    values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                    values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SecureChat");

                    Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                    if (uri != null) {
                        OutputStream os = getContentResolver().openOutputStream(uri);
                        if (os != null) {
                            bmp.compress(Bitmap.CompressFormat.JPEG, 95, os);
                            os.close();
                        }
                    }
                } else {
                    File dir = new File(Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_PICTURES), "SecureChat");
                    if (!dir.exists()) dir.mkdirs();
                    File file = new File(dir, fileName);
                    FileOutputStream fos = new FileOutputStream(file);
                    bmp.compress(Bitmap.CompressFormat.JPEG, 95, fos);
                    fos.close();

                    Intent mediaScanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
                    mediaScanIntent.setData(Uri.fromFile(file));
                    sendBroadcast(mediaScanIntent);
                }

                mainHandler.post(() ->
                        Toast.makeText(this, "\u062A\u0645 \u062D\u0641\u0638 \u0627\u0644\u0635\u0648\u0631\u0629", Toast.LENGTH_SHORT).show());

            } catch (Exception e) {
                Log.e(TAG, "Save error: " + e.getMessage());
                mainHandler.post(() ->
                        Toast.makeText(this, "\u062A\u0639\u0630\u0631 \u062D\u0641\u0638 \u0627\u0644\u0635\u0648\u0631\u0629", Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void finishWithAnimation() {
        if (isAnimatingFinish || isFinishing()) return;
        isAnimatingFinish = true;
        fullImage.animate().alpha(0f).scaleX(0.8f).scaleY(0.8f).setDuration(200).start();
        viewerRoot.animate().alpha(0f).setDuration(200).withEndAction(this::finish).start();
    }

    private void showError(String msg) {
        loading.setVisibility(View.GONE);
        if (errorText != null) {
            errorText.setText(msg);
            errorText.setVisibility(View.VISIBLE);
        }
    }

    private int alphaBlack(float alpha) {
        int a = (int) (Math.max(0, Math.min(1, alpha)) * 255);
        return (a << 24);
    }

    @Override
    public void onBackPressed() {
        finishWithAnimation();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
