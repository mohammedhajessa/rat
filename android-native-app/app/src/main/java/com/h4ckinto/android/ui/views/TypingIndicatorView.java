package com.h4ckinto.android.ui.views;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import com.h4ckinto.android.R;

public class TypingIndicatorView extends View {

    private static final int DOT_COUNT = 3;
    private static final float DOT_RADIUS_DP = 3.5f;
    private static final float DOT_SPACING_DP = 8f;
    private static final float BOUNCE_HEIGHT_DP = 4f;

    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float dotRadius;
    private float dotSpacing;
    private float bounceHeight;
    private float animPhase = 0f;
    private ValueAnimator animator;

    public TypingIndicatorView(Context context) {
        super(context);
        init();
    }

    public TypingIndicatorView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public TypingIndicatorView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        float density = getResources().getDisplayMetrics().density;
        dotRadius = DOT_RADIUS_DP * density;
        dotSpacing = DOT_SPACING_DP * density;
        bounceHeight = BOUNCE_HEIGHT_DP * density;

        dotPaint.setStyle(Paint.Style.FILL);
        dotPaint.setColor(getContext().getResources().getColor(R.color.chat_accent, getContext().getTheme()));
    }

    public void startAnimating() {
        if (animator != null && animator.isRunning()) return;
        setVisibility(VISIBLE);
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(1000);
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
        setVisibility(GONE);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w == 0 || h == 0) return;

        float totalWidth = (DOT_COUNT - 1) * dotSpacing;
        float startX = (w - totalWidth) / 2f;
        float baseY = h / 2f;

        for (int i = 0; i < DOT_COUNT; i++) {
            float phase = animPhase - (i * 0.15f);
            if (phase < 0) phase += 1f;

            float bounce = 0;
            if (phase < 0.4f) {
                float t = phase / 0.4f;
                bounce = (float) Math.sin(t * Math.PI) * bounceHeight;
            }

            float alpha = 0.4f + 0.6f * (bounce / bounceHeight);
            if (bounce <= 0) alpha = 0.4f;
            dotPaint.setAlpha((int) (alpha * 255));

            float cx = startX + i * dotSpacing;
            float cy = baseY - bounce;

            canvas.drawCircle(cx, cy, dotRadius, dotPaint);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        float density = getResources().getDisplayMetrics().density;
        int desiredWidth = (int) ((DOT_COUNT - 1) * DOT_SPACING_DP * density + DOT_RADIUS_DP * 2 * density + 8 * density);
        int desiredHeight = (int) (16 * density);

        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int width;
        if (widthMode == MeasureSpec.EXACTLY) {
            width = MeasureSpec.getSize(widthMeasureSpec);
        } else {
            width = desiredWidth;
            if (widthMode == MeasureSpec.AT_MOST) {
                width = Math.min(width, MeasureSpec.getSize(widthMeasureSpec));
            }
        }

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
