package dev.audiobox;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Scans library/ and merges it with library.json (APP-SPEC section 5).
 * Plain java.io + org.json so it runs in local unit tests.
 */
public final class Library {
    /** Formats Android 5.1 decodes natively (APP-SPEC 5.1). Opus is left out until tested. */
    static final Set<String> AUDIO_EXTENSIONS = new HashSet<>(Arrays.asList(
            "mp3", "m4a", "aac", "mp4", "ogg", "oga", "flac", "wav"));
    static final List<String> ICON_NAMES = Arrays.asList(
            "icon.png", "icon.jpg", "icon.jpeg");

    private Library() {}

    /**
     * Every playlist found on disk, including hidden ones, in grid order:
     * explicit "order" first (ascending), then the rest by label.
     */
    public static List<Playlist> scan(File libraryDir, JSONObject libraryJson) {
        JSONObject settings = libraryJson.optJSONObject("playlists");
        if (settings == null) settings = new JSONObject();

        File[] dirs = libraryDir.listFiles(File::isDirectory);
        List<Playlist> out = new ArrayList<>();
        if (dirs == null) return out;

        for (File dir : dirs) {
            String id = dir.getName();
            if (id.startsWith(".")) continue;
            List<File> tracks = tracksIn(dir);
            if (tracks.isEmpty()) continue; // nothing playable: not on the grid

            JSONObject s = settings.optJSONObject(id);
            if (s == null) s = new JSONObject();
            String label = s.optString("label", "").trim();
            if (label.isEmpty()) label = id;
            Integer order = s.has("order") && !s.isNull("order") ? s.optInt("order") : null;

            out.add(new Playlist(id, dir, tracks, iconIn(dir), label, order,
                    s.optBoolean("resume", true),
                    s.optBoolean("shuffle", false),
                    s.optBoolean("bedtime", false),
                    s.optBoolean("hidden", false)));
        }
        Collections.sort(out, GRID_ORDER);
        return out;
    }

    /** The playlists the grid shows: scan() minus hidden ones. */
    public static List<Playlist> visible(List<Playlist> all) {
        List<Playlist> out = new ArrayList<>();
        for (Playlist p : all) if (!p.hidden) out.add(p);
        return out;
    }

    static final Comparator<Playlist> GRID_ORDER = (a, b) -> {
        if (a.order != null && b.order != null) {
            int c = Integer.compare(a.order, b.order);
            if (c != 0) return c;
        } else if (a.order != null) {
            return -1;
        } else if (b.order != null) {
            return 1;
        }
        int c = NaturalOrder.INSTANCE.compare(a.label, b.label);
        return c != 0 ? c : a.id.compareTo(b.id);
    };

    static List<File> tracksIn(File dir) {
        File[] files = dir.listFiles(f -> f.isFile() && isAudio(f.getName()));
        List<File> out = new ArrayList<>();
        if (files == null) return out;
        out.addAll(Arrays.asList(files));
        Collections.sort(out, (a, b) -> NaturalOrder.INSTANCE.compare(a.getName(), b.getName()));
        return out;
    }

    static boolean isAudio(String name) {
        if (name.startsWith(".")) return false; // macOS "._" resource forks etc.
        int dot = name.lastIndexOf('.');
        return dot > 0 && AUDIO_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    static File iconIn(File dir) {
        for (String n : ICON_NAMES) {
            File f = new File(dir, n);
            if (f.isFile()) return f;
        }
        // Case-insensitive fallback (Icon.PNG from a Mac).
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && ICON_NAMES.contains(f.getName().toLowerCase(Locale.ROOT))) return f;
            }
        }
        return null;
    }
}
