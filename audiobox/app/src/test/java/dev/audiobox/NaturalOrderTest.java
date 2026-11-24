package dev.audiobox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class NaturalOrderTest {
    private static List<String> sorted(String... names) {
        List<String> l = new ArrayList<>(Arrays.asList(names));
        Collections.sort(l, NaturalOrder.INSTANCE);
        return l;
    }

    @Test
    public void numbersCompareByValue() {
        assertEquals(Arrays.asList("2 Intro.mp3", "10 Outro.mp3"),
                sorted("10 Outro.mp3", "2 Intro.mp3"));
        assertEquals(Arrays.asList("Track 9", "Track 10", "Track 100"),
                sorted("Track 100", "Track 9", "Track 10"));
    }

    @Test
    public void caseInsensitiveText() {
        assertEquals(Arrays.asList("apple", "Banana", "cherry"), sorted("cherry", "Banana", "apple"));
    }

    @Test
    public void leadingZerosAndLongRuns() {
        // equal values: the shorter spelling first
        assertEquals(Arrays.asList("1", "01", "002", "10"), sorted("10", "002", "1", "01"));
        assertTrue(NaturalOrder.INSTANCE.compare("99999999999999999999", "100000000000000000000") < 0);
    }

    @Test
    public void prefixSortsFirst() {
        assertEquals(Arrays.asList("Chapter", "Chapter 1"), sorted("Chapter 1", "Chapter"));
    }
}
