package com.ansub.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * Opens this home screen whenever Google TV's own home screen comes to the front.
 *
 * Google TV keeps its home screen as the Home button's target even when another app holds the
 * HOME role, and the Home key never reaches accessibility key filters there. So instead of
 * catching the key, this watches which window is in front and switches back. It only reacts to
 * Google TV's HomeActivity, so its other screens (accounts, panels) still open normally.
 * Reads no window content. Turn it off in Accessibility settings to undo.
 */
public final class HomeRedirectService extends AccessibilityService {
    private static final String TAG = "TvHomeRedirect";
    private static final String GTV = "com.google.android.apps.tv.launcherx";
    private static final String GTV_HOME = GTV + ".home.HomeActivity";
    /** No wait: switch on the next main-loop turn, if Google TV's home is still in front. */
    private static final long GUARD_MS = 0;
    /** Service starting this soon after power-on counts as boot (it starts ~28 s in on this TV). */
    private static final long BOOT_WINDOW_MS = 120_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean gtvHomeInFront;
    private boolean pending;

    private final Runnable redirect = new Runnable() {
        @Override public void run() {
            pending = false;
            if (!gtvHomeInFront) return;
            try {
                startActivity(new Intent(HomeRedirectService.this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException e) {
                Log.w(TAG, "Could not open the home screen", e);
            }
        }
    };

    @Override protected void onServiceConnected() {
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 100;
        setServiceInfo(info);
        // At power-on Google TV's home is shown a few seconds before this service starts, so its
        // window event is missed. Right after boot, assume it's in front and switch once. Later
        // restarts of the service (app update, crash) never pull you out of what you're watching.
        if (SystemClock.elapsedRealtime() < BOOT_WINDOW_MS && !pending) {
            gtvHomeInFront = true;
            pending = true;
            handler.post(redirect);
        }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null || "com.android.systemui".contentEquals(pkg)) return;
        CharSequence cls = event.getClassName();
        if (GTV.contentEquals(pkg)) {
            String c = cls == null ? "" : cls.toString();
            if (c.equals(GTV_HOME)) gtvHomeInFront = true;
            else if (c.endsWith("Activity")) gtvHomeInFront = false;
            // Other Google TV events are views redrawing; keep the current state.
        } else {
            gtvHomeInFront = false;
        }
        if (gtvHomeInFront && !pending) {
            pending = true;
            handler.postDelayed(redirect, GUARD_MS);
        }
    }

    @Override public void onInterrupt() { }

    @Override public void onDestroy() {
        handler.removeCallbacks(redirect);
        super.onDestroy();
    }
}
