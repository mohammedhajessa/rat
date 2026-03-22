package com.h4ckinto.android.ui.views;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import com.h4ckinto.android.R;

public class RecordingWaveformView extends View {

    private static final int BAR_COUNT = 24;
    private static final float BAR_WIDTH_RATIO = 0.50f;
    private static final float BAR_CORNER_RADIUS = 3f;

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF barRect = new RectF();

    private float animPhase = 0f;
    private ValueAnimator animator;

    public RecordingWaveformView(Context context) {
        super(context);
        init();
    }

    public RecordingWaveformView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public RecordingWaveformView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        barPaint.setStyle(Paint.Style.FILL);
        barPaint.setColor(getContext().getResources().getColor(R.color.chat_accent, getContext().getTheme()));
    }

    public void startAnimating() {
        if (animator != null && animator.isRunning()) return;
        animator = ValueAnimator.ofFloat(0f, (float) (2 * Math.PI));
        animator.setDuration(1200);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            animPhase = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    public void stopAnimating() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopAnimating();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        float totalBarWidth = (float) w / BAR_COUNT;
        float barWidth = totalBarWidth * BAR_WIDTH_RATIO;
        float gap = totalBarWidth - barWidth;
        float centerY = h / 2f;

        for (int i = 0; i < BAR_COUNT; i++) {
            float wave1 = (float) Math.sin(animPhase + i * 0.45);
            float wave2 = (float) Math.sin(animPhase * 1.3 + i * 0.7);
            float combined = (wave1 * 0.6f + wave2 * 0.4f + 1f) / 2f;

            float normalizedHeight = 0.15f + combined * 0.85f;
            float barH = normalizedHeight * h * 0.80f;
            float halfH = barH / 2f;
            float x = i * totalBarWidth + gap / 2f;

            float alpha = 0.5f + combined * 0.5f;
            barPaint.setAlpha((int) (alpha * 255));

            barRect.set(x, centerY - halfH, x + barWidth, centerY + halfH);
            canvas.drawRoundRect(barRect, BAR_CORNER_RADIUS, BAR_CORNER_RADIUS, barPaint);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desiredHeight = (int) (32 * getResources().getDisplayMetrics().density);
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
