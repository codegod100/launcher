package dev.ultra.launcher;

/**
 * Calls into the C++ backend (qt/src/backend.cpp registers these). HomeActivity only calls
 * them once the backend has said it is ready, so they are never called unregistered.
 * Geometry is in physical screen px.
 */
final class Native {
    private Native() {}

    static native void appsChanged(String packages); // comma-separated
    static native void appList(String json);         // HomeActivity.listApps' answer
    static native void homePressed();
    static native void backPressed();
    static native void insets(int top, int bottom);
    static native void widgetAdded(int id);
    static native void widgetMenu(int id);
    static native void widgetGrab(int id);
    static native void widgetDrag(int x, int y);
    static native void widgetDrop(boolean moved);
    static native void legacyHome(String json);  // the WebView build's ultra.home.* keys
}
