package tv.clean.home;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/** The system sends the Apps button to the *system* home (here tv.settings, which ignores it),
 *  so catch the key itself and open our Launchpad. All other keys are passed through. */
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
    @Override public void onAccessibilityEvent(AccessibilityEvent e) {}
    @Override public void onInterrupt() {}
}
