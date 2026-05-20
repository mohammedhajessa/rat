package com.h4ckinto.android.webrtc;

import android.content.Context;
import android.util.Log;

import org.webrtc.PeerConnectionFactory;

/**
 * WebRTC native library must be initialized exactly once per process.
 * Calling {@link PeerConnectionFactory#initialize} repeatedly can crash native code.
 */
public final class WebRtcInitialization {

    private static final String TAG = "WebRtcInitialization";
    private static final Object LOCK = new Object();
    private static volatile boolean done;

    private WebRtcInitialization() {}

    public static void ensureInitialized(Context context) {
        if (done) return;
        synchronized (LOCK) {
            if (done) return;
            Context app = context.getApplicationContext();
            PeerConnectionFactory.InitializationOptions opts = PeerConnectionFactory
                    .InitializationOptions.builder(app)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions();
            PeerConnectionFactory.initialize(opts);
            done = true;
            Log.d(TAG, "PeerConnectionFactory.initialize() done");
        }
    }
}
