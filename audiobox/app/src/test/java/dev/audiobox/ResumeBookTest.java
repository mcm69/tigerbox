package dev.audiobox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class ResumeBookTest {
    private static Playlist playlist(String id, boolean resume, String... files) {
        List<File> tracks = new ArrayList<>();
        for (String f : files) tracks.add(new File("/lib/" + id + "/" + f));
        return new Playlist(id, new File("/lib/" + id), tracks, null, id, null,
                resume, false, false, false);
    }

    @Test
    public void unknownPlaylistStartsAtTheBeginning() {
        ResumeBook b = new ResumeBook(new JSONObject());
        ResumeBook.Spot s = b.spotFor(playlist("a", true, "1.mp3", "2.mp3"));
        assertEquals(0, s.track);
        assertEquals(0, s.positionMs);
        assertNull(b.last());
    }

    @Test
    public void recordsAndResumes() {
        Playlist p = playlist("a", true, "1.mp3", "2.mp3", "3.mp3");
        ResumeBook b = new ResumeBook(new JSONObject());
        b.record(p, 1, 42_000, 600_000);
        b.setLast("a");
        ResumeBook.Spot s = new ResumeBook(b.json()).spotFor(p);
        assertEquals(1, s.track);
        assertEquals(42_000, s.positionMs);
        assertEquals("a", b.last());
    }

    @Test
    public void nearTheEndMovesToTheNextTrack() {
        Playlist p = playlist("a", true, "1.mp3", "2.mp3");
        ResumeBook b = new ResumeBook(new JSONObject());
        b.record(p, 0, 590_000, 600_000);
        ResumeBook.Spot s = b.spotFor(p);
        assertEquals(1, s.track);
        assertEquals(0, s.positionMs);
    }

    @Test
    public void nearTheEndOfTheLastTrackRewinds() {
        Playlist p = playlist("a", true, "1.mp3", "2.mp3");
        ResumeBook b = new ResumeBook(new JSONObject());
        b.record(p, 1, 590_000, 600_000);
        assertEquals(0, b.spotFor(p).track);
    }

    @Test
    public void shortTracksDontSkipAtStart() {
        // A 20 s track is entirely "within 30 s of the end"; that must not skip it.
        Playlist p = playlist("a", true, "1.mp3", "2.mp3");
        ResumeBook b = new ResumeBook(new JSONObject());
        b.record(p, 0, 1_000, 20_000);
        assertEquals(0, b.spotFor(p).track);
        assertEquals(1_000, b.spotFor(p).positionMs);
    }

    @Test
    public void followsTheFileWhenTracksAreInsertedBefore() {
        Playlist before = playlist("a", true, "1.mp3", "2.mp3");
        ResumeBook b = new ResumeBook(new JSONObject());
        b.record(before, 1, 5_000, 600_000);
        Playlist after = playlist("a", true, "0.mp3", "1.mp3", "2.mp3");
        assertEquals(2, b.spotFor(after).track);
    }

    @Test
    public void noResumeAlwaysStartsAtTrackOne() {
        Playlist p = playlist("m", false, "1.mp3", "2.mp3");
        ResumeBook b = new ResumeBook(new JSONObject());
        b.record(p, 1, 5_000, 600_000);
        assertEquals(0, b.spotFor(p).track);
        assertEquals(0, b.spotFor(p).positionMs);
    }

    @Test
    public void indexOutOfRangeFallsBackToStart() throws Exception {
        JSONObject state = new JSONObject(
                "{\"positions\":{\"a\":{\"track\":7,\"file\":\"gone.mp3\",\"positionMs\":9}}}");
        assertEquals(0, new ResumeBook(state).spotFor(playlist("a", true, "1.mp3")).track);
    }
}
