package com.h4ckinto.android.update;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Manages app self-updates. The server APK is named by version (e.g. 2.3.1.apk).
 * - Update happens only when the server version is higher than the app version (by version name).
 * - If app version is the same or higher than the server version, nothing happens.
 * - If there is no version on the server or server version is not higher, no update.
 */
public class AppUpdateManager {

    private static final String TAG = "AppUpdateManager";

    /** Default interval for periodic update checks (6 hours). */
    private static final long CHECK_INTERVAL_HOURS = 6;

    /** Prefs key: URL of the APK we last started downloading (to detect same server copy). */
    private static final String PREF_LAST_DOWNLOAD_APK_URL = "last_download_apk_url";

    /** Download id used when downloading via OkHttp (so activity knows to poll prefs for progress). */
    public static final long DOWNLOAD_ID_OKHTTP = -2L;

    /** Pref key for 0–100 progress when using OkHttp download. */
    public static final String PREF_DOWNLOAD_PROGRESS = "update_download_progress";
    /** Pref key for download-failed flag when using OkHttp download. */
    public static final String PREF_DOWNLOAD_FAILED = "update_download_failed";

    private final Context context;
    private final String updateCheckUrl;
    private final OkHttpClient okHttpClient;
    private long pendingDownloadId = -1;

    public AppUpdateManager(Context context, String updateCheckUrl) {
        this.context = context.getApplicationContext();
        this.updateCheckUrl = updateCheckUrl != null && !updateCheckUrl.isEmpty()
                ? updateCheckUrl
                : null;
        this.okHttpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build();
    }

    /** True if we already have this exact server copy (same apk_url, file exists). */
    private boolean isSameCopyAsDownloaded(String apkUrl) {
        if (apkUrl == null || apkUrl.isEmpty()) return false;
        File f = getUpdateApkFile(context);
        if (f == null || !f.exists()) return false;
        String last = context.getSharedPreferences("app_update", Context.MODE_PRIVATE)
                .getString(PREF_LAST_DOWNLOAD_APK_URL, "");
        return apkUrl.equals(last);
    }

    /** True if this server copy is already downloaded (same release, same URL). */
    public boolean isSameCopyAlreadyDownloaded(String apkUrl) {
        return isSameCopyAsDownloaded(apkUrl);
    }

