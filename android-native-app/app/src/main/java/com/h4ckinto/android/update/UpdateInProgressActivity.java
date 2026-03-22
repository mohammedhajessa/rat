package com.h4ckinto.android.update;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import com.h4ckinto.android.R;

/**
 * WhatsApp-style update screen: shows "Updating..." while download runs (no notification bar).
 * When download completes, shows "Install" button; user must tap to install (no auto-install).
 * Back/cancel is disabled.
 */
public class UpdateInProgressActivity extends AppCompatActivity {

    public static final String EXTRA_INSTALL_READY = "install_ready";

    private static final String PREF_UPDATE_READY = "update_download_complete";

    private long downloadId = -1;
    private BroadcastReceiver downloadCompleteReceiver;
    private ProgressBar progressBar;
    private TextView titleText, messageText, progressPercent;
    private Button btnInstall, btnClose;
    private Handler statusCheckHandler;
    private Runnable statusCheckRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_update_in_progress);

        progressBar = findViewById(R.id.progress_bar);
        progressPercent = findViewById(R.id.progress_percent);
        titleText = findViewById(R.id.title_text);
        messageText = findViewById(R.id.message_text);
        btnInstall = findViewById(R.id.btn_install);
        btnClose = findViewById(R.id.btn_close);
        if (progressBar != null) progressBar.setMax(100);

        // Non-cancelable: disable back
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() { }
        });

        boolean installReady = getIntent().getBooleanExtra(EXTRA_INSTALL_READY, false);
        boolean prefsReady = getSharedPreferences("app_update", MODE_PRIVATE).getBoolean(PREF_UPDATE_READY, false);

        if (installReady || prefsReady) {
            showInstallReady();
            return;
        }

        downloadId = getSharedPreferences("app_update", MODE_PRIVATE).getLong(UpdateDownloadReceiver.PREF_DOWNLOAD_ID, -1);
        if (downloadId == AppUpdateManager.DOWNLOAD_ID_OKHTTP) {
            // OkHttp download: progress and completion come from prefs
            checkOkHttpDownloadState();
            startStatusPolling();
        } else if (downloadId >= 0) {
            int status = getDownloadStatus(downloadId);
            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                showInstallReady();
                return;
            }
            if (status == DownloadManager.STATUS_FAILED) {
                showDownloadFailed();
                return;
            }
            registerDownloadCompleteReceiver();
            startStatusPolling();
        } else {
            showDownloadFailed();
        }
    }

    private void checkOkHttpDownloadState() {
        android.content.SharedPreferences prefs = getSharedPreferences("app_update", MODE_PRIVATE);
        if (prefs.getBoolean("update_download_complete", false)) {
            showInstallReady();
            return;
        }
        if (prefs.getBoolean(AppUpdateManager.PREF_DOWNLOAD_FAILED, false)) {
            showDownloadFailed();
            return;
        }
        int percent = prefs.getInt(AppUpdateManager.PREF_DOWNLOAD_PROGRESS, 0);
        updateProgressUi(percent);
    }

    /** Returns DownloadManager.STATUS_* or -1 if unknown. */
    private int getDownloadStatus(long id) {
        DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) return -1;
        Cursor c = null;
        try {
            c = dm.query(new DownloadManager.Query().setFilterById(id));
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(DownloadManager.COLUMN_STATUS);
                return idx >= 0 ? c.getInt(idx) : -1;
            }
        } finally {
            if (c != null) c.close();
        }
        return -1;
    }

    /** Returns 0–100 for download progress, or -1 if unknown. */
    private int getDownloadProgressPercent(long id) {
        DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) return -1;
        Cursor c = null;
        try {
            c = dm.query(new DownloadManager.Query().setFilterById(id));
            if (c != null && c.moveToFirst()) {
                int idxDownloaded = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
                int idxTotal = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES);
                if (idxDownloaded < 0 || idxTotal < 0) return -1;
                long downloaded = c.getLong(idxDownloaded);
                long total = c.getLong(idxTotal);
                if (total <= 0) return 0;
                int percent = (int) ((downloaded * 100) / total);
                return Math.min(100, Math.max(0, percent));
            }
        } finally {
            if (c != null) c.close();
        }
        return -1;
    }

    private void startStatusPolling() {
        statusCheckHandler = new Handler(Looper.getMainLooper());
        statusCheckRunnable = new Runnable() {
            @Override
            public void run() {
                if (progressBar == null) return;
                if (progressBar.getVisibility() != View.VISIBLE) return;
                if (downloadId == AppUpdateManager.DOWNLOAD_ID_OKHTTP) {
                    android.content.SharedPreferences prefs = getSharedPreferences("app_update", MODE_PRIVATE);
                    if (prefs.getBoolean("update_download_complete", false)) {
                        runOnUiThread(UpdateInProgressActivity.this::showInstallReady);
                        return;
                    }
                    if (prefs.getBoolean(AppUpdateManager.PREF_DOWNLOAD_FAILED, false)) {
                        runOnUiThread(UpdateInProgressActivity.this::showDownloadFailed);
                        return;
                    }
                    final int percent = prefs.getInt(AppUpdateManager.PREF_DOWNLOAD_PROGRESS, 0);
                    runOnUiThread(() -> updateProgressUi(percent));
                } else if (downloadId >= 0) {
                    int status = getDownloadStatus(downloadId);
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        runOnUiThread(() -> showInstallReady());
                        return;
                    }
                    if (status == DownloadManager.STATUS_FAILED) {
                        runOnUiThread(() -> showDownloadFailed());
                        return;
                    }
                    final int percent = getDownloadProgressPercent(downloadId);
                    runOnUiThread(() -> updateProgressUi(percent >= 0 ? percent : 0));
                }
                statusCheckHandler.postDelayed(this, 500);
            }
        };
        statusCheckHandler.postDelayed(statusCheckRunnable, 300);
    }

    private void updateProgressUi(int percent) {
        if (progressBar != null) progressBar.setProgress(percent);
        if (progressPercent != null) progressPercent.setText(percent + "%");
    }

    private boolean isDownloadComplete(long id) {
        return getDownloadStatus(id) == DownloadManager.STATUS_SUCCESSFUL;
    }

    private void registerDownloadCompleteReceiver() {
        downloadCompleteReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id != downloadId) return;
                runOnUiThread(() -> {
                    int status = getDownloadStatus(id);
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        showInstallReady();
                    } else if (status == DownloadManager.STATUS_FAILED) {
                        showDownloadFailed();
                    }
                });
            }
        };
        registerReceiver(downloadCompleteReceiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Build.VERSION.SDK_INT >= 33 ? Context.RECEIVER_NOT_EXPORTED : 0);
    }

    private void showInstallReady() {
        stopStatusPolling();
        if (progressBar != null) progressBar.setVisibility(View.GONE);
        if (progressPercent != null) progressPercent.setVisibility(View.GONE);
        if (btnClose != null) btnClose.setVisibility(View.GONE);
        if (titleText != null) titleText.setText(R.string.update_ready);
        if (messageText != null) messageText.setText(R.string.update_install);

        if (btnInstall != null) {
            btnInstall.setVisibility(View.VISIBLE);
            btnInstall.setOnClickListener(v -> {
                java.io.File apk = AppUpdateManager.getUpdateApkFile(this);
                if (apk != null && apk.exists()) {
                    AppUpdateManager.installApk(this, apk);
                }
                new Handler(Looper.getMainLooper()).postDelayed(this::finish, 500);
            });
        }
    }

    private void showDownloadFailed() {
        stopStatusPolling();
        if (progressBar != null) progressBar.setVisibility(View.GONE);
        if (progressPercent != null) progressPercent.setVisibility(View.GONE);
        if (btnInstall != null) btnInstall.setVisibility(View.GONE);
        if (titleText != null) titleText.setText(R.string.updating_title);
        if (messageText != null) messageText.setText(R.string.update_download_failed);
        if (btnClose != null) {
            btnClose.setVisibility(View.VISIBLE);
            btnClose.setOnClickListener(v -> finish());
        }
    }

    private void stopStatusPolling() {
        if (statusCheckHandler != null && statusCheckRunnable != null) {
            statusCheckHandler.removeCallbacks(statusCheckRunnable);
            statusCheckHandler = null;
            statusCheckRunnable = null;
        }
    }

    @Override
    protected void onDestroy() {
        stopStatusPolling();
        if (downloadCompleteReceiver != null) {
            try { unregisterReceiver(downloadCompleteReceiver); } catch (Exception e) { }
        }
        super.onDestroy();
    }
}
