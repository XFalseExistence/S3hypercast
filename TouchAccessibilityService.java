package com.telephonichoudini.s3cast;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;

public class TouchAccessibilityService extends AccessibilityService {
    private static volatile TouchAccessibilityService instance;
    private float downX, downY, lastX, lastY;
    private long downAt;
    private boolean down;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    public static void remoteTouch(int panelX, int panelY, int state) {
        TouchAccessibilityService s = instance;
        if (s != null) s.handleRemoteTouch(panelX, panelY, state);
    }

    private void handleRemoteTouch(int panelX, int panelY, int state) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float x = clamp(panelX / 1023f, 0f, 1f) * Math.max(1, dm.widthPixels - 1);
        float y = clamp(panelY / 599f, 0f, 1f) * Math.max(1, dm.heightPixels - 1);

        if (state == 1) { // DOWN
            down = true;
            downX = lastX = x;
            downY = lastY = y;
            downAt = android.os.SystemClock.uptimeMillis();
        } else if (state == 2 && down) { // MOVE
            lastX = x;
            lastY = y;
        } else if (state == 0 && down) { // UP
            lastX = x;
            lastY = y;
            long elapsed = android.os.SystemClock.uptimeMillis() - downAt;
            float dx = lastX - downX;
            float dy = lastY - downY;
            float dist2 = dx * dx + dy * dy;
            if (dist2 < 900f) dispatchTap(lastX, lastY);
            else dispatchDrag(downX, downY, lastX, lastY, Math.max(100, Math.min(900, elapsed)));
            down = false;
        }
    }

    private void dispatchTap(float x, float y) {
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(p, 0, 55);
        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(stroke);
        dispatchGesture(b.build(), null, null);
    }

    private void dispatchDrag(float x0, float y0, float x1, float y1, long duration) {
        Path p = new Path();
        p.moveTo(x0, y0);
        p.lineTo(x1, y1);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(p, 0, duration);
        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(stroke);
        dispatchGesture(b.build(), null, null);
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }
    @Override public void onDestroy() { if (instance == this) instance = null; super.onDestroy(); }
}
