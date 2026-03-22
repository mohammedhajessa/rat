package com.h4ckinto.android.utils;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ConversationCache {

    private static final String TAG = "ConversationCache";

    private static ConversationCache instance;
    private int currentUserId = -1;
    private final LocalChatDatabase db;
    private final ExecutorService writeExecutor = Executors.newSingleThreadExecutor();

    private ConversationCache(Context ctx) {
        db = LocalChatDatabase.getInstance(ctx);
    }

    public static synchronized ConversationCache getInstance(Context ctx) {
        if (instance == null) {
            instance = new ConversationCache(ctx);
        }
        return instance;
    }

    public void setUserId(int userId) {
        if (userId > 0) {
            currentUserId = userId;
        }
    }

    public void saveConversations(JSONArray conversations) {
        if (conversations == null || currentUserId <= 0) return;
        final int uid = currentUserId;
        final String json = conversations.toString();
        writeExecutor.execute(() -> {
            try {
                db.saveConversations(uid, new JSONArray(json));
            } catch (Exception e) {
                Log.e(TAG, "saveConversations bg: " + e.getMessage());
            }
        });
    }

    public JSONArray getConversations() {
        if (currentUserId <= 0) return null;
        try {
            JSONArray arr = db.getConversations(currentUserId);
            return (arr != null && arr.length() > 0) ? arr : null;
        } catch (Exception e) {
            Log.e(TAG, "getConversations: " + e.getMessage());
            return null;
        }
    }

    public void saveMessages(int conversationId, JSONArray messages) {
        if (messages == null || conversationId <= 0) return;
        final String json = messages.toString();
        writeExecutor.execute(() -> {
            try {
                db.saveMessages(conversationId, new JSONArray(json));
            } catch (Exception e) {
                Log.e(TAG, "saveMessages bg: " + e.getMessage());
            }
        });
    }

    public JSONArray getMessages(int conversationId) {
        return getMessages(conversationId, 200, 0);
    }

    public JSONArray getMessages(int conversationId, int limit, int offset) {
        if (conversationId <= 0) return null;
        try {
            JSONArray arr = db.getMessages(conversationId, limit, offset);
            return (arr != null && arr.length() > 0) ? arr : null;
        } catch (Exception e) {
            Log.e(TAG, "getMessages: " + e.getMessage());
            return null;
        }
    }

    public int getMessageCount(int conversationId) {
        try {
            return db.getMessageCount(conversationId);
        } catch (Exception e) {
            return 0;
        }
    }

    public int savePendingMessage(int conversationId, int senderId, String text,
                                   String messageType, String fileUrl, String fileName,
                                   long fileSize, int duration, String localFilePath) {
        return db.savePendingMessage(conversationId, senderId, text,
                messageType, fileUrl, fileName, fileSize, duration, localFilePath);
    }

    public void deletePendingMessages(int conversationId) {
        writeExecutor.execute(() -> {
            try {
                db.deletePendingMessages(conversationId);
            } catch (Exception e) {
                Log.e(TAG, "deletePendingMessages bg: " + e.getMessage());
            }
        });
    }

    public void promotePendingMessageId(int tempId, int realId) {
        writeExecutor.execute(() -> {
            try {
                db.promotePendingMessageId(tempId, realId);
            } catch (Exception e) {
                Log.e(TAG, "promotePendingMessageId bg: " + e.getMessage());
            }
        });
    }

    public void insertSingleMessage(int conversationId, JSONObject msg) {
        final String json = msg.toString();
        writeExecutor.execute(() -> {
            try {
                db.insertSingleMessage(conversationId, new JSONObject(json));
            } catch (Exception e) {
                Log.e(TAG, "insertSingleMessage bg: " + e.getMessage());
            }
        });
    }

    public void setLocalFilePath(int messageId, String path) {
        writeExecutor.execute(() -> {
            try {
                db.setLocalFilePath(messageId, path);
            } catch (Exception e) {
                Log.e(TAG, "setLocalFilePath bg: " + e.getMessage());
            }
        });
    }

    public String getLocalFilePath(int messageId) {
        try {
            return db.getLocalFilePath(messageId);
        } catch (Exception e) {
            return null;
        }
    }

    public void clearConversationMessages(int conversationId) {
        writeExecutor.execute(() -> {
            try {
                db.clearConversationMessages(conversationId);
            } catch (Exception e) {
                Log.e(TAG, "clearConversationMessages bg: " + e.getMessage());
            }
        });
    }

    public void updateMuteStatus(int conversationId, boolean muted) {
        writeExecutor.execute(() -> {
            try {
                db.updateMuteStatus(conversationId, muted);
            } catch (Exception e) {
                Log.e(TAG, "updateMuteStatus bg: " + e.getMessage());
            }
        });
    }

    public void updateFavoriteStatus(int conversationId, boolean favorited) {
        writeExecutor.execute(() -> {
            try {
                db.updateFavoriteStatus(conversationId, favorited);
            } catch (Exception e) {
                Log.e(TAG, "updateFavoriteStatus bg: " + e.getMessage());
            }
        });
    }

    public void updateArchiveStatus(int conversationId, boolean archived) {
        writeExecutor.execute(() -> {
            try {
                db.updateArchiveStatus(conversationId, archived);
            } catch (Exception e) {
                Log.e(TAG, "updateArchiveStatus bg: " + e.getMessage());
            }
        });
    }

    public void updateConversationLastMessage(int conversationId, String lastMessage,
                                               String lastMessageTime, int senderId) {
        writeExecutor.execute(() -> {
            try {
                db.updateConversationLastMessage(conversationId, lastMessage, lastMessageTime, senderId);
            } catch (Exception e) {
                Log.e(TAG, "updateConversationLastMessage bg: " + e.getMessage());
            }
        });
    }

    public void deleteMessage(int messageId, int conversationId) {
        writeExecutor.execute(() -> {
            try {
                db.deleteMessage(messageId, conversationId);
            } catch (Exception e) {
                Log.e(TAG, "deleteMessage bg: " + e.getMessage());
            }
        });
    }

    public void clear() {
        writeExecutor.execute(() -> {
            try {
                db.clearAllData();
            } catch (Exception e) {
                Log.e(TAG, "clear bg: " + e.getMessage());
            }
        });
    }
}
