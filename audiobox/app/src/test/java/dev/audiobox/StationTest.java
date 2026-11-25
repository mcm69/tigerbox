package dev.audiobox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.List;

public class StationTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void scansInGridOrderAndSkipsStationsWithoutUrl() throws Exception {
        File dir = tmp.newFolder("radio");
        assertTrue(new File(dir, "b.png").createNewFile());
        JSONObject json = new JSONObject("{\"version\":1,\"stations\":{"
                + "\"a\":{\"label\":\"Zeta\",\"url\":\"https://x/a.m3u8\"},"
                + "\"b\":{\"label\":\"Beta\",\"url\":\"https://x/b\",\"order\":1,\"bedtime\":true},"
                + "\"c\":{\"label\":\"Alpha\",\"url\":\"https://x/c\",\"hidden\":true},"
                + "\"d\":{\"label\":\"No url\"}}}");
        List<Station> all = Station.scan(dir, json);
        assertEquals(3, all.size());
        assertEquals("b", all.get(0).id); // explicit order first
        assertEquals("c", all.get(1).id); // then by label
        assertEquals("a", all.get(2).id);
        assertTrue(all.get(0).bedtime);
        assertNotNull(all.get(0).logo);
        assertNull(all.get(2).logo);
        assertEquals(2, Station.visible(all).size());
    }

    @Test
    public void labelFallsBackToId() throws Exception {
        List<Station> all = Station.scan(null,
                new JSONObject("{\"stations\":{\"klassika\":{\"url\":\"https://x\"}}}"));
        assertEquals("klassika", all.get(0).label);
    }

    @Test
    public void missingFileMeansNoStations() {
        assertEquals(0, Station.scan(null, new JSONObject()).size());
    }
}
