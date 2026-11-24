package dev.audiobox;

import java.io.File;
import java.util.Collections;
import java.util.List;

/** One folder under library/, merged with its library.json settings (APP-SPEC section 5). */
public final class Playlist {
    /** Folder name; the stable key used by state.json and, in phase 2, cards.json. */
    public final String id;
    public final File dir;
    /** Audio files in natural filename order; never empty. */
    public final List<File> tracks;
    /** icon.png/icon.jpg in the folder, or null (then embedded art or a letter tile). */
    public final File icon;

    public final String label;
    /** Explicit grid position, or null for "after the ordered ones, alphabetically". */
    public final Integer order;
    public final boolean resume;
    public final boolean shuffle;
    public final boolean bedtime;
    public final boolean hidden;

    Playlist(String id, File dir, List<File> tracks, File icon, String label, Integer order,
             boolean resume, boolean shuffle, boolean bedtime, boolean hidden) {
        this.id = id;
        this.dir = dir;
        this.tracks = Collections.unmodifiableList(tracks);
        this.icon = icon;
        this.label = label;
        this.order = order;
        this.resume = resume;
        this.shuffle = shuffle;
        this.bedtime = bedtime;
        this.hidden = hidden;
    }

    @Override
    public String toString() {
        return id + " (" + tracks.size() + " tracks)";
    }
}
