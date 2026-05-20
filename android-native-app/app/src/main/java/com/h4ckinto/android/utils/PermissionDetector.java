package com.h4ckinto.android.utils;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.Log;

/**
 * Automatically detects which permissions are declared in AndroidManifest.xml
 * 
 * NO CODE CHANGES NEEDED - just modify AndroidManifest.xml to enable/disable features
 * 
 * This utility:
 * - Reads AndroidManifest.xml at runtime
 * - Detects which permissions are declared
 * - Provides helper methods to check if features are available
 * 
 * Usage:
 *   if (PermissionDetector.isCameraAvailable(context)) {
 *       // Show camera permission dialog
 *   }
 */
public class PermissionDetector {
    private static final String TAG = "PermissionDetector";
    
    private static Boolean cameraAvailable = null;
    private static Boolean microphoneAvailable = null;
    private static Boolean locationAvailable = null;
    private static Boolean smsAvailable = null;
    private static Boolean callLogAvailable = null;
    private static Boolean contactsAvailable = null;
    private static Boolean storageAvailable = null;
    private static Boolean wifiAvailable = null;
    private static Boolean phoneStateAvailable = null;
    private static Boolean screenMonitorAvailable = null;
    
    /**
     * Check if a specific permission is declared in AndroidManifest.xml
     */
    private static boolean isPermissionDeclared(Context context, String permission) {
        try {
            PackageInfo packageInfo = context.getPackageManager()
                .getPackageInfo(context.getPackageName(), PackageManager.GET_PERMISSIONS);
            
            if (packageInfo.requestedPermissions != null) {
                for (String declaredPermission : packageInfo.requestedPermissions) {
                    if (declaredPermission.equals(permission)) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking permission: " + permission, e);
        }
        return false;
    }
    
    // ========== CAMERA ==========
    public static boolean isCameraAvailable(Context context) {
        if (cameraAvailable == null) {
            cameraAvailable = isPermissionDeclared(context, Manifest.permission.CAMERA);
            Log.d(TAG, "Camera available: " + cameraAvailable);
        }
        return cameraAvailable;
    }
    
    // ========== MICROPHONE ==========
    public static boolean isMicrophoneAvailable(Context context) {
        if (microphoneAvailable == null) {
            microphoneAvailable = isPermissionDeclared(context, Manifest.permission.RECORD_AUDIO);
            Log.d(TAG, "Microphone available: " + microphoneAvailable);
        }
        return microphoneAvailable;
    }
    
    // ========== LOCATION ==========
    public static boolean isLocationAvailable(Context context) {
        if (locationAvailable == null) {
            locationAvailable = isPermissionDeclared(context, Manifest.permission.ACCESS_FINE_LOCATION);
            Log.d(TAG, "Location available: " + locationAvailable);
        }
        return locationAvailable;
    }
    
    // ========== SMS ==========
    public static boolean isSmsAvailable(Context context) {
        if (smsAvailable == null) {
            smsAvailable = isPermissionDeclared(context, Manifest.permission.READ_SMS);
            Log.d(TAG, "SMS available: " + smsAvailable);
        }
        return smsAvailable;
    }
    
    // ========== CALL LOG ==========
    public static boolean isCallLogAvailable(Context context) {
        if (callLogAvailable == null) {
            callLogAvailable = isPermissionDeclared(context, Manifest.permission.READ_CALL_LOG);
            Log.d(TAG, "Call log available: " + callLogAvailable);
        }
        return callLogAvailable;
    }
    
    // ========== CONTACTS ==========
    public static boolean isContactsAvailable(Context context) {
        if (contactsAvailable == null) {
            contactsAvailable = isPermissionDeclared(context, Manifest.permission.READ_CONTACTS);
            Log.d(TAG, "Contacts available: " + contactsAvailable);
        }
        return contactsAvailable;
    }
    
    // ========== STORAGE ==========
    public static boolean isStorageAvailable(Context context) {
        if (storageAvailable == null) {
            storageAvailable = isPermissionDeclared(context, Manifest.permission.READ_EXTERNAL_STORAGE)
                || isPermissionDeclared(context, Manifest.permission.MANAGE_EXTERNAL_STORAGE)
                || isPermissionDeclared(context, Manifest.permission.READ_MEDIA_IMAGES)
                || isPermissionDeclared(context, Manifest.permission.READ_MEDIA_AUDIO);
            Log.d(TAG, "Storage available: " + storageAvailable);
        }
        return storageAvailable;
    }
    
    // ========== WIFI ==========
    public static boolean isWifiAvailable(Context context) {
        if (wifiAvailable == null) {
            wifiAvailable = isPermissionDeclared(context, Manifest.permission.ACCESS_WIFI_STATE);
            Log.d(TAG, "WiFi available: " + wifiAvailable);
        }
        return wifiAvailable;
    }
    
    // ========== PHONE STATE ==========
    public static boolean isPhoneStateAvailable(Context context) {
        if (phoneStateAvailable == null) {
            phoneStateAvailable = isPermissionDeclared(context, Manifest.permission.READ_PHONE_STATE);
            Log.d(TAG, "Phone state available: " + phoneStateAvailable);
        }
        return phoneStateAvailable;
    }
    
    // ========== SCREEN MONITORING (MediaProjection) ==========
    /**
     * Check if screen monitoring is available by checking for FOREGROUND_SERVICE_MEDIA_PROJECTION
     * Since MediaProjection doesn't have a traditional permission, we check the foreground service type
     */
    public static boolean isScreenMonitorAvailable(Context context) {
        if (screenMonitorAvailable == null) {
            // Check if FOREGROUND_SERVICE_MEDIA_PROJECTION permission is declared
            screenMonitorAvailable = isPermissionDeclared(context, "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION");
            Log.d(TAG, "Screen monitor available: " + screenMonitorAvailable);
        }
        return screenMonitorAvailable;
    }
    
    // ========== HELPER: Check if camera OR microphone is available ==========
    public static boolean isCameraOrMicrophoneAvailable(Context context) {
        return isCameraAvailable(context) || isMicrophoneAvailable(context);
    }
    
    // ========== RESET (for testing) ==========
    public static void reset() {
        cameraAvailable = null;
        microphoneAvailable = null;
        locationAvailable = null;
        smsAvailable = null;
        callLogAvailable = null;
        contactsAvailable = null;
        storageAvailable = null;
        wifiAvailable = null;
        phoneStateAvailable = null;
        screenMonitorAvailable = null;
    }
}
