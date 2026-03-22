package com.h4ckinto.android;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

public class PermissionHelper {
    private static final String TAG = "PermissionHelper";

    /**
     * Check if notification listener service is enabled
     */
    public static boolean isNotificationListenerEnabled(Context context) {
        String pkgName = context.getPackageName();
        String flat = Settings.Secure.getString(
            context.getContentResolver(), 
            "enabled_notification_listeners"
        );
        
        if (!TextUtils.isEmpty(flat)) {
            String[] list = flat.split(":");
            for (String name : list) {
                ComponentName cn = ComponentName.unflattenFromString(name);
                if (cn != null && pkgName.equals(cn.getPackageName())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Open notification listener settings
     */
    public static void openNotificationListenerSettings(Activity activity) {
        try {
            Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            activity.startActivity(intent);
            Log.d(TAG, "Opened notification listener settings");
        } catch (Exception e) {
            Log.e(TAG, "Error opening notification listener settings: " + e.getMessage());
            // Fallback to general settings
            try {
                Intent intent = new Intent(Settings.ACTION_SETTINGS);
                activity.startActivity(intent);
            } catch (Exception ex) {
                Log.e(TAG, "Error opening settings: " + ex.getMessage());
            }
        }
    }

    /**
     * Get notification listener service component name
     */
    public static String getNotificationListenerComponentName(Context context) {
        return context.getPackageName() + "/" + 
               context.getPackageName() + ".service.H4CKINTONotificationListenerService";
    }
}