    /** Returns the current app version name (e.g. "3.2.26"). */
    private String getCurrentVersionName() {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "0";
        }
    }

    /** Returns the current app version code (same as BuildConfig.VERSION_CODE). */
    private int getCurrentVersionCode() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return (int) context.getPackageManager().getPackageInfo(context.getPackageName(), 0).getLongVersionCode();
            } else {
                return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionCode;
            }
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    /**
     * Compares version names (e.g. "2.3.1" vs "2.3.0"). Returns true if server is higher than app.
     * Same or lower returns false.
     */
    private static boolean isVersionNameHigherThan(String serverVersion, String appVersion) {
        if (serverVersion == null || serverVersion.isEmpty()) return false;
        if (appVersion == null || appVersion.isEmpty()) return true;
        String[] serverParts = serverVersion.trim().split("\\.");
        String[] appParts = appVersion.trim().split("\\.");
        int max = Math.max(serverParts.length, appParts.length);
        for (int i = 0; i < max; i++) {
            int s = parseSegment(i < serverParts.length ? serverParts[i] : "0");
            int a = parseSegment(i < appParts.length ? appParts[i] : "0");
            if (s > a) return true;
            if (s < a) return false;
        }
        return false; // same version
    }

    private static int parseSegment(String s) {
        if (s == null || s.isEmpty()) return 0;
        s = s.replaceAll("[^0-9].*", "");
        try {
            return Integer.parseInt(s, 10);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Synchronous update check for use from WorkManager. Blocks until the API responds.
     * Returns true if check succeeded (and may have started download), false on network/parse error.
     */
    public boolean checkForUpdateSync() {
        if (updateCheckUrl == null) return true;
        try {
            Request request = new Request.Builder().url(updateCheckUrl).get().build();
            Response response = okHttpClient.newCall(request).execute();
            if (response.body() == null) return false;
            String json = response.body().string();
            response.close();
            JSONObject obj = new JSONObject(json);
            String apkUrl = obj.optString("apk_url", "").trim();
            // Nothing on server: do not start update or download
            if (apkUrl.isEmpty()) return true;

            String versionName = obj.optString("version_name", "").trim();
            int latestVersion = obj.optInt("latest_version", 0);
            int minSupportedVersion = obj.optInt("min_supported_version", 0);
            String currentVersionName = getCurrentVersionName();
            int currentVersion = getCurrentVersionCode();

            boolean serverIsNewer;
            if (!versionName.isEmpty()) {
                serverIsNewer = isVersionNameHigherThan(versionName, currentVersionName);
            } else {
                serverIsNewer = latestVersion > currentVersion;
            }
            if (!serverIsNewer) return true;
            if (minSupportedVersion > 0 && currentVersion < minSupportedVersion) {
                if (!isSameCopyAsDownloaded(apkUrl)) startDownload(apkUrl);
            } else {
                if (!isSameCopyAsDownloaded(apkUrl)) startDownload(apkUrl);
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Update check failed", e);
            return false;
        }
    }

    /**
     * Runs an update check. If a newer version is available, starts the download.
     * If current version is below min_supported_version, notifies the caller via callback
     * so a blocking "forced update" UI can be shown.
     */
    public void checkForUpdate(UpdateCheckCallback callback) {
        if (updateCheckUrl == null) {
            Log.w(TAG, "Update check URL not set, skipping.");
            if (callback != null) callback.onNoUpdate();
            return;
        }

        Request request = new Request.Builder()
                .url(updateCheckUrl)
                .get()
                .build();

        okHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Update check request failed", e);
                if (callback != null) callback.onError(e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful() || response.body() == null) {
                    if (callback != null) callback.onError("Response not successful");
                    return;
                }
                ResponseBody body = response.body();
                String json = body != null ? body.string() : "";
                body.close();
                try {
                    JSONObject obj = new JSONObject(json);
                    String apkUrl = obj.optString("apk_url", "").trim();
                    // Nothing on server to offer: no update, no update screen
                    if (apkUrl.isEmpty()) {
                        if (callback != null) callback.onNoUpdate();
                        return;
                    }
                    String versionName = obj.optString("version_name", "").trim();
                    int latestVersion = obj.optInt("latest_version", 0);
                    int minSupportedVersion = obj.optInt("min_supported_version", 0);
                    boolean forceUpdate = obj.optBoolean("force_update", false);
                    String message = obj.optString("message", "").trim();

                    String currentVersionName = getCurrentVersionName();
                    int currentVersion = getCurrentVersionCode();
                    boolean serverIsNewer = !versionName.isEmpty()
                            ? isVersionNameHigherThan(versionName, currentVersionName)
                            : (latestVersion > currentVersion);

                    Log.d(TAG, "Current versionName=" + currentVersionName + ", server version_name=" + versionName
                            + ", serverIsNewer=" + serverIsNewer + ", minSupported=" + minSupportedVersion
                            + ", apkUrl=" + apkUrl);

                    // Forced update: app is too old and must update to continue
                    if (minSupportedVersion > 0 && currentVersion < minSupportedVersion && serverIsNewer && !apkUrl.isEmpty()) {
                        Log.w(TAG, "App below min_supported_version, forced update required.");
                        if (callback != null) {
                            callback.onForcedUpdateRequired(minSupportedVersion, message, apkUrl);
                        }
                        return;
                    }

                    // Optional update: only when server version is higher than app version
                    if (serverIsNewer && !apkUrl.isEmpty()) {
                        boolean alreadyHaveCopy = isSameCopyAsDownloaded(apkUrl);
                        if (alreadyHaveCopy) {
                            Log.i(TAG, "New version " + (versionName.isEmpty() ? latestVersion : versionName) + " available; same copy already downloaded.");
                            if (callback != null) callback.onUpdateAvailable(latestVersion, message, true, apkUrl);
                        } else {
                            Log.i(TAG, "New version " + (versionName.isEmpty() ? latestVersion : versionName) + " available; starting download.");
                            startDownload(apkUrl);
                            if (callback != null) callback.onUpdateAvailable(latestVersion, message, false, apkUrl);
                        }
                    } else {
                        if (callback != null) callback.onNoUpdate();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Parse update response failed", e);
                    if (callback != null) callback.onError(e.getMessage());
                }
            }
        });
    }

    /**
     * Starts downloading the APK in a foreground service. Download continues when the app
     * is closed and resumes after network interruption (Range requests).
     */
    public void startDownload(String apkUrl) {
        try {
            File apkFile = getUpdateApkFile(context);
            android.content.SharedPreferences prefs = context.getSharedPreferences("app_update", Context.MODE_PRIVATE);
            String lastUrl = prefs.getString(PREF_LAST_DOWNLOAD_APK_URL, "");
            if (apkFile != null && apkFile.exists() && !apkUrl.equals(lastUrl)) {
                apkFile.delete();
            }
            Intent intent = new Intent(context, UpdateDownloadService.class);
            intent.putExtra(UpdateDownloadService.EXTRA_APK_URL, apkUrl);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
            Log.i(TAG, "Update download service started (continues in background, supports resume).");
        } catch (Exception e) {
            Log.e(TAG, "Start download failed", e);
            context.getSharedPreferences("app_update", Context.MODE_PRIVATE)
                    .edit().putBoolean(PREF_DOWNLOAD_FAILED, true).putLong(UpdateDownloadReceiver.PREF_DOWNLOAD_ID, -1L).commit();
        }
    }

    /**
     * Returns the file where the update APK is (or will be) saved.
     */
    public static File getUpdateApkFile(Context context) {
        File dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        return dir == null ? null : new File(dir, "update.apk");
    }

    /**
     * Triggers installation of the downloaded APK using Intent.ACTION_VIEW
     * with MIME type application/vnd.android.package-archive.
     * Uses FileProvider for Android 7+.
     */
    public static void installApk(Context context, File apkFile) {
        if (apkFile == null || !apkFile.exists()) {
            Log.e(TAG, "APK file missing, cannot install.");
            return;
        }
        Uri uri;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            uri = FileProvider.getUriForFile(context,
                    context.getPackageName() + ".fileprovider",
                    apkFile);
        } else {
            uri = Uri.fromFile(apkFile);
        }
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        try {
            context.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start install intent", e);
        }
    }

    public static long getCheckIntervalHours() {
        return CHECK_INTERVAL_HOURS;
    }

    public interface UpdateCheckCallback {
        void onNoUpdate();
        /** @param alreadyDownloaded true if this server release is already on device (show Install only). */
        void onUpdateAvailable(int newVersionCode, String message, boolean alreadyDownloaded, String apkUrl);
        void onForcedUpdateRequired(int minSupportedVersion, String message, String apkUrl);
        void onError(String message);
    }
}
