package com.anland.consumer;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.util.LinkedHashSet;

public class KeyInterceptor extends AccessibilityService {
    LinkedHashSet<Integer> pressedKeys = new LinkedHashSet<>();

    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static KeyInterceptor self;
    private boolean enabled = false;

    public KeyInterceptor() {
        self = this;
    }

    public static void shutdown() {
        if (self != null) {
            self.disableSelf();
            self.pressedKeys.clear();
            self = null;
        }
    }

    public static boolean isLaunched() {
        AccessibilityServiceInfo info = self == null ? null : self.getServiceInfo();
        return info != null && info.getId() != null;
    }

    private static final Runnable disableImmediatelyCallback = KeyInterceptor::disableImmediately;
    private static void disableImmediately() {
        if (self == null) return;
        android.util.Log.d("KeyInterceptor", "disabling interception service");
        AccessibilityServiceInfo info = self.getServiceInfo();
        info.flags &= ~AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        self.setServiceInfo(info);
        self.enabled = false;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        self = this;
        android.util.Log.d("KeyInterceptor", "service connected");
        recheck();
    }

    public static void recheck() {
        MainActivity a = getMainActivity();
        boolean shouldBeEnabled = (a != null && self != null) && a.isAccessibilityInterceptEnabled();
        if (self != null && shouldBeEnabled != self.enabled) {
            if (shouldBeEnabled) {
                handler.removeCallbacks(disableImmediatelyCallback);
                android.util.Log.d("KeyInterceptor", "enabling interception service");
                AccessibilityServiceInfo info = self.getServiceInfo();
                info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
                self.setServiceInfo(info);
                self.enabled = true;
            } else {
                handler.postDelayed(disableImmediatelyCallback, 120000);
            }
        }
    }

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        MainActivity instance = getMainActivity();

        if (instance == null)
            return false;

        // Emergency unlock must remain available even if a SystemUI overlay stole
        // focus while locked mode was active.
        if (instance.handleEmergencyExitShortcut(event))
            return true;

        // Only intercept keys when the activity is in foreground and has focus
        if (!instance.hasWindowFocus())
            return false;

        int keyCode = event.getKeyCode();
        boolean releaseTrackedKey = event.getAction() == KeyEvent.ACTION_UP
                && pressedKeys.contains(keyCode);
        boolean intercept = instance.shouldAccessibilityIntercept(event);
        boolean ret = false;
        if (intercept || releaseTrackedKey)
            ret = instance.handleAccessibilityKey(event);

        if (intercept && ret && event.getAction() == KeyEvent.ACTION_DOWN)
            pressedKeys.add(keyCode);
        else if (event.getAction() == KeyEvent.ACTION_UP)
            pressedKeys.remove(keyCode);

        recheck();

        return ret;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {}

    @Override
    public void onInterrupt() {}

    private static MainActivity getMainActivity() {
        // MainActivity is the only activity; we can locate it via the global
        // reference set in onCreate. Since we don't have a static getInstance()
        // on MainActivity, we use the singleton from the launcher's assumption.
        return MainActivity.sInstance;
    }
}
