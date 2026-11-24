package dev.audiobox;

import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.util.Log;

/** Screen wake-ups and shutdown. */
final class Power {
    private Power() {}

    /**
     * Shuts the box down without a confirmation dialog. Needs
     * android.permission.SHUTDOWN, which only a /system/priv-app install gets
     * (APP-SPEC section 2, milestone M5). As a user app it logs and does nothing;
     * the PMIC's own ~6-8 s hold still works.
     */
    static void shutdown(Context c) {
        Lights.get().allOff();
        Intent i = new Intent("android.intent.action.ACTION_REQUEST_SHUTDOWN")
                .putExtra("android.intent.extra.KEY_CONFIRM", false)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(i);
        } catch (SecurityException e) {
            Log.w(HomeActivity.TAG, "shutdown refused (not a priv-app yet): " + e.getMessage());
            Lights.get().errorFlash();
        }
    }
}

/** Turning the screen on briefly so a button press is visibly acknowledged. */
final class Screen {
    private Screen() {}

    @SuppressWarnings("deprecation") // the only way to wake the screen from a service on API 22
    static void wake(Context c) {
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        if (pm.isInteractive()) return;
        Log.i(HomeActivity.TAG, "waking the screen");
        PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "audiobox:wake");
        wl.acquire(1_000);
    }
}
