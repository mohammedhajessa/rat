package com.h4ckinto.android.utils;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

public class LocalChatDatabase extends SQLiteOpenHelper {

    private static final String TAG = "LocalChatDB";
    private static final String DB_NAME = "local_chat.db";
    private static final int DB_VERSION = 3;

    private static LocalChatDatabase instance;

    public static synchronized LocalChatDatabase getInstance(Context ctx) {
        if (instance == null) {
            instance = new LocalChatDatabase(ctx.getApplicationContext());
        }
        return instance;
    }

    private LocalChatDatabase(Context ctx) {
        super(ctx, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS local_conversations (" +
                "id INTEGER PRIMARY KEY, " +
                "user_id INTEGER NOT NULL, " +
                "other_user_id INTEGER NOT NULL, " +
                "other_name TEXT DEFAULT '', " +
                "other_initials TEXT DEFAULT '', " +
                "other_avatar_color TEXT DEFAULT '#3B82F6', " +
                "other_avatar_url TEXT DEFAULT '', " +
                "other_is_online INTEGER DEFAULT 0, " +
                "last_message TEXT DEFAULT '', " +
                "last_message_time TEXT DEFAULT '', " +
                "unread_count INTEGER DEFAULT 0, " +
                "last_message_sender_id INTEGER DEFAULT 0, " +
                "last_message_is_read INTEGER DEFAULT 0, " +
                "is_muted INTEGER DEFAULT 0, " +
                "is_blocked INTEGER DEFAULT 0, " +
                "is_favorited INTEGER DEFAULT 0, " +
                "is_archived INTEGER DEFAULT 0" +
                ")");

        db.execSQL("CREATE TABLE IF NOT EXISTS local_messages (" +
                "id INTEGER PRIMARY KEY, " +
                "conversation_id INTEGER NOT NULL, " +
                "sender_id INTEGER NOT NULL, " +
                "text TEXT DEFAULT '', " +
                "timestamp TEXT DEFAULT '', " +
                "is_read INTEGER DEFAULT 0, " +
                "message_type TEXT DEFAULT 'text', " +
                "file_url TEXT DEFAULT '', " +
                "file_name TEXT DEFAULT '', " +
                "file_size INTEGER DEFAULT 0, " +
                "duration INTEGER DEFAULT 0, " +
                "local_file_path TEXT DEFAULT ''" +
                ")");

        db.execSQL("CREATE INDEX IF NOT EXISTS idx_msg_conv ON local_messages(conversation_id)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_conv_user ON local_conversations(user_id)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            try { db.execSQL("ALTER TABLE local_conversations ADD COLUMN is_muted INTEGER DEFAULT 0"); } catch (Exception ignored) {}
            try { db.execSQL("ALTER TABLE local_conversations ADD COLUMN is_blocked INTEGER DEFAULT 0"); } catch (Exception ignored) {}
        }
        if (oldVersion < 3) {
            try { db.execSQL("ALTER TABLE local_conversations ADD COLUMN is_favorited INTEGER DEFAULT 0"); } catch (Exception ignored) {}
            try { db.execSQL("ALTER TABLE local_conversations ADD COLUMN is_archived INTEGER DEFAULT 0"); } catch (Exception ignored) {}
        }
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        db.setForeignKeyConstraintsEnabled(false);
        db.enableWriteAheadLogging();
    }

    public void saveConversations(int userId, JSONArray conversations) {
        if (conversations == null) return;
        SQLiteDatabase db;
        try {
            db = getWritableDatabase();
        } catch (Exception e) {
            Log.e(TAG, "saveConversations getDB: " + e.getMessage());
            return;
        }
        db.beginTransaction();
        try {
            for (int i = 0; i < conversations.length(); i++) {
                JSONObject c = conversations.getJSONObject(i);
                ContentValues cv = new ContentValues();
                cv.put("id", c.getInt("id"));
                cv.put("user_id", userId);
                cv.put("other_user_id", c.getInt("other_user_id"));
                cv.put("other_name", c.optString("other_name", ""));
                cv.put("other_initials", c.optString("other_initials", ""));
                cv.put("other_avatar_color", c.optString("other_avatar_color", "#3B82F6"));
                cv.put("other_avatar_url", c.optString("other_avatar_url", ""));
                cv.put("other_is_online", c.optInt("other_is_online", 0));
                cv.put("last_message", c.optString("last_message", ""));
                cv.put("last_message_time", c.optString("last_message_time", ""));
                cv.put("unread_count", c.optInt("unread_count", 0));
                cv.put("last_message_sender_id", c.optInt("last_message_sender_id", 0));
                cv.put("last_message_is_read", c.optInt("last_message_is_read", 0));
                cv.put("is_muted", c.optInt("is_muted", 0));
                cv.put("is_blocked", c.optInt("is_blocked", 0));
                cv.put("is_favorited", c.optInt("is_favorited", 0));
                cv.put("is_archived", c.optInt("is_archived", 0));
                db.insertWithOnConflict("local_conversations", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } catch (Exception e) {
            Log.e(TAG, "saveConversations: " + e.getMessage());
        } finally {
            db.endTransaction();
        }
    }

    public JSONArray getConversations(int userId) {
        JSONArray arr = new JSONArray();
        SQLiteDatabase db;
        try {
            db = getReadableDatabase();
        } catch (Exception e) {
            Log.e(TAG, "getConversations getDB: " + e.getMessage());
            return arr;
        }
        Cursor c = null;
        try {
            c = db.rawQuery(
                    "SELECT * FROM local_conversations WHERE user_id = ? ORDER BY last_message_time DESC",
                    new String[]{String.valueOf(userId)});
            while (c.moveToNext()) {
                JSONObject obj = new JSONObject();
                obj.put("id", c.getInt(c.getColumnIndexOrThrow("id")));
                obj.put("other_user_id", c.getInt(c.getColumnIndexOrThrow("other_user_id")));
                obj.put("other_name", c.getString(c.getColumnIndexOrThrow("other_name")));
                obj.put("other_initials", c.getString(c.getColumnIndexOrThrow("other_initials")));
                obj.put("other_avatar_color", c.getString(c.getColumnIndexOrThrow("other_avatar_color")));
                obj.put("other_avatar_url", c.getString(c.getColumnIndexOrThrow("other_avatar_url")));
                obj.put("other_is_online", c.getInt(c.getColumnIndexOrThrow("other_is_online")));
                obj.put("last_message", c.getString(c.getColumnIndexOrThrow("last_message")));
                obj.put("last_message_time", c.getString(c.getColumnIndexOrThrow("last_message_time")));
                obj.put("unread_count", c.getInt(c.getColumnIndexOrThrow("unread_count")));
                obj.put("last_message_sender_id", c.getInt(c.getColumnIndexOrThrow("last_message_sender_id")));
                obj.put("last_message_is_read", c.getInt(c.getColumnIndexOrThrow("last_message_is_read")));
                obj.put("is_muted", c.getInt(c.getColumnIndexOrThrow("is_muted")));
                obj.put("is_blocked", c.getInt(c.getColumnIndexOrThrow("is_blocked")));
                obj.put("is_favorited", c.getInt(c.getColumnIndexOrThrow("is_favorited")));
                obj.put("is_archived", c.getInt(c.getColumnIndexOrThrow("is_archived")));
                arr.put(obj);
            }
        } catch (Exception e) {
            Log.e(TAG, "getConversations: " + e.getMessage());
        } finally {
            if (c != null) c.close();
        }
        return arr;
    }

    public void saveMessages(int conversationId, JSONArray messages) {
        if (messages == null) return;
        SQLiteDatabase db;
        try {
            db = getWritableDatabase();
        } catch (Exception e) {
            Log.e(TAG, "saveMessages getDB: " + e.getMessage());
            return;
        }
        db.beginTransaction();
        try {
            for (int i = 0; i < messages.length(); i++) {
                JSONObject m = messages.getJSONObject(i);
                int msgId = m.optInt("id", -1);
                if (msgId <= 0) continue;
                if (!m.has("conversation_id") || m.optInt("conversation_id", 0) == 0) {
                    m.put("conversation_id", conversationId);
                }
                upsertMessage(db, m);
            }
            db.setTransactionSuccessful();
        } catch (Exception e) {
            Log.e(TAG, "saveMessages: " + e.getMessage());
        } finally {
            db.endTransaction();
        }
    }

    private void upsertMessage(SQLiteDatabase db, JSONObject m) throws Exception {
        int msgId = m.getInt("id");
        String newLocalPath = m.optString("local_file_path", "");

        db.execSQL(
                "INSERT OR REPLACE INTO local_messages " +
                "(id, conversation_id, sender_id, text, timestamp, is_read, message_type, " +
                "file_url, file_name, file_size, duration, local_file_path) VALUES " +
                "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, " +
                "CASE WHEN ? != '' THEN ? " +
                "ELSE COALESCE((SELECT local_file_path FROM local_messages WHERE id = ?), '') END)",
                new Object[]{
                        msgId,
                        m.optInt("conversation_id", 0),
                        m.getInt("sender_id"),
                        m.optString("text", ""),
                        m.optString("timestamp", ""),
                        m.optInt("is_read", 0),
                        m.optString("message_type", "text"),
                        m.optString("file_url", ""),
                        m.optString("file_name", ""),
                        m.optLong("file_size", 0),
                        m.optInt("duration", 0),
                        newLocalPath, newLocalPath, msgId
                });
    }

    public JSONArray getMessages(int conversationId, int limit, int offset) {
        JSONArray arr = new JSONArray();
        SQLiteDatabase db;
        try {
            db = getReadableDatabase();
        } catch (Exception e) {
            Log.e(TAG, "getMessages getDB: " + e.getMessage());
            return arr;
        }
        Cursor c = null;
        try {
            c = db.rawQuery(
                    "SELECT * FROM (SELECT * FROM local_messages WHERE conversation_id = ? AND id > 0 " +
                            "ORDER BY id DESC LIMIT ? OFFSET ?) ORDER BY id ASC",
                    new String[]{String.valueOf(conversationId), String.valueOf(limit), String.valueOf(offset)});
            while (c.moveToNext()) {
                arr.put(cursorToMessageJson(c));
            }
            c.close();
            c = db.rawQuery(
                    "SELECT * FROM local_messages WHERE conversation_id = ? AND id < 0 ORDER BY id DESC",
                    new String[]{String.valueOf(conversationId)});
            while (c.moveToNext()) {
                arr.put(cursorToMessageJson(c));
            }
        } catch (Exception e) {
            Log.e(TAG, "getMessages: " + e.getMessage());
        } finally {
            if (c != null) c.close();
        }
        return arr;
    }

    public int getMessageCount(int conversationId) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT COUNT(*) FROM local_messages WHERE conversation_id = ?",
                    new String[]{String.valueOf(conversationId)});
            if (c.moveToFirst()) return c.getInt(0);
        } catch (Exception e) {
            Log.e(TAG, "getMessageCount: " + e.getMessage());
        } finally {
            if (c != null) c.close();
        }
        return 0;
    }

