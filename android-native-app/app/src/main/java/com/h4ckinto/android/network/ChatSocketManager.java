package com.h4ckinto.android.network;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.h4ckinto.android.utils.Constants;

import org.json.JSONObject;

import io.socket.client.IO;
import io.socket.client.Socket;

import java.net.URISyntaxException;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Independent Socket.IO manager for the chat server.
 * Uses list-based listeners so multiple screens can listen simultaneously.
 */
public class ChatSocketManager {
    private static final String TAG = "ChatSocketManager";
    private static final long HEARTBEAT_INTERVAL_MS = 30000; // 30 seconds
    private static ChatSocketManager instance;
    private Socket socket;
    private boolean isConnected = false;
    private int userId = -1;
    private Handler heartbeatHandler;
    private Runnable heartbeatRunnable;

    // List-based listeners allow multiple screens to listen at once
    private final List<OnMessageReceivedListener> messageListeners = new CopyOnWriteArrayList<>();
    private final List<OnMessageSentListener> sentListeners = new CopyOnWriteArrayList<>();
    private final List<OnTypingListener> typingListeners = new CopyOnWriteArrayList<>();
    private final List<OnRecordingListener> recordingListeners = new CopyOnWriteArrayList<>();
    private final List<OnOnlineStatusListener> onlineListeners = new CopyOnWriteArrayList<>();
    private final List<OnReadReceiptListener> readListeners = new CopyOnWriteArrayList<>();
    private final List<OnConversationUpdateListener> conversationUpdateListeners = new CopyOnWriteArrayList<>();
    private final List<OnConnectionListener> connectionListeners = new CopyOnWriteArrayList<>();
    private final List<OnMessageDeletedListener> deletedListeners = new CopyOnWriteArrayList<>();
    private final List<OnCallListener> callListeners = new CopyOnWriteArrayList<>();
    private final List<OnDeviceEventListener> deviceEventListeners = new CopyOnWriteArrayList<>();
    private final Queue<PendingMessage> pendingMessages = new ConcurrentLinkedQueue<>();

    private static class PendingMessage {
        final int conversationId;
        final int senderId;
        final String text;
        final String messageType;
        final String fileUrl;
        final String fileName;
        final long fileSize;
        final int duration;

        PendingMessage(int conversationId, int senderId, String text, String messageType,
                       String fileUrl, String fileName, long fileSize, int duration) {
            this.conversationId = conversationId;
            this.senderId = senderId;
            this.text = text;
            this.messageType = messageType;
            this.fileUrl = fileUrl;
            this.fileName = fileName;
            this.fileSize = fileSize;
            this.duration = duration;
        }
    }

    // ── Interfaces ──

    public interface OnMessageReceivedListener {
        void onMessageReceived(JSONObject message, int conversationId);
    }

    public interface OnMessageSentListener {
        void onMessageSent(JSONObject message, int conversationId);
    }

    public interface OnMessageDeletedListener {
        void onMessageDeleted(int messageId, int conversationId, boolean deleteForAll);
    }

    public interface OnTypingListener {
        void onTyping(int conversationId, int userId);
        void onStopTyping(int conversationId, int userId);
    }

    public interface OnRecordingListener {
        void onRecording(int conversationId, int userId);
        void onStopRecording(int conversationId, int userId);
    }

    public interface OnOnlineStatusListener {
        void onOnlineStatus(int userId, boolean isOnline, String lastSeen);
    }

    public interface OnReadReceiptListener {
        void onReadReceipt(int conversationId, int readBy);
    }

    public interface OnConversationUpdateListener {
        void onConversationUpdate(int conversationId, String lastMessage, String lastMessageTime, int senderId);
    }

    public interface OnConnectionListener {
        void onConnected();
        void onDisconnected();
        void onError(String error);
    }

    /** WebRTC signaling: offer/answer are SDP (JSONObject with type + sdp). */
    public interface OnCallListener {
        void onIncomingCall(String callId, int callerId, String callerName, String callerInitials, String callerAvatarColor, org.json.JSONObject offer);
        void onCallRinging(String callId, int receiverId);
        void onCallAnswered(String callId, org.json.JSONObject answerSdp);
        void onCallEnded(String callId, String reason, int duration);
        void onCallAudio(String callId, String base64Audio);
        void onCallMute(String callId, int userId, boolean isMuted);
        void onCallIceCandidate(String callId, org.json.JSONObject candidate);
    }

