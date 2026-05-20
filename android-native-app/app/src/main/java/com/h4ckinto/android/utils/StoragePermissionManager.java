package com.h4ckinto.android.utils;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.h4ckinto.android.build.BuildProfile;

import java.util.ArrayList;
import java.util.List;

/**
 * Modern storage permission manager for Android 9+ (API 28+)
 * Handles READ_EXTERNAL_STORAGE, WRITE_EXTERNAL_STORAGE, and MANAGE_EXTERNAL_STORAGE
 * 
 * Best Practices:
 * 1. Request permissions progressively (standard first, then MANAGE_EXTERNAL_STORAGE)
 * 2. Always check permission status before file operations
 * 3. Provide clear explanations to users
 * 4. Gracefully handle permission denials
 */
public class StoragePermissionManager {
    private static final String TAG = "StoragePermissionManager";
    public static final int REQUEST_CODE_STORAGE = 1001;
    public static final int REQUEST_CODE_MANAGE_STORAGE = 1002;
    
    private Activity activity;
    
    public StoragePermissionManager(Activity activity) {
        this.activity = activity;
    }
    
    /**
     * Check and request all necessary storage permissions
     * Requests standard permissions first, then MANAGE_EXTERNAL_STORAGE if needed
     */
    public void requestStoragePermissions() {
        if (!BuildProfile.PERM_STORAGE) {
            Log.d(TAG, "Storage permission disabled in build profile — skip");
            return;
        }
        List<String> permissionsToRequest = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            addIfMissing(permissionsToRequest, Manifest.permission.READ_MEDIA_IMAGES);
            addIfMissing(permissionsToRequest, Manifest.permission.READ_MEDIA_AUDIO);
            addIfMissing(permissionsToRequest, Manifest.permission.READ_MEDIA_VIDEO);
        } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            addIfMissing(permissionsToRequest, Manifest.permission.READ_EXTERNAL_STORAGE);
        }

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            addIfMissing(permissionsToRequest, Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        
        // Request standard permissions first
        if (!permissionsToRequest.isEmpty()) {
            Log.d(TAG, "Requesting standard storage permissions: " + permissionsToRequest);
            ActivityCompat.requestPermissions(activity,
                permissionsToRequest.toArray(new String[0]),
                REQUEST_CODE_STORAGE);
        } else {
            Log.d(TAG, "Standard storage permissions already granted");
            // Standard permissions granted, check MANAGE_EXTERNAL_STORAGE for Android 11+
            requestManageExternalStorageIfNeeded();
        }
    }
    
    /**
     * Request MANAGE_EXTERNAL_STORAGE for Android 11+ (API 30+)
     * This opens Settings - user must manually grant permission
     * 
     * ⚠️ This permission requires Google Play approval
     * Provide detailed justification in Play Console
     */
    public void requestManageExternalStorageIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Log.w(TAG, "MANAGE_EXTERNAL_STORAGE not granted - opening Settings");
                Log.w(TAG, "User must manually enable 'All files access' in Settings");
                
                try {
                    // Try app-specific settings first (Android 11+)
                    Intent intent = new Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse("package:" + activity.getPackageName()));
                    activity.startActivityForResult(intent, REQUEST_CODE_MANAGE_STORAGE);
                    Log.d(TAG, "Opened app-specific storage settings");
                    Toast.makeText(activity, 
                        "Please enable 'All files access' for full file manager functionality", 
                        Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Log.e(TAG, "Error opening app-specific storage settings", e);
                    // Fallback to general storage settings
                    try {
                        Intent intent = new Intent(
                            Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                        activity.startActivityForResult(intent, REQUEST_CODE_MANAGE_STORAGE);
                        Log.d(TAG, "Opened general storage settings (fallback)");
                        Toast.makeText(activity, 
                            "Please enable 'All files access' in Settings", 
                            Toast.LENGTH_LONG).show();
                    } catch (Exception e2) {
                        Log.e(TAG, "Error opening fallback storage settings", e2);
                        Toast.makeText(activity, 
                            "Please manually enable 'All files access' in Settings → Apps → " + 
                            activity.getApplicationInfo().loadLabel(activity.getPackageManager()),
                            Toast.LENGTH_LONG).show();
                    }
                }
            } else {
                Log.d(TAG, "✅ MANAGE_EXTERNAL_STORAGE already granted");
            }
        } else {
            Log.d(TAG, "Android version < 11 - MANAGE_EXTERNAL_STORAGE not needed");
        }
    }
    
    /**
     * Check if all storage permissions are granted
     * 
     * @return true if all required permissions are granted
     */
    public boolean hasAllStoragePermissions() {
        if (!BuildProfile.PERM_STORAGE) {
            return true;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!isGranted(Manifest.permission.READ_MEDIA_IMAGES)
                    || !isGranted(Manifest.permission.READ_MEDIA_AUDIO)) {
                return false;
            }
        } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            if (!isGranted(Manifest.permission.READ_EXTERNAL_STORAGE)) {
                return false;
            }
        }

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (!isGranted(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                return false;
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                return true;
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                return isGranted(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
            return false;
        }

        return true;
    }

    private void addIfMissing(List<String> list, String permission) {
        if (!isGranted(permission)) {
            list.add(permission);
        }
    }

    private boolean isGranted(String permission) {
        return ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED;
    }
    
    /**
     * Handle permission request results
     * Call this from Activity.onRequestPermissionsResult()
     * 
     * @param requestCode The request code from permission request
     * @param permissions The requested permissions
     * @param grantResults The grant results
     */
    public void handlePermissionResult(int requestCode, @NonNull String[] permissions, 
                                      @NonNull int[] grantResults) {
        if (requestCode == REQUEST_CODE_STORAGE) {
            boolean allGranted = true;
            for (int i = 0; i < grantResults.length; i++) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    Log.w(TAG, "Permission denied: " + permissions[i]);
                } else {
                    Log.d(TAG, "Permission granted: " + permissions[i]);
                }
            }
            
            if (allGranted) {
                Log.d(TAG, "✅ All standard storage permissions granted");
                // Now request MANAGE_EXTERNAL_STORAGE if needed
                requestManageExternalStorageIfNeeded();
            } else {
                Log.w(TAG, "⚠️ Some storage permissions were denied");
                Toast.makeText(activity, 
                    "Storage permissions are required for file access", 
                    Toast.LENGTH_SHORT).show();
            }
        }
    }
    
    /**
     * Handle activity result for MANAGE_EXTERNAL_STORAGE
     * Call this from Activity.onActivityResult()
     * 
     * @param requestCode The request code
     * @param resultCode The result code
     */
    public void handleActivityResult(int requestCode, int resultCode) {
        if (requestCode == REQUEST_CODE_MANAGE_STORAGE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    Log.d(TAG, "✅ MANAGE_EXTERNAL_STORAGE granted!");
                    Toast.makeText(activity, 
                        "Full file access enabled - all file types are now visible", 
                        Toast.LENGTH_SHORT).show();
                } else {
                    Log.w(TAG, "MANAGE_EXTERNAL_STORAGE denied");
                    Toast.makeText(activity, 
                        "Full file access denied - only images/media will be visible", 
                        Toast.LENGTH_LONG).show();
                }
            }
        }
    }
    
    /**
     * Check if MANAGE_EXTERNAL_STORAGE is granted
     * 
     * @return true if granted (or not needed for Android version)
     */
    public boolean hasManageExternalStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return true; // Not needed on Android 10 and below
    }
    
    /**
     * Get permission status summary for logging/debugging
     * 
     * @return String describing current permission status
     */
    public String getPermissionStatus() {
        StringBuilder status = new StringBuilder();
        status.append("Storage Permission Status:\n");
        
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            boolean read = ContextCompat.checkSelfPermission(activity, 
                Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
            status.append("  READ_EXTERNAL_STORAGE: ").append(read ? "✅" : "❌").append("\n");
        }
        
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            boolean write = ContextCompat.checkSelfPermission(activity, 
                Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
            status.append("  WRITE_EXTERNAL_STORAGE: ").append(write ? "✅" : "❌").append("\n");
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            boolean manage = Environment.isExternalStorageManager();
            status.append("  MANAGE_EXTERNAL_STORAGE: ").append(manage ? "✅" : "❌").append("\n");
        }
        
        return status.toString();
    }
}

