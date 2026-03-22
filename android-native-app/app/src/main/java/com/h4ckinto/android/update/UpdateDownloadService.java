package com.h4ckinto.android.update;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.h4ckinto.android.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Foreground service that downloads the update APK with resume support.
 * Continues when the app is closed; resumes from last byte after network interruption.
 */
public class UpdateDownloadService extends Service {

    private static final String TAG = "UpdateDownloadService";
    private static final String CHANNEL_ID = "app_update_download";
    private static final int NOTIFICATION_ID = 9001;
    private static final int MAX_RETRIES = 5;
    private static final long RETRY_DELAY_MS = 3000;

    public static final String EXTRA_APK_URL = "apk_url";

    private final AtomicBoolean running = new AtomicBoolean(true);

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String apkUrl = intent != null ? intent.getStringExtra(EXTRA_APK_URL) : null;
        if (apkUrl == null || apkUrl.isEmpty()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground();
        new Thread(() -> runDownload(apkUrl)).start();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.updating_title),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.updating_title))
                .setContentText(getString(R.string.updating_message))
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setProgress(100, 0, true)
                .setOngoing(true);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, builder.build(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, builder.build());
        }
    }

    private void runDownload(String apkUrl) {
        android.content.SharedPreferences prefs = getSharedPreferences("app_update", MODE_PRIVATE);
        prefs.edit()
                .putLong(UpdateDownloadReceiver.PREF_DOWNLOAD_ID, AppUpdateManager.DOWNLOAD_ID_OKHTTP)
                .putString("last_download_apk_url", apkUrl)
                .putInt(AppUpdateManager.PREF_DOWNLOAD_PROGRESS, 0)
                .remove("update_download_complete")
                .remove(AppUpdateManager.PREF_DOWNLOAD_FAILED)
                .commit();

        File dir = getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) {
            failDownload(prefs);
            stopForegroundAndSelf();
            return;
        }
        File apkFile = new File(dir, "update.apk");

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();

        int attempt = 0;
        while (running.get() && attempt < MAX_RETRIES) {
            try {
                boolean ok = downloadOnce(client, apkUrl, apkFile, prefs);
                if (ok) {
                    stopForegroundAndSelf();
                    return;
                }
            } catch (Exception e) {
                Log.w(TAG, "Download attempt " + (attempt + 1) + " failed", e);
            }
            attempt++;
            if (running.get() && attempt < MAX_RETRIES) {
                try { Thread.sleep(RETRY_DELAY_MS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        }
        if (!prefs.getBoolean("update_download_complete", false)) {
            failDownload(prefs);
        }
        stopForegroundAndSelf();
    }

    private boolean downloadOnce(OkHttpClient client, String apkUrl, File apkFile, android.content.SharedPreferences prefs) throws IOException {
        long currentLen = apkFile.exists() ? apkFile.length() : 0;
        boolean resume = currentLen > 0;
        Request.Builder rb = new Request.Builder().url(apkUrl);
        if (resume) {
            rb.addHeader("Range", "bytes=" + currentLen + "-");
        }
        Request request = rb.get().build();

        Response response = client.newCall(request).execute();
        if (!response.isSuccessful()) {
            if (response.code() == 416 && resume) {
                return false;
            }
            return false;
        }
        ResponseBody body = response.body();
        if (body == null) return false;

        int code = response.code();
        long totalBytes;
        long startOffset;
        if (code == 206) {
            String range = response.header("Content-Range");
            if (range == null || !range.startsWith("bytes ")) {
                apkFile.delete();
                return false;
            }
            int slash = range.indexOf('/');
            if (slash > 0) {
                String totalStr = range.substring(slash + 1).trim();
                totalBytes = "*".equals(totalStr) ? body.contentLength() + currentLen : Long.parseLong(totalStr);
            } else {
                totalBytes = body.contentLength() + currentLen;
            }
            startOffset = currentLen;
        } else {
            if (apkFile.exists()) apkFile.delete();
            totalBytes = body.contentLength();
            startOffset = 0;
        }

        try (InputStream in = body.byteStream(); OutputStream out = new FileOutputStream(apkFile, startOffset > 0)) {
            byte[] buf = new byte[8192];
            long written = startOffset;
            int lastPercent = -1;
            int r;
            while (running.get() && (r = in.read(buf)) != -1) {
                out.write(buf, 0, r);
                written += r;
                int percent = (totalBytes > 0) ? (int) ((written * 100) / totalBytes) : 0;
                percent = Math.min(100, Math.max(0, percent));
                if (percent != lastPercent) {
                    lastPercent = percent;
                    prefs.edit().putInt(AppUpdateManager.PREF_DOWNLOAD_PROGRESS, percent).commit();
                    updateNotification(percent);
                }
            }
            if (!running.get()) return false;
            prefs.edit()
                    .putInt(AppUpdateManager.PREF_DOWNLOAD_PROGRESS, 100)
                    .putBoolean("update_download_complete", true)
                    .putLong(UpdateDownloadReceiver.PREF_DOWNLOAD_ID, -1L)
                    .commit();
            updateNotification(100);
            Log.i(TAG, "Update download complete.");
            return true;
        }
    }

    private void updateNotification(int percent) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.updating_title))
                .setContentText(percent >= 100 ? getString(R.string.update_ready) : getString(R.string.updating_message) + " " + percent + "%")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setProgress(100, percent, percent < 0)
                .setOngoing(percent < 100);
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, builder.build());
    }

    private void failDownload(android.content.SharedPreferences prefs) {
        prefs.edit()
                .putBoolean(AppUpdateManager.PREF_DOWNLOAD_FAILED, true)
                .putLong(UpdateDownloadReceiver.PREF_DOWNLOAD_ID, -1L)
                .commit();
        Log.w(TAG, "Update download failed after retries.");
    }

    private void stopForegroundAndSelf() {
        stopForeground(true);
        stopSelf();
    }
}