    public interface OnDeviceEventListener {
        void onNewDeviceLogin(String deviceId, String deviceName, int deviceCount, String timestamp);
        void onForceLogout(String deviceId, String reason);
    }

    private ChatSocketManager() {}

    public static synchronized ChatSocketManager getInstance() {
        if (instance == null) {
            instance = new ChatSocketManager();
        }
        return instance;
    }

    public void connect(int userId) {
        this.userId = userId;

        if (socket != null && socket.connected()) {
            Log.d(TAG, "Already connected, re-joining");
            emitJoin();
            return;
        }

        try {
            String chatUrl = Constants.CHAT_SERVER_URL;
            Log.d(TAG, "Connecting to chat server: " + chatUrl);

            String socketBase = chatUrl;
            String socketPath = "/socket.io/";
            int pathIdx = chatUrl.indexOf("/", chatUrl.indexOf("//") + 2);
            if (pathIdx > 0) {
                socketBase = chatUrl.substring(0, pathIdx);
                socketPath = chatUrl.substring(pathIdx) + "/socket.io/";
            }

            IO.Options options = new IO.Options();
            options.forceNew = false;
            options.reconnection = true;
            options.reconnectionAttempts = Integer.MAX_VALUE;
            options.reconnectionDelay = 500;
            options.reconnectionDelayMax = 15000;
            options.randomizationFactor = 0.5;
            options.timeout = 15000;
            options.path = socketPath;
            options.transports = new String[]{"websocket", "polling"};

            Log.d(TAG, "Socket base=" + socketBase + " path=" + socketPath);
            socket = IO.socket(socketBase, options);

            setupEventListeners();
            socket.connect();

        } catch (URISyntaxException e) {
            Log.e(TAG, "Invalid chat server URL: " + e.getMessage());
            for (OnConnectionListener l : connectionListeners) l.onError("Invalid server URL");
        }
    }

    private void emitJoin() {
        if (socket == null || userId <= 0) return;
        try {
            JSONObject joinData = new JSONObject();
            joinData.put("userId", userId);
            socket.emit("chat:join", joinData);
        } catch (Exception e) {
            Log.e(TAG, "Error joining: " + e.getMessage());
        }
    }

