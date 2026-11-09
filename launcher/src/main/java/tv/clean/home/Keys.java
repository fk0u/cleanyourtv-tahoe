package tv.clean.home;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Build;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/** The system sends the Apps button to the *system* home (here tv.settings, which ignores it),
 *  so catch the key itself and open our Launchpad. All other keys are passed through.
 *  Also lets the phone remote press D-pad/OK inside other apps (Android can't inject keys into another app). */
public class Keys extends AccessibilityService {
    static Keys inst; // lets the phone remote send Back/Home while another app is in front

    @Override protected void onServiceConnected() { inst = this; }
    @Override public boolean onUnbind(Intent i) { inst = null; return super.onUnbind(i); }

    @Override protected boolean onKeyEvent(KeyEvent e) {
        if (e.getKeyCode() != KeyEvent.KEYCODE_ALL_APPS) return false;
        if (e.getAction() == KeyEvent.ACTION_UP)
            startActivity(new Intent(Intent.ACTION_ALL_APPS).setClass(this, Home.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        return true;
    }
    /** D-pad/OK for whatever app is in front. Android 13+ has real D-pad actions; older TVs move focus / click the focused view. */
    void press(int code) {
        if (Build.VERSION.SDK_INT >= 33) {
            int a = code == KeyEvent.KEYCODE_DPAD_UP ? GLOBAL_ACTION_DPAD_UP : code == KeyEvent.KEYCODE_DPAD_DOWN ? GLOBAL_ACTION_DPAD_DOWN
                    : code == KeyEvent.KEYCODE_DPAD_LEFT ? GLOBAL_ACTION_DPAD_LEFT : code == KeyEvent.KEYCODE_DPAD_RIGHT ? GLOBAL_ACTION_DPAD_RIGHT
                    : code == KeyEvent.KEYCODE_DPAD_CENTER ? GLOBAL_ACTION_DPAD_CENTER : 0;
            if (a != 0 && performGlobalAction(a)) return;
        }
        // Service-level findFocus() returns null on Android 9 (Mi TV); the active window's root works.
        AccessibilityNodeInfo root = getRootInActiveWindow();
        AccessibilityNodeInfo f = root == null ? null : root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (f == null) return;
        if (code == KeyEvent.KEYCODE_DPAD_CENTER) { f.performAction(AccessibilityNodeInfo.ACTION_CLICK); return; }
        int dir = code == KeyEvent.KEYCODE_DPAD_UP ? View.FOCUS_UP : code == KeyEvent.KEYCODE_DPAD_DOWN ? View.FOCUS_DOWN
                : code == KeyEvent.KEYCODE_DPAD_LEFT ? View.FOCUS_LEFT : code == KeyEvent.KEYCODE_DPAD_RIGHT ? View.FOCUS_RIGHT : 0;
        AccessibilityNodeInfo n = dir == 0 ? null : f.focusSearch(dir);
        if (n != null) n.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {}
    @Override public void onInterrupt() {}
}
