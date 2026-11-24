package dev.audiobox;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.view.KeyEvent;

/**
 * Fallback for media buttons when no MediaSession of ours is alive (e.g. the
 * service was killed): Android 5.x then delivers to the session's last media
 * button receiver. Starts the service and toggles; hold-to-off needs the session.
 */
public class MediaButtonReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        KeyEvent k = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
        if (k == null || k.getAction() != KeyEvent.ACTION_DOWN || k.getRepeatCount() > 0) return;
        Log.i(HomeActivity.TAG, "media button via receiver: " + KeyEvent.keyCodeToString(k.getKeyCode()));
        switch (k.getKeyCode()) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_HEADSETHOOK:
                PlayerService.send(context, PlayerService.ACTION_TOGGLE);
                break;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                PlayerService.send(context, PlayerService.ACTION_NEXT);
                break;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                PlayerService.send(context, PlayerService.ACTION_PREV);
                break;
            default:
                break;
        }
    }
}