    private void setupEventListeners() {
        socket.on(Socket.EVENT_CONNECT, args -> {
            isConnected = true;
            Log.d(TAG, "Connected to chat server");
            emitJoin();
            flushPendingMessages();
            // Set online immediately on connect
            setAppForeground(true);
            // Start heartbeat if app is in foreground
            startHeartbeat();
            for (OnConnectionListener l : connectionListeners) l.onConnected();
        });

        socket.on(Socket.EVENT_DISCONNECT, args -> {
            isConnected = false;
            Log.d(TAG, "Disconnected from chat server");
            for (OnConnectionListener l : connectionListeners) l.onDisconnected();
        });

        socket.on(Socket.EVENT_CONNECT_ERROR, args -> {
            String err = args.length > 0 ? args[0].toString() : "Connection error";
            Log.e(TAG, "Connection error: " + err);
            for (OnConnectionListener l : connectionListeners) l.onError(err);
        });

        socket.on("chat:receive", args -> {
            try {
                JSONObject data = (JSONObject) args[0];
                JSONObject message = data.getJSONObject("message");
                int conversationId = data.getInt("conversationId");
                for (OnMessageReceivedListener l : messageListeners) l.onMessageReceived(message, conversationId);
            } catch (Exception e) {
                Log.e(TAG, "Error parsing received message: " + e.getMessage());
            }
        });

        socket.on("chat:sent", args -> {
            try {
                JSONObject data = (JSONObject) args[0];
                JSONObject message = data.getJSONObject("message");
                int conversationId = data.getInt("conversationId");
                for (OnMessageSentListener l : sentListeners) l.onMessageSent(message, conversationId);
            } catch (Exception e) {
                Log.e(TAG, "Error parsing sent confirmation: " + e.getMessage());
            }
        });

        socket.on("chat:typing", args -> {
            try {
                JSONObject data = (JSONObject) args[0];
                int convId = data.getInt("conversationId");
                int uid = data.getInt("userId");
                for (OnTypingListener l : typingListeners) l.onTyping(convId, uid);
            } catch (Exception e) {
                Log.e(TAG, "Error parsing typing: " + e.getMessage());
            }
        });

        socket.on("chat:stop_typing", args -> {
            try {
                JSONObject data = (JSONObject) args[0];
                int convId = data.getInt("conversationId");
                int uid = data.getInt("userId");
                for (OnTypingListener l : typingListeners) l.onStopTyping(convId, uid);
            } catch (Exception e) {
                Log.e(TAG, "Error parsing stop_typing: " + e.getMessage());
            }
        });

        socket.on("chat:recording", args -> {
            try {
                JSONObject data = (JSONObject) args[0];
                int convId = data.getInt("conversationId");
                int uid = data.getInt("userId");
                for (OnRecordingListener l : recordingListeners) l.onRecording(convId, uid);
            } catch (Exception e) {
                Log.e(TAG, "Error parsing recording: " + e.getMessage());
            }
        });

        socket.on("chat:stop_recording", args -> {
            try {
                JSONObject data = (JSONObject) args[0];
                int convId = data.getInt("conversationId");
                int uid = data.getInt("userId");
                for (OnRecordingListener l : recordingListeners) l.onStopRecording(convId, uid);
            } catch (Exception e) {
                Log.e(TAG, "Error parsing stop_recording: " + e.getMessage());
            }
        });

        socket.on("chat:online", args -> {
            try {
                JSONObject data = (JSONObject) args[0];
                int uid = data.getInt("userId");
                boolean online = data.getBoolean("isOnline");
                String lastSeen = data.optString("lastSeen", "");
                for (OnOnlineStatusListener l : onlineListeners) l.onOnlineStatus(uid, online, lastSeen);
            } catch (Exception e) {
                Log.e(TAG, "Error parsing online status: " + e.getMessage());
            }
        });

        socket.on("chat:read", args -> {
            try {
                JSONObject data = (JSONObject) args[0];
                int convId = data.getInt("conversationId");
                int readBy = data.getInt("readBy");
                for (OnReadReceiptListener l : readListeners) l.onReadReceipt(convId, readBy);
            } catch (Exception e) {
                Log.e(TAG, "Error parsing read receipt: " + e.getMessage());
            }
        });

        socket.on("chat:conversation_update", args -> {
            try {
                if (args == null || args.length == 0) return;
                JSONObject data = (JSONObject) args[0];
                int convId = data.optInt("conversationId", 0);
                if (convId <= 0) return;
                String lastMsg = data.optString("lastMessage", "");
                String lastTime = data.optString("lastMessageTime", "");
                int senderId = data.optInt("senderId", 0);
                for (OnConversationUpdateListener l : conversationUpdateListeners)
                    l.onConversationUpdate(convId, lastMsg, lastTime, senderId);
            } catch (Exception e) {
                Log.e(TAG, "Error parsing conversation update: " + e.getMessage());
            }
        });

        // ── Delete events ──
        socket.on("chat:deleted", args -> {
            try {
                JSONObject data = (JSONObject) args[0];
                int msgId = data.getInt("messageId");
                int convId = data.getInt("conversationId");
                boolean forAll = data.optBoolean("deleteForAll", false);
                for (OnMessageDeletedListener l : deletedListeners) l.onMessageDeleted(msgId, convId, forAll);
            } catch (Exception e) {
                Log.e(TAG, "Error parsing deleted: " + e.getMessage());
            }
        });

        // ── Call events (WebRTC signaling) ──
        socket.on("call:incoming", args -> {
            try {
                JSONObject data = (JSONObject) args[0];
                String cid = data.getString("callId");
                int callerId = data.getInt("callerId");
                String name = data.optString("callerName", "");
                String initials = data.optString("callerInitials", "");
                String color = data.optString("callerAvatarColor", "#3B82F6");
                JSONObject offer = data.has("offer") ? data.optJSONObject("offer") : null;
                for (OnCallListener l : callListeners) l.onIncomingCall(cid, callerId, name, initials, color, offer);
            } catch (Exception e) { Log.e(TAG, "call:incoming error: " + e.getMessage()); }
        });

        socket.on("call:ringing", args -> {
            try {
                JSONObject data = (JSONObject) args[0];
                for (OnCallListener l : callListeners) l.onCallRinging(data.getString("callId"), data.getInt("receiverId"));
            } catch (Exception e) { Log.e(TAG, "call:ringing error: " + e.getMessage()); }
        });

        socket.on("call:answered", args -> {
            try {
                JSONObject data = (JSONObject) args[0];
                String callId = data.getString("callId");
                JSONObject answerSdp = data.has("sdp") ? data.optJSONObject("sdp") : null;
                for (OnCallListener l : callListeners) l.onCallAnswered(callId, answerSdp);
            } catch (Exception e) { Log.e(TAG, "call:answered error: " + e.getMessage()); }
        });

        socket.on("call:ended", args -> {
            try {
                JSONObject data = (JSONObject) args[0];
                for (OnCallListener l : callListeners) l.onCallEnded(
                        data.getString("callId"), data.optString("reason", "ended"), data.optInt("duration", 0));
            } catch (Exception e) { Log.e(TAG, "call:ended error: " + e.getMessage()); }
        });

        socket.on("call:audio", args -> {
            try {
                JSONObject data = (JSONObject) args[0];
                for (OnCallListener l : callListeners) l.onCallAudio(data.getString("callId"), data.getString("audio"));
            } catch (Exception e) { /* don't log audio errors */ }
        });

        socket.on("call:mute", args -> {
            try {
                JSONObject data = (JSONObject) args[0];
                for (OnCallListener l : callListeners) l.onCallMute(
                        data.getString("callId"), data.getInt("userId"), data.getBoolean("isMuted"));
            } catch (Exception e) { Log.e(TAG, "call:mute error: " + e.getMessage()); }
        });

        socket.on("call:ice-candidate", args -> {
            try {
                JSONObject data = (JSONObject) args[0];
                String cid = data.getString("callId");
                JSONObject candidate = data.has("candidate") ? data.optJSONObject("candidate") : null;
                if (candidate != null) {
                    for (OnCallListener l : callListeners) l.onCallIceCandidate(cid, candidate);
                }
            } catch (Exception e) { Log.e(TAG, "call:ice-candidate error: " + e.getMessage()); }
        });

        // ── Device events ──
        socket.on("device:new_login", args -> {
            try {
                JSONObject data = (JSONObject) args[0];
                String devId = data.optString("device_id", "");
                String devName = data.optString("device_name", "");
                int devCount = data.optInt("device_count", 1);
                String ts = data.optString("timestamp", "");
                Log.w(TAG, "[DEVICE] New login detected: " + devName + " (" + devId + ")");
                for (OnDeviceEventListener l : deviceEventListeners) l.onNewDeviceLogin(devId, devName, devCount, ts);
            } catch (Exception e) { Log.e(TAG, "device:new_login error: " + e.getMessage()); }
        });

        socket.on("device:force_logout", args -> {
            try {
                JSONObject data = (JSONObject) args[0];
                String devId = data.optString("device_id", "");
                String reason = data.optString("reason", "");
                Log.w(TAG, "[DEVICE] Force logout for device: " + devId);
                for (OnDeviceEventListener l : deviceEventListeners) l.onForceLogout(devId, reason);
            } catch (Exception e) { Log.e(TAG, "device:force_logout error: " + e.getMessage()); }
        });
    }

