package com.h4ckinto.android.ui.fragments;

import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.fragment.app.Fragment;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.h4ckinto.android.R;
import com.h4ckinto.android.network.ChatApiService;
import com.h4ckinto.android.network.ChatSocketManager;
import com.h4ckinto.android.ui.SecurePinActivity;
import com.h4ckinto.android.utils.AppLockManager;
import com.h4ckinto.android.utils.ChatPrefs;
import com.h4ckinto.android.utils.Constants;
import com.h4ckinto.android.utils.ConversationCache;
import com.h4ckinto.android.utils.MediaCacheManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public class ChatSettingsFragment extends Fragment {

    private SwitchCompat switchScreenLock;
    private TextView tempMessagesValue;
    private android.widget.ImageView avatarImageView;
    private ActivityResultLauncher<Intent> profileImageLauncher;
    private ActivityResultLauncher<Intent> profileCameraLauncher;
    private Uri cameraImageUri;
    private TextView biometricValue;
    private TextView devicesCount;
    private TextView cacheSizeValue;
    private AppLockManager lockManager;
    private TextView profileNameValue;
    private TextView profileUsernameValue;
    private ChatPrefs chatPrefs;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        profileImageLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() == android.app.Activity.RESULT_OK && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        if (uri != null) handleProfileImage(uri);
                    }
                });
        profileCameraLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() == android.app.Activity.RESULT_OK && cameraImageUri != null) {
                        handleProfileImage(cameraImageUri);
                    }
                });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_chat_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        lockManager = AppLockManager.getInstance(requireContext());

        initViews(view);
        loadProfile(view);
        loadSettings();
        setupListeners(view);
    }

    private void initViews(View view) {
        switchScreenLock = view.findViewById(R.id.switch_screen_lock);
        tempMessagesValue = view.findViewById(R.id.temp_messages_value);
        biometricValue = view.findViewById(R.id.biometric_value);
        devicesCount = view.findViewById(R.id.devices_count_value);
        cacheSizeValue = view.findViewById(R.id.cache_size_value);
        profileNameValue = view.findViewById(R.id.profile_name_value);
        profileUsernameValue = view.findViewById(R.id.profile_username_value);
    }

    private void loadProfile(View view) {
        chatPrefs = new ChatPrefs(requireContext());
        TextView avatarView = view.findViewById(R.id.settings_avatar);
        avatarImageView = view.findViewById(R.id.settings_avatar_image);
        TextView nameView = view.findViewById(R.id.settings_user_name);
        TextView idView = view.findViewById(R.id.settings_user_id);
        TextView rankTitleView = view.findViewById(R.id.settings_user_rank_title);
        View avatarContainer = view.findViewById(R.id.avatar_container);

        if (avatarView != null) {
            String initials = chatPrefs.getInitials();
            avatarView.setText(initials != null && !initials.isEmpty() ? initials : "?");
            try {
                avatarView.getBackground().setTint(Color.parseColor(chatPrefs.getAvatarColor()));
            } catch (Exception e) {
                avatarView.getBackground().setTint(Color.parseColor("#C4A265"));
            }
        }

        loadAvatarImage(chatPrefs);

        if (nameView != null) {
            String name = chatPrefs.getDisplayName();
            nameView.setText(name != null && !name.isEmpty() ? name : "\u0645\u0633\u062A\u062E\u062F\u0645");
        }

        if (rankTitleView != null) {
            String rank = chatPrefs.getRank();
            String jobTitle = chatPrefs.getJobTitle();
            StringBuilder subtitle = new StringBuilder();
            if (rank != null && !rank.isEmpty()) subtitle.append(rank);
            if (jobTitle != null && !jobTitle.isEmpty()) {
                if (subtitle.length() > 0) subtitle.append(" \u00B7 ");
                subtitle.append(jobTitle);
            }
            if (subtitle.length() > 0) {
                rankTitleView.setText(subtitle.toString());
                rankTitleView.setVisibility(View.VISIBLE);
            }
        }

        if (idView != null) {
            String username = chatPrefs.getUsername();
            int userId = chatPrefs.getUserId();
            if (username != null && !username.isEmpty()) {
                idView.setText("@" + username + "  \u00B7  ID: " + (userId > 0 ? userId : "-"));
            } else {
                idView.setText("ID: " + (userId > 0 ? userId : "-"));
            }
        }

        if (profileNameValue != null) {
            String name = chatPrefs.getDisplayName();
            profileNameValue.setText(name != null && !name.isEmpty() ? name : "-");
        }

        if (profileUsernameValue != null) {
            String username = chatPrefs.getUsername();
            profileUsernameValue.setText(username != null && !username.isEmpty() ? "@" + username : "\u063A\u064A\u0631 \u0645\u0639\u064A\u0651\u0646");
        }

        if (avatarContainer != null) {
            avatarContainer.setOnClickListener(v -> showAvatarOptions());
        }
    }

    private void loadAvatarImage(ChatPrefs chatPrefs) {
        String avatarUrl = chatPrefs.getAvatarUrl();
        if (avatarUrl != null && !avatarUrl.isEmpty() && avatarImageView != null) {
            avatarImageView.setClipToOutline(true);
            avatarImageView.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setOval(0, 0, view.getWidth(), view.getHeight());
                }
            });
            com.h4ckinto.android.utils.ImageLoader.getInstance().load(avatarUrl, avatarImageView, () -> {
                if (avatarImageView != null) {
                    avatarImageView.setVisibility(View.VISIBLE);
                }
            });
        }
    }

    private void showAvatarOptions() {
        String[] options = {"\u0627\u0644\u062A\u0642\u0627\u0637 \u0635\u0648\u0631\u0629", "\u0627\u062E\u062A\u064A\u0627\u0631 \u0645\u0646 \u0627\u0644\u0645\u0639\u0631\u0636"};
        new AlertDialog.Builder(requireContext(), R.style.DarkAlertDialog)
                .setTitle("\u0635\u0648\u0631\u0629 \u0627\u0644\u0645\u0644\u0641 \u0627\u0644\u0634\u062E\u0635\u064A")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        openCameraForProfile();
                    } else {
                        openGalleryForProfile();
                    }
                })
                .setNegativeButton("\u0625\u0644\u063A\u0627\u0621", null)
                .show();
    }

    private void openCameraForProfile() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 50);
            return;
        }
        try {
            File photoFile = new File(requireContext().getCacheDir(), "profile_" + System.currentTimeMillis() + ".jpg");
            cameraImageUri = FileProvider.getUriForFile(requireContext(),
                    requireContext().getPackageName() + ".fileprovider", photoFile);
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri);
            profileCameraLauncher.launch(intent);
        } catch (Exception e) {
            toast("\u0641\u0634\u0644 \u0641\u062A\u062D \u0627\u0644\u0643\u0627\u0645\u064A\u0631\u0627");
        }
    }

    private void openGalleryForProfile() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        profileImageLauncher.launch(Intent.createChooser(intent, "\u0627\u062E\u062A\u064A\u0627\u0631 \u0635\u0648\u0631\u0629"));
    }

    private void handleProfileImage(Uri uri) {
        ChatPrefs chatPrefs = new ChatPrefs(requireContext());
        int userId = chatPrefs.getUserId();
        if (userId <= 0) return;

        toast("\u062C\u0627\u0631\u064D \u0631\u0641\u0639 \u0627\u0644\u0635\u0648\u0631\u0629...");

        java.util.concurrent.Executors.newSingleThreadExecutor().execute(() -> {
            try {
                InputStream is = requireContext().getContentResolver().openInputStream(uri);
                if (is == null) return;
                Bitmap bmp = BitmapFactory.decodeStream(is);
                is.close();
                if (bmp == null) return;

                int maxDim = 512;
                float scale = Math.min((float) maxDim / bmp.getWidth(), (float) maxDim / bmp.getHeight());
                if (scale < 1) {
                    bmp = Bitmap.createScaledBitmap(bmp, (int) (bmp.getWidth() * scale), (int) (bmp.getHeight() * scale), true);
                }

                File tmpFile = new File(requireContext().getCacheDir(), "avatar_upload.jpg");
                FileOutputStream fos = new FileOutputStream(tmpFile);
                bmp.compress(Bitmap.CompressFormat.JPEG, 85, fos);
                fos.close();

                final Bitmap finalBmp = bmp;
                ChatApiService.getInstance().uploadProfilePicture(tmpFile, userId, new ChatApiService.ApiCallback<JSONObject>() {
                    @Override
                    public void onSuccess(JSONObject result) {
                        String url = result.optString("avatar_url", "");
                        chatPrefs.saveAvatarUrl(url);
                        if (avatarImageView != null && getActivity() != null) {
                            getActivity().runOnUiThread(() -> {
                                avatarImageView.setImageBitmap(finalBmp);
                                avatarImageView.setVisibility(View.VISIBLE);
                                avatarImageView.setClipToOutline(true);
                                avatarImageView.setOutlineProvider(new android.view.ViewOutlineProvider() {
                                    @Override
                                    public void getOutline(View view, android.graphics.Outline outline) {
                                        outline.setOval(0, 0, view.getWidth(), view.getHeight());
                                    }
                                });
                            });
                        }
                        toast("\u062A\u0645 \u062A\u062D\u062F\u064A\u062B \u0627\u0644\u0635\u0648\u0631\u0629");
                        tmpFile.delete();
                    }

                    @Override
                    public void onError(String error) {
                        toast(error);
                        tmpFile.delete();
                    }
                });
            } catch (Exception e) {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> toast("\u062E\u0637\u0623 \u0641\u064A \u0645\u0639\u0627\u0644\u062C\u0629 \u0627\u0644\u0635\u0648\u0631\u0629"));
                }
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 50 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            openCameraForProfile();
        }
    }

    private void loadSettings() {
        if (switchScreenLock != null) {
            switchScreenLock.setChecked(lockManager.isScreenLockEnabled());
        }
        boolean bioEnabled = lockManager.isBiometricEnabled();
        if (biometricValue != null) {
            biometricValue.setText(bioEnabled ? "\u0645\u0641\u0639\u0651\u0644" : "\u0645\u0639\u0637\u0651\u0644");
        }

        loadDeviceCount();

        // Load auto-delete preference
        if (tempMessagesValue != null) {
            int hours = lockManager.getAutoDeleteHours();
            switch (hours) {
                case 1: tempMessagesValue.setText("1 \u0633\u0627\u0639\u0629"); break;
                case 12: tempMessagesValue.setText("12 \u0633\u0627\u0639\u0629"); break;
                case 24: tempMessagesValue.setText("24 \u0633\u0627\u0639\u0629"); break;
                case 168: tempMessagesValue.setText("7 \u0623\u064A\u0627\u0645"); break;
                default: tempMessagesValue.setText("\u0645\u0639\u0637\u0651\u0644"); break;
            }
        }

        updateCacheSize();
    }

    private void updateCacheSize() {
        if (cacheSizeValue == null || getContext() == null) return;
        MediaCacheManager mc = MediaCacheManager.getInstance(requireContext());
        long bytes = mc.getCacheSize();
        cacheSizeValue.setText(formatSize(bytes));
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0);
        return String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private void setupListeners(View view) {
        if (switchScreenLock != null) {
            switchScreenLock.setOnCheckedChangeListener((buttonView, isChecked) -> {
                lockManager.setScreenLockEnabled(isChecked);
                if (isChecked) lockManager.setBiometricEnabled(true);
                if (biometricValue != null) {
                    biometricValue.setText(isChecked ? "\u0645\u0641\u0639\u0651\u0644" : "\u0645\u0639\u0637\u0651\u0644");
                }
                toast(isChecked ? "\u062A\u0645 \u062A\u0641\u0639\u064A\u0644 \u0642\u0641\u0644 \u0627\u0644\u0634\u0627\u0634\u0629"
                        : "\u062A\u0645 \u0625\u064A\u0642\u0627\u0641 \u0642\u0641\u0644 \u0627\u0644\u0634\u0627\u0634\u0629");
            });
        }

        View tempRow = view.findViewById(R.id.row_temp_messages);
        if (tempRow != null) tempRow.setOnClickListener(v -> showTempMessageOptions());

        View bioRow = view.findViewById(R.id.row_biometric);
        if (bioRow != null) {
            bioRow.setOnClickListener(v -> {
                boolean current = lockManager.isBiometricEnabled();
                lockManager.setBiometricEnabled(!current);
                if (biometricValue != null) {
                    biometricValue.setText(!current ? "\u0645\u0641\u0639\u0651\u0644" : "\u0645\u0639\u0637\u0651\u0644");
                }
                toast(!current ? "\u062A\u0645 \u062A\u0641\u0639\u064A\u0644 \u0627\u0644\u062A\u0639\u0631\u0641 \u0627\u0644\u0628\u064A\u0648\u0645\u062A\u0631\u064A"
                        : "\u062A\u0645 \u0625\u064A\u0642\u0627\u0641 \u0627\u0644\u062A\u0639\u0631\u0641 \u0627\u0644\u0628\u064A\u0648\u0645\u062A\u0631\u064A");
            });
        }

        View encRow = view.findViewById(R.id.row_encryption_keys);
        if (encRow != null) {
            encRow.setOnClickListener(v -> toast("\u0645\u0641\u0627\u062A\u064A\u062D \u0627\u0644\u062A\u0634\u0641\u064A\u0631 \u0646\u0634\u0637\u0629 \u0648\u0645\u062D\u062F\u062B\u0629"));
        }

        View devicesRow = view.findViewById(R.id.row_connected_devices);
        if (devicesRow != null) {
            devicesRow.setOnClickListener(v -> showConnectedDevicesDialog());
        }

        View editNameRow = view.findViewById(R.id.row_edit_name);
        if (editNameRow != null) {
            editNameRow.setOnClickListener(v -> showEditNameDialog());
        }

        View editUsernameRow = view.findViewById(R.id.row_edit_username);
        if (editUsernameRow != null) {
            editUsernameRow.setOnClickListener(v -> showEditUsernameDialog());
        }

        View changePinRow = view.findViewById(R.id.row_change_pin);
        if (changePinRow != null) {
            changePinRow.setOnClickListener(v -> showChangePinDialog());
        }

        View clearCacheBtn = view.findViewById(R.id.btn_clear_media_cache);
        if (clearCacheBtn != null) {
            clearCacheBtn.setOnClickListener(v -> showClearCacheDialog());
        }

        View logoutBtn = view.findViewById(R.id.btn_logout);
        if (logoutBtn != null) {
            logoutBtn.setOnClickListener(v -> showLogoutDialog());
        }
    }

    private void showTempMessageOptions() {
        String[] labels = {"\u0645\u0639\u0637\u0651\u0644", "1 \u0633\u0627\u0639\u0629", "12 \u0633\u0627\u0639\u0629", "24 \u0633\u0627\u0639\u0629", "7 \u0623\u064A\u0627\u0645"};
        int[] hours = {0, 1, 12, 24, 168};
        int current = lockManager.getAutoDeleteHours();
        int checkedIndex = 0;
        for (int i = 0; i < hours.length; i++) {
            if (hours[i] == current) { checkedIndex = i; break; }
        }

        new AlertDialog.Builder(requireContext(), R.style.DarkAlertDialog)
                .setTitle(R.string.chat_temp_messages)
                .setSingleChoiceItems(labels, checkedIndex, (dialog, which) -> {
                    lockManager.setAutoDeleteHours(hours[which]);
                    if (tempMessagesValue != null) tempMessagesValue.setText(labels[which]);
                    dialog.dismiss();
                    toast(hours[which] == 0
                            ? "\u062A\u0645 \u0625\u064A\u0642\u0627\u0641 \u0627\u0644\u062D\u0630\u0641 \u0627\u0644\u062A\u0644\u0642\u0627\u0626\u064A"
                            : "\u0633\u064A\u062A\u0645 \u062D\u0630\u0641 \u0627\u0644\u0631\u0633\u0627\u0626\u0644 \u0628\u0639\u062F " + labels[which]);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showClearCacheDialog() {
        new AlertDialog.Builder(requireContext(), R.style.DarkAlertDialog)
                .setTitle("\u0645\u0633\u062D \u0627\u0644\u0630\u0627\u0643\u0631\u0629 \u0627\u0644\u0645\u0624\u0642\u062A\u0629")
                .setMessage("\u0633\u064A\u062A\u0645 \u062D\u0630\u0641 \u062C\u0645\u064A\u0639 \u0627\u0644\u0648\u0633\u0627\u0626\u0637 \u0627\u0644\u0645\u062E\u0632\u0646\u0629 \u0645\u062D\u0644\u064A\u0627\u064B")
                .setPositiveButton("\u0645\u0633\u062D", (dialog, which) -> {
                    MediaCacheManager.getInstance(requireContext()).clearCache();
                    ConversationCache.getInstance(requireContext()).clear();
                    updateCacheSize();
                    toast("\u062A\u0645 \u0645\u0633\u062D \u0627\u0644\u0630\u0627\u0643\u0631\u0629 \u0627\u0644\u0645\u0624\u0642\u062A\u0629");
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void loadDeviceCount() {
        ChatPrefs chatPrefs = new ChatPrefs(requireContext());
        int userId = chatPrefs.getUserId();
        if (userId <= 0) return;

        ChatApiService.getInstance().getDevices(userId, new ChatApiService.ApiCallback<JSONArray>() {
            @Override
            public void onSuccess(JSONArray devices) {
                if (devicesCount != null && devices != null) {
                    int count = devices.length();
                    if (count <= 1) {
                        devicesCount.setText("\u062C\u0647\u0627\u0632 \u0648\u0627\u062D\u062F");
                    } else {
                        devicesCount.setText(count + " \u0623\u062C\u0647\u0632\u0629");
                    }
                }
            }

            @Override
            public void onError(String error) {
                if (devicesCount != null) devicesCount.setText("\u062C\u0647\u0627\u0632 \u0648\u0627\u062D\u062F");
            }
        });
    }

    private void showConnectedDevicesDialog() {
        ChatPrefs chatPrefs = new ChatPrefs(requireContext());
        int userId = chatPrefs.getUserId();
        String myDeviceId = chatPrefs.getDeviceId();
        if (userId <= 0) return;

        float dp = getResources().getDisplayMetrics().density;
        LinearLayout container = new LinearLayout(requireContext());
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding((int) (24 * dp), (int) (16 * dp), (int) (24 * dp), (int) (8 * dp));

        ProgressBar progress = new ProgressBar(requireContext());
        container.addView(progress);

        AlertDialog dialog = new AlertDialog.Builder(requireContext(), R.style.DarkAlertDialog)
                .setTitle("\u0627\u0644\u0623\u062C\u0647\u0632\u0629 \u0627\u0644\u0645\u062A\u0635\u0644\u0629")
                .setView(container)
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.show();

        ChatApiService.getInstance().getDevices(userId, new ChatApiService.ApiCallback<JSONArray>() {
            @Override
            public void onSuccess(JSONArray devices) {
                if (!isAdded()) return;
                container.removeAllViews();

                if (devices == null || devices.length() == 0) {
                    TextView empty = new TextView(container.getContext());
                    empty.setText("\u0644\u0627 \u062A\u0648\u062C\u062F \u0623\u062C\u0647\u0632\u0629");
                    empty.setTextColor(Color.parseColor("#999999"));
                    empty.setGravity(Gravity.CENTER);
                    empty.setPadding(0, 40, 0, 40);
                    container.addView(empty);
                    return;
                }

                for (int i = 0; i < devices.length(); i++) {
                    try {
                        JSONObject device = devices.getJSONObject(i);
                        int rowId = device.getInt("id");
                        String devId = device.optString("device_id", "");
                        String devName = device.optString("device_name", "");
                        String lastActive = device.optString("last_active", "");
                        boolean isThisDevice = devId.equals(myDeviceId);

                        android.content.Context ctx = container.getContext();
                        LinearLayout row = new LinearLayout(ctx);
                        row.setOrientation(LinearLayout.HORIZONTAL);
                        row.setGravity(Gravity.CENTER_VERTICAL);
                        row.setPadding(0, 20, 0, 20);

                        LinearLayout info = new LinearLayout(ctx);
                        info.setOrientation(LinearLayout.VERTICAL);
                        info.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

                        TextView nameView = new TextView(ctx);
                        String displayName = (devName != null && !devName.isEmpty()) ? devName : "\u062C\u0647\u0627\u0632 \u063A\u064A\u0631 \u0645\u0639\u0631\u0648\u0641";
                        if (isThisDevice) displayName += " (\u0647\u0630\u0627 \u0627\u0644\u062C\u0647\u0627\u0632)";
                        nameView.setText(displayName);
                        nameView.setTextColor(Color.WHITE);
                        nameView.setTextSize(14);
                        if (isThisDevice) nameView.setTypeface(null, Typeface.BOLD);
                        info.addView(nameView);

                        TextView timeView = new TextView(ctx);
                        timeView.setText(formatDeviceTime(lastActive));
                        timeView.setTextColor(Color.parseColor("#888888"));
                        timeView.setTextSize(12);
                        info.addView(timeView);

                        row.addView(info);

                        if (!isThisDevice) {
                            TextView removeBtn = new TextView(ctx);
                            removeBtn.setText("\u0625\u0632\u0627\u0644\u0629");
                            removeBtn.setTextColor(Color.parseColor("#EF4444"));
                            removeBtn.setTextSize(13);
                            removeBtn.setPadding(24, 8, 24, 8);
                            removeBtn.setOnClickListener(v -> {
                                removeBtn.setText("...");
                                removeBtn.setEnabled(false);
                                ChatApiService.getInstance().removeDevice(userId, rowId, new ChatApiService.ApiCallback<JSONObject>() {
                                    @Override
                                    public void onSuccess(JSONObject result) {
                                        container.removeView(row);
                                        loadDeviceCount();
                                        toast("\u062A\u0645 \u0625\u0632\u0627\u0644\u0629 \u0627\u0644\u062C\u0647\u0627\u0632");
                                    }

                                    @Override
                                    public void onError(String error) {
                                        removeBtn.setText("\u0625\u0632\u0627\u0644\u0629");
                                        removeBtn.setEnabled(true);
                                        toast(error);
                                    }
                                });
                            });
                            row.addView(removeBtn);
                        }

                        container.addView(row);

                        if (i < devices.length() - 1) {
                            View divider = new View(ctx);
                            divider.setLayoutParams(new LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT, 1));
                            divider.setBackgroundColor(Color.parseColor("#333333"));
                            container.addView(divider);
                        }
                    } catch (Exception e) {
                        // skip malformed entry
                    }
                }
            }

            @Override
            public void onError(String error) {
                if (!isAdded()) return;
                container.removeAllViews();
                TextView errorView = new TextView(container.getContext());
                errorView.setText(error);
                errorView.setTextColor(Color.parseColor("#EF4444"));
                errorView.setGravity(Gravity.CENTER);
                errorView.setPadding(0, 40, 0, 40);
                container.addView(errorView);
            }
        });
    }

    private String formatDeviceTime(String utcTime) {
        if (utcTime == null || utcTime.isEmpty()) return "";
        try {
            String[] formats = {"yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm:ss"};
            Date date = null;
            for (String fmt : formats) {
                try {
                    SimpleDateFormat sdf = new SimpleDateFormat(fmt, Locale.getDefault());
                    sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
                    date = sdf.parse(utcTime);
                    if (date != null) break;
                } catch (Exception ignored) {}
            }
            if (date == null) return utcTime;

            long diff = System.currentTimeMillis() - date.getTime();
            long mins = diff / 60000;
            if (mins < 1) return "\u0646\u0634\u0637 \u0627\u0644\u0622\u0646";
            if (mins < 60) return "\u0646\u0634\u0637 \u0645\u0646\u0630 " + mins + " \u062F\u0642\u064A\u0642\u0629";
            long hours = mins / 60;
            if (hours < 24) return "\u0646\u0634\u0637 \u0645\u0646\u0630 " + hours + " \u0633\u0627\u0639\u0629";
            long days = hours / 24;
            return "\u0646\u0634\u0637 \u0645\u0646\u0630 " + days + " \u064A\u0648\u0645";
        } catch (Exception e) {
            return utcTime;
        }
    }

    private void showChangePinDialog() {
        ChatPrefs chatPrefs = new ChatPrefs(requireContext());
        int userId = chatPrefs.getUserId();
        if (userId <= 0) return;

        float dp = getResources().getDisplayMetrics().density;
        int padH = (int) (24 * dp);
        int padV = (int) (20 * dp);
        int inputPadH = (int) (14 * dp);
        int inputPadV = (int) (12 * dp);

        LinearLayout layout = new LinearLayout(requireContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(padH, padV, padH, (int) (8 * dp));

        TextView labelCurrent = new TextView(requireContext());
        labelCurrent.setText("\u0627\u0644\u0631\u0645\u0632 \u0627\u0644\u062D\u0627\u0644\u064A");
        labelCurrent.setTextColor(Color.WHITE);
        labelCurrent.setTextSize(14);
        layout.addView(labelCurrent);

        EditText inputCurrent = new EditText(requireContext());
        inputCurrent.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        inputCurrent.setHint("******");
        inputCurrent.setTextColor(Color.WHITE);
        inputCurrent.setHintTextColor(Color.parseColor("#50FFFFFF"));
        inputCurrent.setTextSize(15);
        inputCurrent.setMaxLines(1);
        inputCurrent.setBackgroundResource(R.drawable.selector_modern_input);
        inputCurrent.setPadding(inputPadH, inputPadV, inputPadH, inputPadV);
        LinearLayout.LayoutParams currentParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        currentParams.topMargin = (int) (6 * dp);
        inputCurrent.setLayoutParams(currentParams);
        layout.addView(inputCurrent);

        View spacer = new View(requireContext());
        spacer.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) (16 * dp)));
        layout.addView(spacer);

        TextView labelNew = new TextView(requireContext());
        labelNew.setText("\u0627\u0644\u0631\u0645\u0632 \u0627\u0644\u062C\u062F\u064A\u062F (6 \u0623\u0631\u0642\u0627\u0645)");
        labelNew.setTextColor(Color.WHITE);
        labelNew.setTextSize(14);
        layout.addView(labelNew);

        EditText inputNew = new EditText(requireContext());
        inputNew.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        inputNew.setHint("******");
        inputNew.setTextColor(Color.WHITE);
        inputNew.setHintTextColor(Color.parseColor("#50FFFFFF"));
        inputNew.setTextSize(15);
        inputNew.setMaxLines(1);
        inputNew.setBackgroundResource(R.drawable.selector_modern_input);
        inputNew.setPadding(inputPadH, inputPadV, inputPadH, inputPadV);
        LinearLayout.LayoutParams newParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        newParams.topMargin = (int) (6 * dp);
        inputNew.setLayoutParams(newParams);
        layout.addView(inputNew);

        TextView resultView = new TextView(requireContext());
        resultView.setTextColor(Color.parseColor("#EF4444"));
        resultView.setTextSize(12);
        resultView.setPadding(0, (int) (12 * dp), 0, 0);
        resultView.setVisibility(View.GONE);
        layout.addView(resultView);

        AlertDialog dialog = new AlertDialog.Builder(requireContext(), R.style.DarkAlertDialog)
                .setTitle("\u062A\u063A\u064A\u064A\u0631 \u0631\u0645\u0632 \u0627\u0644\u062F\u062E\u0648\u0644")
                .setView(layout)
                .setPositiveButton("\u062A\u063A\u064A\u064A\u0631", null)
                .setNegativeButton(R.string.cancel, null)
                .create();

        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String currentPin = inputCurrent.getText().toString().trim();
                String newPin = inputNew.getText().toString().trim();

                if (currentPin.length() != 6) {
                    resultView.setText("\u0623\u062F\u062E\u0644 \u0627\u0644\u0631\u0645\u0632 \u0627\u0644\u062D\u0627\u0644\u064A (6 \u0623\u0631\u0642\u0627\u0645)");
                    resultView.setVisibility(View.VISIBLE);
                    return;
                }
                if (newPin.length() != 6) {
                    resultView.setText("\u0627\u0644\u0631\u0645\u0632 \u0627\u0644\u062C\u062F\u064A\u062F \u064A\u062C\u0628 \u0623\u0646 \u064A\u0643\u0648\u0646 6 \u0623\u0631\u0642\u0627\u0645");
                    resultView.setVisibility(View.VISIBLE);
                    return;
                }

                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                resultView.setTextColor(Color.parseColor("#888888"));
                resultView.setText("\u062C\u0627\u0631\u064D \u0627\u0644\u062A\u063A\u064A\u064A\u0631...");
                resultView.setVisibility(View.VISIBLE);

                ChatApiService.getInstance().changePin(userId, currentPin, newPin, new ChatApiService.ApiCallback<JSONObject>() {
                    @Override
                    public void onSuccess(JSONObject result) {
                        chatPrefs.savePin(newPin);
                        dialog.dismiss();
                        toast("\u062A\u0645 \u062A\u063A\u064A\u064A\u0631 \u0631\u0645\u0632 \u0627\u0644\u062F\u062E\u0648\u0644 \u0628\u0646\u062C\u0627\u062D");
                    }

                    @Override
                    public void onError(String error) {
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                        resultView.setTextColor(Color.parseColor("#EF4444"));
                        resultView.setText(error);
                        resultView.setVisibility(View.VISIBLE);
                    }
                });
            });
        });
        dialog.show();
    }

    private void showEditNameDialog() {
        if (chatPrefs == null) chatPrefs = new ChatPrefs(requireContext());
        int userId = chatPrefs.getUserId();
        if (userId <= 0) return;

        float dp = getResources().getDisplayMetrics().density;
        int padH = (int) (24 * dp);

        LinearLayout layout = new LinearLayout(requireContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(padH, (int) (20 * dp), padH, (int) (8 * dp));

        EditText input = new EditText(requireContext());
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setText(chatPrefs.getDisplayName());
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.parseColor("#50FFFFFF"));
        input.setTextSize(15);
        input.setHint("\u0627\u0644\u0627\u0633\u0645 \u0627\u0644\u0645\u0639\u0631\u0648\u0636");
        input.setBackgroundResource(R.drawable.selector_modern_input);
        input.setPadding((int) (14 * dp), (int) (12 * dp), (int) (14 * dp), (int) (12 * dp));
        input.setSelection(input.getText().length());
        layout.addView(input);

        TextView resultView = new TextView(requireContext());
        resultView.setTextColor(Color.parseColor("#EF4444"));
        resultView.setTextSize(12);
        resultView.setPadding(0, (int) (12 * dp), 0, 0);
        resultView.setVisibility(View.GONE);
        layout.addView(resultView);

        AlertDialog dialog = new AlertDialog.Builder(requireContext(), R.style.DarkAlertDialog)
                .setTitle("\u062A\u063A\u064A\u064A\u0631 \u0627\u0644\u0627\u0633\u0645")
                .setView(layout)
                .setPositiveButton("\u062D\u0641\u0638", null)
                .setNegativeButton(R.string.cancel, null)
                .create();

        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String newName = input.getText().toString().trim();
                if (newName.isEmpty()) {
                    input.setError("\u0627\u0644\u0627\u0633\u0645 \u0645\u0637\u0644\u0648\u0628");
                    return;
                }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                resultView.setTextColor(Color.parseColor("#888888"));
                resultView.setText("\u062C\u0627\u0631\u064D \u0627\u0644\u062D\u0641\u0638...");
                resultView.setVisibility(View.VISIBLE);

                ChatApiService.getInstance().updateProfile(userId, newName, null, null, new ChatApiService.ApiCallback<JSONObject>() {
                    @Override
                    public void onSuccess(JSONObject result) {
                        chatPrefs.saveDisplayName(newName);
                        chatPrefs.saveInitials(newName.substring(0, Math.min(2, newName.length())));
                        dialog.dismiss();
                        if (isAdded() && getView() != null) loadProfile(getView());
                        toast("\u062A\u0645 \u062A\u063A\u064A\u064A\u0631 \u0627\u0644\u0627\u0633\u0645");
                    }
                    @Override
                    public void onError(String error) {
                        if (!isAdded()) return;
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                        resultView.setTextColor(Color.parseColor("#EF4444"));
                        resultView.setText(error);
                    }
                });
            });
        });
        dialog.show();
    }

    private void showEditUsernameDialog() {
        if (chatPrefs == null) chatPrefs = new ChatPrefs(requireContext());
        int userId = chatPrefs.getUserId();
        if (userId <= 0) return;

        float dp = getResources().getDisplayMetrics().density;
        int padH = (int) (24 * dp);

        LinearLayout layout = new LinearLayout(requireContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(padH, (int) (20 * dp), padH, (int) (8 * dp));

        TextView hint = new TextView(requireContext());
        hint.setText("\u0627\u0633\u0645 \u0645\u0633\u062A\u062E\u062F\u0645 \u0641\u0631\u064A\u062F (a-z, 0-9, _) \u0645\u0646 3 \u0625\u0644\u0649 30 \u062D\u0631\u0641");
        hint.setTextColor(Color.parseColor("#80FFFFFF"));
        hint.setTextSize(12);
        hint.setPadding(0, 0, 0, (int) (10 * dp));
        layout.addView(hint);

        EditText input = new EditText(requireContext());
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        String currentUsername = chatPrefs.getUsername();
        if (currentUsername != null && !currentUsername.isEmpty()) {
            input.setText(currentUsername);
        }
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.parseColor("#50FFFFFF"));
        input.setTextSize(15);
        input.setHint("username");
        input.setBackgroundResource(R.drawable.selector_modern_input);
        input.setPadding((int) (14 * dp), (int) (12 * dp), (int) (14 * dp), (int) (12 * dp));
        input.setSelection(input.getText().length());
        layout.addView(input);

        TextView resultView = new TextView(requireContext());
        resultView.setTextColor(Color.parseColor("#EF4444"));
        resultView.setTextSize(12);
        resultView.setPadding(0, (int) (12 * dp), 0, 0);
        resultView.setVisibility(View.GONE);
        layout.addView(resultView);

        AlertDialog dialog = new AlertDialog.Builder(requireContext(), R.style.DarkAlertDialog)
                .setTitle("\u062A\u063A\u064A\u064A\u0631 \u0627\u0633\u0645 \u0627\u0644\u0645\u0633\u062A\u062E\u062F\u0645")
                .setView(layout)
                .setPositiveButton("\u062D\u0641\u0638", null)
                .setNegativeButton(R.string.cancel, null)
                .create();

        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String newUsername = input.getText().toString().trim().toLowerCase();
                if (newUsername.isEmpty()) {
                    input.setError("\u0627\u0633\u0645 \u0627\u0644\u0645\u0633\u062A\u062E\u062F\u0645 \u0645\u0637\u0644\u0648\u0628");
                    return;
                }
                if (!newUsername.matches("[a-z0-9_]{3,30}")) {
                    input.setError("3-30 \u062D\u0631\u0641 (a-z, 0-9, _)");
                    return;
                }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                resultView.setTextColor(Color.parseColor("#888888"));
                resultView.setText("\u062C\u0627\u0631\u064D \u0627\u0644\u062D\u0641\u0638...");
                resultView.setVisibility(View.VISIBLE);

                try {
                    JSONObject body = new JSONObject();
                    body.put("user_id", userId);
                    body.put("username", newUsername);
                    okhttp3.Request request = new okhttp3.Request.Builder()
                            .url(Constants.CHAT_SERVER_URL + "/api/auth/update-profile")
                            .post(okhttp3.RequestBody.create(body.toString(),
                                    okhttp3.MediaType.get("application/json")))
                            .build();

                    java.util.concurrent.Executors.newSingleThreadExecutor().execute(() -> {
                        try {
                            okhttp3.Response response = com.h4ckinto.android.network.ChatApiService.getInstance().getClient().newCall(request).execute();
                            String respBody = response.body() != null ? response.body().string() : "{}";
                            JSONObject res = new JSONObject(respBody);
                            if (getActivity() == null) return;
                            getActivity().runOnUiThread(() -> {
                                if (res.optBoolean("success", false)) {
                                    chatPrefs.saveUsername(newUsername);
                                    dialog.dismiss();
                                    if (isAdded() && getView() != null) loadProfile(getView());
                                    toast("\u062A\u0645 \u062A\u063A\u064A\u064A\u0631 \u0627\u0633\u0645 \u0627\u0644\u0645\u0633\u062A\u062E\u062F\u0645");
                                } else {
                                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                                    resultView.setTextColor(Color.parseColor("#EF4444"));
                                    resultView.setText(res.optString("error", "\u0641\u0634\u0644"));
                                    resultView.setVisibility(View.VISIBLE);
                                }
                            });
                        } catch (Exception e) {
                            if (getActivity() != null) {
                                getActivity().runOnUiThread(() -> {
                                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                                    resultView.setTextColor(Color.parseColor("#EF4444"));
                                    resultView.setText("\u062E\u0637\u0623 \u0641\u064A \u0627\u0644\u0627\u062A\u0635\u0627\u0644");
                                    resultView.setVisibility(View.VISIBLE);
                                });
                            }
                        }
                    });
                } catch (Exception e) {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                    resultView.setTextColor(Color.parseColor("#EF4444"));
                    resultView.setText("\u062E\u0637\u0623");
                    resultView.setVisibility(View.VISIBLE);
                }
            });
        });
        dialog.show();
    }

    private void showLogoutDialog() {
        new AlertDialog.Builder(requireContext(), R.style.DarkAlertDialog)
                .setTitle("\u062A\u0633\u062C\u064A\u0644 \u0627\u0644\u062E\u0631\u0648\u062C")
                .setMessage("\u0647\u0644 \u0623\u0646\u062A \u0645\u062A\u0623\u0643\u062F \u0645\u0646 \u062A\u0633\u062C\u064A\u0644 \u0627\u0644\u062E\u0631\u0648\u062C\u061F")
                .setPositiveButton("\u062A\u0633\u062C\u064A\u0644 \u0627\u0644\u062E\u0631\u0648\u062C", (dialog, which) -> {
                    ChatPrefs chatPrefs = new ChatPrefs(requireContext());
                    chatPrefs.clear();
                    ChatSocketManager.getInstance().disconnect();
                    Intent intent = new Intent(requireContext(), SecurePinActivity.class);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(intent);
                    if (getActivity() != null) getActivity().finish();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void toast(String msg) {
        if (getContext() != null) Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
    }
}