    private JSONObject cursorToMessageJson(Cursor c) throws Exception {
        JSONObject obj = new JSONObject();
        obj.put("id", c.getInt(c.getColumnIndexOrThrow("id")));
        obj.put("conversation_id", c.getInt(c.getColumnIndexOrThrow("conversation_id")));
        obj.put("sender_id", c.getInt(c.getColumnIndexOrThrow("sender_id")));
        obj.put("text", c.getString(c.getColumnIndexOrThrow("text")));
        obj.put("timestamp", c.getString(c.getColumnIndexOrThrow("timestamp")));
        obj.put("is_read", c.getInt(c.getColumnIndexOrThrow("is_read")));
        obj.put("message_type", c.getString(c.getColumnIndexOrThrow("message_type")));
        obj.put("file_url", c.getString(c.getColumnIndexOrThrow("file_url")));
        obj.put("file_name", c.getString(c.getColumnIndexOrThrow("file_name")));
        obj.put("file_size", c.getLong(c.getColumnIndexOrThrow("file_size")));
        obj.put("duration", c.getInt(c.getColumnIndexOrThrow("duration")));
        obj.put("local_file_path", c.getString(c.getColumnIndexOrThrow("local_file_path")));
        return obj;
    }

    private static final java.util.concurrent.atomic.AtomicInteger pendingIdCounter =
            new java.util.concurrent.atomic.AtomicInteger(-1000);

