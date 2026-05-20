package com.h4ckinto.android.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.h4ckinto.android.R;
import com.h4ckinto.android.ui.AudioCallActivity;

public class CallService extends Service {

    private static final String TAG = "CallService";
    public static final String CHANNEL_ACTIVE_CALL = "active_call";
    public static final String CHANNEL_INCOMING_CALL = "incoming_call";
    private static final int NOTIFICATION_ID_ACTIVE = 9001;
    public static final int NOTIFICATION_ID_INCOMING = 9002;
    private static final String EXTRA_CALLER_NAME = "caller_name";

    private final IBinder binder = new LocalBinder();
    private NotificationCompat.Builder activeNotifBuilder;

    public class LocalBinder extends Binder {
        public CallService getService() { return CallService.this; }
    }

    public static Intent createIntent(Context ctx, String callerName) {
        Intent intent = new Intent(ctx, CallService.class);
        intent.putExtra(EXTRA_CALLER_NAME, callerName);
        return intent;
    }

    public static void createNotificationChannels(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);

            NotificationChannel activeChannel = new NotificationChannel(
                    CHANNEL_ACTIVE_CALL,
                    "\u0645\u0643\u0627\u0644\u0645\u0629 \u062C\u0627\u0631\u064A\u0629",
                    NotificationManager.IMPORTANCE_LOW);
            activeChannel.setDescription("\u0625\u0634\u0639\u0627\u0631 \u0627\u0644\u0645\u0643\u0627\u0644\u0645\u0629 \u0627\u0644\u062C\u0627\u0631\u064A\u0629");
            activeChannel.setShowBadge(false);
            nm.createNotificationChannel(activeChannel);

            NotificationChannel incomingChannel = new NotificationChannel(
                    CHANNEL_INCOMING_CALL,
                    "\u0645\u0643\u0627\u0644\u0645\u0629 \u0648\u0627\u0631\u062F\u0629",
                    NotificationManager.IMPORTANCE_HIGH);
            incomingChannel.setDescription("\u0625\u0634\u0639\u0627\u0631 \u0627\u0644\u0645\u0643\u0627\u0644\u0645\u0629 \u0627\u0644\u0648\u0627\u0631\u062F\u0629");
            incomingChannel.enableVibration(true);
            incomingChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(incomingChannel);
        }
    }

    /**
     * Post a full-screen incoming call notification with Answer/Decline actions.
     */
    public static void showIncomingCallNotification(Context ctx, String callId, int callerId,
            String callerName, String callerInitials, String callerAvatarColor,
            String offerSdpJson) {
        createNotificationChannels(ctx);

        Intent fullScreenIntent = AudioCallActivity.createIncoming(ctx, callId, callerId,
                callerName, callerInitials, callerAvatarColor, null);
        if (offerSdpJson != null) {
            fullScreenIntent.putExtra(AudioCallActivity.EXTRA_OFFER_SDP, offerSdpJson);
        }
        fullScreenIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        PendingIntent fullScreenPi = PendingIntent.getActivity(ctx, 0, fullScreenIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, CHANNEL_INCOMING_CALL)
                .setSmallIcon(R.drawable.ic_call)
                .setContentTitle("\u0645\u0643\u0627\u0644\u0645\u0629 \u0648\u0627\u0631\u062F\u0629")
                .setContentText(callerName != null ? callerName : "\u0645\u0633\u062A\u062E\u062F\u0645")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setAutoCancel(true)
                .setOngoing(true)
                .setFullScreenIntent(fullScreenPi, true)
                .setContentIntent(fullScreenPi);

        NotificationManager nm = (NotificationManager) ctx.getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIFICATION_ID_INCOMING, builder.build());
    }

    public static void cancelIncomingCallNotification(Context ctx) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(NOTIFICATION_SERVICE);
        nm.cancel(NOTIFICATION_ID_INCOMING);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannels(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String callerName = "\u0645\u0643\u0627\u0644\u0645\u0629";
        if (intent != null && intent.hasExtra(EXTRA_CALLER_NAME)) {
            callerName = intent.getStringExtra(EXTRA_CALLER_NAME);
        }

        activeNotifBuilder = new NotificationCompat.Builder(this, CHANNEL_ACTIVE_CALL)
                .setSmallIcon(R.drawable.ic_call)
                .setContentTitle("\u0645\u0643\u0627\u0644\u0645\u0629 \u062C\u0627\u0631\u064A\u0629")
                .setContentText(callerName)
                .setOngoing(true)
                .setShowWhen(false)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_LOW);

        // Android 14+: must pass foreground service type matching the manifest or the
        // system throws SecurityException and the app exits.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                        NOTIFICATION_ID_ACTIVE,
                        activeNotifBuilder.build(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(NOTIFICATION_ID_ACTIVE, activeNotifBuilder.build());
            }
        } catch (SecurityException e) {
            Log.e(TAG, "startForeground failed (mic FGS policy?)", e);
            stopSelf();
            return START_NOT_STICKY;
        }
        Log.d(TAG, "CallService started for: " + callerName);
        return START_STICKY;
    }

    public void updateDuration(String duration) {
        if (activeNotifBuilder != null) {
            activeNotifBuilder.setSubText(duration);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(NOTIFICATION_ID_ACTIVE, activeNotifBuilder.build());
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "CallService destroyed");
    }
}
