package dev.audiobox;

import android.media.MediaMetadataRetriever;

import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The song title for the player screen: the file's title tag, else a cleaned-up
 * filename ("07 - Old MacDonald.mp3" becomes "Old MacDonald"). Tags are trusted
 * as they are; getting their encoding right is up to whoever loads the box.
 */
final class TrackTitle {
    private TrackTitle() {
    }

    /**
     * A leading track number ("07", "1-07", "07.") followed by a separator
     * (" - ", ". ", "_", ") ", or plain whitespace) and then the actual name.
     */
    private static final Pattern TRACK_NUMBER =
            Pattern.compile("^\\d{1,3}(?:[-.]\\d{1,3})?\\s*[-._)]*\\s*(.+)$");

    /** Reads the title tag; falls back to the filename. Blocking: call off the UI thread. */
    static String read(File track) {
        String tag = null;
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(track.getPath());
            tag = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
        } catch (RuntimeException e) {
            // unreadable file or tags: use the filename
        } finally {
            try {
                r.release();
            } catch (Exception ignored) { // IOException in newer SDK stubs
            }
        }
        if (tag != null && !tag.trim().isEmpty()) return tag.trim();
        return fromFilename(track.getName());
    }

    static String fromFilename(String name) {
        int dot = name.lastIndexOf('.');
        String base = (dot > 0 ? name.substring(0, dot) : name).replace('_', ' ').trim();
        Matcher m = TRACK_NUMBER.matcher(base);
        if (m.matches()) {
            String rest = m.group(1).trim();
            // Only strip when a name is left that isn't just more digits.
            if (!rest.isEmpty() && !rest.matches("\\d+")) base = rest;
        }
        return base.replaceAll("\\s+", " ");
    }
}
