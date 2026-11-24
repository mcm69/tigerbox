package dev.audiobox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class LibraryTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File lib;

    @Before
    public void setUp() throws IOException {
        lib = tmp.newFolder("library");
    }

    private File touch(String path) throws IOException {
        File f = new File(lib, path);
        f.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(1);
        }
        return f;
    }

    private static List<String> ids(List<Playlist> ps) {
        List<String> out = new ArrayList<>();
        for (Playlist p : ps) out.add(p.id);
        return out;
    }

    private static List<String> names(List<File> fs) {
        List<String> out = new ArrayList<>();
        for (File f : fs) out.add(f.getName());
        return out;
    }

    @Test
    public void tracksAreAudioOnlyInNaturalOrder() throws IOException {
        touch("songs/10 ten.mp3");
        touch("songs/2 two.M4A");
        touch("songs/1 one.flac");
        touch("songs/notes.txt");
        touch("songs/._2 two.M4A"); // macOS resource fork
        touch("songs/icon.png");
        touch("songs/sub/3 nested.mp3"); // subfolders are ignored

        List<Playlist> ps = Library.scan(lib, new JSONObject());
        assertEquals(1, ps.size());
        assertEquals(List.of("1 one.flac", "2 two.M4A", "10 ten.mp3"), names(ps.get(0).tracks));
    }

    @Test
    public void foldersWithoutAudioAndDotFoldersAreSkipped() throws IOException {
        touch("empty/readme.txt");
        touch(".trash/a.mp3");
        touch("real/a.mp3");
        assertEquals(List.of("real"), ids(Library.scan(lib, new JSONObject())));
    }

    @Test
    public void defaultsWithoutSettings() throws IOException {
        touch("gruffalo/a.mp3");
        Playlist p = Library.scan(lib, new JSONObject()).get(0);
        assertEquals("gruffalo", p.label);
        assertNull(p.order);
        assertTrue(p.resume);
        assertFalse(p.shuffle);
        assertFalse(p.bedtime);
        assertFalse(p.hidden);
        assertNull(p.icon);
    }

    @Test
    public void settingsAreMergedAndOrderApplied() throws Exception {
        touch("zebra/a.mp3");
        touch("apple/a.mp3");
        touch("lullabies/a.mp3");
        touch("secret/a.mp3");
        JSONObject json = new JSONObject("{\"version\":1,\"playlists\":{"
                + "\"lullabies\":{\"label\":\"Schlaflieder\",\"order\":1,\"resume\":false,\"bedtime\":true},"
                + "\"secret\":{\"hidden\":true},"
                + "\"ghost\":{\"label\":\"not on disk\"}}}");

        List<Playlist> all = Library.scan(lib, json);
        // explicit order first, then by label
        assertEquals(List.of("lullabies", "apple", "secret", "zebra"), ids(all));
        Playlist l = all.get(0);
        assertEquals("Schlaflieder", l.label);
        assertFalse(l.resume);
        assertTrue(l.bedtime);

        assertEquals(List.of("lullabies", "apple", "zebra"), ids(Library.visible(all)));
    }

    @Test
    public void iconIsFoundCaseInsensitively() throws IOException {
        touch("a/x.mp3");
        File icon = touch("a/Icon.JPG");
        File found = Library.scan(lib, new JSONObject()).get(0).icon;
        // Case-insensitive filesystems (macOS, the box's /sdcard) hand back the
        // name as asked; case-sensitive ones return the real name.
        assertTrue(icon.getName().equalsIgnoreCase(found.getName()));
    }

    @Test
    public void missingLibraryDirGivesEmptyList() {
        assertTrue(Library.scan(new File(lib, "nope"), new JSONObject()).isEmpty());
    }

    @Test
    public void storeIsForgivingAndAtomic() throws Exception {
        Store store = new Store(tmp.getRoot());
        assertEquals(0, store.read("missing.json").length());

        File broken = new File(tmp.getRoot(), "broken.json");
        try (FileOutputStream out = new FileOutputStream(broken)) {
            out.write("{not json".getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(0, store.read("broken.json").length());

        store.write(Store.STATE_JSON, new JSONObject().put("last", "gruffalo"));
        assertEquals("gruffalo", store.read(Store.STATE_JSON).getString("last"));
        assertFalse(new File(tmp.getRoot(), Store.STATE_JSON + ".tmp").exists());

        store.ensureLayout();
        assertTrue(store.libraryDir().isDirectory());
        assertTrue(new File(tmp.getRoot(), ".nomedia").isFile());
    }
}
