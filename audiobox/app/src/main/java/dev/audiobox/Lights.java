package dev.audiobox;

import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * The button ring, the tiger head and the blue light under the box (APP-SPEC
 * section 9, SURVEY section 4). The sysfs files are world-writable but read back
 * garbage, so this class keeps the state itself and only ever writes. Writes
 * happen on a background thread.
 *
 * Priority, highest first: power-off warning, error flash, player. Low battery
 * turns the ring's green into yellow. The ring is steady while playing and
 * breathes while paused or idle.
 *
 * The bottom light is an SN3199 LED driver on I2C. Its kernel driver starts in
 * a sound-reactive mode ({@code led_debug} = 1); we switch that off and drive the
 * chip ourselves through {@code /system/bin/sn3199w}, which needs
 * {@code /dev/i2c-2} opened up at boot (tools/audiobox install sets that up).
 * Without it, the light just stays off.
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
    private static final String BREATH_YELLOW = "7";
    // laohutou_status: 0 (off) .. 71 (brightest), in visible steps
    private static final int HEAD_DIM = 12;
    private static final int HEAD_ON = 71;

    private static final String LED_DEBUG = "/sys/bus/i2c/devices/i2c-2/2-0064/led_debug";
    private static final String SN3199W = "/system/bin/sn3199w";
    /**
     * Slow hardware breathing on OUT1-OUT3 (the blue LEDs), 5 mA. Values from
     * tools/sn3199/blue-demo-device.sh: 2.08 s rise and fall, 0.26 s hold,
     * 0.52 s off. The mode (0x03) is set before the peak PWM is loaded, or the
     * LEDs flash at full brightness for a moment.
     */
    private static final String[] BOTTOM_BREATHE = {
            "0xff=0", "0x00=0x01", "0x04=0x30", "0x01=0x07", "0x02=0x00", "0x03=0x10",
            "0x11=0", "0x12=0", "0x13=0", "0x1a=0x13", "0x1d=0x02", "0x1e=0x02", "0x1f=0x02",
            "0x07=0xff", "0x08=0xff", "0x09=0xff", "0x10=0", "0x26=0"};
    /** Chip reset: all registers to defaults, which is dark. */
    private static final String[] BOTTOM_OFF = {"0xff=0"};

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
    private boolean bottomEnabled = true;
    private String lastRing, lastBreath;
    private int lastHead = -1;
    /** null = not written yet (the driver's sound-reactive mode may be on). */
    private Boolean lastBottom;
    private boolean bottomBroken;

    private final Runnable endError = () -> {
        errorFlash = false;
        apply();
    };

    /** Writes the current steady ring colour again (see apply()). */
    private final Runnable resetRing = () -> {
        if (BREATH_STOP.equals(lastBreath) && lastRing != null) write(RING, lastRing, true);
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

    /** The settings switch for the bottom light. */
    void bottomLight(boolean enabled) {
        worker.post(() -> {
            bottomEnabled = enabled;
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
            writeBottom(false, true);
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
        } else {
            ring = lowBattery ? YELLOW : GREEN;
            // Steady while playing (breathing there distracts), breathing otherwise.
            if (player != Player.PLAYING) breath = lowBattery ? BREATH_YELLOW : BREATH_GREEN;
            head = headFor(player);
        }
        boolean breathChanged = !breath.equals(lastBreath);
        if (breathChanged) {
            // Stop any breathing, then set the colour, then start the new one.
            // After a stop the driver turns the ring off a moment later, over
            // what we just wrote; so the steady colour is set again after 1 s
            // (tested on the box; right away it stays dark).
            worker.removeCallbacks(resetRing);
            write(BREATH, BREATH_STOP, false);
            write(RING, ring, true);
            if (!BREATH_STOP.equals(breath)) write(BREATH, breath, false);
            else worker.postDelayed(resetRing, 1_000);
        } else {
            write(RING, ring, false);
        }
        writeHead(head, false);
        writeBottom(bottomEnabled && !warning && player == Player.PLAYING, false);
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

    private void writeBottom(boolean on, boolean force) {
        if (!force && lastBottom != null && lastBottom == on) return;
        if (lastBottom == null) writeFile(LED_DEBUG, "0"); // take over from the driver
        lastBottom = on;
        if (bottomBroken) return;
        String[] regs = on ? BOTTOM_BREATHE : BOTTOM_OFF;
        String[] cmd = new String[regs.length + 1];
        cmd[0] = SN3199W;
        System.arraycopy(regs, 0, cmd, 1, regs.length);
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = readAll(p.getInputStream());
            if (p.waitFor() != 0) {
                Log.w(HomeActivity.TAG, "lights: bottom light: " + out.trim());
                if (out.contains("open /dev/i2c")) bottomBroken = true; // no access: stop trying
            }
        } catch (IOException e) {
            Log.w(HomeActivity.TAG, "lights: no " + SN3199W + ": " + e.getMessage());
            bottomBroken = true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String readAll(java.io.InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[512];
        for (int n; (n = in.read(buf)) > 0; ) sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
        in.close();
        return sb.toString();
    }

    private static void writeFile(String path, String value) {
        try (FileOutputStream out = new FileOutputStream(path)) {
            out.write(value.getBytes(StandardCharsets.US_ASCII));
        } catch (IOException e) {
            Log.w(HomeActivity.TAG, "lights: " + path + " <- " + value + ": " + e.getMessage());
        }
    }
}
