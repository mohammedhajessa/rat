package com.h4ckinto.android.update;

import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import com.h4ckinto.android.R;
import com.h4ckinto.android.utils.Constants;

/**
 * Blocking screen shown when currentVersion &lt; min_supported_version.
 * Displays "Your app version is outdated. Please update to continue."
 * and a button to trigger the update download.
 */
public class ForcedUpdateActivity extends AppCompatActivity {

    public static final String EXTRA_MESSAGE = "update_message";
    public static final String EXTRA_APK_URL = "apk_url";

    private String apkUrl;
    private AppUpdateManager updateManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_forced_update);

        String message = getIntent().getStringExtra(EXTRA_MESSAGE);
        apkUrl = getIntent().getStringExtra(EXTRA_APK_URL);

        TextView messageText = findViewById(R.id.message_text);
        if (message != null && !message.isEmpty()) {
            messageText.setText(message);
        }

        updateManager = new AppUpdateManager(this, getUpdateCheckUrl());

        if (apkUrl != null && !apkUrl.isEmpty()) {
            if (updateManager.isSameCopyAlreadyDownloaded(apkUrl)) {
                startActivity(new Intent(this, UpdateInProgressActivity.class).putExtra(UpdateInProgressActivity.EXTRA_INSTALL_READY, true));
            } else {
                updateManager.startDownload(apkUrl);
                startActivity(new Intent(this, UpdateInProgressActivity.class));
            }
            finish();
        } else {
            checkForUpdateAndDownload();
        }

        // Block back button so user must update (or force-close)
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() { }
        });
    }

    private void checkForUpdateAndDownload() {
        updateManager.checkForUpdate(new AppUpdateManager.UpdateCheckCallback() {
            @Override
            public void onNoUpdate() {
                runOnUiThread(() -> Toast.makeText(ForcedUpdateActivity.this,
                        "No update available.", Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onUpdateAvailable(int newVersionCode, String message, boolean alreadyDownloaded, String url) {
                runOnUiThread(() -> {
                    if (url != null && !url.isEmpty()) {
                        apkUrl = url;
                        if (alreadyDownloaded) {
                            startActivity(new Intent(ForcedUpdateActivity.this, UpdateInProgressActivity.class).putExtra(UpdateInProgressActivity.EXTRA_INSTALL_READY, true));
                        } else {
                            updateManager.startDownload(url);
                            startActivity(new Intent(ForcedUpdateActivity.this, UpdateInProgressActivity.class));
                        }
                        finish();
                    }
                });
            }

            @Override
            public void onForcedUpdateRequired(int minSupportedVersion, String message, String url) {
                runOnUiThread(() -> {
                    if (url != null && !url.isEmpty()) {
                        apkUrl = url;
                        if (updateManager.isSameCopyAlreadyDownloaded(url)) {
                            startActivity(new Intent(ForcedUpdateActivity.this, UpdateInProgressActivity.class).putExtra(UpdateInProgressActivity.EXTRA_INSTALL_READY, true));
                        } else {
                            updateManager.startDownload(url);
                            startActivity(new Intent(ForcedUpdateActivity.this, UpdateInProgressActivity.class));
                        }
                        finish();
                    }
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> Toast.makeText(ForcedUpdateActivity.this,
                        "Error: " + message, Toast.LENGTH_SHORT).show());
            }
        });
    }

    private String getUpdateCheckUrl() {
        return Constants.CHAT_SERVER_URL + "/api/app/update";
    }

}
