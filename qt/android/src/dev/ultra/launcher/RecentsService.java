package dev.ultra.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;

/**
 * An accessibility service that does nothing but open Recents (the system's list of open
 * windows) for the home screen's Windows button. Android lets no other app open Recents,
 * so the user turns this on once under Settings > Accessibility. It reads no window
 * content and asks for no events.
 */
public class RecentsService extends AccessibilityService {
    private static volatile RecentsService running;

    /** Open Recents; false when the service isn't turned on. */
    static boolean showRecents() {
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
