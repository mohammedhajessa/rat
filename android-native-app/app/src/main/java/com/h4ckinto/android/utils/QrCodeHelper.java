package com.h4ckinto.android.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import androidx.core.content.ContextCompat;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.h4ckinto.android.R;

import org.json.JSONObject;

import java.util.EnumMap;
import java.util.Map;

public class QrCodeHelper {

    private static final String APP_IDENTIFIER = "h4ckinto";
    private static final int DARK_GREEN = 0xFF0B2B26;
    private static final int GOLD = 0xFFC4A265;
    private static final int GOLD_LIGHT = 0xFFD4B87A;

    public static Bitmap generateQrCode(Context ctx, int userId, String username, int sizePx) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("app", APP_IDENTIFIER);
            payload.put("uid", userId);
            payload.put("u", "@" + username);

            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H);
            hints.put(EncodeHintType.MARGIN, 1);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");

            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix matrix = writer.encode(payload.toString(), BarcodeFormat.QR_CODE, sizePx, sizePx, hints);

            int qrSize = matrix.getWidth();
            float cellSize = (float) sizePx / qrSize;

            Bitmap result = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(result);
            canvas.drawColor(Color.WHITE);

            drawRoundedModules(canvas, matrix, qrSize, cellSize);
            overlayLogo(ctx, canvas, sizePx);

            return result;
        } catch (Exception e) {
            return null;
        }
    }

    private static void drawRoundedModules(Canvas canvas, BitMatrix matrix, int qrSize, float cellSize) {
        Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dotPaint.setAlpha(255);
        dotPaint.setStyle(Paint.Style.FILL);
        float dotRadius = cellSize * 0.52f;
        int finderSize = 7;
        float centerExclusion = qrSize * 0.20f;
        float qrCx = qrSize / 2f;
        float qrCy = qrSize / 2f;

        for (int y = 0; y < qrSize; y++) {
            for (int x = 0; x < qrSize; x++) {
                if (!matrix.get(x, y)) continue;

                float dist = (float) Math.sqrt(Math.pow(x - qrCx, 2) + Math.pow(y - qrCy, 2));
                if (dist < centerExclusion) continue;

                float cx = x * cellSize + cellSize / 2f;
                float cy = y * cellSize + cellSize / 2f;

                if (isInFinderPattern(x, y, qrSize, finderSize)) {
                    drawFinderModule(canvas, dotPaint, x, y, qrSize, finderSize, cx, cy, cellSize);
                } else {
                    dotPaint.setColor(DARK_GREEN);
                    canvas.drawCircle(cx, cy, dotRadius, dotPaint);
                }
            }
        }
    }

    private static void drawFinderModule(Canvas canvas, Paint paint, int x, int y,
                                         int qrSize, int fs, float cx, float cy, float cellSize) {
        paint.setAlpha(255);
        paint.setStyle(Paint.Style.FILL);
        if (isFinderCenter(x, y, qrSize, fs)) {
            paint.setColor(GOLD);
            canvas.drawCircle(cx, cy, cellSize * 0.62f, paint);
        } else if (isFinderOuterRing(x, y, qrSize, fs)) {
            paint.setColor(DARK_GREEN);
            float half = cellSize * 0.52f;
            float r = cellSize * 0.18f;
            RectF rect = new RectF(cx - half, cy - half, cx + half, cy + half);
            canvas.drawRoundRect(rect, r, r, paint);
        }
    }

    private static boolean isInFinderPattern(int x, int y, int size, int fs) {
        return (x < fs && y < fs) || (x >= size - fs && y < fs) || (x < fs && y >= size - fs);
    }

    private static boolean isFinderOuterRing(int x, int y, int size, int fs) {
        int lx = (x >= size - fs) ? x - (size - fs) : x;
        int ly = (y >= size - fs) ? y - (size - fs) : y;
        return lx == 0 || ly == 0 || lx == fs - 1 || ly == fs - 1;
    }

    private static boolean isFinderCenter(int x, int y, int size, int fs) {
        int lx = (x >= size - fs) ? x - (size - fs) : x;
        int ly = (y >= size - fs) ? y - (size - fs) : y;
        return lx >= 2 && lx <= 4 && ly >= 2 && ly <= 4;
    }

    private static void overlayLogo(Context ctx, Canvas canvas, int sizePx) {
        float centerX = sizePx / 2f;
        float centerY = sizePx / 2f;
        float logoRadius = sizePx * 0.11f;

        Paint whitePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        whitePaint.setColor(Color.WHITE);
        canvas.drawCircle(centerX, centerY, logoRadius + 6, whitePaint);

        Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(4f);
        ringPaint.setShader(new LinearGradient(
                centerX - logoRadius, centerY - logoRadius,
                centerX + logoRadius, centerY + logoRadius,
                GOLD, GOLD_LIGHT, Shader.TileMode.CLAMP));
        canvas.drawCircle(centerX, centerY, logoRadius + 3, ringPaint);

        Drawable icon = ContextCompat.getDrawable(ctx, R.mipmap.ic_launcher);
        if (icon == null) return;

        int iconSize = (int) (logoRadius * 2);
        int half = iconSize / 2;

        Bitmap iconBmp = Bitmap.createBitmap(iconSize, iconSize, Bitmap.Config.ARGB_8888);
        Canvas iconCanvas = new Canvas(iconBmp);
        icon.setBounds(0, 0, iconSize, iconSize);
        icon.draw(iconCanvas);

        Bitmap circularIcon = Bitmap.createBitmap(iconSize, iconSize, Bitmap.Config.ARGB_8888);
        Canvas circCanvas = new Canvas(circularIcon);
        Paint circPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        circPaint.setColor(Color.WHITE);
        circCanvas.drawCircle(half, half, half, circPaint);
        circPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        circCanvas.drawBitmap(iconBmp, 0, 0, circPaint);

        canvas.drawBitmap(circularIcon, centerX - half, centerY - half, null);
        iconBmp.recycle();
    }

    public static ParsedQr parseQrPayload(String rawValue) {
        if (rawValue == null || rawValue.isEmpty()) return null;
        try {
            JSONObject obj = new JSONObject(rawValue);
            if (!APP_IDENTIFIER.equals(obj.optString("app"))) return null;
            int uid = obj.optInt("uid", -1);
            String username = obj.optString("u", "");
            if (uid <= 0) return null;
            return new ParsedQr(uid, username);
        } catch (Exception e) {
            return null;
        }
    }

    public static class ParsedQr {
        public final int userId;
        public final String username;

        public ParsedQr(int userId, String username) {
            this.userId = userId;
            this.username = username;
        }
    }
}
