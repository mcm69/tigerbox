package dev.audiobox;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * Parent settings in settings.json (APP-SPEC section 8). Defaults are the ones
 * decided on 2026-11-24: no volume cap, idle auto-off 15 min (never while
 * charging), sleep timer off, bedtime off.
 */
public final class Settings {
    public int speakerCapPercent = 100;
    public int headphoneCapPercent = 100;
    /** 0 = off. */
    public int sleepDefaultMinutes = 0;
    /** 0 = never. */
    public int idleOffMinutes = 15;
    public boolean idleOffWhileCharging = false;
    public boolean bedtimeEnabled = false;
    /** The blue light under the box breathes while playing (APP-SPEC section 9). */
    public boolean bottomLight = true;
    /** The radio tab (RADIO-SPEC R7): off until a parent switches it on. */
    public boolean radioEnabled = false;
    /** Minutes after midnight. */
    public int bedtimeStart = 19 * 60;
    public int bedtimeEnd = 7 * 60;
    /** Sleep timer forced on during bedtime when the default is off. */
    static final int BEDTIME_FALLBACK_SLEEP_MINUTES = 30;

    private String pinSalt = "";
    private String pinHash = "";

    public static Settings from(JSONObject j) {
        Settings s = new Settings();
        s.speakerCapPercent = clampPercent(j.optInt("speakerCapPercent", s.speakerCapPercent));
        s.headphoneCapPercent = clampPercent(j.optInt("headphoneCapPercent", s.headphoneCapPercent));
        s.sleepDefaultMinutes = Math.max(0, j.optInt("sleepDefaultMinutes", s.sleepDefaultMinutes));
        s.idleOffMinutes = Math.max(0, j.optInt("idleOffMinutes", s.idleOffMinutes));
        s.idleOffWhileCharging = j.optBoolean("idleOffWhileCharging", s.idleOffWhileCharging);
        s.bottomLight = j.optBoolean("bottomLight", s.bottomLight);
        s.radioEnabled = j.optBoolean("radio", s.radioEnabled);
        JSONObject b = j.optJSONObject("bedtime");
        if (b != null) {
            s.bedtimeEnabled = b.optBoolean("enabled", false);
            s.bedtimeStart = parseTime(b.optString("start", ""), s.bedtimeStart);
            s.bedtimeEnd = parseTime(b.optString("end", ""), s.bedtimeEnd);
        }
        JSONObject pin = j.optJSONObject("pin");
        if (pin != null) {
            s.pinSalt = pin.optString("salt", "");
            s.pinHash = pin.optString("sha256", "");
        }
        return s;
    }

    public JSONObject toJson() {
        try {
            JSONObject j = new JSONObject();
            j.put("version", 1);
            j.put("speakerCapPercent", speakerCapPercent);
            j.put("headphoneCapPercent", headphoneCapPercent);
            j.put("sleepDefaultMinutes", sleepDefaultMinutes);
            j.put("idleOffMinutes", idleOffMinutes);
            j.put("idleOffWhileCharging", idleOffWhileCharging);
            j.put("bottomLight", bottomLight);
            j.put("radio", radioEnabled);
            JSONObject b = new JSONObject();
            b.put("enabled", bedtimeEnabled);
            b.put("start", formatTime(bedtimeStart));
            b.put("end", formatTime(bedtimeEnd));
            j.put("bedtime", b);
            if (hasPin()) {
                JSONObject pin = new JSONObject();
                pin.put("salt", pinSalt);
                pin.put("sha256", pinHash);
                j.put("pin", pin);
            }
            return j;
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    // --- bedtime -----------------------------------------------------------

    /** Is minuteOfDay (0..1439) inside the bedtime window? Handles windows across midnight. */
    public boolean isBedtime(int minuteOfDay) {
        if (!bedtimeEnabled || bedtimeStart == bedtimeEnd) return false;
        if (bedtimeStart < bedtimeEnd) return minuteOfDay >= bedtimeStart && minuteOfDay < bedtimeEnd;
        return minuteOfDay >= bedtimeStart || minuteOfDay < bedtimeEnd;
    }

    /** Sleep timer to apply when playback starts: the default, or forced on at bedtime. */
    public int sleepMinutesFor(boolean bedtimeNow) {
        if (sleepDefaultMinutes > 0) return sleepDefaultMinutes;
        return bedtimeNow ? BEDTIME_FALLBACK_SLEEP_MINUTES : 0;
    }

    // --- volume ------------------------------------------------------------

    /** Highest allowed stream index for the current output. */
    public int capIndex(int maxIndex, boolean headphones) {
        int pct = headphones ? headphoneCapPercent : speakerCapPercent;
        return Math.max(1, Math.round(maxIndex * pct / 100f));
    }

    // --- PIN ---------------------------------------------------------------

    public boolean hasPin() {
        return !pinHash.isEmpty();
    }

    public boolean checkPin(String pin) {
        return hasPin() && MessageDigest.isEqual(
                hash(pinSalt, pin).getBytes(StandardCharsets.US_ASCII),
                pinHash.getBytes(StandardCharsets.US_ASCII));
    }

    /** Sets a new PIN, or clears it with null. */
    public void setPin(String pin) {
        if (pin == null) {
            pinSalt = "";
            pinHash = "";
            return;
        }
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        pinSalt = hex(salt);
        pinHash = hash(pinSalt, pin);
    }

    static String hash(String salt, String pin) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            return hex(d.digest((salt + ":" + pin).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // --- helpers -----------------------------------------------------------

    static int parseTime(String hhmm, int fallback) {
        String[] p = hhmm.trim().split(":");
        if (p.length != 2) return fallback;
        try {
            int h = Integer.parseInt(p[0]), m = Integer.parseInt(p[1]);
            if (h < 0 || h > 23 || m < 0 || m > 59) return fallback;
            return h * 60 + m;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    static String formatTime(int minuteOfDay) {
        return String.format(java.util.Locale.ROOT, "%02d:%02d", minuteOfDay / 60, minuteOfDay % 60);
    }

    private static int clampPercent(int v) {
        return Math.max(5, Math.min(100, v));
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }
}
