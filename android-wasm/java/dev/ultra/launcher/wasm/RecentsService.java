package dev.ultra.launcher.wasm;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Accessibility service that opens Recents (the system's list of open windows)
 * for the home screen's Windows button.
 */
public class RecentsService extends AccessibilityService {
    private static volatile RecentsService running;

    public static boolean showRecents() {
        RecentsService s = running;
        return s != null && s.performGlobalAction(GLOBAL_ACTION_RECENTS);
    }

    @Override
    protected void onServiceConnected() {
        running = this;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        running = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        running = null;
        super.onDestroy();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }
}
