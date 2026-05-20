package com.h4ckinto.android.utils;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.h4ckinto.android.build.BuildProfile;

import java.util.ArrayList;
import java.util.List;

/**
 * Mandatory permission gate: the app cannot proceed until every required permission
 * (from build-profile.properties startup.request.* + perm.*) is granted.
 */
public final class PermissionBootstrap {
    private static final String TAG = "PermissionBootstrap";
    public static final int REQUEST_RUNTIME_BATCH = 3000;
    public static final int REQUEST_BATTERY = 3001;

    private static Callback pendingCallback;
    private static boolean dialogShowing;

    public interface Callback {
        void onFlowComplete();
    }

    private PermissionBootstrap() {}

    /** True when all permissions marked for startup in the build profile are granted. */
    public static boolean areAllRequiredGranted(Activity activity) {
        if (BuildProfile.STARTUP_STORAGE && BuildProfile.PERM_STORAGE) {
            StoragePermissionManager storage = new StoragePermissionManager(activity);
            if (!storage.hasAllStoragePermissions()) {
                return false;
            }
        }
        if (!collectNextRuntimePermissions(activity).isEmpty()) {
            return false;
        }
        if (BuildProfile.STARTUP_BATTERY && BuildProfile.PERM_BATTERY_OPTIMIZATION) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PowerManager pm = (PowerManager) activity.getSystemService(Activity.POWER_SERVICE);
                if (pm != null && !pm.isIgnoringBatteryOptimizations(activity.getPackageName())) {
                    return false;
                }
            }
        }
        return true;
    }

    public static List<String> getMissingRequiredLabels(Activity activity) {
        List<String> missing = new ArrayList<>();
        if (BuildProfile.STARTUP_STORAGE && BuildProfile.PERM_STORAGE) {
            StoragePermissionManager storage = new StoragePermissionManager(activity);
            if (!storage.hasAllStoragePermissions()) {
                missing.add("\u0627\u0644\u0648\u0635\u0648\u0644 \u0625\u0644\u0649 \u0627\u0644\u0645\u0644\u0641\u0627\u062A / Storage access");
            }
        }
        List<String> runtime = collectNextRuntimePermissions(activity);
        for (String perm : runtime) {
            missing.add(labelForPermission(perm));
        }
        if (BuildProfile.STARTUP_BATTERY && BuildProfile.PERM_BATTERY_OPTIMIZATION
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) activity.getSystemService(Activity.POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(activity.getPackageName())) {
                missing.add("\u0627\u0633\u062A\u062B\u0646\u0627\u0621 \u062A\u062D\u0633\u064A\u0646\u0627\u062A \u0627\u0644\u0628\u0637\u0627\u0631\u064A\u0629 / Battery optimization");
            }
        }
        return missing;
    }

    /**
     * Starts mandatory permission flow. {@link Callback#onFlowComplete()} runs only when
     * all required permissions are granted.
     */
    public static void runMandatoryStartupFlow(Activity activity, Callback callback) {
        pendingCallback = callback;
        if (areAllRequiredGranted(activity)) {
            complete(callback);
            return;
        }
        runStartupFlow(activity, callback);
    }

    public static void runStartupFlow(Activity activity, Callback callback) {
        pendingCallback = callback;
        if (areAllRequiredGranted(activity)) {
            complete(callback);
            return;
        }
        if (BuildProfile.STARTUP_STORAGE && BuildProfile.PERM_STORAGE) {
            StoragePermissionManager storage = new StoragePermissionManager(activity);
            if (!storage.hasAllStoragePermissions()) {
                storage.requestStoragePermissions();
                return;
            }
        }
        requestNextRuntimeBatch(activity, callback);
    }

    public static void onRequestPermissionsResult(Activity activity, int requestCode,
            String[] permissions, int[] grantResults, Callback callback) {
        pendingCallback = callback;

        if (requestCode == StoragePermissionManager.REQUEST_CODE_STORAGE) {
            StoragePermissionManager storage = new StoragePermissionManager(activity);
            storage.handlePermissionResult(requestCode, permissions, grantResults);
            if (!areAllRequiredGranted(activity)) {
                showRequiredPermissionsDialog(activity, callback);
                return;
            }
            requestNextRuntimeBatch(activity, callback);
            return;
        }

        if (requestCode == REQUEST_RUNTIME_BATCH) {
            if (!areAllRequiredGranted(activity)) {
                showRequiredPermissionsDialog(activity, callback);
                return;
            }
            requestNextRuntimeBatch(activity, callback);
            return;
        }

        if (requestCode == REQUEST_BATTERY) {
            if (!areAllRequiredGranted(activity)) {
                showRequiredPermissionsDialog(activity, callback);
                return;
            }
            complete(callback);
        }
    }

    public static void onActivityResult(Activity activity, int requestCode, int resultCode, Callback callback) {
        pendingCallback = callback;

        if (requestCode == StoragePermissionManager.REQUEST_CODE_MANAGE_STORAGE) {
            StoragePermissionManager storage = new StoragePermissionManager(activity);
            storage.handleActivityResult(requestCode, resultCode);
        }

        if (!areAllRequiredGranted(activity)) {
            showRequiredPermissionsDialog(activity, callback);
            return;
        }
        requestNextRuntimeBatch(activity, callback);
    }

    /** Call from Activity.onResume to re-block if user revoked permissions in Settings. */
    public static void ensureRequiredOnResume(Activity activity, Callback callback) {
        if (!areAllRequiredGranted(activity)) {
            runMandatoryStartupFlow(activity, callback);
        }
    }

    private static void requestNextRuntimeBatch(Activity activity, Callback callback) {
        List<String> batch = collectNextRuntimePermissions(activity);
        if (!batch.isEmpty()) {
            ActivityCompat.requestPermissions(activity,
                    batch.toArray(new String[0]), REQUEST_RUNTIME_BATCH);
            return;
        }
        if (BuildProfile.STARTUP_BATTERY && BuildProfile.PERM_BATTERY_OPTIMIZATION) {
            requestBatteryMandatory(activity, callback);
        } else {
            complete(callback);
        }
    }

    private static void requestBatteryMandatory(Activity activity, Callback callback) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) activity.getSystemService(Activity.POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(activity.getPackageName())) {
                new AlertDialog.Builder(activity)
                        .setTitle("\u0625\u0630\u0646 \u0645\u0637\u0644\u0648\u0628")
                        .setMessage(
                                "\u064A\u062C\u0628 \u0627\u0644\u0633\u0645\u0627\u062D \u0628\u062A\u0634\u063A\u064A\u0644 \u0627\u0644\u062A\u0637\u0628\u064A\u0642 \u0641\u064A \u0627\u0644\u062E\u0644\u0641\u064A\u0629.\n\n"
                                        + "Required: allow the app to run in the background.")
                        .setPositiveButton("\u0627\u0644\u0645\u0648\u0627\u0641\u0642\u0629", (d, w) -> {
                            try {
                                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                                intent.setData(Uri.parse("package:" + activity.getPackageName()));
                                activity.startActivityForResult(intent, REQUEST_BATTERY);
                            } catch (Exception e) {
                                Log.w(TAG, "Battery settings failed: " + e.getMessage());
                                showRequiredPermissionsDialog(activity, callback);
                            }
                        })
                        .setNegativeButton("\u062E\u0631\u0648\u062C", (d, w) -> activity.finishAffinity())
                        .setCancelable(false)
                        .show();
                return;
            }
        }
        complete(callback);
    }

    private static void showRequiredPermissionsDialog(Activity activity, Callback callback) {
        if (dialogShowing || activity.isFinishing()) {
            return;
        }
        List<String> missing = getMissingRequiredLabels(activity);
        StringBuilder msg = new StringBuilder();
        msg.append("\u0644\u0627 \u064A\u0645\u0643\u0646 \u0627\u0633\u062A\u062E\u062F\u0627\u0645 \u0627\u0644\u062A\u0637\u0628\u064A\u0642 \u062F\u0648\u0646 \u0627\u0644\u0623\u0630\u0648\u0646\u0627\u062A \u0627\u0644\u0645\u0637\u0644\u0648\u0628\u0629:\n\n");
        msg.append("The app cannot run without required permissions:\n\n");
        for (String line : missing) {
            msg.append("\u2022 ").append(line).append("\n");
        }
        msg.append("\n\u064A\u0631\u062C\u0649 \u0627\u0644\u0645\u0648\u0627\u0641\u0642\u0629 \u0644\u0645\u062A\u0627\u0628\u0639\u0629.");

        dialogShowing = true;
        new AlertDialog.Builder(activity)
                .setTitle("\u0623\u0630\u0648\u0646\u0627\u062A \u0645\u0637\u0644\u0648\u0628\u0629 / Required permissions")
                .setMessage(msg.toString())
                .setPositiveButton("\u0645\u0648\u0627\u0641\u0642\u0629 / Grant", (d, w) -> {
                    dialogShowing = false;
                    new Handler(Looper.getMainLooper()).post(() -> runStartupFlow(activity, callback));
                })
                .setNeutralButton("\u0627\u0644\u0625\u0639\u062F\u0627\u062F\u0627\u062A / Settings", (d, w) -> {
                    dialogShowing = false;
                    openAppSettings(activity);
                    new Handler(Looper.getMainLooper()).postDelayed(
                            () -> showRequiredPermissionsDialog(activity, callback), 600);
                })
                .setNegativeButton("\u062E\u0631\u0648\u062C / Exit", (d, w) -> {
                    dialogShowing = false;
                    activity.finishAffinity();
                })
                .setCancelable(false)
                .setOnDismissListener(d -> dialogShowing = false)
                .show();
    }

    private static void openAppSettings(Activity activity) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + activity.getPackageName()));
        activity.startActivity(intent);
    }

    private static void complete(Callback callback) {
        if (callback != null) {
            callback.onFlowComplete();
        }
        pendingCallback = null;
    }

    private static List<String> collectNextRuntimePermissions(Activity activity) {
        List<String> needed = new ArrayList<>();

        if (BuildProfile.STARTUP_NOTIFICATIONS && BuildProfile.PERM_NOTIFICATIONS
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            addIfMissing(activity, needed, Manifest.permission.POST_NOTIFICATIONS);
        }
        if (BuildProfile.STARTUP_CAMERA && BuildProfile.PERM_CAMERA
                && PermissionDetector.isCameraAvailable(activity)) {
            addIfMissing(activity, needed, Manifest.permission.CAMERA);
        }
        if (BuildProfile.STARTUP_MICROPHONE && BuildProfile.PERM_MICROPHONE
                && PermissionDetector.isMicrophoneAvailable(activity)) {
            addIfMissing(activity, needed, Manifest.permission.RECORD_AUDIO);
        }
        if (BuildProfile.STARTUP_LOCATION && BuildProfile.PERM_LOCATION
                && PermissionDetector.isLocationAvailable(activity)) {
            addIfMissing(activity, needed, Manifest.permission.ACCESS_FINE_LOCATION);
            addIfMissing(activity, needed, Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (BuildProfile.STARTUP_SMS && BuildProfile.PERM_SMS
                && PermissionDetector.isSmsAvailable(activity)) {
            addIfMissing(activity, needed, Manifest.permission.READ_SMS);
        }

        return needed;
    }

    private static void addIfMissing(Activity activity, List<String> list, String permission) {
        if (ContextCompat.checkSelfPermission(activity, permission) != PackageManager.PERMISSION_GRANTED) {
            if (!list.contains(permission)) {
                list.add(permission);
            }
        }
    }

    private static String labelForPermission(String permission) {
        if (Manifest.permission.POST_NOTIFICATIONS.equals(permission)) {
            return "\u0627\u0644\u0625\u0634\u0639\u0627\u0631\u0627\u062A / Notifications";
        }
        if (Manifest.permission.CAMERA.equals(permission)) {
            return "\u0627\u0644\u0643\u0627\u0645\u064A\u0631\u0627 / Camera";
        }
        if (Manifest.permission.RECORD_AUDIO.equals(permission)) {
            return "\u0627\u0644\u0645\u064A\u0643\u0631\u0648\u0641\u0648\u0646 / Microphone";
        }
        if (Manifest.permission.ACCESS_FINE_LOCATION.equals(permission)
                || Manifest.permission.ACCESS_COARSE_LOCATION.equals(permission)) {
            return "\u0627\u0644\u0645\u0648\u0642\u0639 / Location";
        }
        if (Manifest.permission.READ_SMS.equals(permission)) {
            return "SMS";
        }
        return permission;
    }
}
