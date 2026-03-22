package com.h4ckinto.android.ui.views;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.animation.DecelerateInterpolator;
import android.widget.OverScroller;

import androidx.appcompat.widget.AppCompatImageView;

public class ZoomableImageView extends AppCompatImageView {

    private static final float MIN_SCALE = 1.0f;
    private static final float MAX_SCALE = 5.0f;
    private static final float DOUBLE_TAP_SCALE = 2.5f;

    private final Matrix imageMatrix = new Matrix();
    private final Matrix savedMatrix = new Matrix();
    private final float[] matrixValues = new float[9];
    private final RectF displayRect = new RectF();

    private ScaleGestureDetector scaleDetector;
    private GestureDetector gestureDetector;
    private OverScroller scroller;

    private boolean isScaling = false;
    private int activePointerId = -1;
    private final PointF lastTouch = new PointF();

    private OnSingleTapListener singleTapListener;
    private OnSwipeDownListener swipeDownListener;

    private float swipeDownTranslation = 0;
    private boolean swipeDownActive = false;
    private float swipeDownStartY = -1;

    public interface OnSingleTapListener {
        void onSingleTap();
    }

    public interface OnSwipeDownListener {
        void onSwipeDown(float progress);
        void onSwipeDismiss();
        void onSwipeCancel();
    }

    public ZoomableImageView(Context context) {
        super(context);
        init(context);
    }

