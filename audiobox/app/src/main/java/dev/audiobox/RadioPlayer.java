package dev.audiobox;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;

/**
 * One live station through ExoPlayer (RADIO-SPEC R2, R6). The platform player
 * can't do TLS 1.2 on this box, ExoPlayer can (it uses Java networking).
 *
 * An ExoPlayer exists only while a station is starting or playing: stop()
 * releases it, so its memory is only used while listening. Reconnects: after an
 * error or a long stall, retry after 2, 5, 10, 30 s, then every 30 s, giving up
 * 2 minutes after the first failure. Each retry starts again at the live edge.
 *
 * Main thread only.
 */
@androidx.annotation.OptIn(markerClass = UnstableApi.class)
final class RadioPlayer {
    static final String TAG = HomeActivity.TAG;

    enum State {
        /** Not playing (never started, paused, or given up after an error). */
        STOPPED,
        /** Started, no audio yet (first connect or a retry under way). */
        CONNECTING,
        /** Audio is playing. */
        LIVE,
        /** Lost the stream; waiting for the next retry. */
        RETRYING,
        /** Gave up after the retries (or the stream can't be played at all). */
        FAILED
    }

    interface Listener {
        void onRadioChanged();
    }

    static final long[] RETRY_DELAYS_MS = {2_000, 5_000, 10_000, 30_000};
    static final long GIVE_UP_AFTER_MS = 120_000;
    /** Buffering longer than this while live counts as a lost stream. */
    static final long STALL_MS = 20_000;
    /** RADIO-SPEC R6: HLS capped at ~160 kbit/s. */
    static final int MAX_BITRATE = 160_000;

    /** Delay before retry number {@code attempt} (0-based). */
    static long retryDelay(int attempt) {
        return RETRY_DELAYS_MS[Math.min(attempt, RETRY_DELAYS_MS.length - 1)];
    }

    /** Whether another retry is allowed, given when the first failure happened. */
    static boolean shouldRetry(long firstFailureAt, long now) {
        return now - firstFailureAt < GIVE_UP_AFTER_MS;
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;

    private ExoPlayer exo;
    private String url;
    private State state = State.STOPPED;
    private String nowPlaying;
    private float volume = 1f;
    private int attempt;
    private long firstFailureAt;
    private String lastError;

    private final Runnable retry = this::connect;
    private final Runnable stalled = () -> failed("stalled for " + STALL_MS / 1000 + " s");

    RadioPlayer(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    State state() {
        return state;
    }

    /** The "now playing" text the stream sends (Icecast StreamTitle, HLS ID3), or null. */
    String nowPlaying() {
        return nowPlaying;
    }

    String lastError() {
        return lastError;
    }

    boolean isPlaying() {
        return state == State.LIVE;
    }

    /** Playing, or trying to: what the button, lights and idle check treat as "on". */
    boolean isActive() {
        return state == State.CONNECTING || state == State.LIVE || state == State.RETRYING;
    }

    void start(String url) {
        this.url = url;
        attempt = 0;
        firstFailureAt = 0;
        lastError = null;
        nowPlaying = null;
        connect();
    }

    /** Stops and releases the player. The next start() reconnects at the live edge. */
    void stop() {
        main.removeCallbacks(retry);
        main.removeCallbacks(stalled);
        release();
        if (state != State.FAILED) set(State.STOPPED);
    }

    /** Back to a fresh state (forgets a failure), e.g. when the station is unloaded. */
    void reset() {
        stop();
        nowPlaying = null;
        lastError = null;
        set(State.STOPPED);
    }

    void setVolume(float v) {
        volume = v;
        if (exo != null) exo.setVolume(v);
    }

    /** Connectivity came back: don't wait for the next scheduled retry. */
    void retryNow() {
        if (state == State.RETRYING) {
            main.removeCallbacks(retry);
            connect();
        }
    }

    private void connect() {
        main.removeCallbacks(retry);
        main.removeCallbacks(stalled);
        release();
        if (url == null) return;
        set(State.CONNECTING);
        Log.i(TAG, "radio: connecting to " + url + (attempt > 0 ? " (retry " + attempt + ")" : ""));

        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent("audiobox")
                .setAllowCrossProtocolRedirects(true) // http -> https redirects
                .setConnectTimeoutMs(10_000)
                .setReadTimeoutMs(15_000);
        DefaultTrackSelector tracks = new DefaultTrackSelector(context);
        tracks.setParameters(tracks.buildUponParameters().setMaxAudioBitrate(MAX_BITRATE));
        // Live radio needs little buffer; the default (up to 50 s) costs memory.
        DefaultLoadControl load = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(10_000, 20_000, 1_500, 3_000)
                .build();
        ExoPlayer p = new ExoPlayer.Builder(context)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(http))
                .setTrackSelector(tracks)
                .setLoadControl(load)
                // Audio focus stays with PlayerService, which handles it for both sources.
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
                .setWakeMode(C.WAKE_MODE_NETWORK) // wake lock + Wi-Fi lock while playing
                .build();
        exo = p;
        p.setVolume(volume);
        p.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int s) {
                if (p != exo) return;
                if (s == Player.STATE_READY && p.getPlayWhenReady()) {
                    main.removeCallbacks(stalled);
                    attempt = 0;
                    firstFailureAt = 0;
                    set(State.LIVE);
                } else if (s == Player.STATE_BUFFERING && state == State.LIVE) {
                    main.removeCallbacks(stalled);
                    main.postDelayed(stalled, STALL_MS);
                } else if (s == Player.STATE_ENDED) {
                    failed("stream ended");
                }
            }

            @Override
            public void onPlayerError(PlaybackException e) {
                if (p != exo) return;
                failed(e.getErrorCodeName() + (e.getCause() != null ? ": " + e.getCause() : ""));
            }

            @Override
            public void onMediaMetadataChanged(MediaMetadata m) {
                if (p != exo) return;
                String t = m.title != null ? m.title.toString().trim() : "";
                if (m.artist != null && m.artist.length() > 0 && !t.isEmpty()) t = m.artist + " – " + t;
                String now = t.isEmpty() ? null : t;
                if (now == null ? nowPlaying != null : !now.equals(nowPlaying)) {
                    nowPlaying = now;
                    listener.onRadioChanged();
                }
            }
        });
        p.setMediaItem(MediaItem.fromUri(url));
        p.setPlayWhenReady(true);
        p.prepare();
    }

    private void failed(String why) {
        main.removeCallbacks(stalled);
        lastError = why;
        long now = SystemClock.elapsedRealtime();
        if (firstFailureAt == 0) firstFailureAt = now;
        release();
        if (!shouldRetry(firstFailureAt, now)) {
            Log.w(TAG, "radio: giving up on " + url + ": " + why);
            set(State.FAILED);
            return;
        }
        long delay = retryDelay(attempt++);
        Log.w(TAG, "radio: " + why + "; retrying in " + delay / 1000 + " s");
        set(State.RETRYING);
        main.postDelayed(retry, delay);
    }

    private void release() {
        if (exo != null) {
            ExoPlayer old = exo;
            exo = null;
            old.release();
        }
    }

    private void set(State s) {
        if (s == state) return;
        state = s;
        listener.onRadioChanged();
    }
}