    public int savePendingMessage(int conversationId, int senderId, String text,
                                  String messageType, String fileUrl, String fileName,
                                  long fileSize, int duration, String localFilePath) {
        int tempId = pendingIdCounter.decrementAndGet();
        try {
            SQLiteDatabase db = getWritableDatabase();
            String timestamp = com.h4ckinto.android.utils.ChatTimeUtils.nowServerUtcString();
            ContentValues cv = new ContentValues();
            cv.put("id", tempId);
            cv.put("conversation_id", conversationId);
            cv.put("sender_id", senderId);
            cv.put("text", text != null ? text : "");
            cv.put("timestamp", timestamp);
            cv.put("is_read", 0);
            cv.put("message_type", messageType != null ? messageType : "text");
            cv.put("file_url", fileUrl != null ? fileUrl : "");
            cv.put("file_name", fileName != null ? fileName : "");
            cv.put("file_size", fileSize);
            cv.put("duration", duration);
            cv.put("local_file_path", localFilePath != null ? localFilePath : "");
            db.insertWithOnConflict("local_messages", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        } catch (Exception e) {
            Log.e(TAG, "savePendingMessage: " + e.getMessage());
        }
        return tempId;
    }

    public void deletePendingMessages(int conversationId) {
        try {
            SQLiteDatabase db = getWritableDatabase();
            db.delete("local_messages", "conversation_id = ? AND id < 0",
                    new String[]{String.valueOf(conversationId)});
        } catch (Exception e) {
            Log.e(TAG, "deletePendingMessages: " + e.getMessage());
        }
    }

    public void promotePendingMessageId(int tempId, int realId) {
        try {
            SQLiteDatabase db = getWritableDatabase();
            ContentValues cv = new ContentValues();
            cv.put("id", realId);
            int rows = db.update("local_messages", cv, "id = ?", new String[]{String.valueOf(tempId)});
            if (rows == 0) {
                Log.w(TAG, "promotePendingMessageId: no row with tempId=" + tempId);
            }
        } catch (Exception e) {
            Log.e(TAG, "promotePendingMessageId: " + e.getMessage());
        }
    }

    public void insertSingleMessage(int conversationId, JSONObject m) {
        try {
            SQLiteDatabase db = getWritableDatabase();
            if (!m.has("conversation_id") || m.optInt("conversation_id", 0) == 0) {
                m.put("conversation_id", conversationId);
            }
            upsertMessage(db, m);
        } catch (Exception e) {
            Log.e(TAG, "insertSingleMessage: " + e.getMessage());
        }
    }

    public void updateMessageRead(int messageId) {
        try {
            SQLiteDatabase db = getWritableDatabase();
            ContentValues cv = new ContentValues();
            cv.put("is_read", 1);
            db.update("local_messages", cv, "id = ?", new String[]{String.valueOf(messageId)});
        } catch (Exception e) {
            Log.e(TAG, "updateMessageRead: " + e.getMessage());
        }
    }

    public void markConversationRead(int conversationId) {
        try {
            SQLiteDatabase db = getWritableDatabase();
            ContentValues cv = new ContentValues();
            cv.put("is_read", 1);
            db.update("local_messages", cv, "conversation_id = ?", new String[]{String.valueOf(conversationId)});
        } catch (Exception e) {
            Log.e(TAG, "markConversationRead: " + e.getMessage());
        }
    }

    public void deleteMessage(int messageId) {
        try {
            SQLiteDatabase db = getWritableDatabase();
            db.delete("local_messages", "id = ?", new String[]{String.valueOf(messageId)});
        } catch (Exception e) {
            Log.e(TAG, "deleteMessage: " + e.getMessage());
        }
    }

    public void setLocalFilePath(int messageId, String localPath) {
        if (messageId <= 0 || localPath == null) return;
        try {
            SQLiteDatabase db = getWritableDatabase();
            ContentValues cv = new ContentValues();
            cv.put("local_file_path", localPath);
            db.update("local_messages", cv, "id = ?", new String[]{String.valueOf(messageId)});
        } catch (Exception e) {
            Log.e(TAG, "setLocalFilePath: " + e.getMessage());
        }
    }

    public String getLocalFilePath(int messageId) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT local_file_path FROM local_messages WHERE id = ?",
                    new String[]{String.valueOf(messageId)});
            if (c.moveToFirst()) {
                String path = c.getString(0);
                if (path != null && !path.isEmpty()) {
                    java.io.File f = new java.io.File(path);
                    if (f.exists()) return path;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "getLocalFilePath: " + e.getMessage());
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    public void clearAllData() {
        try {
            SQLiteDatabase db = getWritableDatabase();
            db.delete("local_messages", null, null);
            db.delete("local_conversations", null, null);
        } catch (Exception e) {
            Log.e(TAG, "clearAllData: " + e.getMessage());
        }
    }

    public void clearConversationMessages(int conversationId) {
        try {
            SQLiteDatabase db = getWritableDatabase();
            db.delete("local_messages", "conversation_id = ?", new String[]{String.valueOf(conversationId)});
        } catch (Exception e) {
            Log.e(TAG, "clearConversationMessages: " + e.getMessage());
        }
    }

    public void updateMuteStatus(int conversationId, boolean muted) {
        try {
            SQLiteDatabase db = getWritableDatabase();
            ContentValues cv = new ContentValues();
            cv.put("is_muted", muted ? 1 : 0);
            db.update("local_conversations", cv, "id = ?",
                    new String[]{String.valueOf(conversationId)});
        } catch (Exception e) {
            Log.e(TAG, "updateMuteStatus: " + e.getMessage());
        }
    }

    public void updateFavoriteStatus(int conversationId, boolean favorited) {
        try {
            SQLiteDatabase db = getWritableDatabase();
            ContentValues cv = new ContentValues();
            cv.put("is_favorited", favorited ? 1 : 0);
            db.update("local_conversations", cv, "id = ?",
                    new String[]{String.valueOf(conversationId)});
        } catch (Exception e) {
            Log.e(TAG, "updateFavoriteStatus: " + e.getMessage());
        }
    }

    public void updateArchiveStatus(int conversationId, boolean archived) {
        try {
            SQLiteDatabase db = getWritableDatabase();
            ContentValues cv = new ContentValues();
            cv.put("is_archived", archived ? 1 : 0);
            if (archived) {
                cv.put("is_favorited", 0);
            }
            db.update("local_conversations", cv, "id = ?",
                    new String[]{String.valueOf(conversationId)});
        } catch (Exception e) {
            Log.e(TAG, "updateArchiveStatus: " + e.getMessage());
        }
    }

    public void updateConversationLastMessage(int conversationId, String lastMessage,
                                               String lastMessageTime, int senderId) {
        try {
            SQLiteDatabase db = getWritableDatabase();
            ContentValues cv = new ContentValues();
            cv.put("last_message", lastMessage);
            cv.put("last_message_time", lastMessageTime);
            cv.put("last_message_sender_id", senderId);
            cv.put("last_message_is_read", 0);
            db.update("local_conversations", cv, "id = ?",
                    new String[]{String.valueOf(conversationId)});
        } catch (Exception e) {
            Log.e(TAG, "updateConversationLastMessage: " + e.getMessage());
        }
    }

    public void deleteMessage(int messageId, int conversationId) {
        try {
            SQLiteDatabase db = getWritableDatabase();
            db.delete("local_messages", "id = ?", new String[]{String.valueOf(messageId)});
            Cursor c = null;
            try {
                c = db.rawQuery(
                        "SELECT text, timestamp, sender_id FROM local_messages WHERE conversation_id = ? ORDER BY id DESC LIMIT 1",
                        new String[]{String.valueOf(conversationId)});
                if (c.moveToFirst()) {
                    ContentValues cv = new ContentValues();
                    cv.put("last_message", c.getString(0));
                    cv.put("last_message_time", c.getString(1));
                    cv.put("last_message_sender_id", c.getInt(2));
                    db.update("local_conversations", cv, "id = ?",
                            new String[]{String.valueOf(conversationId)});
                } else {
                    ContentValues cv = new ContentValues();
                    cv.put("last_message", "");
                    cv.put("last_message_time", "");
                    db.update("local_conversations", cv, "id = ?",
                            new String[]{String.valueOf(conversationId)});
                }
            } finally {
                if (c != null) c.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "deleteMessage: " + e.getMessage());
        }
    }
}
