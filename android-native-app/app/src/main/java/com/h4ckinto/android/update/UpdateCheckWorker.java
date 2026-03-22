package com.h4ckinto.android.update;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/**
 * WorkManager worker that runs the update check in the background (e.g. every 6 hours).
 * Does not show UI; only starts download if a newer version is available.
 */
public class UpdateCheckWorker extends Worker {

    private static final String TAG = "UpdateCheckWorker";

    public UpdateCheckWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        String url = com.h4ckinto.android.utils.Constants.CHAT_SERVER_URL + "/api/app/update";
        AppUpdateManager manager = new AppUpdateManager(getApplicationContext(), url);
        boolean ok = manager.checkForUpdateSync();
        Log.d(TAG, "Periodic update check: " + (ok ? "done" : "failed"));
        return ok ? Result.success() : Result.retry();
    }
}