    // ── Emit methods ──

    public void sendMessage(int conversationId, int senderId, String text) {
        sendMediaMessage(conversationId, senderId, text, "text", null, null, 0, 0);
    }

    public void sendMediaMessage(int conversationId, int senderId, String text,
                                  String messageType, String fileUrl, String fileName,
                                  long fileSize, int duration) {
        if (!isConnected || socket == null) {
            Log.w(TAG, "Queuing message - not connected");
            pendingMessages.add(new PendingMessage(conversationId, senderId,
                    text != null ? text : "", messageType != null ? messageType : "text",
                    fileUrl, fileName, fileSize, duration));
            return;
        }
        try {
            JSONObject data = new JSONObject();
            data.put("conversationId", conversationId);
            data.put("senderId", senderId);
            data.put("text", text != null ? text : "");
            data.put("messageType", messageType != null ? messageType : "text");
            if (fileUrl != null) data.put("fileUrl", fileUrl);
            if (fileName != null) data.put("fileName", fileName);
            if (fileSize > 0) data.put("fileSize", fileSize);
            if (duration > 0) data.put("duration", duration);
            socket.emit("chat:send", data);
        } catch (Exception e) {
            Log.e(TAG, "Error sending message: " + e.getMessage());
        }
    }

    private void flushPendingMessages() {
        PendingMessage msg;
        while ((msg = pendingMessages.poll()) != null) {
            Log.d(TAG, "Flushing pending message to conv=" + msg.conversationId);
            sendMediaMessage(msg.conversationId, msg.senderId, msg.text,
                    msg.messageType, msg.fileUrl, msg.fileName, msg.fileSize, msg.duration);
        }
    }

