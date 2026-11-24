package dev.audiobox;

import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * The button ring and the tiger head (APP-SPEC section 9, SURVEY section 4).
 * The sysfs files are world-writable but read back garbage, so this class keeps
 * the state itself and only ever writes. Writes happen on a background thread.
 *
 * Priority, highest first: power-off warning, error flash, low battery, player.
 */
final class Lights {
    private static final String DIR = "/sys/devices/pwmleds.20/";
    private static final String RING = DIR + "modem_status";
    private static final String BREATH = DIR + "breathled_switch";
    private static final String HEAD = DIR + "laohutou_status";

    // modem_status values
    private static final String OFF = "000000";
    private static final String YELLOW = "100000";
    private static final String GREEN = "001000";
    private static final String RED = "000010";
    private static final String RED_FLICKER = "000011";
    // breathled_switch values
    private static final String BREATH_STOP = "0";
    private static final String BREATH_GREEN = "1";
    // laohutou_status: 0 (off) .. 71 (brightest), in visible steps
    private static final int HEAD_DIM = 12;
    private static final int HEAD_ON = 71;

    enum Player { IDLE, PLAYING, PAUSED }

    private static Lights instance;

    static synchronized Lights get() {
        if (instance == null) instance = new Lights();
        return instance;
    }

    private final Handler worker;
    private Player player = Player.IDLE;
    private boolean lowBattery;
    private boolean warning;
    private boolean errorFlash;
    private String lastRing, lastBreath;
    private int lastHead = -1;

    private final Runnable endError = () -> {
        errorFlash = false;
        apply();
    };

    private Lights() {
        HandlerThread t = new HandlerThread("lights");
        t.start();
        worker = new Handler(t.getLooper());
    }

    void player(Player p) {
        worker.post(() -> {
            player = p;
            apply();
        });
    }

    void lowBattery(boolean low) {
        worker.post(() -> {
            lowBattery = low;
            apply();
        });
    }

    void powerOffWarning(boolean on) {
        worker.post(() -> {
            warning = on;
            apply();
        });
    }

    /** Red flicker for 3 s: an error or "not allowed now" (bedtime). */
    void errorFlash() {
        worker.post(() -> {
            errorFlash = true;
            worker.removeCallbacks(endError);
            worker.postDelayed(endError, 3_000);
            apply();
        });
    }

    /** Ring and head off, e.g. right before shutdown. */
    void allOff() {
        worker.post(() -> {
            write(BREATH, BREATH_STOP, true);
            write(RING, OFF, true);
            writeHead(0, true);
        });
    }

    private void apply() {
        String ring, breath = BREATH_STOP;
        int head;
        if (warning) {
            ring = RED;
            head = 0;
        } else if (errorFlash) {
            ring = RED_FLICKER;
            head = headFor(player);
        } else if (lowBattery) {
            ring = YELLOW;
            head = headFor(player);
        } else {
            ring = GREEN;
            if (player == Player.PLAYING) breath = BREATH_GREEN;
            head = headFor(player);
        }
        // Stop breathing before setting a steady colour, start it after.
        if (BREATH_STOP.equals(breath)) {
            write(BREATH, breath, false);
            write(RING, ring, false);
        } else {
            write(RING, ring, false);
            write(BREATH, breath, false);
        }
        writeHead(head, false);
    }

    private static int headFor(Player p) {
        return p == Player.PLAYING ? HEAD_ON : HEAD_DIM;
    }

    private void writeHead(int v, boolean force) {
        if (!force && v == lastHead) return;
        lastHead = v;
        writeFile(HEAD, Integer.toString(v));
    }

    private void write(String path, String value, boolean force) {
        boolean isRing = path.equals(RING);
        String last = isRing ? lastRing : lastBreath;
        if (!force && value.equals(last)) return;
        if (isRing) lastRing = value;
        else lastBreath = value;
        writeFile(path, value);
    }

    private static void writeFile(String path, String value) {
        try (FileOutputStream out = new FileOutputStream(path)) {
            out.write(value.getBytes(StandardCharsets.US_ASCII));
        } catch (IOException e) {
            Log.w(HomeActivity.TAG, "lights: " + path + " <- " + value + ": " + e.getMessage());
        }
    }
}
