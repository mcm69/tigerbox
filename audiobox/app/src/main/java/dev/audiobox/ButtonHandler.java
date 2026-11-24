package dev.audiobox;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;

/**
 * The big button (APP-SPEC section 4). The keymap turns it into MEDIA_PLAY_PAUSE;
 * it arrives here from the MediaSession (screen off) or from HomeActivity (screen
 * on), as plain down/up with no reliable repeats, so the hold is timed here:
 *
 *   short press -> play/pause
 *   hold ~3 s   -> power off (the PMIC cuts power by itself at ~6-8 s)
 */
final class ButtonHandler {
    static final long HOLD_TO_OFF_MS = 3_000;
    /** Lights the ring red as a warning before the power-off fires. */
    static final long WARN_AFTER_MS = 1_000;

    private static ButtonHandler instance;

    static synchronized ButtonHandler get(Context c) {
        if (instance == null) instance = new ButtonHandler(c.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private long downAt = -1;
    private boolean fired;

    private final Runnable warn = () -> Lights.get().powerOffWarning(true);
    private final Runnable holdDone = () -> {
        fired = true;
        powerOff();
    };

    private ButtonHandler(Context app) {
        this.app = app;
    }

    /** Returns true if the key was handled. */
    boolean onKey(KeyEvent k) {
        switch (k.getKeyCode()) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
                return onBigButton(k);
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                if (k.getAction() == KeyEvent.ACTION_DOWN) PlayerService.send(app, PlayerService.ACTION_TOGGLE);
                return true;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                if (k.getAction() == KeyEvent.ACTION_DOWN) PlayerService.send(app, PlayerService.ACTION_NEXT);
                return true;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                if (k.getAction() == KeyEvent.ACTION_DOWN) PlayerService.send(app, PlayerService.ACTION_PREV);
                return true;
            default:
                return false;
        }
    }

    private boolean onBigButton(KeyEvent k) {
        if (k.getAction() == KeyEvent.ACTION_DOWN) {
            if (k.getRepeatCount() > 0 || downAt >= 0) return true; // already timing
            downAt = SystemClock.uptimeMillis();
            fired = false;
            main.postDelayed(warn, WARN_AFTER_MS);
            main.postDelayed(holdDone, HOLD_TO_OFF_MS);
            Screen.wake(app); // a reaction the child can see (APP-SPEC section 4)
            return true;
        }
        if (k.getAction() == KeyEvent.ACTION_UP) {
            long held = downAt < 0 ? 0 : SystemClock.uptimeMillis() - downAt;
            downAt = -1;
            main.removeCallbacks(warn);
            main.removeCallbacks(holdDone);
            Lights.get().powerOffWarning(false);
            if (!fired) {
                Log.i(HomeActivity.TAG, "big button short press (" + held + " ms)");
                PlayerService.send(app, PlayerService.ACTION_TOGGLE);
            }
            return true;
        }
        return false;
    }

    private void powerOff() {
        Log.i(HomeActivity.TAG, "big button held: powering off");
        PlayerService.send(app, PlayerService.ACTION_SAVE_AND_STOP);
        main.postDelayed(() -> Power.shutdown(app), 500); // let state.json reach the disk
    }
}
