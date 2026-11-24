package dev.audiobox;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TrackTitleTest {
    @Test
    public void stripsTrackNumbers() {
        assertEquals("Old MacDonald", TrackTitle.fromFilename("07 - Old MacDonald.mp3"));
        assertEquals("Old MacDonald", TrackTitle.fromFilename("07. Old MacDonald.mp3"));
        assertEquals("Old MacDonald", TrackTitle.fromFilename("07 Old MacDonald.mp3"));
        assertEquals("Old MacDonald", TrackTitle.fromFilename("07_Old_MacDonald.mp3"));
        assertEquals("Old MacDonald", TrackTitle.fromFilename("1-07 Old MacDonald.mp3"));
        assertEquals("Old MacDonald", TrackTitle.fromFilename("07) Old MacDonald.mp3"));
        assertEquals("Ёлочка", TrackTitle.fromFilename("03 - Ёлочка.mp3"));
    }

    @Test
    public void leavesOtherNamesAlone() {
        assertEquals("Old MacDonald", TrackTitle.fromFilename("Old MacDonald.mp3"));
        assertEquals("07", TrackTitle.fromFilename("07.mp3"));
        assertEquals("01 02", TrackTitle.fromFilename("01 02.mp3"));
        assertEquals("no extension", TrackTitle.fromFilename("no extension"));
        assertEquals("Song 2", TrackTitle.fromFilename("Song  2.m4a"));
    }
}
