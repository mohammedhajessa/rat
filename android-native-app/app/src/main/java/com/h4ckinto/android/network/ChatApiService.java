package com.h4ckinto.android.network;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.h4ckinto.android.utils.Constants;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import java.io.File;

import okhttp3.ConnectionPool;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * HTTP client for the chat server REST API.
 * Handles authentication, contacts, conversations, and message history.
 */
public class ChatApiService {
    private static final String TAG = "ChatApiService";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private static ChatApiService instance;
    private final OkHttpClient client;
    private final ExecutorService executor;
    private final Handler mainHandler;
    private final String baseUrl;

    public interface ApiCallback<T> {
        void onSuccess(T result);
        void onError(String error);
    }

    private ChatApiService() {
        Interceptor retryInterceptor = new Interceptor() {
            @Override
            public Response intercept(Chain chain) throws IOException {
                Request request = chain.request();
                Response response = null;
                IOException lastException = null;
                for (int attempt = 0; attempt < 3; attempt++) {
                    try {
                        response = chain.proceed(request);
                        if (response.isSuccessful() || response.code() < 500) return response;
                        response.close();
                    } catch (IOException e) {
                        lastException = e;
                    }
                    if (attempt < 2) {
                        try { Thread.sleep(800 * (attempt + 1)); } catch (InterruptedException ignored) { break; }
                    }
                }
                if (lastException != null) throw lastException;
                return response;
            }
        };

        client = new OkHttpClient.Builder()
                .connectionPool(new ConnectionPool(8, 3, TimeUnit.MINUTES))
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .addInterceptor(retryInterceptor)
                .build();
        executor = Executors.newFixedThreadPool(4);
        mainHandler = new Handler(Looper.getMainLooper());
        baseUrl = Constants.CHAT_SERVER_URL;
    }

    public static synchronized ChatApiService getInstance() {
        if (instance == null) {
            instance = new ChatApiService();
        }
        return instance;
    }

    public OkHttpClient getClient() {
        return client;
    }

    // ===================== AUTH =====================

