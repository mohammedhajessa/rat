package com.h4ckinto.android.ui;

import android.Manifest;
import android.app.KeyguardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.PopupMenu;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.h4ckinto.android.R;
import com.h4ckinto.android.network.ChatApiService;
import com.h4ckinto.android.network.ChatSocketManager;
import com.h4ckinto.android.ui.adapters.MessagesAdapter;
import com.h4ckinto.android.utils.ChatNotificationManager;
import com.h4ckinto.android.utils.ChatPrefs;
import com.h4ckinto.android.utils.ChatTimeUtils;
import com.h4ckinto.android.utils.Constants;
import com.h4ckinto.android.utils.AppLockManager;
import com.h4ckinto.android.utils.ConversationCache;
import com.h4ckinto.android.utils.MediaCacheManager;
import com.h4ckinto.android.ui.views.RecordingWaveformView;
import com.h4ckinto.android.ui.views.TypingIndicatorView;
import com.h4ckinto.android.utils.VoiceRecorder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ConversationActivity extends AppCompatActivity {

    private static final String TAG = "ConversationActivity";
    private static final int PERMISSION_REQUEST_RECORD = 100;
    private static final int PERMISSION_REQUEST_CAMERA = 101;
    private static final int PERMISSION_REQUEST_STORAGE = 102;
    private static final int REQUEST_MANAGE_STORAGE = 200;
    private static final int REQUEST_CHAT_LOCK_CREDENTIAL = 2010;

    private String pendingStorageAction = null;

    private RecyclerView messagesRecycler;
    private MessagesAdapter adapter;
    private List<MessagesAdapter.Message> messages;
    private EditText messageInput;
    private TextView contactStatusView;
    private TypingIndicatorView typingIndicatorView;
    private LinearLayout inputBar;

    // Recording UI
    private LinearLayout recordingOverlay;
    private TextView recordingTimer;
    private View recordingDot;
    private RecordingWaveformView recordingWaveform;
    private ImageView btnMic, btnCancelRecording;

    private int conversationId = -1;
    private int myUserId = -1;
    private int otherUserId = -1;
    private String otherName = "";

    private ChatSocketManager chatSocket;
    private Handler mainHandler;

    // Typing: outgoing
    private Handler typingHandler;
    private boolean iAmTyping = false;
    private Runnable stopTypingRunnable;

    // Typing: incoming
    private Runnable clearRemoteTypingRunnable;
    private static final long TYPING_DISPLAY_TIMEOUT = 5000;

    // Status tracking
    private boolean otherIsOnline = false;
    private String otherLastSeen = "";

    // Listeners
    private ChatSocketManager.OnMessageReceivedListener msgListener;
    private ChatSocketManager.OnMessageSentListener sentListener;
    private ChatSocketManager.OnTypingListener typingSocketListener;
    private ChatSocketManager.OnOnlineStatusListener onlineSocketListener;
    private ChatSocketManager.OnReadReceiptListener readSocketListener;
    private ChatSocketManager.OnMessageDeletedListener deletedSocketListener;
    private ChatSocketManager.OnRecordingListener recordingSocketListener;
    private Runnable clearRemoteRecordingRunnable;
    private boolean listenersRegistered = false;
    private boolean historyLoaded = false;
    private long lastMessageFetchMs = 0;
    private boolean isMuted = false;
    private static final int REQ_PROFILE = 1001;

    // Voice recording
    private VoiceRecorder voiceRecorder;
    private Handler recordingTimerHandler;
    private Runnable recordingTimerRunnable;
    private boolean isRecording = false;

    // Camera
    private Uri cameraImageUri;
    private boolean unlockPromptVisible = false;

    // Activity result launchers
    private ActivityResultLauncher<Intent> cameraLauncher;
    private ActivityResultLauncher<Intent> galleryLauncher;
    private ActivityResultLauncher<Intent> documentLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(getResources().getColor(R.color.chat_surface, getTheme()));
        getWindow().setNavigationBarColor(getResources().getColor(R.color.chat_surface, getTheme()));

        setContentView(R.layout.activity_conversation);

        mainHandler = new Handler(Looper.getMainLooper());
        typingHandler = new Handler(Looper.getMainLooper());
        recordingTimerHandler = new Handler(Looper.getMainLooper());

        conversationId = getIntent().getIntExtra("conversation_id", -1);
        otherUserId = getIntent().getIntExtra("other_user_id", -1);
        otherName = getIntent().getStringExtra("contact_name");
        String contactInitials = getIntent().getStringExtra("contact_initials");
        final String avatarColor = getIntent().getStringExtra("avatar_color") != null ?
                getIntent().getStringExtra("avatar_color") : "#3B82F6";

        isMuted = getIntent().getBooleanExtra("is_muted", false);
                if (otherName == null) otherName = "مستخدم";
        if (contactInitials == null) contactInitials = otherName.substring(0, Math.min(2, otherName.length()));

        ChatPrefs chatPrefs = new ChatPrefs(this);
        myUserId = chatPrefs.getUserId();

        voiceRecorder = new VoiceRecorder();

        // UI setup
        TextView nameView = findViewById(R.id.contact_name);
        nameView.setText(otherName);
        contactStatusView = findViewById(R.id.contact_status);
        typingIndicatorView = findViewById(R.id.typing_indicator);

        TextView avatarView = findViewById(R.id.contact_avatar);
        avatarView.setText(contactInitials);
        try { avatarView.getBackground().setTint(Color.parseColor(avatarColor)); }
        catch (Exception e) { avatarView.getBackground().setTint(Color.parseColor("#3B82F6")); }

        String otherAvatarUrl = getIntent().getStringExtra("avatar_url");
        if (otherAvatarUrl != null && !otherAvatarUrl.isEmpty()) {
            ImageView avatarImageView = findViewById(R.id.contact_avatar_image);
            avatarImageView.setVisibility(View.VISIBLE);
            avatarView.setVisibility(View.INVISIBLE);
            avatarImageView.setClipToOutline(true);
            avatarImageView.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setOval(0, 0, view.getWidth(), view.getHeight());
                }
            });
            com.h4ckinto.android.utils.ImageLoader.getInstance().load(otherAvatarUrl, avatarImageView);
        }

        ImageView btnBack = findViewById(R.id.btn_back);
        btnBack.setOnClickListener(v -> finish());

        final String otherAvatarUrlFinal = getIntent().getStringExtra("avatar_url");
        final String finalInitials = contactInitials;
        final String finalAvatarColor = avatarColor;

        findViewById(R.id.header_profile_area).setOnClickListener(v -> {
            Intent profileIntent = UserProfileActivity.create(
                    this, otherUserId, otherName, finalInitials,
                    finalAvatarColor, otherAvatarUrlFinal, conversationId);
            startActivityForResult(profileIntent, REQ_PROFILE);
        });

        // Call button
        ImageView btnCall = findViewById(R.id.btn_call);
        btnCall.setOnClickListener(v -> {
            Intent callIntent = AudioCallActivity.createOutgoing(
                    this, otherUserId, otherName, finalInitials, finalAvatarColor);
            startActivity(callIntent);
        });

        // More menu button
        ImageView btnMore = findViewById(R.id.btn_more);
        btnMore.setOnClickListener(v -> showMoreMenu(v));

        messagesRecycler = findViewById(R.id.messages_recycler);
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        layoutManager.setStackFromEnd(true);
        messagesRecycler.setLayoutManager(layoutManager);

        messagesRecycler.setItemViewCacheSize(20);
        messagesRecycler.setHasFixedSize(false);

        messages = new ArrayList<>();
        adapter = new MessagesAdapter(messages);
        adapter.setOnMessageLongClickListener((msg, position) -> showDeleteDialog(msg, position));
        messagesRecycler.setAdapter(adapter);

        messageInput = findViewById(R.id.message_input);
        ImageView btnSend = findViewById(R.id.btn_send);
        btnSend.setOnClickListener(v -> sendTextMessage());

        btnMic = findViewById(R.id.btn_mic);
        ImageView btnAttach = findViewById(R.id.btn_attach);

        // Recording overlay
        recordingOverlay = findViewById(R.id.recording_overlay);
        recordingTimer = findViewById(R.id.recording_timer);
        recordingDot = findViewById(R.id.recording_dot);
        recordingWaveform = findViewById(R.id.recording_waveform);
        btnCancelRecording = findViewById(R.id.btn_cancel_recording);
        ImageView btnSendRecording = findViewById(R.id.btn_send_recording);
        inputBar = (LinearLayout) btnSend.getParent();

        // Mic button: tap to start recording
        btnMic.setOnClickListener(v -> startVoiceRecording());

        // Send recording button in overlay
        btnSendRecording.setOnClickListener(v -> stopAndSendVoice());

        btnCancelRecording.setOnClickListener(v -> cancelVoiceRecording());

        // Attach button
        btnAttach.setOnClickListener(v -> showAttachBottomSheet());

        // Register activity result launchers
        registerLaunchers();

        // Typing detection
        stopTypingRunnable = () -> {
            iAmTyping = false;
            if (conversationId > 0) chatSocket.sendStopTyping(conversationId);
        };
        clearRemoteTypingRunnable = () -> {
            if (typingIndicatorView != null) {
                typingIndicatorView.stopAnimating();
            }
            updateStatus();
        };

        messageInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (conversationId > 0 && s.length() > 0) {
                    if (!iAmTyping) {
                        iAmTyping = true;
                        chatSocket.sendTyping(conversationId);
                    }
                    typingHandler.removeCallbacks(stopTypingRunnable);
                    typingHandler.postDelayed(stopTypingRunnable, 2000);
                }
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        chatSocket = ChatSocketManager.getInstance();
        createListeners();

        if (otherUserId > 0) chatSocket.requestStatus(otherUserId);

        if (conversationId <= 0 && otherUserId > 0 && myUserId > 0) {
            ChatApiService.getInstance().getOrCreateConversation(myUserId, otherUserId, new ChatApiService.ApiCallback<Integer>() {
                @Override public void onSuccess(Integer convId) {
                    conversationId = convId;
                    if (ensureConversationUnlocked()) {
                        loadMessageHistory();
                    }
                }
                @Override public void onError(String error) { Log.e(TAG, "Error getting conversation: " + error); }
            });
        } else if (conversationId > 0) {
            if (ensureConversationUnlocked()) {
                loadMessageHistory();
            }
        }

        if (conversationId > 0 && ensureConversationUnlocked()) chatSocket.markAsRead(conversationId);
    }

    // ── Activity Result Launchers ──

    private void registerLaunchers() {
        cameraLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && cameraImageUri != null) {
                        uploadAndSendMedia(cameraImageUri, "image");
                    }
                }
        );

        galleryLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        if (uri != null) uploadAndSendMedia(uri, "image");
                    }
                }
        );

        documentLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        if (uri != null) uploadAndSendMedia(uri, "document");
                    }
                }
        );
    }

    // ── Attach Bottom Sheet ──

    private void showAttachBottomSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this, R.style.BottomSheetDialogTheme);
        View sheetView = getLayoutInflater().inflate(R.layout.bottom_sheet_attach, null);
        dialog.setContentView(sheetView);

        sheetView.findViewById(R.id.option_camera).setOnClickListener(v -> {
            dialog.dismiss();
            openCamera();
        });

        sheetView.findViewById(R.id.option_gallery).setOnClickListener(v -> {
            dialog.dismiss();
            openGallery();
        });

        sheetView.findViewById(R.id.option_document).setOnClickListener(v -> {
            dialog.dismiss();
            openDocumentPicker();
        });

        dialog.show();
    }

    private void openCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, PERMISSION_REQUEST_CAMERA);
            return;
        }
        try {
            File photoFile = new File(getCacheDir(), "photo_" + System.currentTimeMillis() + ".jpg");
            cameraImageUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", photoFile);
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri);
            cameraLauncher.launch(intent);
        } catch (Exception e) {
            Log.e(TAG, "Error opening camera: " + e.getMessage());
            Toast.makeText(this, "فشل فتح الكاميرا", Toast.LENGTH_SHORT).show();
        }
    }

    private void openGallery() {
        if (!checkStoragePermission("gallery")) return;
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        galleryLauncher.launch(Intent.createChooser(intent, "\u0627\u062E\u062A\u0631 \u0635\u0648\u0631\u0629"));
    }

    private void openDocumentPicker() {
        if (!checkStoragePermission("document")) return;
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        documentLauncher.launch(Intent.createChooser(intent, "\u0627\u062E\u062A\u0631 \u0645\u0633\u062A\u0646\u062F"));
    }

    private boolean checkStoragePermission(String action) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                pendingStorageAction = action;
                new AlertDialog.Builder(this, R.style.DarkAlertDialog)
                        .setTitle("\u0625\u0630\u0646 \u0627\u0644\u0648\u0635\u0648\u0644 \u0644\u0644\u0645\u0644\u0641\u0627\u062A")
                        .setMessage("\u0627\u0644\u062A\u0637\u0628\u064A\u0642 \u064A\u062D\u062A\u0627\u062C \u0625\u0644\u0649 \u0627\u0644\u0648\u0635\u0648\u0644 \u0625\u0644\u0649 \u0627\u0644\u0645\u0644\u0641\u0627\u062A.")
                        .setPositiveButton("\u0645\u0648\u0627\u0641\u0642", (dialog, which) -> {
                            try {
                                Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                                intent.setData(Uri.parse("package:" + getPackageName()));
                                startActivityForResult(intent, REQUEST_MANAGE_STORAGE);
                            } catch (Exception e) {
                                Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                                startActivityForResult(intent, REQUEST_MANAGE_STORAGE);
                            }
                        })
                        .setNegativeButton("\u0625\u0644\u063A\u0627\u0621", null)
                        .setCancelable(false)
                        .show();
                return false;
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                pendingStorageAction = action;
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, PERMISSION_REQUEST_STORAGE);
                return false;
            }
        }
        return true;
    }

    // ── Voice Recording ──

    private void startVoiceRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSION_REQUEST_RECORD);
            return;
        }

        if (voiceRecorder.startRecording(this)) {
            isRecording = true;
            inputBar.setVisibility(View.GONE);
            recordingOverlay.setVisibility(View.VISIBLE);
            if (recordingWaveform != null) {
                recordingWaveform.startAnimating();
            }
            if (recordingDot != null) {
                recordingDot.startAnimation(AnimationUtils.loadAnimation(this, R.anim.pulse_recording));
            }
            if (conversationId > 0) chatSocket.sendRecording(conversationId);

            recordingTimerRunnable = new Runnable() {
                @Override
                public void run() {
                    if (isRecording) {
                        int secs = voiceRecorder.getCurrentDurationSeconds();
                        recordingTimer.setText(String.format(Locale.getDefault(), "%d:%02d", secs / 60, secs % 60));
                        recordingTimerHandler.postDelayed(this, 500);
                    }
                }
            };
            recordingTimerHandler.post(recordingTimerRunnable);
        } else {
            Toast.makeText(this, "فشل بدء التسجيل", Toast.LENGTH_SHORT).show();
        }
    }

    private void stopAndSendVoice() {
        isRecording = false;
        recordingTimerHandler.removeCallbacks(recordingTimerRunnable);
        if (recordingWaveform != null) {
            recordingWaveform.stopAnimating();
        }
        if (recordingDot != null) {
            recordingDot.clearAnimation();
        }
        if (conversationId > 0) chatSocket.sendStopRecording(conversationId);
        recordingOverlay.setVisibility(View.GONE);
        inputBar.setVisibility(View.VISIBLE);

        VoiceRecorder.RecordingResult result = voiceRecorder.stopRecording();
        if (result == null || result.durationSeconds < 1) {
            Toast.makeText(this, "التسجيل قصير جداً", Toast.LENGTH_SHORT).show();
            return;
        }

        File voiceFile = new File(result.filePath);
        int duration = result.durationSeconds;
        String localPath = result.filePath;

        String now = ChatTimeUtils.nowServerUtcString();
        String timeStr = ChatTimeUtils.formatLocalTimeNow();
        MessagesAdapter.Message voiceOptimistic = new MessagesAdapter.Message("", timeStr, true, false, "voice", localPath, null, result.fileSize, duration);
        voiceOptimistic.localFilePath = localPath;
        messages.add(voiceOptimistic);
        adapter.notifyItemInserted(messages.size() - 1);
        messagesRecycler.scrollToPosition(messages.size() - 1);
        int voiceIndex = messages.size() - 1;

        ConversationCache.getInstance(this)
                .updateConversationLastMessage(conversationId, "\u0631\u0633\u0627\u0644\u0629 \u0635\u0648\u062A\u064A\u0629", now, myUserId);

        ChatApiService.getInstance().uploadFile(voiceFile, "voice", myUserId, new ChatApiService.ApiCallback<JSONObject>() {
            @Override
            public void onSuccess(JSONObject uploadResult) {
                String rawUrl = uploadResult.optString("fileUrl");
                String serverUrl = rawUrl.startsWith("http") ? rawUrl : Constants.CHAT_SERVER_URL + rawUrl;
                String fileName = uploadResult.optString("fileName");
                long fileSize = uploadResult.optLong("fileSize", 0);

                chatSocket.sendMediaMessage(conversationId, myUserId, "", "voice", serverUrl, fileName, fileSize, duration);

                // Cache voice file with S3 URL key and update message
                MediaCacheManager mc = MediaCacheManager.getInstance(ConversationActivity.this);
                mc.cacheFromFile(serverUrl, voiceFile, "voice");
                if (voiceIndex < messages.size()) {
                    MessagesAdapter.Message msg = messages.get(voiceIndex);
                    msg.serverFileUrl = serverUrl;
                }
            }
            @Override
            public void onError(String error) {
                Toast.makeText(ConversationActivity.this, "فشل رفع التسجيل: " + error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void cancelVoiceRecording() {
        isRecording = false;
        recordingTimerHandler.removeCallbacks(recordingTimerRunnable);
        if (recordingWaveform != null) {
            recordingWaveform.stopAnimating();
        }
        if (recordingDot != null) {
            recordingDot.clearAnimation();
        }
        if (conversationId > 0) chatSocket.sendStopRecording(conversationId);
        recordingOverlay.setVisibility(View.GONE);
        inputBar.setVisibility(View.VISIBLE);
        voiceRecorder.cancelRecording();
    }

    // ── Upload & Send Media ──

    private void uploadAndSendMedia(Uri uri, String type) {
        File tempFile = copyUriToTempFile(uri, type);
        if (tempFile == null) {
            Toast.makeText(this, "\u0641\u0634\u0644 \u0642\u0631\u0627\u0621\u0629 \u0627\u0644\u0645\u0644\u0641", Toast.LENGTH_SHORT).show();
            return;
        }

        String originalName = getFileName(uri);
        long fileSize = tempFile.length();

        String now = ChatTimeUtils.nowServerUtcString();
        String timeStr = ChatTimeUtils.formatLocalTimeNow();
        String localPath = tempFile.getAbsolutePath();
        MessagesAdapter.Message optimistic = new MessagesAdapter.Message("", timeStr, true, false, type, localPath, originalName, fileSize, 0);
        optimistic.localFilePath = localPath;

        ConversationCache cache = ConversationCache.getInstance(this);
        int tempId = cache.savePendingMessage(conversationId, myUserId, "",
                type, null, originalName, fileSize, 0, localPath);
        optimistic.id = tempId;

        messages.add(optimistic);
        adapter.notifyItemInserted(messages.size() - 1);
        messagesRecycler.scrollToPosition(messages.size() - 1);
        int optimisticIndex = messages.size() - 1;

        String previewText = "image".equals(type) ? "\u0635\u0648\u0631\u0629" :
                             "voice".equals(type) ? "\u0631\u0633\u0627\u0644\u0629 \u0635\u0648\u062A\u064A\u0629" :
                             "document".equals(type) ? "\u0645\u0633\u062A\u0646\u062F" : originalName;
        cache.updateConversationLastMessage(conversationId, previewText, now, myUserId);

        final File uploadTempFile = tempFile;
        ChatApiService.getInstance().uploadFile(tempFile, type, myUserId, new ChatApiService.ApiCallback<JSONObject>() {
            @Override
            public void onSuccess(JSONObject uploadResult) {
                String rawUrl = uploadResult.optString("fileUrl");
                String serverUrl = rawUrl.startsWith("http") ? rawUrl : Constants.CHAT_SERVER_URL + rawUrl;
                String fileName = uploadResult.optString("fileName", originalName);
                long uploadedSize = uploadResult.optLong("fileSize", fileSize);

                chatSocket.sendMediaMessage(conversationId, myUserId, "", type, serverUrl, fileName, uploadedSize, 0);

                // Cache the local temp file with the S3 URL as key
                MediaCacheManager mc = MediaCacheManager.getInstance(ConversationActivity.this);
                File cachedFile = mc.cacheFromFile(serverUrl, uploadTempFile, type);
                String cachedPath = cachedFile != null ? cachedFile.getAbsolutePath() : localPath;

                if (optimisticIndex < messages.size()) {
                    MessagesAdapter.Message msg = messages.get(optimisticIndex);
                    msg.fileUrl = cachedPath;
                    msg.localFilePath = cachedPath;
                    msg.serverFileUrl = serverUrl;
                    msg.fileName = fileName;
                    adapter.notifyItemChanged(optimisticIndex);
                }
            }
            @Override
            public void onError(String error) {
                Toast.makeText(ConversationActivity.this, "\u0641\u0634\u0644 \u0631\u0641\u0639 \u0627\u0644\u0645\u0644\u0641: " + error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private File copyUriToTempFile(Uri uri, String type) {
        try {
            InputStream is = getContentResolver().openInputStream(uri);
            if (is == null) return null;

            String ext = ".bin";
            if ("image".equals(type)) ext = ".jpg";
            else {
                String name = getFileName(uri);
                if (name != null && name.contains(".")) ext = name.substring(name.lastIndexOf("."));
            }

            File tempFile = new File(getCacheDir(), type + "_" + System.currentTimeMillis() + ext);
            FileOutputStream fos = new FileOutputStream(tempFile);
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) != -1) fos.write(buf, 0, len);
            fos.close();
            is.close();
            return tempFile;
        } catch (Exception e) {
            Log.e(TAG, "Error copying uri to temp: " + e.getMessage());
            return null;
        }
    }

    private String getFileName(Uri uri) {
        String result = null;
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) result = cursor.getString(idx);
                }
            }
        }
        if (result == null) {
            result = uri.getLastPathSegment();
        }
        return result != null ? result : "file";
    }

    // ── Permission Result ──

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (requestCode == PERMISSION_REQUEST_RECORD) {
                startVoiceRecording();
            } else if (requestCode == PERMISSION_REQUEST_CAMERA) {
                openCamera();
            } else if (requestCode == PERMISSION_REQUEST_STORAGE) {
                resumePendingStorageAction();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CHAT_LOCK_CREDENTIAL) {
            if (resultCode == RESULT_OK && conversationId > 0) {
                AppLockManager.getInstance(this).unlockChat(conversationId);
                unlockPromptVisible = false;
                loadMessageHistory();
                chatSocket.markAsRead(conversationId);
            } else {
                finish();
            }
        } else if (requestCode == REQUEST_MANAGE_STORAGE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
                resumePendingStorageAction();
            }
        } else if (requestCode == REQ_PROFILE && resultCode == RESULT_OK) {
            messages.clear();
            adapter.notifyDataSetChanged();
            lastMessageFetchMs = 0;
            historyLoaded = false;
            loadMessageHistory();
        }
    }

    private void resumePendingStorageAction() {
        if (pendingStorageAction == null) return;
        String action = pendingStorageAction;
        pendingStorageAction = null;
        if ("gallery".equals(action)) {
            openGallery();
        } else if ("document".equals(action)) {
            openDocumentPicker();
        }
    }

    private boolean ensureConversationUnlocked() {
        if (conversationId <= 0) return true;
        AppLockManager lockMgr = AppLockManager.getInstance(this);
        if (!lockMgr.shouldLockChat(conversationId)) return true;
        if (unlockPromptVisible) return false;

        if (lockMgr.isChatLockDevice(conversationId)) {
            KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            if (km != null && km.isKeyguardSecure()) {
                @SuppressWarnings("deprecation")
                Intent intent = km.createConfirmDeviceCredentialIntent("قفل المحادثة", "أدخل رمز الجهاز لفتح المحادثة");
                if (intent != null) {
                    unlockPromptVisible = true;
                    startActivityForResult(intent, REQUEST_CHAT_LOCK_CREDENTIAL);
                    return false;
                }
            }
            Toast.makeText(this, "قفل الجهاز غير مُفعّل", Toast.LENGTH_SHORT).show();
            finish();
            return false;
        }

        if (lockMgr.isChatLockPin(conversationId)) {
            showChatPinUnlockDialog();
            return false;
        }

        return true;
    }

    private void showChatPinUnlockDialog() {
        unlockPromptVisible = true;
        float dp = getResources().getDisplayMetrics().density;
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setHint("أدخل رمز PIN");
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.parseColor("#50FFFFFF"));
        input.setBackgroundResource(R.drawable.selector_modern_input);
        input.setPadding((int) (14 * dp), (int) (12 * dp), (int) (14 * dp), (int) (12 * dp));
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(8)});

        android.widget.FrameLayout container = new android.widget.FrameLayout(this);
        android.widget.FrameLayout.LayoutParams params = new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins((int) (24 * dp), (int) (12 * dp), (int) (24 * dp), 0);
        input.setLayoutParams(params);
        container.addView(input);

        AlertDialog dialog = new AlertDialog.Builder(this, R.style.DarkAlertDialog)
                .setTitle("قفل المحادثة")
                .setView(container)
                .setCancelable(false)
                .setPositiveButton("فتح", null)
                .setNegativeButton("إلغاء", (d, w) -> {
                    unlockPromptVisible = false;
                    finish();
                })
                .create();

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String pin = input.getText().toString().trim();
            AppLockManager lockMgr = AppLockManager.getInstance(this);
            if (lockMgr.verifyChatPin(conversationId, pin)) {
                lockMgr.unlockChat(conversationId);
                unlockPromptVisible = false;
                dialog.dismiss();
                loadMessageHistory();
                chatSocket.markAsRead(conversationId);
            } else {
                input.setError("رمز غير صحيح");
            }
        }));

        dialog.show();
    }

    // ── Listeners ──

    private void createListeners() {
        msgListener = (message, convId) -> {
            if (convId == conversationId) {
                ConversationCache.getInstance(this).insertSingleMessage(conversationId, message);
                mainHandler.post(() -> {
                    try {
                        MessagesAdapter.Message msg = parseMessage(message);
                        maybeInsertDateSeparator(msg.rawDate);
                        messages.add(msg);
                        adapter.notifyItemInserted(messages.size() - 1);
                        messagesRecycler.scrollToPosition(messages.size() - 1);
                        chatSocket.markAsRead(conversationId);
                        preCacheSingleMedia(message);
                    } catch (Exception e) {
                        Log.e(TAG, "Error adding received message: " + e.getMessage());
                    }
                });
            }
        };

        sentListener = (message, convId) -> {
            if (convId != conversationId) return;
            int realId = message.optInt("id", -1);
            ConversationCache cache = ConversationCache.getInstance(this);
            mainHandler.post(() -> {
                try {
                    String msgType = message.optString("message_type", "text");
                    String srvUrl = message.optString("file_url", null);
                    if ("null".equals(srvUrl) || (srvUrl != null && srvUrl.isEmpty())) srvUrl = null;
                    if (srvUrl != null && !srvUrl.startsWith("http")) {
                        srvUrl = Constants.CHAT_SERVER_URL + srvUrl;
                    }

                    for (int i = messages.size() - 1; i >= 0; i--) {
                        MessagesAdapter.Message m = messages.get(i);
                        if (m.isSent && m.id < 0 && msgType.equals(m.messageType)) {
                            int oldTempId = m.id;
                            m.id = realId;
                            m.serverFileUrl = srvUrl;
                            if (!"text".equals(msgType) && srvUrl != null) {
                                MediaCacheManager mc = MediaCacheManager.getInstance(ConversationActivity.this);
                                File cached = mc.getLocalFile(srvUrl);
                                if (cached != null) {
                                    m.localFilePath = cached.getAbsolutePath();
                                }
                            }
                            adapter.notifyItemChanged(i);
                            cache.promotePendingMessageId(oldTempId, realId);
                            cache.insertSingleMessage(conversationId, message);
                            break;
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "sentListener error: " + e.getMessage());
                }
            });
        };

        typingSocketListener = new ChatSocketManager.OnTypingListener() {
            @Override
            public void onTyping(int convId, int userId) {
                if (convId == conversationId && userId == otherUserId) {
                    mainHandler.post(() -> {
                        contactStatusView.setText("\u064A\u0643\u062A\u0628");
                        contactStatusView.setTextColor(getResources().getColor(R.color.chat_accent, getTheme()));
                        if (typingIndicatorView != null) {
                            typingIndicatorView.startAnimating();
                        }
                        mainHandler.removeCallbacks(clearRemoteTypingRunnable);
                        mainHandler.postDelayed(clearRemoteTypingRunnable, TYPING_DISPLAY_TIMEOUT);
                    });
                }
            }

            @Override
            public void onStopTyping(int convId, int userId) {
                if (convId == conversationId && userId == otherUserId) {
                    mainHandler.post(() -> {
                        mainHandler.removeCallbacks(clearRemoteTypingRunnable);
                        if (typingIndicatorView != null) {
                            typingIndicatorView.stopAnimating();
                        }
                        updateStatus();
                    });
                }
            }
        };

        onlineSocketListener = (userId, isOnline, lastSeen) -> {
            if (userId == otherUserId) {
                otherIsOnline = isOnline;
                otherLastSeen = lastSeen != null ? lastSeen : "";
                mainHandler.post(() -> updateStatus());
            }
        };

        readSocketListener = (convId, readBy) -> {
            if (convId == conversationId) {
                mainHandler.post(() -> {
                    for (int i = 0; i < messages.size(); i++) {
                        MessagesAdapter.Message msg = messages.get(i);
                        if (msg.isSent && !msg.isRead) {
                            msg.isRead = true;
                            adapter.notifyItemChanged(i);
                        }
                    }
                });
            }
        };

        deletedSocketListener = (messageId, convId, deleteForAll) -> {
            if (convId == conversationId) {
                mainHandler.post(() -> {
                    for (int i = 0; i < messages.size(); i++) {
                        if (messages.get(i).id == messageId) {
                            messages.remove(i);
                            adapter.notifyItemRemoved(i);
                            break;
                        }
                    }
                });
            }
        };

        clearRemoteRecordingRunnable = () -> {
            updateStatus();
        };

        recordingSocketListener = new ChatSocketManager.OnRecordingListener() {
            @Override
            public void onRecording(int convId, int userId) {
                if (convId == conversationId && userId == otherUserId) {
                    mainHandler.post(() -> {
                        contactStatusView.setText("\u064A\u0633\u062C\u0644 \u0635\u0648\u062A\u064A\u0627\u064B...");
                        contactStatusView.setTextColor(getResources().getColor(R.color.error, getTheme()));
                        if (typingIndicatorView != null) {
                            typingIndicatorView.stopAnimating();
                        }
                        mainHandler.removeCallbacks(clearRemoteRecordingRunnable);
                        mainHandler.postDelayed(clearRemoteRecordingRunnable, 30000);
                    });
                }
            }

            @Override
            public void onStopRecording(int convId, int userId) {
                if (convId == conversationId && userId == otherUserId) {
                    mainHandler.post(() -> {
                        mainHandler.removeCallbacks(clearRemoteRecordingRunnable);
                        updateStatus();
                    });
                }
            }
        };
    }

    private void registerListeners() {
        if (listenersRegistered) return;
        chatSocket.addMessageReceivedListener(msgListener);
        chatSocket.addMessageSentListener(sentListener);
        chatSocket.addTypingListener(typingSocketListener);
        chatSocket.addOnlineStatusListener(onlineSocketListener);
        chatSocket.addReadReceiptListener(readSocketListener);
        chatSocket.addMessageDeletedListener(deletedSocketListener);
        chatSocket.addRecordingListener(recordingSocketListener);
        listenersRegistered = true;
    }

    private void unregisterListeners() {
        if (!listenersRegistered) return;
        chatSocket.removeMessageReceivedListener(msgListener);
        chatSocket.removeMessageSentListener(sentListener);
        chatSocket.removeTypingListener(typingSocketListener);
        chatSocket.removeOnlineStatusListener(onlineSocketListener);
        chatSocket.removeReadReceiptListener(readSocketListener);
        chatSocket.removeMessageDeletedListener(deletedSocketListener);
        chatSocket.removeRecordingListener(recordingSocketListener);
        listenersRegistered = false;
    }

    // ── Delete Message ──

    private void showDeleteDialog(MessagesAdapter.Message msg, int position) {
        new androidx.appcompat.app.AlertDialog.Builder(this, R.style.DarkAlertDialog)
                .setTitle(R.string.delete_message_title)
                .setMessage(getString(R.string.delete_for_all) + "?")
                .setPositiveButton(getString(R.string.delete_for_all), (dialog, which) -> {
                    if (position < messages.size() && messages.get(position).id == msg.id) {
                        messages.remove(position);
                        adapter.notifyItemRemoved(position);
                    }
                    if (msg.id > 0) {
                        ConversationCache.getInstance(this)
                                .deleteMessage(msg.id, conversationId);
                    }
                    chatSocket.deleteMessage(msg.id, myUserId, true);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ── Status display ──

    private void updateStatus() {
        if (otherIsOnline) {
            contactStatusView.setText("متصل");
            contactStatusView.setTextColor(getResources().getColor(R.color.chat_online, getTheme()));
        } else {
            String display = formatLastSeen(otherLastSeen);
            contactStatusView.setText(display);
            contactStatusView.setTextColor(getResources().getColor(R.color.chat_text_tertiary, getTheme()));
        }
    }

    private String formatLastSeen(String timestamp) {
        if (timestamp == null || timestamp.isEmpty()) return "\u063A\u064A\u0631 \u0645\u062A\u0635\u0644";
        try {
            Date date = ChatTimeUtils.parseServerUtc(timestamp);
            if (date == null) return "\u063A\u064A\u0631 \u0645\u062A\u0635\u0644";

            long diff = System.currentTimeMillis() - date.getTime();
            long minute = 60000;
            long hour = 3600000;
            long day = 86400000;

            SimpleDateFormat localTime = new SimpleDateFormat("hh:mm a", Locale.US);
            localTime.setTimeZone(java.util.TimeZone.getDefault());

            if (diff < minute) return "\u0622\u062E\u0631 \u0638\u0647\u0648\u0631 \u0627\u0644\u0622\u0646";
            else if (diff < hour) return "\u0622\u062E\u0631 \u0638\u0647\u0648\u0631 \u0645\u0646\u0630 " + (diff / minute) + " \u062F";
            else if (diff < day) return "\u0622\u062E\u0631 \u0638\u0647\u0648\u0631 " + localTime.format(date);
            else if (diff < 2 * day) return "\u0622\u062E\u0631 \u0638\u0647\u0648\u0631 \u0623\u0645\u0633 " + localTime.format(date);
            else {
                SimpleDateFormat full = new SimpleDateFormat("dd/MM hh:mm a", Locale.US);
                full.setTimeZone(java.util.TimeZone.getDefault());
                return "\u0622\u062E\u0631 \u0638\u0647\u0648\u0631 " + full.format(date);
            }
        } catch (Exception e) {
            return "\u063A\u064A\u0631 \u0645\u062A\u0635\u0644";
        }
    }

    // ── Messages ──

    private void loadMessageHistory() {
        ConversationCache cache = ConversationCache.getInstance(this);
        cache.setUserId(myUserId);

        JSONArray cached = cache.getMessages(conversationId, 50, 0);
        if (cached != null && cached.length() > 0 && messages.isEmpty()) {
            displayMessages(cached);
        }

        int perConvSeconds = AppLockManager.getInstance(this).getAutoDeleteSeconds(conversationId);
        int globalHours = AppLockManager.getInstance(this).getAutoDeleteHours();

        ChatApiService.getInstance().getMessages(conversationId, null, 50, globalHours, perConvSeconds, new ChatApiService.ApiCallback<JSONArray>() {
            @Override
            public void onSuccess(JSONArray result) {
                lastMessageFetchMs = System.currentTimeMillis();
                cache.deletePendingMessages(conversationId);
                cache.saveMessages(conversationId, result);
                displayMessages(result);
                historyLoaded = true;
            }
            @Override public void onError(String error) { Log.e(TAG, "Error loading messages: " + error); }
        });
    }

    private void displayMessages(JSONArray result) {
        try {
            java.util.List<MessagesAdapter.Message> newList = new java.util.ArrayList<>();
            String lastDateLabel = "";
            for (int i = 0; i < result.length(); i++) {
                MessagesAdapter.Message msg = parseMessage(result.getJSONObject(i));
                String dateLabel = getDateLabel(msg.rawDate);
                if (!dateLabel.isEmpty() && !dateLabel.equals(lastDateLabel)) {
                    newList.add(MessagesAdapter.Message.createDateSeparator(dateLabel));
                    lastDateLabel = dateLabel;
                }
                newList.add(msg);
            }

            for (MessagesAdapter.Message m : messages) {
                if (m.isSent && m.id < 0 && !m.isDateSeparator) {
                    newList.add(m);
                }
            }

            androidx.recyclerview.widget.DiffUtil.DiffResult diff =
                    androidx.recyclerview.widget.DiffUtil.calculateDiff(new MessageDiffCallback(messages, newList));
            messages.clear();
            messages.addAll(newList);
            diff.dispatchUpdatesTo(adapter);

            if (messages.size() > 0) messagesRecycler.scrollToPosition(messages.size() - 1);
            preCacheMedia(result);
        } catch (Exception e) {
            Log.e(TAG, "Error parsing messages: " + e.getMessage());
        }
    }

    private static class MessageDiffCallback extends androidx.recyclerview.widget.DiffUtil.Callback {
        private final java.util.List<MessagesAdapter.Message> oldList;
        private final java.util.List<MessagesAdapter.Message> newList;

        MessageDiffCallback(java.util.List<MessagesAdapter.Message> oldList, java.util.List<MessagesAdapter.Message> newList) {
            this.oldList = oldList;
            this.newList = newList;
        }

        @Override public int getOldListSize() { return oldList.size(); }
        @Override public int getNewListSize() { return newList.size(); }

        @Override
        public boolean areItemsTheSame(int oldPos, int newPos) {
            MessagesAdapter.Message oldMsg = oldList.get(oldPos);
            MessagesAdapter.Message newMsg = newList.get(newPos);
            if (oldMsg.isDateSeparator && newMsg.isDateSeparator) {
                return oldMsg.text != null && oldMsg.text.equals(newMsg.text);
            }
            if (oldMsg.isDateSeparator || newMsg.isDateSeparator) return false;
            return oldMsg.id == newMsg.id;
        }

        @Override
        public boolean areContentsTheSame(int oldPos, int newPos) {
            MessagesAdapter.Message oldMsg = oldList.get(oldPos);
            MessagesAdapter.Message newMsg = newList.get(newPos);
            if (oldMsg.isDateSeparator && newMsg.isDateSeparator) {
                return oldMsg.text != null && oldMsg.text.equals(newMsg.text);
            }
            return oldMsg.id == newMsg.id && oldMsg.isRead == newMsg.isRead
                    && java.util.Objects.equals(oldMsg.text, newMsg.text)
                    && java.util.Objects.equals(oldMsg.fileUrl, newMsg.fileUrl);
        }
    }

    private void preCacheSingleMedia(JSONObject msgObj) {
        String type = msgObj.optString("message_type", "text");
        if ("text".equals(type)) return;
        String fileUrl = msgObj.optString("file_url", "");
        if (fileUrl.isEmpty() || "null".equals(fileUrl)) return;
        if (!fileUrl.startsWith("http")) fileUrl = Constants.CHAT_SERVER_URL + fileUrl;
        MediaCacheManager mc = MediaCacheManager.getInstance(this);
        if (mc.getLocalFile(fileUrl) != null) return;
        final String url = fileUrl;
        final int msgId = msgObj.optInt("id", -1);
        final String cacheType = "image".equals(type) ? "image" : "voice".equals(type) ? "voice" : "document";
        final ConversationCache cache = ConversationCache.getInstance(this);
        new Thread(() -> {
            try {
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("User-Agent", "Android-Chat/1.0");
                conn.connect();
                if (conn.getResponseCode() == 200) {
                    java.io.File f = mc.cacheFromStream(url, conn.getInputStream(), cacheType);
                    if (f != null && msgId > 0) {
                        cache.setLocalFilePath(msgId, f.getAbsolutePath());
                    }
                }
                conn.disconnect();
            } catch (Exception e) { /* skip */ }
        }).start();
    }

    private void preCacheMedia(JSONArray result) {
        MediaCacheManager mc = MediaCacheManager.getInstance(this);
        ConversationCache cache = ConversationCache.getInstance(this);
        java.util.concurrent.ExecutorService cacheExecutor = java.util.concurrent.Executors.newFixedThreadPool(3);

        for (int i = 0; i < result.length(); i++) {
            try {
                JSONObject msgObj = result.getJSONObject(i);
                String type = msgObj.optString("message_type", "text");
                if ("text".equals(type) || "date_separator".equals(type)) continue;

                String fileUrl = msgObj.optString("file_url", "");
                if (fileUrl.isEmpty() || "null".equals(fileUrl)) continue;
                if (!fileUrl.startsWith("http")) {
                    fileUrl = Constants.CHAT_SERVER_URL + fileUrl;
                }

                java.io.File existingCached = mc.getLocalFile(fileUrl);
                final int msgId = msgObj.optInt("id", -1);

                if (existingCached != null) {
                    if (msgId > 0) {
                        cache.setLocalFilePath(msgId, existingCached.getAbsolutePath());
                    }
                    continue;
                }

                final String url = fileUrl;
                final String cacheType;
                switch (type) {
                    case "image": cacheType = "image"; break;
                    case "voice": cacheType = "voice"; break;
                    default: cacheType = "document"; break;
                }

                cacheExecutor.execute(() -> {
                    try {
                        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                        conn.setConnectTimeout(15000);
                        conn.setReadTimeout(30000);
                        conn.setInstanceFollowRedirects(true);
                        conn.setRequestProperty("User-Agent", "Android-Chat/1.0");
                        conn.connect();
                        if (conn.getResponseCode() == 200) {
                            java.io.File f = mc.cacheFromStream(url, conn.getInputStream(), cacheType);
                            if (f != null && msgId > 0) {
                                cache.setLocalFilePath(msgId, f.getAbsolutePath());
                            }
                        }
                        conn.disconnect();
                    } catch (Exception e) {
                        Log.d(TAG, "Pre-cache skip: " + e.getMessage());
                    }
                });
            } catch (Exception e) { /* skip */ }
        }
    }

    private String getDateLabel(String rawDate) {
        if (rawDate == null || rawDate.isEmpty()) return "";
        try {
            Date msgDate = ChatTimeUtils.parseServerUtc(rawDate);
            if (msgDate == null) return "";

            java.util.Calendar msgCal = java.util.Calendar.getInstance();
            msgCal.setTime(msgDate);

            java.util.Calendar today = java.util.Calendar.getInstance();
            today.set(java.util.Calendar.HOUR_OF_DAY, 0);
            today.set(java.util.Calendar.MINUTE, 0);
            today.set(java.util.Calendar.SECOND, 0);
            today.set(java.util.Calendar.MILLISECOND, 0);

            java.util.Calendar msgDay = java.util.Calendar.getInstance();
            msgDay.setTime(msgDate);
            msgDay.set(java.util.Calendar.HOUR_OF_DAY, 0);
            msgDay.set(java.util.Calendar.MINUTE, 0);
            msgDay.set(java.util.Calendar.SECOND, 0);
            msgDay.set(java.util.Calendar.MILLISECOND, 0);

            long diffDays = (today.getTimeInMillis() - msgDay.getTimeInMillis()) / 86400000;
            if (diffDays == 0) return getString(R.string.date_today);
            if (diffDays == 1) return getString(R.string.date_yesterday);
            SimpleDateFormat displayFmt = new SimpleDateFormat("dd/MM/yyyy", Locale.getDefault());
            displayFmt.setTimeZone(java.util.TimeZone.getDefault());
            return displayFmt.format(msgDay.getTime());
        } catch (Exception e) {
            return "";
        }
    }

    private void maybeInsertDateSeparator(String rawDate) {
        String dateLabel = getDateLabel(rawDate != null ? rawDate : ChatTimeUtils.nowServerUtcString());
        if (dateLabel.isEmpty()) return;

        String lastDate = "";
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).isDateSeparator) {
                lastDate = messages.get(i).text;
                break;
            }
        }
        if (!dateLabel.equals(lastDate)) {
            messages.add(MessagesAdapter.Message.createDateSeparator(dateLabel));
            adapter.notifyItemInserted(messages.size() - 1);
        }
    }

    private void sendTextMessage() {
        String text = messageInput.getText().toString().trim();
        if (text.isEmpty() || conversationId <= 0 || myUserId <= 0) return;

        String now = ChatTimeUtils.nowServerUtcString();
        maybeInsertDateSeparator(now);

        String timeStr = ChatTimeUtils.formatLocalTimeNow();
        MessagesAdapter.Message optMsg = new MessagesAdapter.Message(text, timeStr, true, false);

        ConversationCache cache = ConversationCache.getInstance(this);
        int tempId = cache.savePendingMessage(conversationId, myUserId, text,
                "text", null, null, 0, 0, null);
        optMsg.id = tempId;

        messages.add(optMsg);
        adapter.notifyItemInserted(messages.size() - 1);
        messagesRecycler.scrollToPosition(messages.size() - 1);
        messageInput.setText("");

        cache.updateConversationLastMessage(conversationId, text, now, myUserId);

        chatSocket.sendMessage(conversationId, myUserId, text);
        iAmTyping = false;
        typingHandler.removeCallbacks(stopTypingRunnable);
    }

    private MessagesAdapter.Message parseMessage(JSONObject msgObj) throws Exception {
        int msgId = msgObj.optInt("id", -1);
        String text = msgObj.optString("text", "");
        String timestamp = msgObj.optString("timestamp", "");
        int senderId = msgObj.getInt("sender_id");
        boolean isSent = (senderId == myUserId);
        boolean isRead = msgObj.optInt("is_read", 0) == 1;
        String messageType = msgObj.optString("message_type", "text");
        String fileUrl = msgObj.optString("file_url", null);
        String fileName = msgObj.optString("file_name", null);
        long fileSize = msgObj.optLong("file_size", 0);
        int duration = msgObj.optInt("duration", 0);

        if (fileUrl != null && !fileUrl.isEmpty() && !fileUrl.startsWith("http")) {
            fileUrl = Constants.CHAT_SERVER_URL + fileUrl;
        }
        if ("null".equals(fileUrl)) fileUrl = null;

        String timeStr = ChatTimeUtils.formatLocalTime(timestamp);
        if (timeStr.isEmpty()) {
            timeStr = timestamp;
        }

        MessagesAdapter.Message msg = new MessagesAdapter.Message(text, timeStr, isSent, isRead, messageType, fileUrl, fileName, fileSize, duration);
        msg.id = msgId;
        msg.rawDate = timestamp;

        String localPath = msgObj.optString("local_file_path", "");
        if (!localPath.isEmpty()) {
            msg.localFilePath = localPath;
        }
        return msg;
    }

    // ── Lifecycle ──

    @Override
    protected void onResume() {
        super.onResume();
        registerListeners();
        if (!ensureConversationUnlocked()) return;
        
        // Ensure user appears online when viewing a conversation
        ChatPrefs chatPrefs = new ChatPrefs(this);
        if (chatPrefs.isAuthenticated() && chatPrefs.getUserId() > 0) {
            // Explicitly set online status when entering conversation
            chatSocket.setAppForeground(true);
            // Ensure heartbeat is active to keep user online
            chatSocket.startHeartbeat();
        }
        
        // Suppress notifications for this conversation while it's open
        ChatNotificationManager.getInstance().setActiveConversation(conversationId);
        if (conversationId > 0) {
            ChatNotificationManager.getInstance().cancelNotification(this, conversationId);
        }
        if (otherUserId > 0) chatSocket.requestStatus(otherUserId);
        if (conversationId > 0) {
            boolean stale = (System.currentTimeMillis() - lastMessageFetchMs) > 10_000;
            if (historyLoaded && stale) loadMessageHistory();
            chatSocket.markAsRead(conversationId);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterListeners();
        ChatNotificationManager.getInstance().clearActiveConversation();
        mainHandler.removeCallbacks(clearRemoteTypingRunnable);
        mainHandler.removeCallbacks(clearRemoteRecordingRunnable);
        typingHandler.removeCallbacks(stopTypingRunnable);
        saveCurrentMessagesToCache();
    }

    private void saveCurrentMessagesToCache() {
        if (conversationId <= 0 || messages.isEmpty()) return;
        try {
            JSONArray arr = new JSONArray();
            for (MessagesAdapter.Message m : messages) {
                if (m.isDateSeparator) continue;
                JSONObject obj = new JSONObject();
                obj.put("id", m.id);
                obj.put("text", m.text != null ? m.text : "");
                obj.put("timestamp", m.rawDate != null ? m.rawDate : "");
                obj.put("sender_id", m.isSent ? myUserId : otherUserId);
                obj.put("is_read", m.isRead ? 1 : 0);
                obj.put("message_type", m.messageType != null ? m.messageType : "text");
                String url = m.serverFileUrl != null ? m.serverFileUrl : m.fileUrl;
                obj.put("file_url", url != null ? url : "");
                obj.put("file_name", m.fileName != null ? m.fileName : "");
                obj.put("file_size", m.fileSize);
                obj.put("duration", m.duration);
                obj.put("local_file_path", m.localFilePath != null ? m.localFilePath : "");
                arr.put(obj);
            }
            ConversationCache cache = ConversationCache.getInstance(this);
            cache.setUserId(myUserId);
            cache.saveMessages(conversationId, arr);
        } catch (Exception e) {
            Log.e(TAG, "saveCurrentMessagesToCache error: " + e.getMessage());
        }
    }


    private void showMoreMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(0, 1, 0, isMuted ? "\u0625\u0644\u063A\u0627\u0621 \u0627\u0644\u0643\u062A\u0645" : "\u0643\u062A\u0645");
        popup.getMenu().add(0, 2, 1, "\u062D\u0630\u0641 \u0627\u0644\u0645\u062D\u0627\u062F\u062B\u0629");
        popup.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                toggleMute();
                return true;
            } else if (item.getItemId() == 2) {
                showDeleteConversationDialog();
                return true;
            }
            return false;
        });
        popup.show();
    }

    private void toggleMute() {
        ChatApiService api = ChatApiService.getInstance();
        if (isMuted) {
            api.unmuteConversation(myUserId, conversationId, new ChatApiService.ApiCallback<JSONObject>() {
                @Override public void onSuccess(JSONObject r) {
                    isMuted = false;
                    ChatNotificationManager.getInstance().removeMutedConversation(conversationId);
                    Toast.makeText(ConversationActivity.this, "\u062A\u0645 \u0625\u0644\u063A\u0627\u0621 \u0627\u0644\u0643\u062A\u0645", Toast.LENGTH_SHORT).show();
                }
                @Override public void onError(String e) { Toast.makeText(ConversationActivity.this, e, Toast.LENGTH_SHORT).show(); }
            });
        } else {
            api.muteConversation(myUserId, conversationId, new ChatApiService.ApiCallback<JSONObject>() {
                @Override public void onSuccess(JSONObject r) {
                    isMuted = true;
                    ChatNotificationManager.getInstance().addMutedConversation(conversationId);
                    Toast.makeText(ConversationActivity.this, "\u062A\u0645 \u0643\u062A\u0645 \u0627\u0644\u0645\u062D\u0627\u062F\u062B\u0629", Toast.LENGTH_SHORT).show();
                }
                @Override public void onError(String e) { Toast.makeText(ConversationActivity.this, e, Toast.LENGTH_SHORT).show(); }
            });
        }
    }

    private void showDeleteConversationDialog() {
        new AlertDialog.Builder(this, R.style.DarkAlertDialog)
            .setTitle("\u062D\u0630\u0641 \u0627\u0644\u0645\u062D\u0627\u062F\u062B\u0629")
            .setMessage("\u0647\u0644 \u062A\u0631\u064A\u062F \u062D\u0630\u0641 \u0647\u0630\u0647 \u0627\u0644\u0645\u062D\u0627\u062F\u062B\u0629\u061F")
            .setPositiveButton("\u062D\u0630\u0641", (d, w) -> {
                ConversationCache.getInstance(ConversationActivity.this).clearConversationMessages(conversationId);
                Toast.makeText(ConversationActivity.this, "\u062A\u0645 \u062D\u0630\u0641 \u0627\u0644\u0645\u062D\u0627\u062F\u062B\u0629", Toast.LENGTH_SHORT).show();
                finish();
                ChatApiService.getInstance().deleteConversation(conversationId, myUserId, new ChatApiService.ApiCallback<JSONObject>() {
                    @Override public void onSuccess(JSONObject r) { }
                    @Override public void onError(String e) { }
                });
            })
            .setNegativeButton("\u0625\u0644\u063A\u0627\u0621", null)
            .show();
    }
    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterListeners();
        ChatNotificationManager.getInstance().clearActiveConversation();
        mainHandler.removeCallbacks(clearRemoteTypingRunnable);
        mainHandler.removeCallbacks(clearRemoteRecordingRunnable);
        typingHandler.removeCallbacks(stopTypingRunnable);
        recordingTimerHandler.removeCallbacksAndMessages(null);
        if (isRecording) voiceRecorder.cancelRecording();
        adapter.release();
    }
}
