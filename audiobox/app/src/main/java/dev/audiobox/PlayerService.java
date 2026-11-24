package dev.audiobox;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Binder;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.view.KeyEvent;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Plays playlists (APP-SPEC section 3). Runs for as long as the app does, in the
 * foreground, so its MediaSession can take the big button while the screen is off
 * (APP-SPEC section 4) even when nothing is loaded.
 *
 * Everything here runs on the main thread except disk writes.
 */
public class PlayerService extends Service {
    static final String TAG = HomeActivity.TAG;

    public static final String ACTION_START = "dev.audiobox.START";
    public static final String ACTION_PLAY = "dev.audiobox.PLAY";      // extra "id"
    public static final String ACTION_TOGGLE = "dev.audiobox.TOGGLE";
    public static final String ACTION_NEXT = "dev.audiobox.NEXT";
    public static final String ACTION_PREV = "dev.audiobox.PREV";
    /** Before a shutdown: pause and write state.json synchronously. */
    public static final String ACTION_SAVE_AND_STOP = "dev.audiobox.SAVE_AND_STOP";
    public static final String EXTRA_ID = "id";

    private static final int NOTIFICATION_ID = 1;
    private static final long SAVE_EVERY_MS = 15_000;
    /** "Previous" within this much of a track's start goes to the previous track; else restarts. */
    private static final long PREV_RESTART_MS = 3_000;

    /** Read-only snapshot for the UI. */
    public static final class Snapshot {
        public final Playlist playlist; // null when nothing is loaded
        public final int track;
        public final boolean playing;
        public final long positionMs;
        public final long durationMs;

        Snapshot(Playlist playlist, int track, boolean playing, long positionMs, long durationMs) {
            this.playlist = playlist;
            this.track = track;
            this.playing = playing;
            this.positionMs = positionMs;
            this.durationMs = durationMs;
        }

        public String trackTitle() {
            if (playlist == null) return "";
            String n = playlist.tracks.get(track).getName();
            int dot = n.lastIndexOf('.');
            return dot > 0 ? n.substring(0, dot) : n;
        }
    }

    public interface Listener {
        void onPlayerChanged(Snapshot s);
    }

    public final class LocalBinder extends Binder {
        public PlayerService service() {
            return PlayerService.this;
        }
    }

    private final IBinder binder = new LocalBinder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService disk = Executors.newSingleThreadExecutor();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private Store store;
    private ResumeBook book;
    private AudioManager audio;
    private MediaSession session;

    private MediaPlayer player;
    private boolean prepared;
    private Playlist playlist;
    /** Play order: indices into playlist.tracks (shuffled for shuffle playlists). */
    private List<Integer> order = new ArrayList<>();
    private int pos; // index into order
    private boolean wantPlaying;
    private int consecutiveErrors;

    private final Runnable periodicSave = new Runnable() {
        @Override
        public void run() {
            saveSpot();
            if (isPlaying()) main.postDelayed(this, SAVE_EVERY_MS);
        }
    };

