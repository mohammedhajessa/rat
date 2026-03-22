package com.h4ckinto.android.ui.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import com.h4ckinto.android.R;

public class VoiceWaveformView extends View {

    private static final int BAR_COUNT = 28;
    private static final float BAR_WIDTH_RATIO = 0.55f;
    private static final float MIN_BAR_HEIGHT = 0.15f;
    private static final float BAR_CORNER_RADIUS = 2f;

    private final Paint playedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint unplayedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF barRect = new RectF();

    private float[] barHeights;
    private float progress = 0f;
    private int maxProgress = 100;
    private long seed = 0;
    private boolean isSentMessage = true;

    public VoiceWaveformView(Context context) {
        super(context);
        init();
    }

    public VoiceWaveformView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public VoiceWaveformView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        playedPaint.setStyle(Paint.Style.FILL);
        unplayedPaint.setStyle(Paint.Style.FILL);

        playedPaint.setColor(0xFFFFFFFF);
        unplayedPaint.setColor(0x50FFFFFF);

        generateBars(0);
    }

    public void setSentStyle(boolean isSent) {
        this.isSentMessage = isSent;
        if (isSent) {
            playedPaint.setColor(0xFFFFFFFF);
            unplayedPaint.setColor(0x50FFFFFF);
        } else {
            playedPaint.setColor(getContext().getResources().getColor(R.color.chat_accent, getContext().getTheme()));
            unplayedPaint.setColor(0x40FFFFFF);
        }
        invalidate();
    }

    public void setSeed(long seed) {
        if (this.seed != seed) {
            this.seed = seed;
            generateBars(seed);
            invalidate();
        }
    }

    public void setMax(int max) {
        this.maxProgress = Math.max(1, max);
    }

    public void setProgress(int current) {
        this.progress = Math.min(1f, Math.max(0f, (float) current / maxProgress));
        invalidate();
    }

    public void resetProgress() {
        this.progress = 0f;
        invalidate();
    }

    private void generateBars(long seed) {
        barHeights = new float[BAR_COUNT];
        long s = seed == 0 ? 42 : seed;
        for (int i = 0; i < BAR_COUNT; i++) {
            s = (s * 6364136223846793005L + 1442695040888963407L);
            float raw = ((s >> 33) & 0x7FFFFFFF) / (float) 0x7FFFFFFF;

            float envelope = 1f - Math.abs((float) i / BAR_COUNT - 0.4f) * 1.2f;
            envelope = Math.max(0.3f, Math.min(1f, envelope));

            barHeights[i] = MIN_BAR_HEIGHT + (1f - MIN_BAR_HEIGHT) * raw * envelope;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (barHeights == null) return;

        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        float totalBarWidth = (float) w / BAR_COUNT;
        float barWidth = totalBarWidth * BAR_WIDTH_RATIO;
        float gap = totalBarWidth - barWidth;
        float centerY = h / 2f;

        float progressX = progress * w;

        for (int i = 0; i < BAR_COUNT; i++) {
            float barH = barHeights[i] * h * 0.85f;
            float halfH = barH / 2f;
            float x = i * totalBarWidth + gap / 2f;

            barRect.set(x, centerY - halfH, x + barWidth, centerY + halfH);

            float barCenter = x + barWidth / 2f;
            canvas.drawRoundRect(barRect, BAR_CORNER_RADIUS, BAR_CORNER_RADIUS,
                    barCenter <= progressX ? playedPaint : unplayedPaint);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desiredHeight = (int) (28 * getResources().getDisplayMetrics().density);
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        int height;
        if (heightMode == MeasureSpec.EXACTLY) {
            height = MeasureSpec.getSize(heightMeasureSpec);
        } else {
            height = desiredHeight;
            if (heightMode == MeasureSpec.AT_MOST) {
                height = Math.min(height, MeasureSpec.getSize(heightMeasureSpec));
            }
        }
        setMeasuredDimension(width, height);
    }
}
