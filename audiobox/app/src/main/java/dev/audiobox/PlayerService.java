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
    static final String ACTION_SLEEP_DONE = "dev.audiobox.SLEEP_DONE";
    static final String ACTION_IDLE_CHECK = "dev.audiobox.IDLE_CHECK";
    static final int[] SLEEP_STEPS = {0, 15, 30, 45};
    private static final long FADE_MS = 10_000;
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
        /** Wall-clock time the sleep timer ends, or 0 when it's off. */
        public long sleepEndsAt;

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

        /** A playlist was refused because it's bedtime (APP-SPEC section 8). */
        default void onBlocked(Playlist p) {}
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
    private Settings settings = new Settings();
    private android.app.AlarmManager alarms;
    private long sleepEndsAt; // wall clock, 0 = off
    private int sleepChoice;  // minutes last chosen, for cycling
    private long lastActivity = android.os.SystemClock.elapsedRealtime();
    private boolean charging;
    private boolean fading;
    private float fadeVolume = 1f;
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

    /** Settings changes, output changes and volume changes all re-apply the caps. */
    private final android.content.BroadcastReceiver policy = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (HomeActivity.ACTION_RELOAD.equals(i.getAction())) {
                loadSettings();
                scheduleIdleCheck();
                refreshLoaded();
            }
            enforceVolumeCap();
        }
    };

    /**
     * When the screen goes off, bring the home screen back to the front. The box
     * has no navigation bar and the big button is play/pause, so this is the way
     * out of Android settings (opened from the admin screen) or any other app.
     * HomeActivity clears its task on launch, so an open admin screen closes too
     * and the PIN is asked again next time.
     */
    private final android.content.BroadcastReceiver screenOff = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            Log.i(TAG, "screen off: back to home");
            startActivity(new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .setComponent(new ComponentName(c, HomeActivity.class))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
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
            if ((plugged != 0) != charging) {
                charging = plugged != 0;
                userActivity(); // plugging in or out counts as activity
            }
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
        loadSettings();
        alarms = (android.app.AlarmManager) getSystemService(ALARM_SERVICE);
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
        android.content.IntentFilter pf = new android.content.IntentFilter(HomeActivity.ACTION_RELOAD);
        pf.addAction("android.media.VOLUME_CHANGED_ACTION");
        pf.addAction(Intent.ACTION_HEADSET_PLUG);
        registerReceiver(policy, pf);
        registerReceiver(screenOff, new android.content.IntentFilter(Intent.ACTION_SCREEN_OFF));
        enforceVolumeCap();
        scheduleIdleCheck();
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
        } else if (ACTION_SLEEP_DONE.equals(action)) {
            sleepTimerDone();
        } else if (ACTION_IDLE_CHECK.equals(action)) {
            idleCheck();
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
        unregisterReceiver(policy);
        unregisterReceiver(screenOff);
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
        userActivity();
        if (blockedNow(p)) {
            blocked(p);
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
        userActivity();
        if (player != null) {
            if (blockedNow(playlist)) {
                blocked(playlist);
                return;
            }
            start();
            return;
        }
        String last = book.last();
        if (last != null) play(last);
        else Log.i(TAG, "play pressed, nothing to resume");
    }

    public void pause() {
        wantPlaying = false;
        cancelSleepTimer();
        userActivity();
        if (player != null && prepared && player.isPlaying()) player.pause();
        if (fading) endFade(); // after pausing, so restoring the volume is silent
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
        Snapshot s = new Snapshot(playlist, order.get(pos), isPlaying() || wantPlaying, position, duration);
        s.sleepEndsAt = sleepEndsAt;
        return s;
    }

    // --- parental controls (APP-SPEC section 8) ----------------------------

    public Settings settings() {
        return settings;
    }

    /** The UI calls this on touches, so the idle auto-off counts from the last one. */
    public void userActivity() {
        lastActivity = android.os.SystemClock.elapsedRealtime();
        scheduleIdleCheck();
    }

    public boolean bedtimeNow() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        return settings.isBedtime(c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE));
    }

    public boolean blockedNow(Playlist p) {
        return p != null && !p.bedtime && bedtimeNow();
    }

    /** Moon button: off -> 15 -> 30 -> 45 -> off. */
    public void cycleSleepTimer() {
        userActivity();
        int current = sleepEndsAt == 0 ? 0 : sleepChoice;
        int next = 0;
        for (int i = 0; i < SLEEP_STEPS.length; i++) {
            if (SLEEP_STEPS[i] == current) next = SLEEP_STEPS[(i + 1) % SLEEP_STEPS.length];
        }
        if (next == 0) cancelSleepTimer();
        else setSleepTimer(next);
        changed();
    }

    private void setSleepTimer(int minutes) {
        sleepEndsAt = System.currentTimeMillis() + minutes * 60_000L;
        sleepChoice = minutes;
        alarms.setExact(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                android.os.SystemClock.elapsedRealtime() + minutes * 60_000L, alarmIntent(ACTION_SLEEP_DONE));
        Log.i(TAG, "sleep timer " + minutes + " min");
    }

    private void cancelSleepTimer() {
        if (sleepEndsAt == 0) return;
        sleepEndsAt = 0;
        alarms.cancel(alarmIntent(ACTION_SLEEP_DONE));
    }

    /**
     * Fade out over 10 s, then pause. The fade follows whatever track is current
     * (a new track opened mid-fade starts at the faded volume, see openTrack).
     */
    private void sleepTimerDone() {
        sleepEndsAt = 0;
        if (!isPlaying() || fading) return;
        Log.i(TAG, "sleep timer done, fading out");
        fading = true;
        final int steps = 20;
        for (int i = 1; i <= steps; i++) {
            final float v = 1f - (float) i / steps;
            final boolean last = i == steps;
            main.postDelayed(() -> {
                if (!fading) return; // cancelled (e.g. paused by hand)
                fadeVolume = v;
                if (player != null) player.setVolume(v, v);
                if (last) pause();
            }, FADE_MS * i / steps);
        }
    }

    private void endFade() {
        fading = false;
        fadeVolume = 1f;
        if (player != null) player.setVolume(1f, 1f);
    }

    private void scheduleIdleCheck() {
        if (alarms == null) return;
        PendingIntent pi = alarmIntent(ACTION_IDLE_CHECK);
        alarms.cancel(pi);
        if (settings.idleOffMinutes <= 0) return;
        alarms.set(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                lastActivity + settings.idleOffMinutes * 60_000L, pi);
    }

    private void idleCheck() {
        long idleFor = android.os.SystemClock.elapsedRealtime() - lastActivity;
        long limit = settings.idleOffMinutes * 60_000L;
        if (settings.idleOffMinutes <= 0) return;
        if (isPlaying() || wantPlaying || idleFor < limit - 1_000) {
            if (isPlaying()) lastActivity = android.os.SystemClock.elapsedRealtime();
            scheduleIdleCheck();
            return;
        }
        if (charging && !settings.idleOffWhileCharging) {
            Log.i(TAG, "idle but charging: staying on");
            lastActivity = android.os.SystemClock.elapsedRealtime();
            scheduleIdleCheck();
            return;
        }
        Log.i(TAG, "idle for " + idleFor / 60_000 + " min: powering off");
        pause();
        writeNow();
        Power.shutdown(this);
    }

    private PendingIntent alarmIntent(String action) {
        return PendingIntent.getService(this, action.hashCode(),
                new Intent(this, PlayerService.class).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void loadSettings() {
        settings = Settings.from(store.read(Store.SETTINGS_JSON));
    }

    /** Highest volume index allowed on the current output. */
    public int volumeCapIndex() {
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        @SuppressWarnings("deprecation") boolean hp = audio.isWiredHeadsetOn();
        return settings.capIndex(max, hp);
    }

    private void enforceVolumeCap() {
        int cap = volumeCapIndex();
        int v = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        if (v > cap) {
            Log.i(TAG, "volume " + v + " above cap " + cap + ", lowering");
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, cap, 0);
        }
    }

    private void blocked(Playlist p) {
        Log.i(TAG, "bedtime: " + p.id + " not allowed now");
        Lights.get().errorFlash();
        Screen.wake(this);
        for (Listener l : listeners) l.onBlocked(p);
    }

    // --- internals ---------------------------------------------------------

    private Playlist find(String id) {
        if (id == null) return null;
        for (Playlist p : Library.scan(store.libraryDir(), store.read(Store.LIBRARY_JSON))) {
            if (p.id.equals(id)) return p;
        }
        return null;
    }

    /**
     * After a RELOAD, pick up a changed label, icon or flags of the loaded
     * playlist so the player screen shows them. Only when its tracks are the
     * same: the play order and position index into them. Changed tracks are
     * picked up the next time the playlist is loaded.
     */
    private void refreshLoaded() {
        if (playlist == null) return;
        Playlist fresh = find(playlist.id);
        if (fresh == null || !fresh.tracks.equals(playlist.tracks)) return;
        playlist = fresh;
        changed();
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
        mp.setVolume(fadeVolume, fadeVolume);
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
        if (sleepEndsAt == 0) {
            int m = settings.sleepMinutesFor(bedtimeNow());
            if (m > 0) setSleepTimer(m);
        }
        userActivity();
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
