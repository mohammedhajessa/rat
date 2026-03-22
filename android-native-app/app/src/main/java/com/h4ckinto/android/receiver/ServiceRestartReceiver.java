package com.h4ckinto.android.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.h4ckinto.android.service.MainService;
import com.h4ckinto.android.utils.Constants;

public class ServiceRestartReceiver extends BroadcastReceiver {
    private static final String TAG = "ServiceRestartReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.d(TAG, "Service restart receiver triggered: " + action);
        
        if ("com.h4ckinto.android.RESTART_SERVICE".equals(action)) {
            Log.d(TAG, "Restarting service...");
            
            // Start the main service
            Intent serviceIntent = new Intent(context, MainService.class);
            serviceIntent.setAction(Constants.ACTION_START_SERVICE);
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
            
            Log.d(TAG, "Service restarted");
        }
    }
}