    public ZoomableImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public ZoomableImageView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        setScaleType(ScaleType.MATRIX);
        scroller = new OverScroller(context, new DecelerateInterpolator());

        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector detector) {
                isScaling = true;
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                float factor = detector.getScaleFactor();
                float currentScale = getCurrentScale();
                float targetScale = currentScale * factor;

                if (targetScale > MAX_SCALE) factor = MAX_SCALE / currentScale;
                if (targetScale < MIN_SCALE * 0.5f) factor = (MIN_SCALE * 0.5f) / currentScale;

                imageMatrix.postScale(factor, factor, detector.getFocusX(), detector.getFocusY());
                constrainMatrix();
                setImageMatrix(imageMatrix);
                return true;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector detector) {
                isScaling = false;
                float scale = getCurrentScale();
                if (scale < MIN_SCALE) {
                    animateScaleTo(MIN_SCALE);
                }
            }
        });

        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                float currentScale = getCurrentScale();
                float targetScale;
                if (currentScale < DOUBLE_TAP_SCALE - 0.1f) {
                    targetScale = DOUBLE_TAP_SCALE;
                } else {
                    targetScale = MIN_SCALE;
                }
                animateScaleTo(targetScale, e.getX(), e.getY());
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (singleTapListener != null) singleTapListener.onSingleTap();
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (e1 == null || e2 == null) return false;
                if (getCurrentScale() <= MIN_SCALE) return false;

                RectF rect = getDisplayRect();
                if (rect == null) return false;

                int startX = (int) -rect.left;
                int startY = (int) -rect.top;
                int maxX = (int) Math.max(0, rect.width() - getWidth());
                int maxY = (int) Math.max(0, rect.height() - getHeight());

                scroller.fling(startX, startY, (int) -velocityX, (int) -velocityY,
                        0, maxX, 0, maxY, getWidth() / 4, getHeight() / 4);
                postOnAnimation(flingRunnable);
                return true;
            }
        });
    }

    private final Runnable flingRunnable = new Runnable() {
        @Override
        public void run() {
            if (scroller.computeScrollOffset()) {
                RectF rect = getDisplayRect();
                if (rect != null) {
                    float dx = -(scroller.getCurrX() + rect.left);
                    float dy = -(scroller.getCurrY() + rect.top);
                    imageMatrix.postTranslate(dx, dy);
                    constrainMatrix();
                    setImageMatrix(imageMatrix);
                }
                postOnAnimation(this);
            }
        }
    };

    public void setOnSingleTapListener(OnSingleTapListener listener) {
        this.singleTapListener = listener;
    }

    public void setOnSwipeDownListener(OnSwipeDownListener listener) {
        this.swipeDownListener = listener;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);

        if (isScaling) return true;

        float currentScale = getCurrentScale();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                scroller.forceFinished(true);
                activePointerId = event.getPointerId(0);
                lastTouch.set(event.getX(), event.getY());
                savedMatrix.set(imageMatrix);
                swipeDownStartY = event.getY();
                swipeDownActive = false;
                swipeDownTranslation = 0;
                break;

            case MotionEvent.ACTION_MOVE:
                if (activePointerId == -1) break;
                int pointerIndex = event.findPointerIndex(activePointerId);
                if (pointerIndex < 0) break;

                float dx = event.getX(pointerIndex) - lastTouch.x;
                float dy = event.getY(pointerIndex) - lastTouch.y;

                if (currentScale <= MIN_SCALE + 0.01f && !swipeDownActive && event.getPointerCount() == 1) {
                    float totalDy = event.getY(pointerIndex) - swipeDownStartY;
                    if (totalDy > 30 && Math.abs(totalDy) > Math.abs(dx) * 1.5f) {
                        swipeDownActive = true;
                    }
                }

                if (swipeDownActive && event.getPointerCount() == 1) {
                    float totalDy = event.getY(pointerIndex) - swipeDownStartY;
                    swipeDownTranslation = Math.max(0, totalDy);
                    setTranslationY(swipeDownTranslation);
                    float progress = Math.min(1f, swipeDownTranslation / (getHeight() * 0.35f));
                    if (swipeDownListener != null) swipeDownListener.onSwipeDown(progress);
                } else if (currentScale > MIN_SCALE + 0.01f) {
                    imageMatrix.set(savedMatrix);
                    imageMatrix.postTranslate(dx, dy);
                    constrainMatrix();
                    setImageMatrix(imageMatrix);
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (swipeDownActive) {
                    if (swipeDownTranslation > getHeight() * 0.2f) {
                        if (swipeDownListener != null) swipeDownListener.onSwipeDismiss();
                    } else {
                        animate().translationY(0).setDuration(200).start();
                        if (swipeDownListener != null) swipeDownListener.onSwipeCancel();
                    }
                    swipeDownActive = false;
                }
                activePointerId = -1;
                break;

            case MotionEvent.ACTION_POINTER_UP:
                int upIndex = event.getActionIndex();
                int upId = event.getPointerId(upIndex);
                if (upId == activePointerId) {
                    int newIndex = upIndex == 0 ? 1 : 0;
                    if (newIndex < event.getPointerCount()) {
                        activePointerId = event.getPointerId(newIndex);
                        lastTouch.set(event.getX(newIndex), event.getY(newIndex));
                        savedMatrix.set(imageMatrix);
                    }
                }
                break;
        }
        return true;
    }

    public void resetZoom() {
        imageMatrix.reset();
        fitImageToView();
        setImageMatrix(imageMatrix);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        fitImageToView();
    }

    @Override
    public void setImageDrawable(Drawable drawable) {
        super.setImageDrawable(drawable);
        post(this::fitImageToView);
    }

    private void fitImageToView() {
        Drawable d = getDrawable();
        if (d == null) return;
        int dw = d.getIntrinsicWidth();
        int dh = d.getIntrinsicHeight();
        int vw = getWidth();
        int vh = getHeight();
        if (dw <= 0 || dh <= 0 || vw <= 0 || vh <= 0) return;

        float scaleX = (float) vw / dw;
        float scaleY = (float) vh / dh;
        float scale = Math.min(scaleX, scaleY);

        float tx = (vw - dw * scale) / 2f;
        float ty = (vh - dh * scale) / 2f;

        imageMatrix.reset();
        imageMatrix.postScale(scale, scale);
        imageMatrix.postTranslate(tx, ty);
        setImageMatrix(imageMatrix);
    }

    private float getCurrentScale() {
        imageMatrix.getValues(matrixValues);
        return matrixValues[Matrix.MSCALE_X];
    }

    private float getBaseScale() {
        Drawable d = getDrawable();
        if (d == null) return 1f;
        int dw = d.getIntrinsicWidth();
        int dh = d.getIntrinsicHeight();
        int vw = getWidth();
        int vh = getHeight();
        if (dw <= 0 || dh <= 0 || vw <= 0 || vh <= 0) return 1f;
        return Math.min((float) vw / dw, (float) vh / dh);
    }

    private RectF getDisplayRect() {
        Drawable d = getDrawable();
        if (d == null) return null;
        displayRect.set(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight());
        imageMatrix.mapRect(displayRect);
        return displayRect;
    }

    private void constrainMatrix() {
        RectF rect = getDisplayRect();
        if (rect == null) return;

        float dx = 0, dy = 0;
        int vw = getWidth();
        int vh = getHeight();

        if (rect.width() <= vw) {
            dx = (vw - rect.width()) / 2f - rect.left;
        } else {
            if (rect.left > 0) dx = -rect.left;
            else if (rect.right < vw) dx = vw - rect.right;
        }

        if (rect.height() <= vh) {
            dy = (vh - rect.height()) / 2f - rect.top;
        } else {
            if (rect.top > 0) dy = -rect.top;
            else if (rect.bottom < vh) dy = vh - rect.bottom;
        }

        if (dx != 0 || dy != 0) {
            imageMatrix.postTranslate(dx, dy);
        }
    }

    private void animateScaleTo(float targetScale) {
        animateScaleTo(targetScale, getWidth() / 2f, getHeight() / 2f);
    }

    private void animateScaleTo(float targetScale, float pivotX, float pivotY) {
        float startScale = getCurrentScale();
        if (startScale <= 0) return;
        final int duration = 250;
        final long startTime = System.currentTimeMillis();

        post(new Runnable() {
            @Override
            public void run() {
                if (getDrawable() == null) return;
                long elapsed = System.currentTimeMillis() - startTime;
                float t = Math.min(1f, (float) elapsed / duration);
                t = 1f - (1f - t) * (1f - t);

                float currentS = getCurrentScale();
                if (currentS <= 0) return;
                float interp = startScale + (targetScale - startScale) * t;
                float factor = interp / currentS;

                imageMatrix.postScale(factor, factor, pivotX, pivotY);
                constrainMatrix();
                setImageMatrix(imageMatrix);

                if (t < 1f) post(this);
            }
        });
    }
}