    /** Low battery turns the ring yellow (APP-SPEC section 9). */
    private final android.content.BroadcastReceiver battery = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            int level = i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, 100);
            int scale = i.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100);
            int plugged = i.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0);
            Lights.get().lowBattery(plugged == 0 && scale > 0 && level * 100 / scale < 15);
        }
    };

    private final AudioManager.OnAudioFocusChangeListener focus = change -> {
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            Log.i(TAG, "audio focus lost (" + change + "), pausing");
            pause();
        }
    };

    // --- lifecycle ---------------------------------------------------------

    public static void send(Context c, String action) {
        c.startService(new Intent(c, PlayerService.class).setAction(action));
    }

    public static void play(Context c, String playlistId) {
        c.startService(new Intent(c, PlayerService.class).setAction(ACTION_PLAY)
                .putExtra(EXTRA_ID, playlistId));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        store = new Store(new File(Environment.getExternalStorageDirectory(), "audiobox"));
        book = new ResumeBook(store.read(Store.STATE_JSON));
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);

        session = new MediaSession(this, "audiobox");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSession.Callback() {
            @Override
            public boolean onMediaButtonEvent(Intent intent) {
                KeyEvent k = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                if (k == null) return false;
                Log.i(TAG, "media button " + KeyEvent.keyCodeToString(k.getKeyCode())
                        + (k.getAction() == KeyEvent.ACTION_DOWN ? " down" : " up"));
                return ButtonHandler.get(PlayerService.this).onKey(k);
            }

            @Override public void onPlay() { resumeOrLast(); }
            @Override public void onPause() { pause(); }
            @Override public void onSkipToNext() { next(); }
            @Override public void onSkipToPrevious() { previous(); }
        });
        Intent mb = new Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(
                new ComponentName(this, MediaButtonReceiver.class));
        session.setMediaButtonReceiver(PendingIntent.getBroadcast(this, 0, mb, 0));
        session.setActive(true);
        publishState();

        startForeground(NOTIFICATION_ID, notification());
        registerReceiver(battery, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        Log.i(TAG, "PlayerService up, last=" + book.last());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_PLAY.equals(action)) {
            play(intent.getStringExtra(EXTRA_ID));
        } else if (ACTION_TOGGLE.equals(action)) {
            toggle();
        } else if (ACTION_NEXT.equals(action)) {
            next();
        } else if (ACTION_PREV.equals(action)) {
            previous();
        } else if (ACTION_SAVE_AND_STOP.equals(action)) {
            pause();
            writeNow();
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        saveSpot();
        releasePlayer();
        session.release();
        unregisterReceiver(battery);
        audio.abandonAudioFocus(focus);
        disk.shutdown();
        super.onDestroy();
    }

    public void addListener(Listener l) {
        listeners.add(l);
        l.onPlayerChanged(snapshot());
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    // --- commands ----------------------------------------------------------

    /** Tapping a playlist (APP-SPEC 5.4): switch to it, or carry on with it. */
    public void play(String id) {
        Playlist p = find(id);
        if (p == null) {
            Log.w(TAG, "play: no playlist " + id);
            return;
        }
        if (playlist != null && playlist.id.equals(p.id) && player != null) {
            if (!isPlaying()) start();
            return;
        }
        saveSpot();
        load(p, true);
    }

    public void toggle() {
        if (isPlaying()) pause();
        else resumeOrLast();
    }

    /** Play/pause with nothing loaded resumes the last playlist (APP-SPEC section 4). */
    public void resumeOrLast() {
        if (player != null) {
            start();
            return;
        }
        String last = book.last();
        if (last != null) play(last);
        else Log.i(TAG, "play pressed, nothing to resume");
    }

    public void pause() {
        wantPlaying = false;
        if (player != null && prepared && player.isPlaying()) player.pause();
        saveSpot();
        main.removeCallbacks(periodicSave);
        changed();
    }

    public void next() {
        if (playlist == null) return;
        if (pos + 1 < order.size()) {
            pos++;
            openTrack(0, wantPlaying || isPlaying());
        }
    }

    public void previous() {
        if (playlist == null) return;
        boolean keepPlaying = wantPlaying || isPlaying();
        if (prepared && player.getCurrentPosition() > PREV_RESTART_MS || pos == 0) {
            if (prepared) player.seekTo(0);
            changed();
            return;
        }
        pos--;
        openTrack(0, keepPlaying);
    }

    public boolean isPlaying() {
        return player != null && prepared && player.isPlaying();
    }

    public Snapshot snapshot() {
        if (playlist == null) return new Snapshot(null, 0, false, 0, 0);
        long position = prepared ? player.getCurrentPosition() : 0;
        long duration = prepared ? player.getDuration() : 0;
        return new Snapshot(playlist, order.get(pos), isPlaying() || wantPlaying, position, duration);
    }

    // --- internals ---------------------------------------------------------

    private Playlist find(String id) {
        if (id == null) return null;
        for (Playlist p : Library.scan(store.libraryDir(), store.read(Store.LIBRARY_JSON))) {
            if (p.id.equals(id)) return p;
        }
        return null;
    }

    private void load(Playlist p, boolean autoplay) {
        playlist = p;
        ResumeBook.Spot spot = book.spotFor(p);
        order = new ArrayList<>();
        for (int i = 0; i < p.tracks.size(); i++) order.add(i);
        if (p.shuffle && spot.track == 0 && spot.positionMs == 0) {
            Collections.shuffle(order); // a fresh start of a shuffle playlist
        }
        pos = Math.max(0, order.indexOf(spot.track));
        book.setLast(p.id);
        Log.i(TAG, "load " + p + " at " + spot);
        consecutiveErrors = 0;
        openTrack(spot.positionMs, autoplay);
    }

    private void openTrack(long startAtMs, boolean autoplay) {
        releasePlayer();
        wantPlaying = autoplay;
        File f = playlist.tracks.get(order.get(pos));
        MediaPlayer mp = new MediaPlayer();
        player = mp;
        prepared = false;
        mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
        mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK); // held only while playing
        mp.setOnPreparedListener(m -> {
            if (m != player) return;
            prepared = true;
            consecutiveErrors = 0;
            if (startAtMs > 0 && startAtMs < m.getDuration()) m.seekTo((int) startAtMs);
            if (wantPlaying) start();
            else changed();
        });
        mp.setOnCompletionListener(m -> {
            if (m != player) return;
            onTrackFinished();
        });
        mp.setOnErrorListener((m, what, extra) -> {
            if (m != player) return true;
            Log.e(TAG, "playback error " + what + "/" + extra + " in " + f.getName());
            onTrackFailed();
            return true;
        });
        try {
            mp.setDataSource(f.getPath());
            mp.prepareAsync();
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "cannot open " + f.getName() + ": " + e);
            onTrackFailed();
        }
        saveSpot();
        changed();
    }

    private void start() {
        if (player == null) return;
        wantPlaying = true;
        if (!prepared) return; // starts in onPrepared
        int r = audio.requestAudioFocus(focus, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        if (r != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) Log.w(TAG, "audio focus not granted");
        player.start();
        main.removeCallbacks(periodicSave);
        main.postDelayed(periodicSave, SAVE_EVERY_MS);
        changed();
    }

    private void onTrackFinished() {
        if (pos + 1 < order.size()) {
            pos++;
            openTrack(0, true);
            return;
        }
        // End of the playlist: stop and rewind (APP-SPEC 5.3).
        Log.i(TAG, "end of " + playlist.id + ", rewinding");
        book.rewind(playlist);
        pos = 0;
        persist();
        openTrack(0, false);
    }

    private void onTrackFailed() {
        prepared = false;
        if (++consecutiveErrors >= Math.min(order.size(), 5)) {
            Log.e(TAG, "giving up on " + playlist.id + " after " + consecutiveErrors + " errors");
            wantPlaying = false;
            releasePlayer();
            changed();
            return;
        }
        boolean keep = wantPlaying;
        main.post(() -> {
            if (pos + 1 < order.size()) pos++;
            else pos = 0;
            openTrack(0, keep);
        });
    }

    private void releasePlayer() {
        if (player != null) {
            MediaPlayer old = player;
            player = null;
            prepared = false;
            try {
                old.reset();
            } catch (IllegalStateException ignored) {
                // already in an error state
            }
            old.release();
        }
    }

    private void saveSpot() {
        if (playlist == null) return;
        long position = prepared ? player.getCurrentPosition() : 0;
        long duration = prepared ? player.getDuration() : 0;
        if (!prepared) {
            // Opening a track: keep what's recorded unless we moved to another track.
            ResumeBook.Spot recorded = book.spotFor(playlist);
            if (recorded.track == order.get(pos)) position = recorded.positionMs;
        }
        // While playing, don't apply "near the end -> next" (it would skip on resume
        // only after the track really ended); record the raw spot instead.
        book.record(playlist, order.get(pos), position, isPlaying() ? 0 : duration);
        persist();
    }

    /** All state.json writes go through the one disk thread, so they never overlap. */
    private java.util.concurrent.Future<?> persist() {
        final String json = book.json().toString();
        return disk.submit(() -> {
            try {
                store.write(Store.STATE_JSON, new org.json.JSONObject(json));
            } catch (Exception e) {
                Log.e(TAG, "saving state failed: " + e.getMessage());
            }
        });
    }

    /** Like persist(), but waits for the write (before a shutdown). */
    private void writeNow() {
        try {
            persist().get(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            Log.e(TAG, "saving state before shutdown failed: " + e);
        }
    }

    private void changed() {
        publishState();
        Lights.get().player(playlist == null ? Lights.Player.IDLE
                : isPlaying() || wantPlaying ? Lights.Player.PLAYING : Lights.Player.PAUSED);
        Snapshot s = snapshot();
        for (Listener l : listeners) l.onPlayerChanged(s);
    }

    private void publishState() {
        int st = playlist == null ? PlaybackState.STATE_NONE
                : isPlaying() ? PlaybackState.STATE_PLAYING
                : wantPlaying ? PlaybackState.STATE_BUFFERING
                : PlaybackState.STATE_PAUSED;
        long position = prepared ? player.getCurrentPosition() : 0;
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                        | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(st, position, 1f)
                .build());
    }

    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, HomeActivity.class), 0);
        return new Notification.Builder(this)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(getString(R.string.app_name))
                .setContentIntent(open)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_MIN)
                .build();
    }
}
