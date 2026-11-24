package dev.audiobox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public class SettingsTest {
    @Test
    public void decidedDefaults() {
        Settings s = Settings.from(new JSONObject());
        assertEquals(100, s.speakerCapPercent);
        assertEquals(100, s.headphoneCapPercent);
        assertEquals(15, s.idleOffMinutes);
        assertFalse(s.idleOffWhileCharging);
        assertEquals(0, s.sleepDefaultMinutes);
        assertFalse(s.bedtimeEnabled);
        assertEquals(19 * 60, s.bedtimeStart);
        assertEquals(7 * 60, s.bedtimeEnd);
        assertFalse(s.hasPin());
    }

    @Test
    public void roundTrip() {
        Settings s = new Settings();
        s.speakerCapPercent = 60;
        s.headphoneCapPercent = 40;
        s.idleOffWhileCharging = true;
        s.bedtimeEnabled = true;
        s.bedtimeStart = 20 * 60 + 30;
        s.setPin("1234");
        Settings t = Settings.from(s.toJson());
        assertEquals(60, t.speakerCapPercent);
        assertEquals(40, t.headphoneCapPercent);
        assertTrue(t.idleOffWhileCharging);
        assertTrue(t.bedtimeEnabled);
        assertEquals(20 * 60 + 30, t.bedtimeStart);
        assertTrue(t.checkPin("1234"));
        assertFalse(t.checkPin("4321"));
    }

    @Test
    public void bedtimeAcrossMidnight() {
        Settings s = new Settings();
        assertFalse("disabled by default", s.isBedtime(22 * 60));
        s.bedtimeEnabled = true; // 19:00-07:00
        assertTrue(s.isBedtime(19 * 60));
        assertTrue(s.isBedtime(23 * 60 + 59));
        assertTrue(s.isBedtime(0));
        assertTrue(s.isBedtime(6 * 60 + 59));
        assertFalse(s.isBedtime(7 * 60));
        assertFalse(s.isBedtime(12 * 60));
        assertFalse(s.isBedtime(18 * 60 + 59));
    }

    @Test
    public void bedtimeSameDay() {
        Settings s = new Settings();
        s.bedtimeEnabled = true;
        s.bedtimeStart = 13 * 60;
        s.bedtimeEnd = 15 * 60; // a nap window
        assertTrue(s.isBedtime(14 * 60));
        assertFalse(s.isBedtime(15 * 60));
        assertFalse(s.isBedtime(12 * 60));
        s.bedtimeEnd = s.bedtimeStart;
        assertFalse("empty window", s.isBedtime(13 * 60));
    }

    @Test
    public void sleepTimerForcedAtBedtime() {
        Settings s = new Settings();
        assertEquals(0, s.sleepMinutesFor(false));
        assertEquals(Settings.BEDTIME_FALLBACK_SLEEP_MINUTES, s.sleepMinutesFor(true));
        s.sleepDefaultMinutes = 45;
        assertEquals(45, s.sleepMinutesFor(false));
        assertEquals(45, s.sleepMinutesFor(true));
    }

    @Test
    public void capIndexPerOutput() {
        Settings s = new Settings();
        s.speakerCapPercent = 60;
        s.headphoneCapPercent = 40;
        assertEquals(60, s.capIndex(100, false));
        assertEquals(40, s.capIndex(100, true));
        assertEquals(9, s.capIndex(15, false)); // a stock 0-15 stream
        s.headphoneCapPercent = 100;
        assertEquals(100, s.capIndex(100, true));
    }

    @Test
    public void badValuesFallBack() throws Exception {
        Settings s = Settings.from(new JSONObject(
                "{\"speakerCapPercent\":500,\"headphoneCapPercent\":-3,"
                        + "\"bedtime\":{\"enabled\":true,\"start\":\"25:00\",\"end\":\"nonsense\"}}"));
        assertEquals(100, s.speakerCapPercent);
        assertEquals(5, s.headphoneCapPercent);
        assertEquals(19 * 60, s.bedtimeStart);
        assertEquals(7 * 60, s.bedtimeEnd);
    }

    @Test
    public void clearingThePin() {
        Settings s = new Settings();
        s.setPin("0000");
        assertTrue(s.hasPin());
        s.setPin(null);
        assertFalse(s.hasPin());
        assertFalse(s.checkPin("0000"));
    }
}
