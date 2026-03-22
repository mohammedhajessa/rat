package com.h4ckinto.android.utils;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import java.util.HashSet;
import java.util.Set;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.h4ckinto.android.R;
import com.h4ckinto.android.ui.ConversationActivity;

public class ChatNotificationManager {

    private static final String CHANNEL_ID = "sarmad_chat_v3";
    private static final String CHANNEL_NAME = "\u0627\u0644\u0631\u0633\u0627\u0626\u0644";
    private static final int NOTIF_ID_OFFSET = 50000;

    private static ChatNotificationManager instance;
    private boolean channelCreated = false;

    private volatile int activeConversationId = -1;
    private volatile boolean appInForeground = false;
    private final Set<Integer> mutedConversationIds = new HashSet<>();

    private ChatNotificationManager() {}

    public void setAppInForeground(boolean inForeground) {
        this.appInForeground = inForeground;
    }

    public void setMutedConversations(Set<Integer> ids) {
        synchronized (mutedConversationIds) {
            mutedConversationIds.clear();
            if (ids != null) mutedConversationIds.addAll(ids);
        }
    }

    public void addMutedConversation(int conversationId) {
        synchronized (mutedConversationIds) { mutedConversationIds.add(conversationId); }
    }

    public void removeMutedConversation(int conversationId) {
        synchronized (mutedConversationIds) { mutedConversationIds.remove(conversationId); }
    }

    public static synchronized ChatNotificationManager getInstance() {
        if (instance == null) instance = new ChatNotificationManager();
        return instance;
    }

    public void setActiveConversation(int conversationId) {
        this.activeConversationId = conversationId;
    }

    public void clearActiveConversation() {
        this.activeConversationId = -1;
    }

    public void createChannel(Context ctx) {
        if (channelCreated) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager mgr = ctx.getSystemService(NotificationManager.class);
            if (mgr != null) {
                // Clean up old channels
                mgr.deleteNotificationChannel("chat_messages");
                mgr.deleteNotificationChannel("chat_messages_v2");

                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        CHANNEL_NAME,
                        NotificationManager.IMPORTANCE_HIGH);
                channel.setDescription("\u0625\u0634\u0639\u0627\u0631\u0627\u062A \u0627\u0644\u0631\u0633\u0627\u0626\u0644 \u0627\u0644\u0648\u0627\u0631\u062F\u0629");
                channel.enableVibration(true);
                channel.setShowBadge(true);
                channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);

                mgr.createNotificationChannel(channel);
            }
        }
        channelCreated = true;
    }

    public void showMessageNotification(Context ctx, int conversationId, int otherUserId,
                                         String senderName, String messagePreview,
                                         String senderInitials, String avatarColor) {
        if (appInForeground) return;
        if (conversationId == activeConversationId) return;
        synchronized (mutedConversationIds) { if (mutedConversationIds.contains(conversationId)) return; }

        createChannel(ctx);

        Intent intent = new Intent(ctx, ConversationActivity.class);
        intent.putExtra("conversation_id", conversationId);
        intent.putExtra("other_user_id", otherUserId);
        intent.putExtra("contact_name", senderName);
        intent.putExtra("contact_initials", senderInitials);
        intent.putExtra("avatar_color", avatarColor);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int notifId = conversationId + NOTIF_ID_OFFSET;

        PendingIntent pendingIntent = PendingIntent.getActivity(ctx, notifId,
                intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String preview = messagePreview;
        if (preview == null || preview.isEmpty()) preview = ctx.getString(R.string.notif_new_message);

        // App launcher icon for both small (notification row) and large (expanded) icons
        Bitmap largeIcon = BitmapFactory.decodeResource(ctx.getResources(), R.mipmap.ic_launcher);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher_round)
                .setLargeIcon(largeIcon)
                .setContentTitle(senderName)
                .setContentText(preview)
                .setAutoCancel(true)
                .setOngoing(false)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE);

        try {
            NotificationManagerCompat.from(ctx).notify(notifId, builder.build());
        } catch (SecurityException e) {
            // Missing POST_NOTIFICATIONS permission on Android 13+
        }
    }

    public void cancelNotification(Context ctx, int conversationId) {
        NotificationManagerCompat.from(ctx).cancel(conversationId + NOTIF_ID_OFFSET);
    }

    public void cancelAllNotifications(Context ctx) {
        NotificationManagerCompat.from(ctx).cancelAll();
    }
}
