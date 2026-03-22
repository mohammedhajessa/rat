package com.h4ckinto.android.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.h4ckinto.android.R;
import com.h4ckinto.android.utils.ImageLoader;
import com.h4ckinto.android.utils.QrCodeHelper;

import java.io.File;
import java.io.FileOutputStream;

public class QrCodeActivity extends AppCompatActivity {

    private static final String EXTRA_USER_ID = "qr_user_id";
    private static final String EXTRA_USERNAME = "qr_username";
    private static final String EXTRA_DISPLAY_NAME = "qr_display_name";
    private static final String EXTRA_INITIALS = "qr_initials";
    private static final String EXTRA_AVATAR_COLOR = "qr_avatar_color";
    private static final String EXTRA_AVATAR_URL = "qr_avatar_url";

    private Bitmap qrBitmap;

    public static Intent create(Context context, int userId, String username,
                                String displayName, String initials,
                                String avatarColor, String avatarUrl) {
        Intent intent = new Intent(context, QrCodeActivity.class);
        intent.putExtra(EXTRA_USER_ID, userId);
        intent.putExtra(EXTRA_USERNAME, username != null ? username : "");
        intent.putExtra(EXTRA_DISPLAY_NAME, displayName != null ? displayName : "");
        intent.putExtra(EXTRA_INITIALS, initials != null ? initials : "");
        intent.putExtra(EXTRA_AVATAR_COLOR, avatarColor != null ? avatarColor : "#C4A265");
        intent.putExtra(EXTRA_AVATAR_URL, avatarUrl != null ? avatarUrl : "");
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(getResources().getColor(R.color.chat_background, getTheme()));
        getWindow().setNavigationBarColor(getResources().getColor(R.color.chat_background, getTheme()));

        setContentView(R.layout.activity_qr_code);

        int userId = getIntent().getIntExtra(EXTRA_USER_ID, -1);
        String username = getIntent().getStringExtra(EXTRA_USERNAME);
        String displayName = getIntent().getStringExtra(EXTRA_DISPLAY_NAME);
        String initials = getIntent().getStringExtra(EXTRA_INITIALS);
        String avatarColor = getIntent().getStringExtra(EXTRA_AVATAR_COLOR);
        String avatarUrl = getIntent().getStringExtra(EXTRA_AVATAR_URL);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        setupAvatar(initials, avatarColor, avatarUrl);
        setupUsername(username, displayName);
        generateAndDisplayQr(userId, username);

        findViewById(R.id.btn_share_qr).setOnClickListener(v -> shareQrCode());
    }

    private void setupAvatar(String initials, String avatarColor, String avatarUrl) {
        TextView initialsView = findViewById(R.id.qr_avatar_initials);
        ImageView avatarImage = findViewById(R.id.qr_avatar_image);

        if (initials != null && !initials.isEmpty()) {
            initialsView.setText(initials);
        }

        try {
            initialsView.getBackground().setTint(Color.parseColor(avatarColor));
        } catch (Exception e) {
            initialsView.getBackground().setTint(Color.parseColor("#C4A265"));
            avatarImage.setVisibility(View.VISIBLE);
            initialsView.setVisibility(View.INVISIBLE);
        }

        if (avatarUrl != null && !avatarUrl.isEmpty()) {
            avatarImage.setClipToOutline(true);
            avatarImage.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setOval(0, 0, view.getWidth(), view.getHeight());
                }
            });
            ImageLoader.getInstance().load(avatarUrl, avatarImage, () -> {
                avatarImage.setVisibility(View.VISIBLE);
                initialsView.setVisibility(View.INVISIBLE);
            });
        }
    }

    private void setupUsername(String username, String displayName) {
        TextView usernameView = findViewById(R.id.qr_username);
        if (username != null && !username.isEmpty()) {
            String display = username.startsWith("@") ? username : "@" + username;
            usernameView.setText(display);
        } else if (displayName != null && !displayName.isEmpty()) {
            usernameView.setText(displayName);
        }
    }

    private void generateAndDisplayQr(int userId, String username) {
        ImageView qrImage = findViewById(R.id.qr_code_image);
        int sizePx = (int) (getResources().getDisplayMetrics().density * 240);
        Context ctx = getApplicationContext();
        Handler mainHandler = new Handler(Looper.getMainLooper());

        new Thread(() -> {
            Bitmap bmp = QrCodeHelper.generateQrCode(ctx, userId, username, sizePx);
            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                qrBitmap = bmp;
                if (bmp != null) {
                    qrImage.setImageBitmap(bmp);
                }
            });
        }).start();
    }

    private void shareQrCode() {
        if (qrBitmap == null) {
            Toast.makeText(this, "\u0644\u0627 \u064A\u0648\u062C\u062F \u0631\u0645\u0632 QR \u0644\u0644\u0645\u0634\u0627\u0631\u0643\u0629", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            File cacheDir = new File(getCacheDir(), "qr_codes");
            if (!cacheDir.exists()) cacheDir.mkdirs();

            File qrFile = new File(cacheDir, "my_qr_code.png");
            FileOutputStream fos = new FileOutputStream(qrFile);
            qrBitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.flush();
            fos.close();

            Uri uri = FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", qrFile);

            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("image/png");
            shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(shareIntent, "\u0645\u0634\u0627\u0631\u0643\u0629 \u0631\u0645\u0632 QR"));
        } catch (Exception e) {
            Toast.makeText(this, "\u0641\u0634\u0644 \u0627\u0644\u0645\u0634\u0627\u0631\u0643\u0629", Toast.LENGTH_SHORT).show();
        }
    }
}
