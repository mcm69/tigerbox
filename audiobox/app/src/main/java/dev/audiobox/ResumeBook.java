package dev.audiobox;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.List;

/**
 * Resume positions per playlist, kept in state.json (APP-SPEC 5.3):
 *
 * <pre>
 * { "last": "animals",
 *   "positions": { "animals": { "track": 1, "file": "2 Owl.m4a", "positionMs": 5300 } } }
 * </pre>
 *
 * The file name is stored next to the index so that adding or removing files in a
 * playlist doesn't make it resume in the wrong track.
 */
public final class ResumeBook {
    /** Within this much of the end of a track, resume moves to the next one. */
    static final long NEAR_END_MS = 30_000;

    /** Where to start a playlist. */
    public static final class Spot {
        public final int track;
        public final long positionMs;

        Spot(int track, long positionMs) {
            this.track = track;
            this.positionMs = positionMs;
        }

        @Override
        public String toString() {
            return "track " + track + " @" + positionMs + "ms";
        }
    }

    private final JSONObject state;

    public ResumeBook(JSONObject state) {
        this.state = state;
    }

    public JSONObject json() {
        return state;
    }

    public String last() {
        String s = state.optString("last", "");
        return s.isEmpty() ? null : s;
    }

    public void setLast(String id) {
        put(state, "last", id);
    }

    /** Where playback of this playlist should start. */
    public Spot spotFor(Playlist p) {
        if (!p.resume) return new Spot(0, 0);
        JSONObject positions = state.optJSONObject("positions");
        JSONObject e = positions == null ? null : positions.optJSONObject(p.id);
        if (e == null) return new Spot(0, 0);

        int index = indexOf(p.tracks, e.optString("file", ""));
        if (index < 0) index = e.optInt("track", 0); // file renamed/removed: best effort
        if (index < 0 || index >= p.tracks.size()) return new Spot(0, 0);
        long pos = Math.max(0, e.optLong("positionMs", 0));
        return new Spot(index, pos);
    }

    /**
     * Records the current spot. A position within {@link #NEAR_END_MS} of the end
     * of the track is saved as the start of the next one; past the last track the
     * playlist rewinds to the start (APP-SPEC 5.3: stop and rewind).
     */
    public void record(Playlist p, int track, long positionMs, long durationMs) {
        if (track < 0 || track >= p.tracks.size()) track = 0;
        if (durationMs > 0 && positionMs >= durationMs - NEAR_END_MS && durationMs > NEAR_END_MS) {
            track++;
            positionMs = 0;
        }
        if (track >= p.tracks.size()) {
            track = 0;
            positionMs = 0;
        }
        JSONObject e = new JSONObject();
        put(e, "track", track);
        put(e, "file", p.tracks.get(track).getName());
        put(e, "positionMs", Math.max(0, positionMs));
        JSONObject positions = state.optJSONObject("positions");
        if (positions == null) {
            positions = new JSONObject();
            put(state, "positions", positions);
        }
        put(positions, p.id, e);
    }

    /** Back to the start (end of playlist reached). */
    public void rewind(Playlist p) {
        record(p, 0, 0, 0);
    }

    private static int indexOf(List<File> tracks, String name) {
        if (name.isEmpty()) return -1;
        for (int i = 0; i < tracks.size(); i++) {
            if (tracks.get(i).getName().equals(name)) return i;
        }
        return -1;
    }

    private static void put(JSONObject o, String k, Object v) {
        try {
            o.put(k, v);
        } catch (JSONException e) {
            throw new IllegalArgumentException(e); // only for NaN/Infinity, never used here
        }
    }
}
