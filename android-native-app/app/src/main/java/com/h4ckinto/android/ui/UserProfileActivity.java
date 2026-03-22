package com.h4ckinto.android.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Outline;
import android.app.KeyguardManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.text.InputType;
import android.widget.ImageView;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.h4ckinto.android.R;
import com.h4ckinto.android.network.ChatApiService;
import com.h4ckinto.android.network.ChatSocketManager;
import com.h4ckinto.android.utils.AppLockManager;
import com.h4ckinto.android.utils.ChatPrefs;
import com.h4ckinto.android.utils.ConversationCache;
import com.h4ckinto.android.utils.ImageLoader;


import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class UserProfileActivity extends AppCompatActivity {

    private int userId;
    private String userName;
    private String userInitials;
    private String avatarColor;
    private String avatarUrl;
    private int conversationId;

    private TextView statusView;
    private ChatSocketManager.OnOnlineStatusListener onlineListener;
    private int myUserId;
    private boolean isBlocked = false;
    private boolean isMuted = false;
    private TextView blockButton;
    private TextView muteText;
    private TextView chatLockValue;
    private String fetchedUsernameForQr = "";

    public static Intent create(Context context, int userId, String name, String initials,
                                String avatarColor, String avatarUrl, int conversationId) {
        Intent intent = new Intent(context, UserProfileActivity.class);
        intent.putExtra("user_id", userId);
        intent.putExtra("user_name", name);
        intent.putExtra("user_initials", initials);
        intent.putExtra("avatar_color", avatarColor);
        intent.putExtra("avatar_url", avatarUrl != null ? avatarUrl : "");
        intent.putExtra("conversation_id", conversationId);
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(getResources().getColor(R.color.chat_surface, getTheme()));
        getWindow().setNavigationBarColor(getResources().getColor(R.color.chat_background, getTheme()));

        setContentView(R.layout.activity_user_profile);

        userId = getIntent().getIntExtra("user_id", -1);
        userName = getIntent().getStringExtra("user_name");
        userInitials = getIntent().getStringExtra("user_initials");
        avatarColor = getIntent().getStringExtra("avatar_color");
        avatarUrl = getIntent().getStringExtra("avatar_url");
        conversationId = getIntent().getIntExtra("conversation_id", -1);

        if (userName == null) userName = "";
        if (userInitials == null) userInitials = userName.length() >= 2 ? userName.substring(0, 2) : userName;
        if (avatarColor == null) avatarColor = "#3B82F6";

        myUserId = new ChatPrefs(this).getUserId();
        setupUI();
        checkBlockStatus();
        checkMuteStatus();
        fetchUserInfo();
        fetchSharedMedia();
    }

    private void setupUI() {
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        TextView initialsView = findViewById(R.id.profile_avatar_initials);
        initialsView.setText(userInitials);
        try {
            initialsView.getBackground().setTint(Color.parseColor(avatarColor));
        } catch (Exception e) {
            initialsView.getBackground().setTint(Color.parseColor("#3B82F6"));
        }

        ImageView avatarImage = findViewById(R.id.profile_avatar_image);
        if (avatarUrl != null && !avatarUrl.isEmpty()) {
            avatarImage.setVisibility(View.VISIBLE);
            initialsView.setVisibility(View.INVISIBLE);
            avatarImage.setClipToOutline(true);
            avatarImage.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setOval(0, 0, view.getWidth(), view.getHeight());
                }
            });
            ImageLoader.getInstance().load(avatarUrl, avatarImage);

            avatarImage.setOnClickListener(v -> {
                Intent viewIntent = new Intent(this, ImageViewerActivity.class);
                viewIntent.putExtra("image_url", avatarUrl);
                startActivity(viewIntent);
            });
        }

        TextView nameView = findViewById(R.id.profile_name);
        nameView.setText(userName);

        statusView = findViewById(R.id.profile_status);

        findViewById(R.id.btn_message).setOnClickListener(v -> {
            finish();
        });

        blockButton = findViewById(R.id.btn_block_text);
        blockButton.setOnClickListener(v -> showBlockConfirmation());

        muteText = findViewById(R.id.profile_mute_text);

        String finalInitials = userInitials;
        String finalColor = avatarColor;
        findViewById(R.id.btn_call).setOnClickListener(v -> {
            Intent callIntent = AudioCallActivity.createOutgoing(
                    this, userId, userName, finalInitials, finalColor);
            startActivity(callIntent);
        });

        findViewById(R.id.btn_qr_code).setOnClickListener(v -> showUserQrCode());

        findViewById(R.id.row_profile_temp_messages).setOnClickListener(v -> showTimedMessagesDialog());
        findViewById(R.id.row_profile_chat_lock).setOnClickListener(v -> showChatLockDialog());
        findViewById(R.id.row_profile_encryption).setOnClickListener(v -> showEncryptionKeysDialog());
        findViewById(R.id.row_profile_clear_chat).setOnClickListener(v -> showClearChatDialog());
        findViewById(R.id.row_profile_mute).setOnClickListener(v -> toggleMute());
        chatLockValue = findViewById(R.id.profile_chat_lock_value);

        updateTimedMessagesLabel();
        updateChatLockLabel();
    }

    private void updateTimedMessagesLabel() {
        TextView tempValue = findViewById(R.id.profile_temp_value);
        if (tempValue == null || conversationId <= 0) return;
        int seconds = AppLockManager.getInstance(this).getAutoDeleteSeconds(conversationId);
        if (seconds <= 0) {
            tempValue.setText("\u0625\u064A\u0642\u0627\u0641");
        } else if (seconds <= 30) {
            tempValue.setText("30 \u062B\u0627\u0646\u064A\u0629");
        } else if (seconds <= 300) {
            tempValue.setText("5 \u062F\u0642\u0627\u0626\u0642");
        } else if (seconds <= 3600) {
            tempValue.setText("1 \u0633\u0627\u0639\u0629");
        } else if (seconds <= 86400) {
            tempValue.setText("24 \u0633\u0627\u0639\u0629");
        } else {
            tempValue.setText("7 \u0623\u064A\u0627\u0645");
        }
    }

    private void fetchUserInfo() {
        String serverUrl = com.h4ckinto.android.utils.Constants.CHAT_SERVER_URL;
        if (serverUrl == null || serverUrl.isEmpty()) return;

        new Thread(() -> {
            try {
                okhttp3.OkHttpClient client = ChatApiService.getInstance().getClient();
                okhttp3.Request request = new okhttp3.Request.Builder()
                        .url(serverUrl + "/api/auth/user/" + userId)
                        .get()
                        .build();
                okhttp3.Response response = client.newCall(request).execute();
                if (response.isSuccessful() && response.body() != null) {
                    JSONObject json = new JSONObject(response.body().string());
                    if (json.optBoolean("success")) {
                        JSONObject user = json.getJSONObject("user");
                        boolean isOnline = user.optInt("is_online", 0) == 1;
                        String lastSeen = user.optString("last_seen", "");
                        String fetchedAvatar = user.optString("avatar_url", "");
                        String displayName = user.optString("display_name", userName);
                        String fetchedUsername = user.optString("username", "");
                        String fetchedRank = user.optString("rank", "");
                        String fetchedJobTitle = user.optString("job_title", "");

                        runOnUiThread(() -> {
                            if (fetchedUsername != null && !fetchedUsername.isEmpty()) {
                                fetchedUsernameForQr = fetchedUsername;
                            }

                            TextView nameView = findViewById(R.id.profile_name);
                            if (displayName != null && !displayName.isEmpty()) {
                                nameView.setText(displayName);
                            }

                            TextView rankTitleView = findViewById(R.id.profile_rank_title);
                            if (rankTitleView != null) {
                                StringBuilder rt = new StringBuilder();
                                if (fetchedRank != null && !fetchedRank.isEmpty()) rt.append(fetchedRank);
                                if (fetchedJobTitle != null && !fetchedJobTitle.isEmpty()) {
                                    if (rt.length() > 0) rt.append(" \u00B7 ");
                                    rt.append(fetchedJobTitle);
                                }
                                if (rt.length() > 0) {
                                    rankTitleView.setText(rt.toString());
                                    rankTitleView.setVisibility(View.VISIBLE);
                                }
                            }

                            TextView usernameView = findViewById(R.id.profile_username);
                            if (usernameView != null && fetchedUsername != null && !fetchedUsername.isEmpty()) {
                                usernameView.setText("@" + fetchedUsername);
                                usernameView.setVisibility(View.VISIBLE);
                            }

                            TextView userIdView = findViewById(R.id.profile_user_id);
                            if (fetchedUsername != null && !fetchedUsername.isEmpty()) {
                                userIdView.setText("@" + fetchedUsername);
                            } else {
                                userIdView.setText(String.valueOf(userId));
                            }

                            View rankRow = findViewById(R.id.row_profile_rank);
                            View rankDivider = findViewById(R.id.divider_rank);
                            TextView rankValue = findViewById(R.id.profile_rank_value);
                            if (fetchedRank != null && !fetchedRank.isEmpty()) {
                                StringBuilder rankDisplay = new StringBuilder(fetchedRank);
                                if (fetchedJobTitle != null && !fetchedJobTitle.isEmpty()) {
                                    rankDisplay.append(" \u00B7 ").append(fetchedJobTitle);
                                }
                                rankValue.setText(rankDisplay.toString());
                                rankRow.setVisibility(View.VISIBLE);
                                rankDivider.setVisibility(View.VISIBLE);
                            }

                            if (isOnline) {
                                statusView.setText(R.string.profile_online);
                                statusView.setTextColor(getResources().getColor(R.color.chat_online, getTheme()));
                            } else {
                                if (lastSeen != null && !lastSeen.isEmpty()) {
                                    statusView.setText(getString(R.string.profile_last_seen, formatLastSeen(lastSeen)));
                                } else {
                                    statusView.setText(R.string.profile_offline);
                                }
                                statusView.setTextColor(getResources().getColor(R.color.chat_text_tertiary, getTheme()));
                            }

                            if (fetchedAvatar != null && !fetchedAvatar.isEmpty() &&
                                    (avatarUrl == null || avatarUrl.isEmpty())) {
                                ImageView avatarImage = findViewById(R.id.profile_avatar_image);
                                TextView initialsView = findViewById(R.id.profile_avatar_initials);
                                avatarImage.setVisibility(View.VISIBLE);
                                initialsView.setVisibility(View.INVISIBLE);
                                avatarImage.setClipToOutline(true);
                                avatarImage.setOutlineProvider(new ViewOutlineProvider() {
                                    @Override
                                    public void getOutline(View view, Outline outline) {
                                        outline.setOval(0, 0, view.getWidth(), view.getHeight());
                                    }
                                });
                                ImageLoader.getInstance().load(fetchedAvatar, avatarImage);
                            }
                        });
                    }
                }
            } catch (Exception e) {
                // Silently fail
            }
        }).start();
    }

    private String formatLastSeen(String isoDate) {
        try {
            SimpleDateFormat isoFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            Date date = isoFormat.parse(isoDate.replace("Z", "").split("\\.")[0]);
            if (date == null) return isoDate;

            long diff = System.currentTimeMillis() - date.getTime();
            long minutes = diff / 60000;
            long hours = minutes / 60;
            long days = hours / 24;

            if (minutes < 1) return "\u0627\u0644\u0622\u0646";
            if (minutes < 60) return "\u0645\u0646\u0630 " + minutes + " \u062F\u0642\u064A\u0642\u0629";
            if (hours < 24) {
                SimpleDateFormat timeFormat = new SimpleDateFormat("hh:mm a", Locale.US);
                return timeFormat.format(date);
            }
            if (days == 1) return "\u0623\u0645\u0633";
            if (days < 7) return "\u0645\u0646\u0630 " + days + " \u0623\u064A\u0627\u0645";

            SimpleDateFormat displayFormat = new SimpleDateFormat("dd/MM/yyyy hh:mm a", Locale.US);
            return displayFormat.format(date);
        } catch (Exception e) {
            return isoDate;
        }
    }

    private void fetchSharedMedia() {
        if (conversationId <= 0) return;
        String serverUrl = com.h4ckinto.android.utils.Constants.CHAT_SERVER_URL;

        new Thread(() -> {
            try {
                okhttp3.OkHttpClient client = ChatApiService.getInstance().getClient();
                okhttp3.Request request = new okhttp3.Request.Builder()
                        .url(serverUrl + "/api/messages/shared-media/" + conversationId + "?limit=50")
                        .get()
                        .build();
                okhttp3.Response response = client.newCall(request).execute();
                if (response.isSuccessful() && response.body() != null) {
                    JSONObject json = new JSONObject(response.body().string());
                    if (json.optBoolean("success")) {
                        JSONObject summary = json.getJSONObject("summary");
                        int images = summary.optInt("images", 0);
                        int audio = summary.optInt("audio", 0);
                        int documents = summary.optInt("documents", 0);
                        int total = summary.optInt("total", 0);

                        JSONArray mediaArr = json.getJSONArray("media");
                        List<MediaItem> mediaItems = new ArrayList<>();
                        for (int i = 0; i < mediaArr.length(); i++) {
                            JSONObject m = mediaArr.getJSONObject(i);
                            MediaItem item = new MediaItem();
                            item.type = m.optString("message_type", "");
                            item.fileUrl = m.optString("file_url", "");
                            item.fileName = m.optString("file_name", "");
                            mediaItems.add(item);
                        }

                        runOnUiThread(() -> {
                            TextView mediaCountView = findViewById(R.id.profile_media_count);

                            if (total == 0) {
                                return;
                            }

                            StringBuilder sb = new StringBuilder();
                            if (images > 0) sb.append(images).append(" \u0635\u0648\u0631\u0629");
                            if (audio > 0) {
                                if (sb.length() > 0) sb.append(" \u00B7 ");
                                sb.append(audio).append(" \u0635\u0648\u062A\u064A\u0629");
                            }
                            if (documents > 0) {
                                if (sb.length() > 0) sb.append(" \u00B7 ");
                                sb.append(documents).append(" \u0645\u0633\u062A\u0646\u062F");
                            }
                            mediaCountView.setText(sb.toString());
                            mediaCountView.setTextColor(getResources().getColor(R.color.chat_text_primary, getTheme()));

                            View summaryRow = findViewById(R.id.media_summary_row);
                            summaryRow.setVisibility(View.VISIBLE);

                            TextView imgCount = findViewById(R.id.media_images_count);
                            TextView audCount = findViewById(R.id.media_audio_count);
                            TextView docCount = findViewById(R.id.media_docs_count);
                            imgCount.setText(String.valueOf(images));
                            audCount.setText(String.valueOf(audio));
                            docCount.setText(String.valueOf(documents));

                            List<MediaItem> imageItems = new ArrayList<>();
                            for (MediaItem mi : mediaItems) {
                                if ("image".equals(mi.type) && mi.fileUrl != null && !mi.fileUrl.isEmpty()) {
                                    imageItems.add(mi);
                                }
                            }

                            if (!imageItems.isEmpty()) {
                                androidx.recyclerview.widget.RecyclerView grid = findViewById(R.id.media_grid);
                                grid.setVisibility(View.VISIBLE);
                                grid.setLayoutManager(new androidx.recyclerview.widget.GridLayoutManager(this, 3));
                                grid.setAdapter(new MediaGridAdapter(imageItems));
                            }
                        });
                    }
                }
            } catch (Exception e) {
                // Silently fail
            }
        }).start();
    }

    static class MediaItem {
        String type;
        String fileUrl;
        String fileName;
    }

    class MediaGridAdapter extends androidx.recyclerview.widget.RecyclerView.Adapter<MediaGridAdapter.VH> {
        private final List<MediaItem> items;

        MediaGridAdapter(List<MediaItem> items) {
            this.items = items;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull android.view.ViewGroup parent, int viewType) {
            View v = android.view.LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_media_grid, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            MediaItem item = items.get(position);
            ImageLoader.getInstance().load(item.fileUrl, holder.thumb);
            holder.thumb.setOnClickListener(v -> {
                Intent viewIntent = new Intent(UserProfileActivity.this, ImageViewerActivity.class);
                viewIntent.putExtra("image_url", item.fileUrl);
                startActivity(viewIntent);
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class VH extends androidx.recyclerview.widget.RecyclerView.ViewHolder {
            ImageView thumb;
            VH(@NonNull View itemView) {
                super(itemView);
                thumb = itemView.findViewById(R.id.media_thumb);
            }
        }
    }

    private void checkBlockStatus() {
        ChatApiService.getInstance().checkBlocked(myUserId, userId, new ChatApiService.ApiCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean blocked) {
                isBlocked = blocked;
                updateBlockButton();
            }
            @Override
            public void onError(String e) { /* ignore */ }
        });
    }

    private void updateBlockButton() {
        if (blockButton == null) return;
        if (isBlocked) {
            blockButton.setText(R.string.unblock_user);
            blockButton.setTextColor(getResources().getColor(R.color.chat_accent, getTheme()));
        } else {
            blockButton.setText(R.string.block_user);
            blockButton.setTextColor(android.graphics.Color.parseColor("#EF4444"));
        }
    }

    private void showBlockConfirmation() {
        if (isBlocked) {
            new AlertDialog.Builder(this, R.style.DarkAlertDialog)
                .setTitle(R.string.unblock_confirm_title)
                .setMessage(R.string.unblock_confirm_message)
                .setPositiveButton(R.string.unblock_user, (d, w) -> performUnblock())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        } else {
            new AlertDialog.Builder(this, R.style.DarkAlertDialog)
                .setTitle(R.string.block_confirm_title)
                .setMessage(R.string.block_confirm_message)
                .setPositiveButton(R.string.block_user, (d, w) -> performBlock())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        }
    }

    private void performBlock() {
        ChatApiService.getInstance().blockUser(myUserId, userId, new ChatApiService.ApiCallback<org.json.JSONObject>() {
            @Override
            public void onSuccess(org.json.JSONObject r) {
                isBlocked = true;
                updateBlockButton();
                Toast.makeText(UserProfileActivity.this, "\u062A\u0645 \u062D\u0638\u0631 \u0627\u0644\u0645\u0633\u062A\u062E\u062F\u0645", Toast.LENGTH_SHORT).show();
            }
            @Override
            public void onError(String e) { Toast.makeText(UserProfileActivity.this, e, Toast.LENGTH_SHORT).show(); }
        });
    }

    private void performUnblock() {
        ChatApiService.getInstance().unblockUser(myUserId, userId, new ChatApiService.ApiCallback<org.json.JSONObject>() {
            @Override
            public void onSuccess(org.json.JSONObject r) {
                isBlocked = false;
                updateBlockButton();
                Toast.makeText(UserProfileActivity.this, "\u062A\u0645 \u0625\u0644\u063A\u0627\u0621 \u062D\u0638\u0631 \u0627\u0644\u0645\u0633\u062A\u062E\u062F\u0645", Toast.LENGTH_SHORT).show();
            }
            @Override
            public void onError(String e) { Toast.makeText(UserProfileActivity.this, e, Toast.LENGTH_SHORT).show(); }
        });
    }
    private void checkMuteStatus() {
        if (conversationId <= 0) return;
        ChatApiService.getInstance().checkMuted(myUserId, conversationId, new ChatApiService.ApiCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean muted) {
                isMuted = muted;
                updateMuteButton();
            }
            @Override
            public void onError(String e) { /* ignore */ }
        });
    }

    private void updateMuteButton() {
        if (muteText == null) return;
        muteText.setText(isMuted
                ? "\u0625\u0644\u063A\u0627\u0621 \u0643\u062A\u0645 \u0627\u0644\u0625\u0634\u0639\u0627\u0631\u0627\u062A"
                : "\u0643\u062A\u0645 \u0627\u0644\u0625\u0634\u0639\u0627\u0631\u0627\u062A");
    }

    private void toggleMute() {
        if (conversationId <= 0) {
            Toast.makeText(this, "\u0644\u0627 \u062A\u0648\u062C\u062F \u0645\u062D\u0627\u062F\u062B\u0629", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean newMuted = !isMuted;

        isMuted = newMuted;
        updateMuteButton();
        ConversationCache.getInstance(this).updateMuteStatus(conversationId, newMuted);

        ChatApiService.ApiCallback<JSONObject> callback = new ChatApiService.ApiCallback<JSONObject>() {
            @Override
            public void onSuccess(JSONObject r) {
                Toast.makeText(UserProfileActivity.this,
                        newMuted ? "\u062A\u0645 \u0643\u062A\u0645 \u0627\u0644\u0625\u0634\u0639\u0627\u0631\u0627\u062A" : "\u062A\u0645 \u0625\u0644\u063A\u0627\u0621 \u0627\u0644\u0643\u062A\u0645",
                        Toast.LENGTH_SHORT).show();
            }
            @Override
            public void onError(String e) {
                isMuted = !newMuted;
                updateMuteButton();
                ConversationCache.getInstance(UserProfileActivity.this).updateMuteStatus(conversationId, !newMuted);
                Toast.makeText(UserProfileActivity.this, e, Toast.LENGTH_SHORT).show();
            }
        };

        if (newMuted) {
            ChatApiService.getInstance().muteConversation(myUserId, conversationId, callback);
        } else {
            ChatApiService.getInstance().unmuteConversation(myUserId, conversationId, callback);
        }
    }

    private void showClearChatDialog() {
        if (conversationId <= 0) {
            Toast.makeText(this, "\u0644\u0627 \u062A\u0648\u062C\u062F \u0645\u062D\u0627\u062F\u062B\u0629", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this, R.style.DarkAlertDialog)
                .setTitle("\u0645\u0633\u062D \u0645\u062D\u062A\u0648\u0649 \u0627\u0644\u062F\u0631\u062F\u0634\u0629")
                .setMessage("\u0647\u0644 \u0623\u0646\u062A \u0645\u062A\u0623\u0643\u062F \u0645\u0646 \u0645\u0633\u062D \u062C\u0645\u064A\u0639 \u0627\u0644\u0631\u0633\u0627\u0626\u0644\u061F \u0644\u0627 \u064A\u0645\u0643\u0646 \u0627\u0644\u062A\u0631\u0627\u062C\u0639.")
                .setPositiveButton("\u0645\u0633\u062D", (d, w) -> {
                    ConversationCache.getInstance(this).clearConversationMessages(conversationId);
                    setResult(RESULT_OK);

                    ChatApiService.getInstance().clearChat(conversationId, myUserId, new ChatApiService.ApiCallback<JSONObject>() {
                        @Override
                        public void onSuccess(JSONObject r) {
                            Toast.makeText(UserProfileActivity.this, "\u062A\u0645 \u0645\u0633\u062D \u0627\u0644\u062F\u0631\u062F\u0634\u0629", Toast.LENGTH_SHORT).show();
                        }
                        @Override
                        public void onError(String e) { Toast.makeText(UserProfileActivity.this, e, Toast.LENGTH_SHORT).show(); }
                    });
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showTimedMessagesDialog() {
        String[] options = {
                "\u0625\u064A\u0642\u0627\u0641", "30 \u062B\u0627\u0646\u064A\u0629", "5 \u062F\u0642\u0627\u0626\u0642",
                "1 \u0633\u0627\u0639\u0629", "24 \u0633\u0627\u0639\u0629", "7 \u0623\u064A\u0627\u0645"
        };
        int[] values = {0, 30, 300, 3600, 86400, 604800};

        int currentSeconds = AppLockManager.getInstance(this).getAutoDeleteSeconds(conversationId);
        int checkedIndex = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == currentSeconds) { checkedIndex = i; break; }
        }

        new AlertDialog.Builder(this, R.style.DarkAlertDialog)
                .setTitle("\u0627\u0644\u0631\u0633\u0627\u0626\u0644 \u0627\u0644\u0645\u0624\u0642\u062A\u0629")
                .setSingleChoiceItems(options, checkedIndex, (d, which) -> {
                    int seconds = values[which];
                    AppLockManager.getInstance(this).setAutoDeleteSeconds(conversationId, seconds);

                    TextView tempValue = findViewById(R.id.profile_temp_value);
                    if (tempValue != null) {
                        tempValue.setText(seconds == 0 ? "\u0625\u064A\u0642\u0627\u0641" : options[which]);
                    }
                    Toast.makeText(this,
                            seconds == 0 ? "\u062A\u0645 \u0625\u064A\u0642\u0627\u0641 \u0627\u0644\u0631\u0633\u0627\u0626\u0644 \u0627\u0644\u0645\u0624\u0642\u062A\u0629"
                                    : "\u0633\u064A\u062A\u0645 \u062D\u0630\u0641 \u0627\u0644\u0631\u0633\u0627\u0626\u0644 \u0628\u0639\u062F " + options[which],
                            Toast.LENGTH_SHORT).show();
                    d.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showEncryptionKeysDialog() {
        new AlertDialog.Builder(this, R.style.DarkAlertDialog)
                .setTitle("\u0645\u0641\u0627\u062A\u064A\u062D \u0627\u0644\u062A\u0634\u0641\u064A\u0631")
                .setMessage("\u0627\u0644\u0631\u0633\u0627\u0626\u0644 \u0648\u0627\u0644\u0645\u0643\u0627\u0644\u0645\u0627\u062A \u0645\u0634\u0641\u0631\u0629 \u0628\u064A\u0646 \u0627\u0644\u0637\u0631\u0641\u064A\u0646. \u0644\u0627 \u064A\u0645\u0643\u0646 \u0644\u0623\u064A \u0637\u0631\u0641 \u062B\u0627\u0644\u062B \u0642\u0631\u0627\u0621\u0629 \u0645\u062D\u0627\u062F\u062B\u0627\u062A\u0643.")
                .setPositiveButton("\u062D\u0633\u0646\u0627\u064B", null)
                .show();
    }

    private void updateChatLockLabel() {
        if (chatLockValue == null) return;
        if (conversationId <= 0) {
            chatLockValue.setText("غير متاح");
            return;
        }
        AppLockManager lockMgr = AppLockManager.getInstance(this);
        if (lockMgr.isChatLockPin(conversationId)) {
            chatLockValue.setText("رمز مرور");
        } else if (lockMgr.isChatLockDevice(conversationId)) {
            chatLockValue.setText("قفل الجهاز");
        } else {
            chatLockValue.setText("إيقاف");
        }
    }

    private void showChatLockDialog() {
        if (conversationId <= 0) {
            Toast.makeText(this, "لا توجد محادثة", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] options = {"إيقاف", "رمز مرور", "قفل الجهاز"};
        AppLockManager lockMgr = AppLockManager.getInstance(this);
        int checked = 0;
        if (lockMgr.isChatLockPin(conversationId)) checked = 1;
        else if (lockMgr.isChatLockDevice(conversationId)) checked = 2;

        new AlertDialog.Builder(this, R.style.DarkAlertDialog)
                .setTitle("قفل المحادثة")
                .setSingleChoiceItems(options, checked, (d, which) -> {
                    if (which == 0) {
                        lockMgr.setChatLockDisabled(conversationId);
                        updateChatLockLabel();
                        Toast.makeText(this, "تم إيقاف قفل المحادثة", Toast.LENGTH_SHORT).show();
                        d.dismiss();
                    } else if (which == 1) {
                        d.dismiss();
                        showSetChatPinDialog();
                    } else {
                        KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
                        if (km == null || !km.isKeyguardSecure()) {
                            Toast.makeText(this, "قفل الجهاز غير مُفعّل", Toast.LENGTH_SHORT).show();
                            d.dismiss();
                            return;
                        }
                        lockMgr.setChatLockDevice(conversationId);
                        updateChatLockLabel();
                        Toast.makeText(this, "تم تفعيل قفل الجهاز للمحادثة", Toast.LENGTH_SHORT).show();
                        d.dismiss();
                    }
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void showSetChatPinDialog() {
        float dp = getResources().getDisplayMetrics().density;
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setHint("أدخل رمز PIN (4-8 أرقام)");
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

        new AlertDialog.Builder(this, R.style.DarkAlertDialog)
                .setTitle("رمز قفل المحادثة")
                .setView(container)
                .setPositiveButton("حفظ", (d, w) -> {
                    String pin = input.getText().toString().trim();
                    if (pin.length() < 4) {
                        Toast.makeText(this, "الرمز يجب أن يكون 4 أرقام على الأقل", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    AppLockManager.getInstance(this).setChatLockPin(conversationId, pin);
                    updateChatLockLabel();
                    Toast.makeText(this, "تم تفعيل قفل المحادثة بالرمز", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void showUserQrCode() {
        String uname = fetchedUsernameForQr;
        if (uname == null || uname.isEmpty()) {
            uname = userName;
        }

        Intent intent = QrCodeActivity.create(
                this, userId, uname, userName,
                userInitials, avatarColor, avatarUrl
        );
        startActivity(intent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateChatLockLabel();
    }
}
