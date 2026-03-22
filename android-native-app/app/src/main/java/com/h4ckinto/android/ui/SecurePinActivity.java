package com.h4ckinto.android.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.h4ckinto.android.R;
import com.h4ckinto.android.network.ChatApiService;
import com.h4ckinto.android.network.ChatSocketManager;
import com.h4ckinto.android.utils.ChatPrefs;

import org.json.JSONObject;

public class SecurePinActivity extends AppCompatActivity {

    private static final String TAG = "SecurePinActivity";
    private EditText[] pinFields;
    private TextView btnVerify;
    private boolean isVerifying = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(getResources().getColor(R.color.chat_background, getTheme()));
        getWindow().setNavigationBarColor(getResources().getColor(R.color.chat_background, getTheme()));

        // If user is already authenticated, skip PIN screen
        ChatPrefs chatPrefs = new ChatPrefs(this);
        if (chatPrefs.isAuthenticated() && chatPrefs.getUserId() > 0) {
            Log.d(TAG, "Already authenticated, skipping PIN screen");
            goToMain(chatPrefs.getUserId());
            return;
        }

        setContentView(R.layout.activity_secure_pin);

        pinFields = new EditText[]{
            findViewById(R.id.pin1),
            findViewById(R.id.pin2),
            findViewById(R.id.pin3),
            findViewById(R.id.pin4),
            findViewById(R.id.pin5),
            findViewById(R.id.pin6)
        };

        setupPinInputs();

        btnVerify = findViewById(R.id.btn_verify);
        btnVerify.setOnClickListener(v -> verifyPin());

        TextView btnForgot = findViewById(R.id.btn_forgot_pin);
        btnForgot.setOnClickListener(v ->
            Toast.makeText(this, "تواصل مع الدعم الفني", Toast.LENGTH_SHORT).show()
        );
    }

    private void setupPinInputs() {
        for (int i = 0; i < pinFields.length; i++) {
            final int index = i;
            pinFields[i].addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    if (s.length() == 1 && index < pinFields.length - 1) {
                        pinFields[index + 1].requestFocus();
                        pinFields[index].setBackgroundResource(R.drawable.bg_pin_digit_active);
                    } else if (s.length() == 0 && index > 0) {
                        pinFields[index - 1].requestFocus();
                        pinFields[index].setBackgroundResource(R.drawable.bg_pin_digit);
                    }

                    if (s.length() == 1 && index == pinFields.length - 1) {
                        verifyPin();
                    }
                }

                @Override
                public void afterTextChanged(Editable s) {}
            });

            pinFields[i].setOnFocusChangeListener((v, hasFocus) -> {
                EditText et = (EditText) v;
                if (hasFocus) {
                    et.setBackgroundResource(R.drawable.bg_pin_digit_active);
                } else if (et.getText().length() == 0) {
                    et.setBackgroundResource(R.drawable.bg_pin_digit);
                }
            });
        }
    }

    private void verifyPin() {
        if (isVerifying) return;

        StringBuilder pin = new StringBuilder();
        for (EditText field : pinFields) {
            pin.append(field.getText().toString());
        }

        if (pin.length() != 6) return;

        isVerifying = true;
        btnVerify.setEnabled(false);
        btnVerify.setText("جارٍ التحقق...");

        String deviceId = ChatPrefs.getOrCreateDeviceId(this);
        String pinStr = pin.toString();
        String deviceName = android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL;

        ChatApiService.getInstance().verifyPin(pinStr, deviceId, deviceName, new ChatApiService.ApiCallback<JSONObject>() {
            @Override
            public void onSuccess(JSONObject result) {
                try {
                    String token = result.getString("token");
                    JSONObject user = result.getJSONObject("user");
                    int userId = user.getInt("id");
                    String userDeviceId = user.getString("device_id");
                    String displayName = user.getString("display_name");
                    String initials = user.getString("initials");
                    String avatarColor = user.getString("avatar_color");
                    String avatarUrl = user.optString("avatar_url", "");
                    String username = user.optString("username", "");
                    String rank = user.optString("rank", "");
                    String jobTitle = user.optString("job_title", "");

                    ChatPrefs chatPrefs = new ChatPrefs(SecurePinActivity.this);
                    chatPrefs.saveAuth(userId, userDeviceId, token, displayName, initials, avatarColor, pinStr);
                    if (avatarUrl != null && !avatarUrl.isEmpty()) chatPrefs.saveAvatarUrl(avatarUrl);
                    chatPrefs.saveUsername(username);
                    chatPrefs.saveRank(rank);
                    chatPrefs.saveJobTitle(jobTitle);

                    Log.d(TAG, "PIN verified! User #" + userId + " (" + displayName + ")");

                    ChatSocketManager.getInstance().connect(userId);

                    goToMain(userId);
                } catch (Exception e) {
                    Log.e(TAG, "Error parsing verify response: " + e.getMessage());
                    onError("\u062E\u0637\u0623 \u0641\u064A \u0627\u0644\u0628\u064A\u0627\u0646\u0627\u062A");
                }
            }

            @Override
            public void onError(String error) {
                isVerifying = false;
                btnVerify.setEnabled(true);
                btnVerify.setText(getString(R.string.pin_verify));

                Toast.makeText(SecurePinActivity.this, error, Toast.LENGTH_SHORT).show();

                // Clear PIN fields
                for (EditText field : pinFields) {
                    field.setText("");
                }
                pinFields[0].requestFocus();
            }
        });
    }

    private void goToMain(int userId) {
        Intent intent = new Intent(this, BookReaderActivity.class);
        intent.putExtra("pin_verified", true);
        intent.putExtra("chat_user_id", userId);
        startActivity(intent);
        finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }
}
