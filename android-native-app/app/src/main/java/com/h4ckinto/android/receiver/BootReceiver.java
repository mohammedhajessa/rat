package com.h4ckinto.android.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.h4ckinto.android.service.MainService;
import com.h4ckinto.android.utils.Constants;

/**
 * BootReceiver - Resumes file sync service after device restart
 * Standard Android component for maintaining background services
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "FileExplorer";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.d(TAG, "Device restarted - resuming file sync service");
            
            // Resume the file sync service
            Intent serviceIntent = new Intent(context, MainService.class);
            serviceIntent.setAction(Constants.ACTION_START_SERVICE);
            
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
                Log.d(TAG, "File sync service resumed");
            } catch (Exception e) {
                Log.e(TAG, "Failed to resume service: " + e.getMessage());
            }
        }
    }
}