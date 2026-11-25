package dev.audiobox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RadioPlayerTest {
    @Test
    public void retriesBackOffThenStayAtThirtySeconds() {
        assertEquals(2_000, RadioPlayer.retryDelay(0));
        assertEquals(5_000, RadioPlayer.retryDelay(1));
        assertEquals(10_000, RadioPlayer.retryDelay(2));
        assertEquals(30_000, RadioPlayer.retryDelay(3));
        assertEquals(30_000, RadioPlayer.retryDelay(9));
    }

    @Test
    public void givesUpTwoMinutesAfterTheFirstFailure() {
        assertTrue(RadioPlayer.shouldRetry(1_000, 1_000 + 119_000));
        assertFalse(RadioPlayer.shouldRetry(1_000, 1_000 + 120_000));
    }
}
