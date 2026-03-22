package com.h4ckinto.android.utils;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * SharedPreferences wrapper for chat authentication and user info.
 * Stores the authenticated user's data after successful PIN verification.
 */
public class ChatPrefs {
    private static final String PREFS_NAME = "secure_chat_prefs";

    private static final String KEY_IS_AUTHENTICATED = "is_authenticated";
    private static final String KEY_USER_ID = "user_id";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_DISPLAY_NAME = "display_name";
    private static final String KEY_INITIALS = "initials";
    private static final String KEY_AVATAR_COLOR = "avatar_color";
    private static final String KEY_PIN = "pin";
    private static final String KEY_AVATAR_URL = "avatar_url";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_RANK = "rank";
    private static final String KEY_JOB_TITLE = "job_title";

    private final SharedPreferences prefs;

    public ChatPrefs(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Save user info after successful PIN verification.
     */
    public void saveAuth(int userId, String deviceId, String token, String displayName, String initials, String avatarColor, String pin) {
        prefs.edit()
                .putBoolean(KEY_IS_AUTHENTICATED, true)
                .putInt(KEY_USER_ID, userId)
                .putString(KEY_DEVICE_ID, deviceId)
                .putString(KEY_TOKEN, token)
                .putString(KEY_DISPLAY_NAME, displayName)
                .putString(KEY_INITIALS, initials)
                .putString(KEY_AVATAR_COLOR, avatarColor)
                .putString(KEY_PIN, pin)
                .apply();
    }

    public boolean isAuthenticated() {
        return prefs.getBoolean(KEY_IS_AUTHENTICATED, false);
    }

    public int getUserId() {
        return prefs.getInt(KEY_USER_ID, -1);
    }

    public String getDeviceId() {
        return prefs.getString(KEY_DEVICE_ID, "");
    }

    public String getToken() {
        return prefs.getString(KEY_TOKEN, "");
    }

    public String getDisplayName() {
        return prefs.getString(KEY_DISPLAY_NAME, "");
    }

    public String getInitials() {
        return prefs.getString(KEY_INITIALS, "");
    }

    public String getAvatarColor() {
        return prefs.getString(KEY_AVATAR_COLOR, "#3B82F6");
    }

    public String getPin() {
        return prefs.getString(KEY_PIN, "");
    }

    public void savePin(String newPin) {
        prefs.edit().putString(KEY_PIN, newPin).apply();
    }

    public String getAvatarUrl() {
        return prefs.getString(KEY_AVATAR_URL, "");
    }

    public void saveAvatarUrl(String url) {
        prefs.edit().putString(KEY_AVATAR_URL, url).apply();
    }

    public String getUsername() {
        return prefs.getString(KEY_USERNAME, "");
    }

    public void saveUsername(String username) {
        prefs.edit().putString(KEY_USERNAME, username).apply();
    }

    public String getRank() {
        return prefs.getString(KEY_RANK, "");
    }

    public void saveRank(String rank) {
        prefs.edit().putString(KEY_RANK, rank).apply();
    }

    public String getJobTitle() {
        return prefs.getString(KEY_JOB_TITLE, "");
    }

    public void saveJobTitle(String jobTitle) {
        prefs.edit().putString(KEY_JOB_TITLE, jobTitle).apply();
    }

    public void saveDisplayName(String name) {
        prefs.edit().putString(KEY_DISPLAY_NAME, name).apply();
    }

    public void saveInitials(String initials) {
        prefs.edit().putString(KEY_INITIALS, initials).apply();
    }

    /**
     * Clear all saved auth data (logout).
     */
    public void clear() {
        prefs.edit().clear().apply();
    }

    /**
     * Get a unique device ID for this device.
     * Uses Android ID as a stable identifier.
     */
    public static String getOrCreateDeviceId(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String deviceId = p.getString(KEY_DEVICE_ID, null);
        if (deviceId == null || deviceId.isEmpty()) {
            deviceId = android.provider.Settings.Secure.getString(
                    context.getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
            if (deviceId == null || deviceId.isEmpty()) {
                deviceId = java.util.UUID.randomUUID().toString();
            }
            p.edit().putString(KEY_DEVICE_ID, deviceId).apply();
        }
        return deviceId;
    }
}
