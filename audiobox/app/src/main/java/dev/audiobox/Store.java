package dev.audiobox;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The JSON files under /sdcard/audiobox/ (APP-SPEC section 6). Reads are forgiving
 * (missing or broken file = empty object, so a bad edit never bricks the box);
 * writes are atomic (temp file + rename) so a power cut never leaves half a file.
 */
public final class Store {
    public static final String LIBRARY_DIR = "library";
    public static final String LIBRARY_JSON = "library.json";
    public static final String SETTINGS_JSON = "settings.json";
    public static final String STATE_JSON = "state.json";

    public final File root;

    public Store(File root) {
        this.root = root;
    }

    public File libraryDir() {
        return new File(root, LIBRARY_DIR);
    }

    /** Creates root/library and the .nomedia marker if missing. */
    public void ensureLayout() throws IOException {
        File lib = libraryDir();
        if (!lib.isDirectory() && !lib.mkdirs()) {
            throw new IOException("cannot create " + lib);
        }
        File nomedia = new File(root, ".nomedia");
        if (!nomedia.exists() && !nomedia.createNewFile()) {
            throw new IOException("cannot create " + nomedia);
        }
    }

    /** Returns the parsed file, or an empty object if it is missing or not valid JSON. */
    public JSONObject read(String name) {
        File f = new File(root, name);
        if (!f.isFile()) return new JSONObject();
        try {
            return new JSONObject(readText(f));
        } catch (IOException | JSONException e) {
            Log.w("Store", "ignoring unreadable " + name + ": " + e.getMessage());
            return new JSONObject();
        }
    }

    public void write(String name, JSONObject json) throws IOException {
        String text;
        try {
            text = json.toString(2) + "\n";
        } catch (JSONException e) {
            throw new IOException(e);
        }
        File target = new File(root, name);
        File tmp = new File(root, name + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
            ((FileOutputStream) out).getFD().sync();
        }
        if (!tmp.renameTo(target)) {
            tmp.delete();
            throw new IOException("cannot replace " + target);
        }
    }

    static String readText(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) Math.min(f.length(), 1 << 20)];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            return new String(buf, 0, n, StandardCharsets.UTF_8);
        }
    }
}
