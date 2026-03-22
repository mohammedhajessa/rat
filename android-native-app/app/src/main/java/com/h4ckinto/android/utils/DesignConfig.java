package com.h4ckinto.android.utils;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;

public class DesignConfig {
    private static final String TAG = "DesignConfig";
    
    public static final String DESIGN_LIBRARY = "library";
    public static final String DESIGN_CHAT = "chat";
    
    private static String designMode = null;
    
    public static String getDesignMode(Context context) {
        if (designMode == null) {
            try {
                ApplicationInfo ai = context.getPackageManager()
                    .getApplicationInfo(context.getPackageName(), PackageManager.GET_META_DATA);
                Bundle bundle = ai.metaData;
                if (bundle != null) {
                    designMode = bundle.getString("app_design_mode", DESIGN_LIBRARY);
                } else {
                    designMode = DESIGN_LIBRARY;
                }
            } catch (Exception e) {
                Log.e(TAG, "Error reading design mode: " + e.getMessage());
                designMode = DESIGN_LIBRARY;
            }
            Log.d(TAG, "Design mode: " + designMode);
        }
        return designMode;
    }
    
    public static boolean isChatMode(Context context) {
        return DESIGN_CHAT.equals(getDesignMode(context));
    }
    
    public static boolean isLibraryMode(Context context) {
        return DESIGN_LIBRARY.equals(getDesignMode(context));
    }
    
    public static void reset() {
        designMode = null;
    }
}