    /**
     * Verify PIN with the chat server.
     * Returns a JSONObject with { success, token, user: { id, device_id, display_name, ... } }
     */
    public void verifyPin(String pin, String deviceId, String deviceName, ApiCallback<JSONObject> callback) {
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("pin", pin);
                body.put("device_id", deviceId);
                body.put("device_name", deviceName != null ? deviceName : "");

                Request request = new Request.Builder()
                        .url(baseUrl + "/api/auth/verify-pin")
                        .post(RequestBody.create(body.toString(), JSON))
                        .build();

                Response response = client.newCall(request).execute();
                String responseBody = response.body() != null ? response.body().string() : "{}";
                JSONObject result = new JSONObject(responseBody);

                mainHandler.post(() -> {
                    if (result.optBoolean("success", false)) {
                        callback.onSuccess(result);
                    } else {
                        callback.onError(result.optString("error", "رمز خاطئ"));
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "verifyPin error: " + e.getMessage());
                mainHandler.post(() -> callback.onError("خطأ في الاتصال بالخادم"));
            }
        });
    }

    public void updateProfile(int userId, String displayName, String initials, String avatarColor, ApiCallback<JSONObject> callback) {
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("user_id", userId);
                if (displayName != null) body.put("display_name", displayName);
                if (initials != null) body.put("initials", initials);
                if (avatarColor != null) body.put("avatar_color", avatarColor);

                Request request = new Request.Builder()
                        .url(baseUrl + "/api/auth/update-profile")
                        .post(RequestBody.create(body.toString(), JSON))
                        .build();

                Response response = client.newCall(request).execute();
                String responseBody = response.body() != null ? response.body().string() : "{}";
                JSONObject result = new JSONObject(responseBody);

                mainHandler.post(() -> {
                    if (result.optBoolean("success", false)) {
                        callback.onSuccess(result);
                    } else {
                        callback.onError(result.optString("error", "\u0641\u0634\u0644 \u062A\u062D\u062F\u064A\u062B \u0627\u0644\u0645\u0644\u0641 \u0627\u0644\u0634\u062E\u0635\u064A"));
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "updateProfile error: " + e.getMessage());
                mainHandler.post(() -> callback.onError("\u062E\u0637\u0623 \u0641\u064A \u0627\u0644\u0627\u062A\u0635\u0627\u0644"));
            }
        });
    }

    // ===================== CONVERSATIONS =====================

    /**
     * Get all conversations for a user.
     * Returns JSONArray of conversation objects.
     */
    public void getConversations(int userId, ApiCallback<JSONArray> callback) {
        executor.execute(() -> {
            try {
                Request request = new Request.Builder()
                        .url(baseUrl + "/api/messages/conversations/" + userId)
                        .get()
                        .build();

                Response response = client.newCall(request).execute();
                String responseBody = response.body() != null ? response.body().string() : "{}";
                JSONObject result = new JSONObject(responseBody);

                mainHandler.post(() -> {
                    if (response.isSuccessful() && result.optBoolean("success", false)) {
                        JSONArray arr = result.optJSONArray("conversations");
                        if (arr == null) {
                            arr = new JSONArray();
                        }
                        callback.onSuccess(arr);
                    } else {
                        callback.onError(result.optString("error", "Failed to load conversations"));
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "getConversations error: " + e.getMessage());
                mainHandler.post(() -> callback.onError("خطأ في الاتصال"));
            }
        });
    }

    // ===================== MESSAGES =====================

    /**
     * Get message history for a conversation.
     * Returns JSONArray of message objects.
     */
    public void getMessages(int conversationId, Integer beforeId, int limit, int maxAgeHours, ApiCallback<JSONArray> callback) {
        getMessages(conversationId, beforeId, limit, maxAgeHours, 0, callback);
    }

    public void getMessages(int conversationId, Integer beforeId, int limit, int maxAgeHours, int maxAgeSeconds, ApiCallback<JSONArray> callback) {
        executor.execute(() -> {
            try {
                String url = baseUrl + "/api/messages/" + conversationId + "?limit=" + limit;
                if (beforeId != null) {
                    url += "&before_id=" + beforeId;
                }
                if (maxAgeSeconds > 0) {
                    url += "&max_age_seconds=" + maxAgeSeconds;
                } else if (maxAgeHours > 0) {
                    url += "&max_age_hours=" + maxAgeHours;
                }

                Request request = new Request.Builder()
                        .url(url)
                        .get()
                        .build();

                Response response = client.newCall(request).execute();
                String responseBody = response.body() != null ? response.body().string() : "{}";
                JSONObject result = new JSONObject(responseBody);

                mainHandler.post(() -> {
                    if (result.optBoolean("success", false)) {
                        callback.onSuccess(result.optJSONArray("messages"));
                    } else {
                        callback.onError(result.optString("error", "Failed to load messages"));
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "getMessages error: " + e.getMessage());
                mainHandler.post(() -> callback.onError("خطأ في الاتصال"));
            }
        });
    }

    /**
     * Get or create a conversation between two users.
     * Returns conversation_id.
     */
    public void getOrCreateConversation(int userId, int otherUserId, ApiCallback<Integer> callback) {
        executor.execute(() -> {
            try {
                Request request = new Request.Builder()
                        .url(baseUrl + "/api/messages/conversation-with/" + userId + "/" + otherUserId)
                        .get()
                        .build();

                Response response = client.newCall(request).execute();
                String responseBody = response.body() != null ? response.body().string() : "{}";
                JSONObject result = new JSONObject(responseBody);

                mainHandler.post(() -> {
                    if (result.optBoolean("success", false)) {
                        callback.onSuccess(result.optInt("conversation_id", -1));
                    } else {
                        callback.onError(result.optString("error", "Failed"));
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "getOrCreateConversation error: " + e.getMessage());
                mainHandler.post(() -> callback.onError("خطأ في الاتصال"));
            }
        });
    }

    // ===================== CONTACTS =====================

    /**
     * Get contacts for a user.
     * Returns JSONArray of contact objects.
     */
    public void getContacts(int userId, ApiCallback<JSONArray> callback) {
        executor.execute(() -> {
            try {
                Request request = new Request.Builder()
                        .url(baseUrl + "/api/contacts/" + userId)
                        .get()
                        .build();

                Response response = client.newCall(request).execute();
                String responseBody = response.body() != null ? response.body().string() : "{}";
                JSONObject result = new JSONObject(responseBody);

                mainHandler.post(() -> {
                    if (result.optBoolean("success", false)) {
                        callback.onSuccess(result.optJSONArray("contacts"));
                    } else {
                        callback.onError(result.optString("error", "Failed to load contacts"));
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "getContacts error: " + e.getMessage());
                mainHandler.post(() -> callback.onError("خطأ في الاتصال"));
            }
        });
    }

    /**
     * Add a contact by device_id.
     */
    public void addContact(int ownerId, String contactDeviceId, ApiCallback<JSONObject> callback) {
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("owner_id", ownerId);
                body.put("contact_device_id", contactDeviceId);

                Request request = new Request.Builder()
                        .url(baseUrl + "/api/contacts")
                        .post(RequestBody.create(body.toString(), JSON))
                        .build();

                Response response = client.newCall(request).execute();
                String responseBody = response.body() != null ? response.body().string() : "{}";
                JSONObject result = new JSONObject(responseBody);

                mainHandler.post(() -> {
                    if (result.optBoolean("success", false)) {
                        callback.onSuccess(result);
                    } else {
                        callback.onError(result.optString("error", "Failed to add contact"));
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "addContact error: " + e.getMessage());
                mainHandler.post(() -> callback.onError("خطأ في الاتصال"));
            }
        });
    }

    /**
     * Search for a user by their numeric ID.
     */
    public void searchUserById(int userId, ApiCallback<JSONObject> callback) {
        executor.execute(() -> {
            try {
                Request request = new Request.Builder()
                        .url(baseUrl + "/api/auth/user/" + userId)
                        .get()
                        .build();

                Response response = client.newCall(request).execute();
                String responseBody = response.body() != null ? response.body().string() : "{}";
                JSONObject result = new JSONObject(responseBody);

                mainHandler.post(() -> {
                    if (result.optBoolean("success", false)) {
                        callback.onSuccess(result.optJSONObject("user"));
                    } else {
                        callback.onError(result.optString("error", "\u0644\u0645 \u064A\u062A\u0645 \u0627\u0644\u0639\u062B\u0648\u0631 \u0639\u0644\u0649 \u0627\u0644\u0645\u0633\u062A\u062E\u062F\u0645"));
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "searchUserById error: " + e.getMessage());
                mainHandler.post(() -> callback.onError("\u062E\u0637\u0623 \u0641\u064A \u0627\u0644\u0627\u062A\u0635\u0627\u0644"));
            }
        });
    }

    /**
     * Add a contact by user ID.
     */
    public void addContactByUserId(int ownerId, int contactUserId, ApiCallback<JSONObject> callback) {
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("owner_id", ownerId);
                body.put("contact_user_id", contactUserId);

                Request request = new Request.Builder()
                        .url(baseUrl + "/api/contacts")
                        .post(RequestBody.create(body.toString(), JSON))
                        .build();

                Response response = client.newCall(request).execute();
                String responseBody = response.body() != null ? response.body().string() : "{}";
                JSONObject result = new JSONObject(responseBody);

                mainHandler.post(() -> {
                    if (result.optBoolean("success", false)) {
                        callback.onSuccess(result);
                    } else {
                        callback.onError(result.optString("error", "\u0641\u0634\u0644 \u0625\u0636\u0627\u0641\u0629 \u062C\u0647\u0629 \u0627\u0644\u0627\u062A\u0635\u0627\u0644"));
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "addContactByUserId error: " + e.getMessage());
                mainHandler.post(() -> callback.onError("\u062E\u0637\u0623 \u0641\u064A \u0627\u0644\u0627\u062A\u0635\u0627\u0644"));
            }
        });
    }

    // ===================== CALL LOGS =====================

    public void getCallLogs(int userId, ApiCallback<JSONArray> callback) {
        executor.execute(() -> {
            try {
                Request request = new Request.Builder()
                        .url(baseUrl + "/api/calls/" + userId)
                        .get()
                        .build();

                Response response = client.newCall(request).execute();
                String responseBody = response.body() != null ? response.body().string() : "{}";
                JSONObject result = new JSONObject(responseBody);

                mainHandler.post(() -> {
                    if (result.optBoolean("success", false)) {
                        callback.onSuccess(result.optJSONArray("calls"));
                    } else {
                        callback.onError(result.optString("error", "Failed to load call logs"));
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "getCallLogs error: " + e.getMessage());
                mainHandler.post(() -> callback.onError("خطأ في الاتصال"));
            }
        });
    }

    // ===================== DEVICES =====================

    public void getDevices(int userId, ApiCallback<JSONArray> callback) {
        executor.execute(() -> {
            try {
                Request request = new Request.Builder()
                        .url(baseUrl + "/api/auth/devices/" + userId)
                        .get()
                        .build();

                Response response = client.newCall(request).execute();
                String responseBody = response.body() != null ? response.body().string() : "{}";
                JSONObject result = new JSONObject(responseBody);

                mainHandler.post(() -> {
                    if (result.optBoolean("success", false)) {
                        callback.onSuccess(result.optJSONArray("devices"));
                    } else {
                        callback.onError(result.optString("error", "فشل تحميل الأجهزة"));
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "getDevices error: " + e.getMessage());
                mainHandler.post(() -> callback.onError("خطأ في الاتصال"));
            }
        });
    }

    public void removeDevice(int userId, int deviceRowId, ApiCallback<JSONObject> callback) {
        executor.execute(() -> {
            try {
                Request request = new Request.Builder()
                        .url(baseUrl + "/api/auth/devices/" + userId + "/" + deviceRowId)
                        .delete()
                        .build();

                Response response = client.newCall(request).execute();
                String responseBody = response.body() != null ? response.body().string() : "{}";
                JSONObject result = new JSONObject(responseBody);

                mainHandler.post(() -> {
                    if (result.optBoolean("success", false)) {
                        callback.onSuccess(result);
                    } else {
                        callback.onError(result.optString("error", "فشل إزالة الجهاز"));
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "removeDevice error: " + e.getMessage());
                mainHandler.post(() -> callback.onError("خطأ في الاتصال"));
            }
        });
    }

    public void changePin(int userId, String currentPin, String newPin, ApiCallback<JSONObject> callback) {
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("user_id", userId);
                body.put("current_pin", currentPin);
                body.put("new_pin", newPin);

                Request request = new Request.Builder()
                        .url(baseUrl + "/api/auth/change-pin")
                        .post(RequestBody.create(body.toString(), JSON))
                        .build();

                Response response = client.newCall(request).execute();
                String responseBody = response.body() != null ? response.body().string() : "{}";
                JSONObject result = new JSONObject(responseBody);

                mainHandler.post(() -> {
                    if (result.optBoolean("success", false)) {
                        callback.onSuccess(result);
                    } else {
                        callback.onError(result.optString("error", "فشل تغيير الرمز"));
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "changePin error: " + e.getMessage());
                mainHandler.post(() -> callback.onError("خطأ في الاتصال"));
            }
        });
    }

    // ===================== FILE UPLOAD =====================

    /**
     * Upload a file to the chat server.
     * @param file The file to upload
     * @param type "image", "voice", or "document"
     * @param senderId The user ID of the sender
     * @param callback Returns JSONObject with { fileUrl, fileName, fileSize }
     */
    public void uploadFile(File file, String type, int senderId, ApiCallback<JSONObject> callback) {
        executor.execute(() -> {
            try {
                String mimeType = guessMimeType(file.getName(), type);
                RequestBody fileBody = RequestBody.create(file, MediaType.parse(mimeType));

                MultipartBody requestBody = new MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("file", file.getName(), fileBody)
                        .addFormDataPart("type", type)
                        .addFormDataPart("sender_id", String.valueOf(senderId))
                        .build();

                Request request = new Request.Builder()
                        .url(baseUrl + "/api/upload")
                        .post(requestBody)
                        .build();

                Response response = client.newCall(request).execute();
                String responseBody = response.body() != null ? response.body().string() : "{}";
                JSONObject result = new JSONObject(responseBody);

                mainHandler.post(() -> {
                    if (result.optBoolean("success", false)) {
                        callback.onSuccess(result);
                    } else {
                        callback.onError(result.optString("error", "فشل رفع الملف"));
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "uploadFile error: " + e.getMessage());
                mainHandler.post(() -> callback.onError("خطأ في رفع الملف"));
            }
        });
    }

    public void deleteCallLogs(int userId, ApiCallback<JSONObject> callback) {
        executor.execute(() -> {
            try {
                Request request = new Request.Builder()
                        .url(baseUrl + "/api/calls/" + userId)
                        .delete()
                        .build();
                Response response = client.newCall(request).execute();
                String body = response.body() != null ? response.body().string() : "{}";
                JSONObject result = new JSONObject(body);
                mainHandler.post(() -> {
                    if (result.optBoolean("success", false)) {
                        callback.onSuccess(result);
                    } else {
                        callback.onError(result.optString("error", "\u0641\u0634\u0644 \u062D\u0630\u0641 \u0627\u0644\u0633\u062C\u0644"));
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "deleteCallLogs error: " + e.getMessage());
                mainHandler.post(() -> callback.onError("\u062E\u0637\u0623 \u0641\u064A \u062D\u0630\u0641 \u0627\u0644\u0633\u062C\u0644"));
            }
        });
    }

    public void uploadProfilePicture(File file, int userId, ApiCallback<JSONObject> callback) {
        executor.execute(() -> {
            try {
                RequestBody fileBody = RequestBody.create(file, MediaType.parse("image/jpeg"));
                MultipartBody requestBody = new MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("file", file.getName(), fileBody)
                        .addFormDataPart("type", "image")
                        .addFormDataPart("sender_id", String.valueOf(userId))
                        .build();

                Request request = new Request.Builder()
                        .url(baseUrl + "/api/upload")
                        .post(requestBody)
                        .build();

                Response response = client.newCall(request).execute();
                String body = response.body() != null ? response.body().string() : "{}";
                JSONObject uploadResult = new JSONObject(body);

                if (uploadResult.optBoolean("success", false)) {
                    String avatarUrl = uploadResult.getString("fileUrl");
                    JSONObject updateBody = new JSONObject();
                    updateBody.put("user_id", userId);
                    updateBody.put("avatar_url", avatarUrl);

                    Request updateReq = new Request.Builder()
                            .url(baseUrl + "/api/auth/update-profile")
                            .post(RequestBody.create(updateBody.toString(), JSON))
                            .build();
                    Response updateResp = client.newCall(updateReq).execute();
                    String updateRespBody = updateResp.body() != null ? updateResp.body().string() : "{}";
                    JSONObject finalResult = new JSONObject(updateRespBody);
                    finalResult.put("avatar_url", avatarUrl);
                    mainHandler.post(() -> callback.onSuccess(finalResult));
                } else {
                    mainHandler.post(() -> callback.onError(uploadResult.optString("error", "\u0641\u0634\u0644 \u0631\u0641\u0639 \u0627\u0644\u0635\u0648\u0631\u0629")));
                }
            } catch (Exception e) {
                Log.e(TAG, "uploadProfilePicture error: " + e.getMessage());
                mainHandler.post(() -> callback.onError("\u062E\u0637\u0623 \u0641\u064A \u0631\u0641\u0639 \u0627\u0644\u0635\u0648\u0631\u0629"));
            }
        });
    }

    // ===================== BLOCK =====================

    public void blockUser(int blockerId, int blockedId, ApiCallback<JSONObject> callback) {
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("blocker_id", blockerId);
                body.put("blocked_id", blockedId);

                Request request = new Request.Builder()
                        .url(baseUrl + "/api/block")
                        .post(RequestBody.create(body.toString(), JSON))
                        .build();

                Response response = client.newCall(request).execute();
                String responseBody = response.body() != null ? response.body().string() : "{}";
                JSONObject result = new JSONObject(responseBody);

                mainHandler.post(() -> {
                    if (result.optBoolean("success", false)) {
                        callback.onSuccess(result);
                    } else {
                        callback.onError(result.optString("error", "Failed"));
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "blockUser error: " + e.getMessage());
                mainHandler.post(() -> callback.onError("Connection error"));
            }
        });
    }

    public void unblockUser(int blockerId, int blockedId, ApiCallback<JSONObject> callback) {
        executor.execute(() -> {
            try {
                Request request = new Request.Builder()
                        .url(baseUrl + "/api/block/" + blockerId + "/" + blockedId)
                        .delete()
                        .build();

                Response response = client.newCall(request).execute();
                String responseBody = response.body() != null ? response.body().string() : "{}";
                JSONObject result = new JSONObject(responseBody);

                mainHandler.post(() -> {
                    if (result.optBoolean("success", false)) {
                        callback.onSuccess(result);
                    } else {
                        callback.onError(result.optString("error", "Failed"));
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "unblockUser error: " + e.getMessage());
                mainHandler.post(() -> callback.onError("Connection error"));
            }
        });
    }

    public void checkBlocked(int blockerId, int blockedId, ApiCallback<Boolean> callback) {
        executor.execute(() -> {
            try {
                Request request = new Request.Builder()
                        .url(baseUrl + "/api/block/check/" + blockerId + "/" + blockedId)
                        .get()
                        .build();

                Response response = client.newCall(request).execute();
                String responseBody = response.body() != null ? response.body().string() : "{}";
                JSONObject result = new JSONObject(responseBody);

                mainHandler.post(() -> {
                    if (result.optBoolean("success", false)) {
                        callback.onSuccess(result.optBoolean("is_blocked", false));
                    } else {
                        callback.onError(result.optString("error", "Failed"));
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "checkBlocked error: " + e.getMessage());
                mainHandler.post(() -> callback.onError("Connection error"));
            }
        });
    }

    // ===================== MUTE =====================

    public void muteConversation(int userId, int conversationId, ApiCallback<JSONObject> callback) {
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("user_id", userId);
                body.put("conversation_id", conversationId);

                Request request = new Request.Builder()
                        .url(baseUrl + "/api/mute")
                        .post(RequestBody.create(body.toString(), JSON))
                        .build();

                Response response = client.newCall(request).execute();
                String responseBody = response.body() != null ? response.body().string() : "{}";
                JSONObject result = new JSONObject(responseBody);

                mainHandler.post(() -> {
                    if (result.optBoolean("success", false)) {
                        callback.onSuccess(result);
                    } else {
                        callback.onError(result.optString("error", "Failed"));
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "muteConversation error: " + e.getMessage());
                mainHandler.post(() -> callback.onError("Connection error"));
            }
        });
    }

    public void unmuteConversation(int userId, int conversationId, ApiCallback<JSONObject> callback) {
        executor.execute(() -> {
            try {
                Request request = new Request.Builder()
                        .url(baseUrl + "/api/mute/" + userId + "/" + conversationId)
                        .delete()
                        .build();

                Response response = client.newCall(request).execute();
                String responseBody = response.body() != null ? response.body().string() : "{}";
                JSONObject result = new JSONObject(responseBody);

                mainHandler.post(() -> {
                    if (result.optBoolean("success", false)) {
                        callback.onSuccess(result);
                    } else {
                        callback.onError(result.optString("error", "Failed"));
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "unmuteConversation error: " + e.getMessage());
                mainHandler.post(() -> callback.onError("Connection error"));
            }
        });
    }

    public void checkMuted(int userId, int conversationId, ApiCallback<Boolean> callback) {
        executor.execute(() -> {
            try {
                Request request = new Request.Builder()
                        .url(baseUrl + "/api/mute/" + userId + "/" + conversationId)
                        .get()
                        .build();

                Response response = client.newCall(request).execute();
                String responseBody = response.body() != null ? response.body().string() : "{}";
                JSONObject result = new JSONObject(responseBody);

                mainHandler.post(() -> {
                    if (result.optBoolean("success", false)) {
                        callback.onSuccess(result.optBoolean("muted", false));
                    } else {
                        callback.onError(result.optString("error", "Failed"));
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "checkMuted error: " + e.getMessage());
                mainHandler.post(() -> callback.onError("Connection error"));
            }
        });
    }

    // ===================== FAVORITE =====================

    public void favoriteConversation(int userId, int conversationId, ApiCallback<JSONObject> callback) {
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("user_id", userId);
                body.put("conversation_id", conversationId);

                Request request = new Request.Builder()
                        .url(baseUrl + "/api/favorite")
                        .post(RequestBody.create(body.toString(), JSON))
                        .build();

                Response response = client.newCall(request).execute();
                String responseBody = response.body() != null ? response.body().string() : "{}";
                JSONObject result = new JSONObject(responseBody);

                mainHandler.post(() -> {
                    if (result.optBoolean("success", false)) {
                        callback.onSuccess(result);
                    } else {
                        callback.onError(result.optString("error", "Failed"));
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "favoriteConversation error: " + e.getMessage());
                mainHandler.post(() -> callback.onError("Connection error"));
            }
        });
    }

    public void unfavoriteConversation(int userId, int conversationId, ApiCallback<JSONObject> callback) {
        executor.execute(() -> {
            try {
                Request request = new Request.Builder()
                        .url(baseUrl + "/api/favorite/" + userId + "/" + conversationId)
                        .delete()
                        .build();

                Response response = client.newCall(request).execute();
                String responseBody = response.body() != null ? response.body().string() : "{}";
                JSONObject result = new JSONObject(responseBody);

                mainHandler.post(() -> {
                    if (result.optBoolean("success", false)) {
                        callback.onSuccess(result);
                    } else {
                        callback.onError(result.optString("error", "Failed"));
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "unfavoriteConversation error: " + e.getMessage());
                mainHandler.post(() -> callback.onError("Connection error"));
            }
        });
    }

    // ===================== ARCHIVE =====================

    public void archiveConversation(int userId, int conversationId, ApiCallback<JSONObject> callback) {
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("user_id", userId);
                body.put("conversation_id", conversationId);

                Request request = new Request.Builder()
                        .url(baseUrl + "/api/archive")
                        .post(RequestBody.create(body.toString(), JSON))
                        .build();

                Response response = client.newCall(request).execute();
                String responseBody = response.body() != null ? response.body().string() : "{}";
                JSONObject result = new JSONObject(responseBody);

                mainHandler.post(() -> {
                    if (result.optBoolean("success", false)) {
                        callback.onSuccess(result);
                    } else {
                        callback.onError(result.optString("error", "Failed"));
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "archiveConversation error: " + e.getMessage());
                mainHandler.post(() -> callback.onError("Connection error"));
            }
        });
    }

    public void unarchiveConversation(int userId, int conversationId, ApiCallback<JSONObject> callback) {
        executor.execute(() -> {
            try {
                Request request = new Request.Builder()
                        .url(baseUrl + "/api/archive/" + userId + "/" + conversationId)
                        .delete()
                        .build();

                Response response = client.newCall(request).execute();
                String responseBody = response.body() != null ? response.body().string() : "{}";
                JSONObject result = new JSONObject(responseBody);

                mainHandler.post(() -> {
                    if (result.optBoolean("success", false)) {
                        callback.onSuccess(result);
                    } else {
                        callback.onError(result.optString("error", "Failed"));
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "unarchiveConversation error: " + e.getMessage());
                mainHandler.post(() -> callback.onError("Connection error"));
            }
        });
    }

    public void clearChat(int conversationId, int userId, ApiCallback<JSONObject> callback) {
        executor.execute(() -> {
            try {
                Request request = new Request.Builder()
                        .url(baseUrl + "/api/messages/clear/" + conversationId + "/" + userId)
                        .delete()
                        .build();

                Response response = client.newCall(request).execute();
                String responseBody = response.body() != null ? response.body().string() : "{}";
                JSONObject result = new JSONObject(responseBody);

                mainHandler.post(() -> {
                    if (result.optBoolean("success", false)) {
                        callback.onSuccess(result);
                    } else {
                        callback.onError(result.optString("error", "Failed"));
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "clearChat error: " + e.getMessage());
                mainHandler.post(() -> callback.onError("Connection error"));
            }
        });
    }

    // ===================== DELETE CONVERSATION =====================

    public void deleteConversation(int conversationId, int userId, ApiCallback<JSONObject> callback) {
        executor.execute(() -> {
            try {
                Request request = new Request.Builder()
                        .url(baseUrl + "/api/messages/conversation/" + conversationId + "/" + userId)
                        .delete()
                        .build();

                Response response = client.newCall(request).execute();
                String responseBody = response.body() != null ? response.body().string() : "{}";
                JSONObject result = new JSONObject(responseBody);

                mainHandler.post(() -> {
                    if (result.optBoolean("success", false)) {
                        callback.onSuccess(result);
                    } else {
                        callback.onError(result.optString("error", "Failed"));
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "deleteConversation error: " + e.getMessage());
                mainHandler.post(() -> callback.onError("Connection error"));
            }
        });
    }

    public void searchByUsername(String username, ApiCallback<JSONObject> callback) {
        executor.execute(() -> {
            try {
                Request request = new Request.Builder()
                        .url(baseUrl + "/api/auth/search-username/" + username.trim().toLowerCase())
                        .get()
                        .build();

                Response response = client.newCall(request).execute();
                String responseBody = response.body() != null ? response.body().string() : "{}";
                JSONObject result = new JSONObject(responseBody);

                mainHandler.post(() -> {
                    if (result.optBoolean("success", false)) {
                        callback.onSuccess(result.optJSONObject("user"));
                    } else {
                        callback.onError(result.optString("error", "\u0644\u0645 \u064A\u062A\u0645 \u0627\u0644\u0639\u062B\u0648\u0631 \u0639\u0644\u0649 \u0627\u0644\u0645\u0633\u062A\u062E\u062F\u0645"));
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "searchByUsername error: " + e.getMessage());
                mainHandler.post(() -> callback.onError("\u062E\u0637\u0623 \u0641\u064A \u0627\u0644\u0627\u062A\u0635\u0627\u0644"));
            }
        });
    }

    public void addContactByUsername(int ownerId, String username, ApiCallback<JSONObject> callback) {
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("owner_id", ownerId);
                body.put("contact_username", username.trim().toLowerCase());

                Request request = new Request.Builder()
                        .url(baseUrl + "/api/contacts")
                        .post(RequestBody.create(body.toString(), JSON))
                        .build();

                Response response = client.newCall(request).execute();
                String responseBody = response.body() != null ? response.body().string() : "{}";
                JSONObject result = new JSONObject(responseBody);

                mainHandler.post(() -> {
                    if (result.optBoolean("success", false)) {
                        callback.onSuccess(result);
                    } else {
                        callback.onError(result.optString("error", "\u0641\u0634\u0644 \u0625\u0636\u0627\u0641\u0629 \u062C\u0647\u0629 \u0627\u0644\u0627\u062A\u0635\u0627\u0644"));
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "addContactByUsername error: " + e.getMessage());
                mainHandler.post(() -> callback.onError("\u062E\u0637\u0623 \u0641\u064A \u0627\u0644\u0627\u062A\u0635\u0627\u0644"));
            }
        });
    }

    private String guessMimeType(String fileName, String type) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".m4a") || lower.endsWith(".aac")) return "audio/mp4";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".ogg")) return "audio/ogg";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".doc") || lower.endsWith(".docx")) return "application/msword";
        if ("image".equals(type)) return "image/jpeg";
        if ("voice".equals(type)) return "audio/mp4";
        return "application/octet-stream";
    }
}
