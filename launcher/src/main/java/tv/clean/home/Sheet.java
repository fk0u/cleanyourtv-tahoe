package tv.clean.home;

import android.view.KeyEvent;

/** A modal full-screen layer over the home. key() returns false to let Home handle it (Back closes). */
interface Sheet {
    boolean key(KeyEvent e);
    void onClose();
}