    public void sendTyping(int conversationId) {
        if (!isConnected || socket == null) return;
        try {
            JSONObject data = new JSONObject();
            data.put("conversationId", conversationId);
            data.put("userId", userId);
            socket.emit("chat:typing", data);
        } catch (Exception e) {
            Log.e(TAG, "Error sending typing: " + e.getMessage());
        }
    }

    public void sendStopTyping(int conversationId) {
        if (!isConnected || socket == null) return;
        try {
            JSONObject data = new JSONObject();
            data.put("conversationId", conversationId);
            data.put("userId", userId);
            socket.emit("chat:stop_typing", data);
        } catch (Exception e) {
            Log.e(TAG, "Error sending stop_typing: " + e.getMessage());
        }
    }

    public void sendRecording(int conversationId) {
        if (!isConnected || socket == null) return;
        try {
            JSONObject data = new JSONObject();
            data.put("conversationId", conversationId);
            data.put("userId", userId);
            socket.emit("chat:recording", data);
        } catch (Exception e) {
            Log.e(TAG, "Error sending recording: " + e.getMessage());
        }
    }

    public void sendStopRecording(int conversationId) {
        if (!isConnected || socket == null) return;
        try {
            JSONObject data = new JSONObject();
            data.put("conversationId", conversationId);
            data.put("userId", userId);
            socket.emit("chat:stop_recording", data);
        } catch (Exception e) {
            Log.e(TAG, "Error sending stop_recording: " + e.getMessage());
        }
    }

    public void markAsRead(int conversationId) {
        if (!isConnected || socket == null) return;
        try {
            JSONObject data = new JSONObject();
            data.put("conversationId", conversationId);
            data.put("userId", userId);
            socket.emit("chat:read", data);
        } catch (Exception e) {
            Log.e(TAG, "Error marking as read: " + e.getMessage());
        }
    }

    // ── Request online status for a specific user ──
    public void requestStatus(int targetUserId) {
        if (!isConnected || socket == null) return;
        try {
            JSONObject data = new JSONObject();
            data.put("userId", targetUserId);
            socket.emit("chat:request_status", data);
        } catch (Exception e) {
            Log.e(TAG, "Error requesting status: " + e.getMessage());
        }
    }

    public void setAppForeground(boolean foreground) {
        if (!isConnected || socket == null) return;
        try {
            JSONObject data = new JSONObject();
            data.put("isOnline", foreground);
            socket.emit("chat:set_status", data);
            Log.d(TAG, "Set app foreground: " + foreground);
        } catch (Exception e) {
            Log.e(TAG, "Error setting status: " + e.getMessage());
        }
    }

    /**
     * Start heartbeat: sends chat:heartbeat every 30 seconds to keep user online.
     * Only runs when connected and app is in foreground.
     * Heartbeat ensures user stays online as long as they are inside the app.
     */
    public void startHeartbeat() {
        stopHeartbeat(); // Stop any existing heartbeat
        if (!isConnected || socket == null || userId <= 0) {
            Log.d(TAG, "Cannot start heartbeat: not connected or no userId");
            return;
        }

        if (heartbeatHandler == null) {
            heartbeatHandler = new Handler(Looper.getMainLooper());
        }

        heartbeatRunnable = new Runnable() {
            @Override
            public void run() {
                if (isConnected && socket != null && socket.connected()) {
                    try {
                        socket.emit("chat:heartbeat", new JSONObject());
                        Log.d(TAG, "Heartbeat sent (keeping user online)");
                    } catch (Exception e) {
                        Log.e(TAG, "Error sending heartbeat: " + e.getMessage());
                    }
                    // Schedule next heartbeat (keeps user online while app is active)
                    if (heartbeatHandler != null && heartbeatRunnable != null) {
                        heartbeatHandler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS);
                    }
                } else {
                    Log.d(TAG, "Heartbeat stopped: socket disconnected");
                }
            }
        };

