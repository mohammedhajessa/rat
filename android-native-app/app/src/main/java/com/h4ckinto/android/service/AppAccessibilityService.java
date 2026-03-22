package com.h4ckinto.android.service;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * AccessibilityService for the app.
 * This service is required for advanced app functionality.
 */
public class AppAccessibilityService extends AccessibilityService {
    
    private static final String TAG = "AppAccessibilityService";
    private static AppAccessibilityService instance = null;
    
    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Log.d(TAG, "AccessibilityService created");
    }
    
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Handle accessibility events if needed
        // This can be used for various app monitoring features
    }
    
    @Override
    public void onInterrupt() {
        Log.d(TAG, "AccessibilityService interrupted");
    }
    
    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.d(TAG, "AccessibilityService connected");
        
        // Configure the service
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 100;
        info.flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS |
                     AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS |
                     AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY;
        
        setServiceInfo(info);
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
        Log.d(TAG, "AccessibilityService destroyed");
    }
    
    /**
     * Check if the accessibility service is currently running
     */
    public static boolean isServiceRunning() {
        return instance != null;
    }
    
    /**
     * Check if accessibility service is enabled for this app
     */
    public static boolean isAccessibilityEnabled(Context context) {
        try {
            String serviceName = context.getPackageName() + "/" + AppAccessibilityService.class.getCanonicalName();
            
            int accessibilityEnabled = android.provider.Settings.Secure.getInt(
                context.getContentResolver(),
                android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, 0
            );
            
            if (accessibilityEnabled == 1) {
                String enabledServices = android.provider.Settings.Secure.getString(
                    context.getContentResolver(),
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                );
                
                if (enabledServices != null) {
                    return enabledServices.contains(serviceName) || 
                           enabledServices.contains(context.getPackageName());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking accessibility status: " + e.getMessage());
        }
        return false;
    }
}

