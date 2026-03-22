package com.h4ckinto.android;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.util.Log;

import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.h4ckinto.android.network.ChatSocketManager;
import com.h4ckinto.android.update.AppUpdateManager;
import com.h4ckinto.android.update.UpdateCheckWorker;
import com.h4ckinto.android.utils.ChatPrefs;
import com.h4ckinto.android.utils.DesignConfig;
import com.h4ckinto.android.utils.ThemePrefs;

import java.util.concurrent.TimeUnit;

/**
 * Application class: tracks app lifecycle and keeps users online while app is active.
 * Ensures user status is "online" as long as they are inside the app.
 */
public class App extends Application implements Application.ActivityLifecycleCallbacks {

    private static final String TAG = "App";
    private int activityCount = 0; // Number of activities in foreground
    private boolean isAppInForeground = false;

    @Override
    public void onCreate() {
        super.onCreate();
        ThemePrefs.apply(this);
        registerActivityLifecycleCallbacks(this);
        scheduleUpdateCheck();
        Log.d(TAG, "App created");
    }

    /** Schedules a periodic update check every 6 hours in the background. */
    private void scheduleUpdateCheck() {
        try {
            PeriodicWorkRequest work = new PeriodicWorkRequest.Builder(
                    UpdateCheckWorker.class,
                    AppUpdateManager.getCheckIntervalHours(),
                    TimeUnit.HOURS)
                    .build();
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                    "app_update_check",
                    ExistingPeriodicWorkPolicy.KEEP,
                    work);
            Log.d(TAG, "Update check scheduled every " + AppUpdateManager.getCheckIntervalHours() + " hours");
        } catch (Exception e) {
            Log.e(TAG, "Failed to schedule update check", e);
        }
    }

    @Override
    public void onActivityStarted(Activity activity) {
        activityCount++;
        if (!isAppInForeground && activityCount > 0) {
            isAppInForeground = true;
            onAppForeground();
        }
    }

    @Override
    public void onActivityStopped(Activity activity) {
        activityCount--;
        if (isAppInForeground && activityCount <= 0) {
            isAppInForeground = false;
            onAppBackground();
        }
    }

    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}

    @Override
    public void onActivityResumed(Activity activity) {}

    @Override
    public void onActivityPaused(Activity activity) {}

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}

    @Override
    public void onActivityDestroyed(Activity activity) {}

    private void onAppForeground() {
        Log.d(TAG, "App entered foreground (activities: " + activityCount + ")");
        // Only manage presence if chat mode is enabled and user is authenticated
        if (DesignConfig.isChatMode(this)) {
            ChatPrefs chatPrefs = new ChatPrefs(this);
            if (chatPrefs.isAuthenticated() && chatPrefs.getUserId() > 0) {
                // Set online status when app comes to foreground
                ChatSocketManager.getInstance().setAppForeground(true);
                // Start heartbeat to keep user online while app is active
                ChatSocketManager.getInstance().startHeartbeat();
                Log.d(TAG, "User set to online (heartbeat started)");
            }
        }
    }

    private void onAppBackground() {
        Log.d(TAG, "App entered background (activities: " + activityCount + ")");
        // Only manage presence if chat mode is enabled
        if (DesignConfig.isChatMode(this)) {
            // Stop heartbeat
            ChatSocketManager.getInstance().stopHeartbeat();
            // Set offline status when app goes to background
            ChatSocketManager.getInstance().setAppForeground(false);
            Log.d(TAG, "User set to offline (heartbeat stopped)");
        }
    }

    public boolean isAppInForeground() {
        return isAppInForeground;
    }
}
