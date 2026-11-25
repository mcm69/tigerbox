package dev.audiobox;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Powering off nicely (APP-SPEC section 4): all lights off at once, the goodbye
 * screen and the shutdown chime, then the actual shutdown. Used by the button
 * hold and by idle auto-off. The caller saves the player state first.
 */
final class Goodbye {
    private Goodbye() {}

    /** HomeActivity shows the goodbye screen when started with this action. */
    static final String ACTION_SHOW = "dev.audiobox.GOODBYE";
    /** Shut down this long after the chime ends... */
    private static final long AFTER_CHIME_MS = 300;
    /** ...or after this long at the latest (no chime, or it never finishes). */
    private static final long LATEST_MS = 3_000;

    private static boolean started;

    /** Main thread only. Safe to call twice: the second call does nothing. */
    static void start(Context c) {
        if (started) return;
        started = true;
        Context app = c.getApplicationContext();
        Log.i(HomeActivity.TAG, "goodbye: powering off");
        Lights.get().allOff();
        Screen.wake(app);
        app.startActivity(new Intent(app, HomeActivity.class)
                .setAction(ACTION_SHOW)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));

        Handler main = new Handler(Looper.getMainLooper());
        boolean[] done = {false};
        Runnable off = () -> {
            if (done[0]) return;
            done[0] = true;
            Power.shutdown(app);
        };
        Chime.play(app, R.raw.chime_off, () -> main.postDelayed(off, AFTER_CHIME_MS));
        main.postDelayed(off, LATEST_MS);
    }
}

/** The short startup and shutdown sounds (res/raw, made by tools/chimes.py). */
final class Chime {
    private Chime() {}

    /** Softer than the music: a fraction of the current media volume. */
    private static final float VOLUME = 0.6f;

    /** Plays a chime on the media stream; {@code done} (may be null) runs when it ends or fails. */
    static void play(Context c, int res, Runnable done) {
        MediaPlayer mp = null;
        try {
            mp = MediaPlayer.create(c.getApplicationContext(), res);
        } catch (RuntimeException e) {
            Log.w(HomeActivity.TAG, "chime: " + e);
        }
        if (mp == null) {
            if (done != null) done.run();
            return;
        }
        mp.setVolume(VOLUME, VOLUME);
        mp.setOnCompletionListener(p -> {
            p.release();
            if (done != null) done.run();
        });
        mp.setOnErrorListener((p, what, extra) -> {
            p.release();
            if (done != null) done.run();
            return true;
        });
        mp.start();
    }

    /** The startup chime, once per boot. */
    public static final class BootReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())) play(c, R.raw.chime_start, null);
        }
    }
}
