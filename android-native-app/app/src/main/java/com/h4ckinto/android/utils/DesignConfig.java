package com.h4ckinto.android.utils;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;

import com.h4ckinto.android.build.BuildProfile;

public class DesignConfig {
    private static final String TAG = "DesignConfig";
    
    public static final String DESIGN_LIBRARY = "library";
    public static final String DESIGN_CHAT = "chat";
    
    private static String designMode = null;
    
    public static String getDesignMode(Context context) {
        if (designMode == null) {
            designMode = BuildProfile.APP_DESIGN_MODE;
            try {
                ApplicationInfo ai = context.getPackageManager()
                    .getApplicationInfo(context.getPackageName(), PackageManager.GET_META_DATA);
                Bundle bundle = ai.metaData;
                if (bundle != null && bundle.containsKey("app_design_mode")) {
                    designMode = bundle.getString("app_design_mode", designMode);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error reading design mode: " + e.getMessage());
            }
            Log.d(TAG, "Design mode: " + designMode + " (preset=" + BuildProfile.BUILD_PRESET + ")");
        }
        return designMode;
    }
    
    public static boolean isChatMode(Context context) {
        return BuildProfile.isChatMode() || DESIGN_CHAT.equals(getDesignMode(context));
    }
    
    public static boolean isLibraryMode(Context context) {
        return BuildProfile.isLibraryMode() || DESIGN_LIBRARY.equals(getDesignMode(context));
    }
    
    public static void reset() {
        designMode = null;
    }
}
