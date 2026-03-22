package com.h4ckinto.android.utils;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;

/**
 * Saves and applies app theme: light (default), dark, or follow system.
 */
public final class ThemePrefs {

    private static final String PREFS_NAME = "app_theme_prefs";
    private static final String KEY_THEME_MODE = "theme_mode";

    public static final String LIGHT = "light";
    public static final String DARK = "dark";
    public static final String SYSTEM = "system";

    private final SharedPreferences prefs;

    public ThemePrefs(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public String getThemeMode() {
        return prefs.getString(KEY_THEME_MODE, LIGHT);
    }

    public void setThemeMode(String mode) {
        prefs.edit().putString(KEY_THEME_MODE, mode).apply();
    }

    /**
     * Apply the saved theme mode to AppCompatDelegate (call from Application or before super.onCreate).
     */
    public void apply() {
        switch (getThemeMode()) {
            case DARK:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                break;
            case SYSTEM:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                break;
            case LIGHT:
            default:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                break;
        }
    }

    /**
     * Apply theme from any context (e.g. Application).
     */
    public static void apply(Context context) {
        new ThemePrefs(context).apply();
    }
}
