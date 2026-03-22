package com.h4ckinto.android.utils;

import android.content.Context;
import android.content.SharedPreferences;

public class AppLockManager {

    private static final String PREFS = "app_lock_prefs";
    private static final String KEY_SCREEN_LOCK = "screen_lock";
    private static final String KEY_BIOMETRIC = "biometric";
    private static final String KEY_LAST_UNLOCK = "last_unlock_time";
    private static final String KEY_AUTO_DELETE = "auto_delete_hours";
    private static final long LOCK_TIMEOUT_MS = 60000;

    private static AppLockManager instance;
    private final SharedPreferences prefs;

    private AppLockManager(Context ctx) {
        prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static synchronized AppLockManager getInstance(Context ctx) {
        if (instance == null) {
            instance = new AppLockManager(ctx);
        }
        return instance;
    }

    public boolean isScreenLockEnabled() {
        return prefs.getBoolean(KEY_SCREEN_LOCK, false);
    }

    public void setScreenLockEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_SCREEN_LOCK, enabled).apply();
    }

    public boolean isBiometricEnabled() {
        return prefs.getBoolean(KEY_BIOMETRIC, false);
    }

    public void setBiometricEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_BIOMETRIC, enabled).apply();
    }

    /**
     * Only lock when the app is re-entered after the timeout has elapsed.
     * Navigating between activities within the app will not trigger a lock
     * because unlock() keeps refreshing the timestamp.
     */
    public boolean shouldLock() {
        if (!isScreenLockEnabled()) return false;
        long lastUnlock = prefs.getLong(KEY_LAST_UNLOCK, 0);
        if (lastUnlock == 0) return true;
        long elapsed = System.currentTimeMillis() - lastUnlock;
        return elapsed >= LOCK_TIMEOUT_MS;
    }

    public void unlock() {
        prefs.edit().putLong(KEY_LAST_UNLOCK, System.currentTimeMillis()).apply();
    }

    /** Call when the user is actively using the app to keep the session alive */
    public void touch() {
        if (isScreenLockEnabled()) {
            prefs.edit().putLong(KEY_LAST_UNLOCK, System.currentTimeMillis()).apply();
        }
    }

    // Auto-delete message settings (0 = disabled)
    public int getAutoDeleteHours() {
        return prefs.getInt(KEY_AUTO_DELETE, 0);
    }

    public void setAutoDeleteHours(int hours) {
        prefs.edit().putInt(KEY_AUTO_DELETE, hours).apply();
    }

    public int getAutoDeleteSeconds(int conversationId) {
        return prefs.getInt("auto_delete_conv_" + conversationId, 0);
    }

    public void setAutoDeleteSeconds(int conversationId, int seconds) {
        prefs.edit().putInt("auto_delete_conv_" + conversationId, seconds).apply();
    }

    // Archive lock PIN (used when app lock is not enabled)
    private static final String KEY_ARCHIVE_PIN = "archive_pin";
    private static final String KEY_ARCHIVE_UNLOCK = "archive_unlock_time";
    private static final long ARCHIVE_LOCK_TIMEOUT_MS = 60000;

    public void setArchivePin(String pin) {
        prefs.edit().putString(KEY_ARCHIVE_PIN, pin).apply();
    }

    public String getArchivePin() {
        return prefs.getString(KEY_ARCHIVE_PIN, null);
    }

    public boolean hasArchivePin() {
        String pin = getArchivePin();
        return pin != null && !pin.isEmpty();
    }

    public void unlockArchive() {
        prefs.edit().putLong(KEY_ARCHIVE_UNLOCK, System.currentTimeMillis()).apply();
    }

    public boolean isArchiveUnlocked() {
        long lastUnlock = prefs.getLong(KEY_ARCHIVE_UNLOCK, 0);
        if (lastUnlock == 0) return false;
        return (System.currentTimeMillis() - lastUnlock) < ARCHIVE_LOCK_TIMEOUT_MS;
    }

    // Per-conversation lock
    private static final String KEY_CHAT_LOCK_MODE_PREFIX = "chat_lock_mode_";
    private static final String KEY_CHAT_LOCK_PIN_PREFIX = "chat_lock_pin_";
    private static final String KEY_CHAT_LOCK_UNLOCK_PREFIX = "chat_lock_unlock_";
    private static final String CHAT_LOCK_MODE_NONE = "none";
    private static final String CHAT_LOCK_MODE_PIN = "pin";
    private static final String CHAT_LOCK_MODE_DEVICE = "device";
    private static final long CHAT_LOCK_TIMEOUT_MS = 15000;

    public void setChatLockDisabled(int conversationId) {
        prefs.edit()
                .putString(KEY_CHAT_LOCK_MODE_PREFIX + conversationId, CHAT_LOCK_MODE_NONE)
                .remove(KEY_CHAT_LOCK_PIN_PREFIX + conversationId)
                .remove(KEY_CHAT_LOCK_UNLOCK_PREFIX + conversationId)
                .apply();
    }

    public void setChatLockPin(int conversationId, String pin) {
        prefs.edit()
                .putString(KEY_CHAT_LOCK_MODE_PREFIX + conversationId, CHAT_LOCK_MODE_PIN)
                .putString(KEY_CHAT_LOCK_PIN_PREFIX + conversationId, pin)
                .remove(KEY_CHAT_LOCK_UNLOCK_PREFIX + conversationId)
                .apply();
    }

    public void setChatLockDevice(int conversationId) {
        prefs.edit()
                .putString(KEY_CHAT_LOCK_MODE_PREFIX + conversationId, CHAT_LOCK_MODE_DEVICE)
                .remove(KEY_CHAT_LOCK_UNLOCK_PREFIX + conversationId)
                .apply();
    }

    public boolean isChatLockEnabled(int conversationId) {
        return !CHAT_LOCK_MODE_NONE.equals(getChatLockMode(conversationId));
    }

    public boolean isChatLockPin(int conversationId) {
        return CHAT_LOCK_MODE_PIN.equals(getChatLockMode(conversationId));
    }

    public boolean isChatLockDevice(int conversationId) {
        return CHAT_LOCK_MODE_DEVICE.equals(getChatLockMode(conversationId));
    }

    public String getChatLockMode(int conversationId) {
        return prefs.getString(KEY_CHAT_LOCK_MODE_PREFIX + conversationId, CHAT_LOCK_MODE_NONE);
    }

    public boolean verifyChatPin(int conversationId, String pin) {
        String saved = prefs.getString(KEY_CHAT_LOCK_PIN_PREFIX + conversationId, null);
        return saved != null && saved.equals(pin);
    }

    public void unlockChat(int conversationId) {
        prefs.edit().putLong(KEY_CHAT_LOCK_UNLOCK_PREFIX + conversationId, System.currentTimeMillis()).apply();
    }

    public boolean isChatUnlocked(int conversationId) {
        long lastUnlock = prefs.getLong(KEY_CHAT_LOCK_UNLOCK_PREFIX + conversationId, 0);
        if (lastUnlock == 0) return false;
        return (System.currentTimeMillis() - lastUnlock) < CHAT_LOCK_TIMEOUT_MS;
    }

    public boolean shouldLockChat(int conversationId) {
        return isChatLockEnabled(conversationId) && !isChatUnlocked(conversationId);
    }
}