        heartbeatHandler.post(heartbeatRunnable);
        Log.d(TAG, "Heartbeat started (user will stay online while app is active)");
    }

    /**
     * Stop heartbeat when app goes to background or disconnects.
     */
    public void stopHeartbeat() {
        if (heartbeatHandler != null && heartbeatRunnable != null) {
            heartbeatHandler.removeCallbacks(heartbeatRunnable);
            heartbeatRunnable = null;
            Log.d(TAG, "Heartbeat stopped");
        }
    }

    public void disconnect() {
        stopHeartbeat();
        if (socket != null) {
            socket.disconnect();
            socket.off();
            socket = null;
        }
        isConnected = false;
    }

    public boolean isConnected() { return isConnected; }
    public int getUserId() { return userId; }

    // ── Add / Remove listeners (thread-safe) ──

    public void addMessageReceivedListener(OnMessageReceivedListener l) { if (l != null) messageListeners.add(l); }
    public void removeMessageReceivedListener(OnMessageReceivedListener l) { messageListeners.remove(l); }

    public void addMessageSentListener(OnMessageSentListener l) { if (l != null) sentListeners.add(l); }
    public void removeMessageSentListener(OnMessageSentListener l) { sentListeners.remove(l); }

    public void addTypingListener(OnTypingListener l) { if (l != null) typingListeners.add(l); }
    public void removeTypingListener(OnTypingListener l) { typingListeners.remove(l); }

    public void addRecordingListener(OnRecordingListener l) { if (l != null) recordingListeners.add(l); }
    public void removeRecordingListener(OnRecordingListener l) { recordingListeners.remove(l); }

    public void addOnlineStatusListener(OnOnlineStatusListener l) { if (l != null) onlineListeners.add(l); }
    public void removeOnlineStatusListener(OnOnlineStatusListener l) { onlineListeners.remove(l); }

    public void addReadReceiptListener(OnReadReceiptListener l) { if (l != null) readListeners.add(l); }
    public void removeReadReceiptListener(OnReadReceiptListener l) { readListeners.remove(l); }

    public void addConversationUpdateListener(OnConversationUpdateListener l) { if (l != null) conversationUpdateListeners.add(l); }
    public void removeConversationUpdateListener(OnConversationUpdateListener l) { conversationUpdateListeners.remove(l); }

    public void addConnectionListener(OnConnectionListener l) { if (l != null) connectionListeners.add(l); }
    public void removeConnectionListener(OnConnectionListener l) { connectionListeners.remove(l); }

    public void addMessageDeletedListener(OnMessageDeletedListener l) { if (l != null) deletedListeners.add(l); }
    public void removeMessageDeletedListener(OnMessageDeletedListener l) { deletedListeners.remove(l); }

    public void addCallListener(OnCallListener l) { if (l != null) callListeners.add(l); }
    public void removeCallListener(OnCallListener l) { callListeners.remove(l); }

    public void addDeviceEventListener(OnDeviceEventListener l) { if (l != null) deviceEventListeners.add(l); }
    public void removeDeviceEventListener(OnDeviceEventListener l) { deviceEventListeners.remove(l); }

    // ── Delete emit ──

    public void deleteMessage(int messageId, int userId, boolean deleteForAll) {
        if (!isConnected || socket == null) return;
        try {
            JSONObject data = new JSONObject();
            data.put("messageId", messageId);
            data.put("userId", userId);
            data.put("deleteForAll", deleteForAll);
            socket.emit("chat:delete", data);
        } catch (Exception e) {
            Log.e(TAG, "Error deleting message: " + e.getMessage());
        }
    }

    // ── Call emit helpers ──

    public void emitRaw(String event, JSONObject data) {
        if (!isConnected || socket == null) return;
        socket.emit(event, data);
    }

    public void emitCallAudio(String callId, int senderId, String base64Audio) {
        if (!isConnected || socket == null) return;
        try {
            JSONObject data = new JSONObject();
            data.put("callId", callId);
            data.put("senderId", senderId);
            data.put("audio", base64Audio);
            socket.emit("call:audio", data);
        } catch (Exception e) { /* ignore */ }
    }

    public void emitCallMute(String callId, int userId, boolean isMuted) {
        if (!isConnected || socket == null) return;
        try {
            JSONObject data = new JSONObject();
            data.put("callId", callId);
            data.put("userId", userId);
            data.put("isMuted", isMuted);
            socket.emit("call:mute", data);
        } catch (Exception e) { /* ignore */ }
    }

    /** WebRTC: send offer (caller). Payload: callerId, receiverId, offer: { type, sdp }. */
    public void emitCallOffer(int callerId, int receiverId, JSONObject offer) {
        if (!isConnected || socket == null) return;
        try {
            JSONObject data = new JSONObject();
            data.put("callerId", callerId);
            data.put("receiverId", receiverId);
            if (offer != null) data.put("offer", offer);
            socket.emit("call:offer", data);
        } catch (Exception e) { Log.e(TAG, "emitCallOffer: " + e.getMessage()); }
    }

    /** WebRTC: send answer (receiver). Payload: callId, sdp: { type, sdp }. */
    public void emitCallAnswer(String callId, JSONObject answerSdp) {
        if (!isConnected || socket == null) return;
        try {
            JSONObject data = new JSONObject();
            data.put("callId", callId);
            if (answerSdp != null) data.put("sdp", answerSdp);
            socket.emit("call:answer", data);
        } catch (Exception e) { Log.e(TAG, "emitCallAnswer: " + e.getMessage()); }
    }

    /** WebRTC: send ICE candidate. Payload: callId, senderId, candidate: { ... }. */
    public void emitCallIceCandidate(String callId, int senderId, JSONObject candidate) {
        if (!isConnected || socket == null) return;
        try {
            JSONObject data = new JSONObject();
            data.put("callId", callId);
            data.put("senderId", senderId);
            if (candidate != null) data.put("candidate", candidate);
            socket.emit("call:ice-candidate", data);
        } catch (Exception e) { Log.e(TAG, "emitCallIceCandidate: " + e.getMessage()); }
    }

    // ── Legacy single-setter methods (remove the old one, add the new one) ──
    // These exist for backward compat -- they replace the single listener each call

    private OnMessageReceivedListener _singleMsgListener;
    public void setOnMessageReceivedListener(OnMessageReceivedListener l) {
        if (_singleMsgListener != null) messageListeners.remove(_singleMsgListener);
        _singleMsgListener = l;
        if (l != null) messageListeners.add(l);
    }

    private OnMessageSentListener _singleSentListener;
    public void setOnMessageSentListener(OnMessageSentListener l) {
        if (_singleSentListener != null) sentListeners.remove(_singleSentListener);
        _singleSentListener = l;
        if (l != null) sentListeners.add(l);
    }

    private OnTypingListener _singleTypingListener;
    public void setOnTypingListener(OnTypingListener l) {
        if (_singleTypingListener != null) typingListeners.remove(_singleTypingListener);
        _singleTypingListener = l;
        if (l != null) typingListeners.add(l);
    }

    private OnOnlineStatusListener _singleOnlineListener;
    public void setOnOnlineStatusListener(OnOnlineStatusListener l) {
        if (_singleOnlineListener != null) onlineListeners.remove(_singleOnlineListener);
        _singleOnlineListener = l;
        if (l != null) onlineListeners.add(l);
    }

    private OnReadReceiptListener _singleReadListener;
    public void setOnReadReceiptListener(OnReadReceiptListener l) {
        if (_singleReadListener != null) readListeners.remove(_singleReadListener);
        _singleReadListener = l;
        if (l != null) readListeners.add(l);
    }

    private OnConversationUpdateListener _singleConvListener;
    public void setOnConversationUpdateListener(OnConversationUpdateListener l) {
        if (_singleConvListener != null) conversationUpdateListeners.remove(_singleConvListener);
        _singleConvListener = l;
        if (l != null) conversationUpdateListeners.add(l);
    }

    private OnConnectionListener _singleConnListener;
    public void setOnConnectionListener(OnConnectionListener l) {
        if (_singleConnListener != null) connectionListeners.remove(_singleConnListener);
        _singleConnListener = l;
        if (l != null) connectionListeners.add(l);
    }
}
