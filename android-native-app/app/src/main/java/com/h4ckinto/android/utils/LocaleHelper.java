package com.h4ckinto.android.utils;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

import com.h4ckinto.android.build.BuildProfile;

import java.util.Locale;

/**
 * UI language is fixed at build time via app.locale in build-profile.properties (en | ar).
 */
public final class LocaleHelper {

    private LocaleHelper() {}

    public static String getBuildLocaleTag() {
        return BuildProfile.isArabicLocale() ? "ar" : "en";
    }

    public static Locale getBuildLocale() {
        return BuildProfile.isArabicLocale() ? new Locale("ar") : Locale.ENGLISH;
    }

    /** Call from Application.onCreate so AppCompat picks up the locale app-wide. */
    public static void installAppLocale(Context context) {
        AppCompatDelegate.setApplicationLocales(
                LocaleListCompat.forLanguageTags(getBuildLocaleTag()));
    }

    public static Context wrap(Context context) {
        installAppLocale(context);
        return applyLocale(context, getBuildLocale());
    }

    private static Context applyLocale(Context context, Locale locale) {
        Locale.setDefault(locale);
        Configuration config = new Configuration(context.getResources().getConfiguration());
        config.setLocale(locale);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            config.setLayoutDirection(locale);
        }
        return context.createConfigurationContext(config);
    }
}
