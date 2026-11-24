package dev.audiobox;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.GradientDrawable;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.StatFs;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.text.DateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

/**
 * Parent settings (APP-SPEC section 7, Admin). Reached from a 5 s hold on the
 * grid's top-left corner plus the PIN. Saves settings.json / library.json and
 * sends RELOAD, so the service and the grid pick the changes up at once.
 */
public class AdminActivity extends Activity {
    private static final int[] CAP_STEPS = {5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60, 65, 70, 75, 80, 85, 90, 95, 100};
    private static final int[] SLEEP_CHOICES = {0, 15, 30, 45, 60};
    private static final int[] IDLE_CHOICES = {0, 5, 10, 15, 30, 60};

    private Store store;
    private Settings settings;
    private LinearLayout content;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new Store(new File(Environment.getExternalStorageDirectory(), "audiobox"));
        settings = Settings.from(store.read(Store.SETTINGS_JSON));

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(32, 16, 32, 32);
        scroll.addView(content);
        setContentView(scroll);
        build();
    }

    @Override
    protected void onResume() {
        super.onResume();
        build(); // clock and battery may have changed
    }

    private void build() {
        content.removeAllViews();

        LinearLayout top = row();
        TextView title = Ui.text(this, 30, Ui.TEXT);
        title.setText("Parent settings");
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        top.addView(pill("Done", true, v -> finish()));
        content.addView(top);

        heading("Playlists");
        buildPlaylists();

        heading("Volume limit");
        content.addView(stepper("Speaker", settings.speakerCapPercent + " %", d -> {
            settings.speakerCapPercent = step(CAP_STEPS, settings.speakerCapPercent, d);
            saveSettings();
        }));
        content.addView(stepper("Headphones", settings.headphoneCapPercent + " %", d -> {
            settings.headphoneCapPercent = step(CAP_STEPS, settings.headphoneCapPercent, d);
            saveSettings();
        }));

        heading("Timers");
        content.addView(stepper("Sleep timer default", minutes(settings.sleepDefaultMinutes, "off"), d -> {
            settings.sleepDefaultMinutes = step(SLEEP_CHOICES, settings.sleepDefaultMinutes, d);
            saveSettings();
        }));
        content.addView(stepper("Switch off when idle after", minutes(settings.idleOffMinutes, "never"), d -> {
            settings.idleOffMinutes = step(IDLE_CHOICES, settings.idleOffMinutes, d);
            saveSettings();
        }));
        content.addView(toggleRow("Also switch off while charging", settings.idleOffWhileCharging, () -> {
            settings.idleOffWhileCharging = !settings.idleOffWhileCharging;
            saveSettings();
        }));

        heading("Lights");
        content.addView(toggleRow("Blue light under the box while playing", settings.bottomLight, () -> {
            settings.bottomLight = !settings.bottomLight;
            saveSettings();
        }));

        heading("Bedtime");
        content.addView(toggleRow("Only bedtime playlists between", settings.bedtimeEnabled, () -> {
            settings.bedtimeEnabled = !settings.bedtimeEnabled;
            saveSettings();
        }));
        LinearLayout times = row();
        times.addView(label("From"));
        times.addView(pill(Settings.formatTime(settings.bedtimeStart), true, v -> pickTime(settings.bedtimeStart, m -> {
            settings.bedtimeStart = m;
            saveSettings();
        })));
        times.addView(label("   to"));
        times.addView(pill(Settings.formatTime(settings.bedtimeEnd), true, v -> pickTime(settings.bedtimeEnd, m -> {
            settings.bedtimeEnd = m;
            saveSettings();
        })));
        content.addView(times);

        heading("Clock");
        LinearLayout clock = row();
        TextView now = label(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, java.util.Locale.UK).format(new Date()));
        clock.addView(now, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        clock.addView(pill("Set date", true, v -> pickDate()));
        clock.addView(pill("Set time", true, v -> {
            Calendar c = Calendar.getInstance();
            pickTime(c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE), m -> {
                Calendar n = Calendar.getInstance();
                n.set(Calendar.HOUR_OF_DAY, m / 60);
                n.set(Calendar.MINUTE, m % 60);
                n.set(Calendar.SECOND, 0);
                setClock(n.getTimeInMillis());
            });
        }));
        content.addView(clock);

        heading("PIN");
        LinearLayout pin = row();
        pin.addView(label(settings.hasPin() ? "A PIN protects this screen." : "No PIN set: anyone can open this screen."),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        pin.addView(pill(settings.hasPin() ? "Change PIN" : "Set PIN", true, v -> changePin()));
        if (settings.hasPin()) pin.addView(pill("Remove", false, v -> {
            settings.setPin(null);
            saveSettings();
        }));
        content.addView(pin);

        heading("System");
        content.addView(label(systemInfo()));
        LinearLayout sys = row();
        sys.addView(pill("Android settings", false, v ->
                startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS))));
        content.addView(sys);
    }

    // --- playlists ---------------------------------------------------------

    private void buildPlaylists() {
        JSONObject lib = store.read(Store.LIBRARY_JSON);
        List<Playlist> all = Library.scan(store.libraryDir(), lib);
        if (all.isEmpty()) {
            content.addView(label("No playlists. Add folders from the Mac (tools/audiobox push)."));
            return;
        }
        for (int i = 0; i < all.size(); i++) {
            final Playlist p = all.get(i);
            final int index = i;
            LinearLayout r = row();
            TextView name = label(p.label + "  (" + p.tracks.size() + ")");
            if (p.hidden) name.setAlpha(0.5f);
            r.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            r.addView(pill("▲", false, v -> move(all, index, -1)));
            r.addView(pill("▼", false, v -> move(all, index, 1)));
            r.addView(pill("Resume", p.resume, v -> setFlag(p.id, "resume", !p.resume)));
            r.addView(pill("Shuffle", p.shuffle, v -> setFlag(p.id, "shuffle", !p.shuffle)));
            r.addView(pill("Bedtime", p.bedtime, v -> setFlag(p.id, "bedtime", !p.bedtime)));
            r.addView(pill("Hidden", p.hidden, v -> setFlag(p.id, "hidden", !p.hidden)));
            content.addView(r);
        }
    }

    private void setFlag(String id, String key, boolean value) {
        JSONObject lib = store.read(Store.LIBRARY_JSON);
        try {
            entry(lib, id).put(key, value);
        } catch (JSONException e) {
            return;
        }
        saveLibrary(lib);
    }

    /** Moving one playlist writes an explicit order for all of them. */
    private void move(List<Playlist> all, int index, int delta) {
        int to = index + delta;
        if (to < 0 || to >= all.size()) return;
        Playlist p = all.remove(index);
        all.add(to, p);
        JSONObject lib = store.read(Store.LIBRARY_JSON);
        try {
            for (int i = 0; i < all.size(); i++) entry(lib, all.get(i).id).put("order", i + 1);
        } catch (JSONException e) {
            return;
        }
        saveLibrary(lib);
    }

    private static JSONObject entry(JSONObject lib, String id) throws JSONException {
        JSONObject playlists = lib.optJSONObject("playlists");
        if (playlists == null) {
            playlists = new JSONObject();
            lib.put("playlists", playlists);
        }
        if (!lib.has("version")) lib.put("version", 1);
        JSONObject e = playlists.optJSONObject(id);
        if (e == null) {
            e = new JSONObject();
            playlists.put(id, e);
        }
        return e;
    }

    // --- saving ------------------------------------------------------------

    private void saveSettings() {
        try {
            store.write(Store.SETTINGS_JSON, settings.toJson());
        } catch (IOException e) {
            Toast.makeText(this, "Could not save: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        reloaded();
    }

    private void saveLibrary(JSONObject lib) {
        try {
            store.write(Store.LIBRARY_JSON, lib);
        } catch (IOException e) {
            Toast.makeText(this, "Could not save: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        reloaded();
    }

    private void reloaded() {
        sendBroadcast(new Intent(HomeActivity.ACTION_RELOAD));
        build();
    }

    // --- clock and PIN -----------------------------------------------------

    interface MinuteDone {
        void on(int minuteOfDay);
    }

    private void pickTime(int minuteOfDay, MinuteDone done) {
        new TimePickerDialog(this, (view, h, m) -> done.on(h * 60 + m),
                minuteOfDay / 60, minuteOfDay % 60, true).show();
    }

    private void pickDate() {
        Calendar c = Calendar.getInstance();
        new DatePickerDialog(this, (view, y, mo, d) -> {
            Calendar n = Calendar.getInstance();
            n.set(y, mo, d);
            setClock(n.getTimeInMillis());
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
    }

    /** Needs SET_TIME, which a /system/priv-app install has (milestone M5). */
    private void setClock(long millis) {
        try {
            ((AlarmManager) getSystemService(ALARM_SERVICE)).setTime(millis);
            Toast.makeText(this, "Clock set", Toast.LENGTH_SHORT).show();
        } catch (SecurityException e) {
            Log.w(HomeActivity.TAG, "setTime refused: " + e.getMessage());
            Toast.makeText(this, "Not allowed yet: use 'audiobox time' from the Mac", Toast.LENGTH_LONG).show();
        }
        build();
    }

    private void changePin() {
        final String[] first = {null};
        PinPad[] pad = new PinPad[1];
        pad[0] = new PinPad(this, "New PIN", pin -> {
            if (first[0] == null) {
                first[0] = pin;
                pad[0].setPrompt("Same PIN again");
            } else if (first[0].equals(pin)) {
                settings.setPin(pin);
                saveSettings();
                pad[0].dismiss();
                Toast.makeText(this, "PIN saved", Toast.LENGTH_SHORT).show();
            } else {
                first[0] = null;
                pad[0].setPrompt("Didn't match. New PIN");
            }
        });
        pad[0].show();
    }

    private String systemInfo() {
        Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        String battery = "?";
        if (b != null) {
            int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            boolean plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
            battery = level * 100 / Math.max(1, scale) + " %" + (plugged ? ", charging" : "");
        }
        StatFs fs = new StatFs(Environment.getExternalStorageDirectory().getPath());
        long free = fs.getAvailableBytes() / (1024 * 1024);
        String version = "?";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
            // keep "?"
        }
        return "Battery " + battery + "    Free space " + String.format(java.util.Locale.ROOT, "%.1f GB", free / 1024f)
                + "    audiobox " + version;
    }

    // --- little views ------------------------------------------------------

    interface Delta {
        void by(int d);
    }

    private static int step(int[] choices, int current, int d) {
        int i = 0;
        for (int k = 0; k < choices.length; k++) if (choices[k] <= current) i = k;
        return choices[Math.max(0, Math.min(choices.length - 1, i + d))];
    }

    private static String minutes(int m, String zero) {
        return m == 0 ? zero : m + " min";
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, 6, 0, 6);
        return r;
    }

    private void heading(String text) {
        TextView t = Ui.text(this, 22, Ui.ACCENT);
        t.setText(text);
        t.setPadding(0, 22, 0, 4);
        content.addView(t);
    }

    private TextView label(String text) {
        TextView t = Ui.text(this, 19, Ui.TEXT);
        t.setText(text);
        return t;
    }

    private View stepper(String name, String value, Delta delta) {
        LinearLayout r = row();
        r.addView(label(name), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        r.addView(pill("−", false, v -> delta.by(-1)));
        TextView val = Ui.text(this, 20, Ui.TEXT);
        val.setText(value);
        val.setGravity(Gravity.CENTER);
        r.addView(val, new LinearLayout.LayoutParams(110, ViewGroup.LayoutParams.WRAP_CONTENT));
        r.addView(pill("+", false, v -> delta.by(1)));
        return r;
    }

    private View toggleRow(String name, boolean on, Runnable flip) {
        LinearLayout r = row();
        r.addView(label(name), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        r.addView(pill(on ? "On" : "Off", on, v -> flip.run()));
        return r;
    }

    private Button pill(String text, boolean on, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTypeface(Ui.font(this));
        b.setTextSize(17);
        b.setTextColor(on ? 0xFF1E2A3A : Ui.TEXT);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(18, 0, 18, 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(on ? Ui.ACCENT : Ui.PANEL);
        bg.setCornerRadius(24);
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, 52);
        lp.setMargins(6, 0, 0, 0);
        b.setLayoutParams(lp);
        b.setOnClickListener(click);
        return b;
    }
}
