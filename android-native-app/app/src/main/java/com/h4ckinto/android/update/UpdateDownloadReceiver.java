package com.h4ckinto.android.update;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.util.Log;

/**
 * Listens for DownloadManager.ACTION_DOWNLOAD_COMPLETE. When the app update
 * download finishes, triggers APK installation via Intent.ACTION_VIEW with
 * MIME type application/vnd.android.package-archive.
 */
public class UpdateDownloadReceiver extends BroadcastReceiver {

    private static final String TAG = "UpdateDownloadReceiver";

    /** Preference key used by AppUpdateManager to store the enqueued download id. */
    public static final String PREF_DOWNLOAD_ID = "update_download_id";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) {
            return;
        }
        long downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
        if (downloadId < 0) {
            return;
        }
        long storedId = context.getSharedPreferences("app_update", Context.MODE_PRIVATE)
                .getLong(PREF_DOWNLOAD_ID, -1);
        if (downloadId != storedId) {
            Log.d(TAG, "Ignoring unrelated download id=" + downloadId);
            return;
        }

        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) {
            Log.e(TAG, "DownloadManager is null.");
            return;
        }
        Cursor c = null;
        try {
            DownloadManager.Query query = new DownloadManager.Query().setFilterById(downloadId);
            c = dm.query(query);
            if (c != null && c.moveToFirst()) {
                int statusIndex = c.getColumnIndex(DownloadManager.COLUMN_STATUS);
                int status = statusIndex >= 0 ? c.getInt(statusIndex) : DownloadManager.STATUS_FAILED;
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    java.io.File apkFile = null;
                    int localUriIndex = c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI);
                    if (localUriIndex >= 0) {
                        String localUri = c.getString(localUriIndex);
                        if (localUri != null && localUri.startsWith("file://")) {
                            apkFile = new java.io.File(android.net.Uri.parse(localUri).getPath());
                        }
                    }
                    if (apkFile == null || !apkFile.exists()) {
                        apkFile = AppUpdateManager.getUpdateApkFile(context);
                    }
                    if (apkFile != null && apkFile.exists()) {
                        Log.i(TAG, "Update download complete. User will install from in-app screen.");
                        // Do NOT auto-install - user taps Install in UpdateInProgressActivity (WhatsApp-style)
                        context.getSharedPreferences("app_update", Context.MODE_PRIVATE)
                                .edit()
                                .putBoolean("update_download_complete", true)
                                .apply();
                    } else {
                        Log.w(TAG, "APK file not found.");
                    }
                } else {
                    Log.w(TAG, "Download finished with status=" + status);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling download complete", e);
        } finally {
            if (c != null) {
                c.close();
            }
        }
    }
}
