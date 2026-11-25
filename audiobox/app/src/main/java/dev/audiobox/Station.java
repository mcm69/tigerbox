package dev.audiobox;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/** One internet radio station from radio.json, with its optional logo (RADIO-SPEC R4). */
public final class Station {
    public static final String RADIO_DIR = "radio";
    public static final String RADIO_JSON = "radio.json";
    static final String[] LOGO_EXTENSIONS = {"png", "jpg", "jpeg"};

    /** The key in radio.json; also the logo's file name. */
    public final String id;
    public final String url;
    public final String label;
    /** radio/<id>.png|jpg, or null (then a letter tile). */
    public final File logo;
    /** Explicit grid position, or null for "after the ordered ones, by label". */
    public final Integer order;
    public final boolean bedtime;
    public final boolean hidden;

    Station(String id, String url, String label, File logo, Integer order, boolean bedtime, boolean hidden) {
        this.id = id;
        this.url = url;
        this.label = label;
        this.logo = logo;
        this.order = order;
        this.bedtime = bedtime;
        this.hidden = hidden;
    }

    /** Every station with a URL, including hidden ones, in grid order. */
    public static List<Station> scan(File radioDir, JSONObject radioJson) {
        JSONObject all = radioJson.optJSONObject("stations");
        List<Station> out = new ArrayList<>();
        if (all == null) return out;
        for (Iterator<String> it = all.keys(); it.hasNext(); ) {
            String id = it.next();
            JSONObject s = all.optJSONObject(id);
            if (s == null) continue;
            String url = s.optString("url", "").trim();
            if (url.isEmpty()) continue;
            String label = s.optString("label", "").trim();
            if (label.isEmpty()) label = id;
            Integer order = s.has("order") && !s.isNull("order") ? s.optInt("order") : null;
            out.add(new Station(id, url, label, logoFor(radioDir, id), order,
                    s.optBoolean("bedtime", false), s.optBoolean("hidden", false)));
        }
        Collections.sort(out, GRID_ORDER);
        return out;
    }

    public static List<Station> visible(List<Station> all) {
        List<Station> out = new ArrayList<>();
        for (Station s : all) if (!s.hidden) out.add(s);
        return out;
    }

    static File logoFor(File radioDir, String id) {
        if (radioDir == null) return null;
        for (String ext : LOGO_EXTENSIONS) {
            File f = new File(radioDir, id + "." + ext);
            if (f.isFile()) return f;
            f = new File(radioDir, id + "." + ext.toUpperCase(Locale.ROOT));
            if (f.isFile()) return f;
        }
        return null;
    }

    static final Comparator<Station> GRID_ORDER = (a, b) -> {
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

    /** Same station, same stream: switching to it again needs no reconnect. */
    boolean sameStream(Station other) {
        return other != null && id.equals(other.id) && url.equals(other.url);
    }

    @Override
    public String toString() {
        return id + " (" + url + ")";
    }
}
