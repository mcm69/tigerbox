package dev.audiobox;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The kid UI (APP-SPEC section 7): a paged 4 x 2 grid of playlists, and the player
 * screen. With radio switched on, two tabs in the top bar switch the grid
 * between playlists and stations (RADIO-SPEC R5). Built in code, sized for the box's 800 x 480 mdpi panel (1 dp = 1 px).
 */
public class HomeActivity extends Activity implements PlayerService.Listener {
    static final String TAG = "audiobox";
    /** Sent by the Mac tool after it changes files (APP-SPEC section 6). */
    public static final String ACTION_RELOAD = "dev.audiobox.RELOAD";

    private static final int COLUMNS = 4, ROWS = 2, PER_PAGE = COLUMNS * ROWS;
    private static final int ICON_PX = 150;
    private static final int COVER_PX = 340;
    /** The highlight ring around the loaded tile. */
    private static final int RING_RADIUS = 18, RING_WIDTH = 6;
    private static final int VOLUME_STEP = 5; // STREAM_MUSIC runs 0..100 on this box

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Icons icons = new Icons(ICON_PX);
    private final Icons covers = new Icons(COVER_PX);
    private final List<Playlist> playlists = new ArrayList<>();
    private final List<Station> stations = new ArrayList<>();
    /** The grid shows stations (radio tab) instead of playlists. */
    private boolean radioTab;
    private boolean radioEnabled;
    private boolean online;

    private Store store;
    private AudioManager audio;
    private PlayerService player;
    private PlayerService.Snapshot snap = new PlayerService.Snapshot(null, 0, false, 0, 0);
    private int page;

    // grid
    private View gridScreen;
    private FrameLayout tiles;
    private Ui.DotsView pageDots;
    private TextView empty;
    private Ui.BatteryView battery;
    private LinearLayout tabs;
    private ImageButton musicTabButton, radioTabButton;
    // player
    private View playerScreen;
    private ImageView cover;
    private TextView title, trackText;
    /** Song title under the playlist label; scrolls (marquee) while playing. */
    private TextView song;
    private String songFor; // path of the track whose title is shown
    private boolean songScrolling;
    private final android.util.LruCache<String, String> songTitles = new android.util.LruCache<>(64);
    private Ui.DotsView trackDots;
    private Ui.BarView progress, volumeBar;
    private ImageButton playPause;
    private TextView sleepText;
    private View messageOverlay;
    private ImageView messageIcon;
    private TextView messageText;

    private final BroadcastReceiver reload = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.i(TAG, "RELOAD received");
            icons.clear();
            covers.clear();
            rescan();
        }
    };

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent i) {
            int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL;
            if (level >= 0 && scale > 0) battery.set(level * 100 / scale, charging);
        }
    };

    private final BroadcastReceiver connectivity = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean now = Net.online(context);
            if (now == online) return;
            online = now;
            if (radioTab) layoutPage();
            showPlayer(snap);
        }
    };

    private final BroadcastReceiver volumeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            showVolume();
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            player = ((PlayerService.LocalBinder) binder).service();
            player.addListener(HomeActivity.this);
            showScreenFor(player.snapshot());
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            player = null;
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (player != null && playerScreen.getVisibility() == View.VISIBLE) {
                showProgress(player.snapshot());
            }
            main.postDelayed(this, 500);
        }
    };

    // --- lifecycle ---------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        icons.face = covers.face = Ui.font(this);
        store = new Store(new File(Environment.getExternalStorageDirectory(), "audiobox"));
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        gridScreen = buildGrid();
        playerScreen = buildPlayer();
        root.addView(gridScreen);
        root.addView(playerScreen);
        playerScreen.setVisibility(View.GONE);
        messageOverlay = buildMessageOverlay();
        root.addView(messageOverlay);
        setContentView(root);

        registerReceiver(reload, new IntentFilter(ACTION_RELOAD));
        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        registerReceiver(volumeReceiver, new IntentFilter("android.media.VOLUME_CHANGED_ACTION"));
        online = Net.online(this);
        registerReceiver(connectivity, new IntentFilter(android.net.ConnectivityManager.CONNECTIVITY_ACTION));
        PlayerService.send(this, PlayerService.ACTION_START);
    }

    @Override
    protected void onStart() {
        super.onStart();
        bindService(new Intent(this, PlayerService.class), connection, BIND_AUTO_CREATE);
        main.post(tick);
    }

    @Override
    protected void onResume() {
        super.onResume();
        rescan();
        showVolume();
        if (player != null) showScreenFor(player.snapshot());
    }

    @Override
    protected void onStop() {
        main.removeCallbacks(tick);
        if (player != null) player.removeListener(this);
        unbindService(connection);
        player = null;
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(reload);
        unregisterReceiver(batteryReceiver);
        unregisterReceiver(volumeReceiver);
        unregisterReceiver(connectivity);
        io.shutdownNow();
        super.onDestroy();
    }

    /**
     * Swipes are seen here, before any tile or button takes the touch: on the grid
     * they turn pages, on the player a swipe right goes back to the grid.
     */
    private GestureDetector swipe;

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (swipe == null) {
            swipe = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                    if (a == null || Math.abs(vx) < 600 || Math.abs(vx) < 1.5f * Math.abs(vy)) return false;
                    if (Math.abs(b.getX() - a.getX()) < 120) return false;
                    if (gridScreen.getVisibility() == View.VISIBLE) turnPage(vx < 0 ? 1 : -1);
                    else if (vx > 0) showGrid();
                    return true;
                }
            });
        }
        swipe.onTouchEvent(ev);
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN && player != null) player.userActivity();
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public void onBackPressed() {
        if (playerScreen.getVisibility() == View.VISIBLE) showGrid();
        // On the grid: nothing. This is the home screen.
    }

    // With the screen on the big button reaches the focused window, not the
    // MediaSession (APP-SPEC section 4).
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return ButtonHandler.get(this).onKey(event) || super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        return ButtonHandler.get(this).onKey(event) || super.onKeyUp(keyCode, event);
    }

    @Override
    public void onBlocked() {
        showMessage(R.drawable.ic_moon, getString(R.string.bedtime));
    }

    /** A big icon and a word or two over everything, for 3 s (bedtime, no internet). */
    private void showMessage(int icon, String text) {
        messageIcon.setImageResource(icon);
        // The moon is tinted; the no-Wi-Fi icon has its own two colours.
        if (icon == R.drawable.ic_moon) messageIcon.setColorFilter(0xFFFFE082);
        else messageIcon.clearColorFilter();
        messageText.setText(text);
        messageOverlay.setVisibility(View.VISIBLE);
        main.removeCallbacks(hideMessage);
        main.postDelayed(hideMessage, 3_000);
    }

    private final Runnable hideMessage = () -> messageOverlay.setVisibility(View.GONE);

    private View buildMessageOverlay() {
        LinearLayout o = new LinearLayout(this);
        o.setOrientation(LinearLayout.VERTICAL);
        o.setGravity(Gravity.CENTER);
        o.setBackgroundColor(0xF0101828);
        messageIcon = new ImageView(this);
        o.addView(messageIcon, new LinearLayout.LayoutParams(200, 200));
        messageText = Ui.text(this, 36, Ui.TEXT);
        messageText.setGravity(Gravity.CENTER);
        messageText.setPadding(0, 20, 0, 0);
        o.addView(messageText);
        o.setVisibility(View.GONE);
        o.setOnClickListener(v -> hideMessage.run());
        return o;
    }

    /** "p:id" or "s:id" for what's loaded, or null. */
    private static String loadedKey(PlayerService.Snapshot s) {
        return s.playlist != null ? "p:" + s.playlist.id : s.station != null ? "s:" + s.station.id : null;
    }

    @Override
    public void onPlayerChanged(PlayerService.Snapshot s) {
        String before = loadedKey(snap), now = loadedKey(s);
        boolean started = s.playing && !snap.playing;
        snap = s;
        showPlayer(s);
        if (started && gridScreen.getVisibility() == View.VISIBLE) {
            // Playback started from the big button (e.g. resuming after boot): show
            // what's playing. A tile tap opens the player itself. Going back lands
            // on the tab and page holding it.
            setTab(s.station != null);
            showPlayerScreen();
        }
        if (before == null ? now != null : !before.equals(now)) layoutPage(); // move the highlight
        else refreshBadges();
    }

    // --- grid --------------------------------------------------------------

    private View buildGrid() {
        FrameLayout screen = new FrameLayout(this);

        battery = new Ui.BatteryView(this);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(48, 24, Gravity.TOP | Gravity.END);
        blp.setMargins(0, 12, 16, 0);
        screen.addView(battery, blp);

        // Clock, opposite the battery. 24-hour whatever the system setting;
        // it sits under the admin corner, which still gets the touches.
        TextClock clock = new TextClock(this);
        clock.setFormat24Hour("H:mm");
        clock.setFormat12Hour("H:mm");
        clock.setTextSize(22);
        clock.setTextColor(Ui.TEXT);
        clock.setTypeface(Ui.font(this));
        clock.setIncludeFontPadding(false);
        FrameLayout.LayoutParams clk = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        clk.setMargins(20, 8, 0, 0);
        screen.addView(clock, clk);

        // Music / radio tabs, centred in the top bar (RADIO-SPEC R5).
        tabs = new LinearLayout(this);
        tabs.setGravity(Gravity.CENTER);
        musicTabButton = tabButton(R.drawable.ic_music);
        musicTabButton.setOnClickListener(v -> setTab(false));
        radioTabButton = tabButton(R.drawable.ic_radio);
        radioTabButton.setOnClickListener(v -> setTab(true));
        LinearLayout.LayoutParams tb = new LinearLayout.LayoutParams(96, 40);
        tb.setMargins(8, 0, 8, 0);
        tabs.addView(musicTabButton, tb);
        tabs.addView(radioTabButton, new LinearLayout.LayoutParams(tb));
        tabs.setVisibility(View.GONE);
        FrameLayout.LayoutParams tabsLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, 44, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        tabsLp.topMargin = 2;
        screen.addView(tabs, tabsLp);

        tiles = new FrameLayout(this);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(800, 400, Gravity.TOP);
        tlp.topMargin = 40;
        screen.addView(tiles, tlp);

        // Admin: hold the top-left corner for 5 s (APP-SPEC section 7).
        View corner = new View(this);
        final Runnable openAdmin = this::askPinForAdmin;
        corner.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                main.postDelayed(openAdmin, 5_000);
            } else if (e.getActionMasked() == MotionEvent.ACTION_UP
                    || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                main.removeCallbacks(openAdmin);
            }
            return true;
        });
        screen.addView(corner, new FrameLayout.LayoutParams(90, 90, Gravity.TOP | Gravity.START));

        pageDots = new Ui.DotsView(this);
        screen.addView(pageDots, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 30, Gravity.BOTTOM));

        empty = Ui.text(this, 26, Ui.TEXT_DIM);
        empty.setText(R.string.empty_library);
        empty.setGravity(Gravity.CENTER);
        empty.setVisibility(View.GONE);
        screen.addView(empty, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        return screen;
    }

    private ImageButton tabButton(int icon) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(icon);
        b.setScaleType(ImageView.ScaleType.FIT_CENTER);
        b.setPadding(0, 5, 0, 5);
        return b;
    }

    private void setTab(boolean radio) {
        if (radio == radioTab) return;
        radioTab = radio;
        page = 0;
        showTabs();
        layoutPage();
    }

    /** Tabs only when radio is on and there is a station; otherwise just playlists. */
    private void showTabs() {
        boolean show = radioEnabled && !stations.isEmpty();
        if (!show) radioTab = false;
        tabs.setVisibility(show ? View.VISIBLE : View.GONE);
        styleTab(musicTabButton, !radioTab);
        styleTab(radioTabButton, radioTab);
    }

    private static void styleTab(ImageButton b, boolean selected) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(selected ? Ui.ACCENT : Ui.PANEL);
        bg.setCornerRadius(20);
        b.setBackground(bg);
        b.setColorFilter(selected ? 0xFF1E2A3A : Ui.TEXT);
    }

    private int itemCount() {
        return radioTab ? stations.size() : playlists.size();
    }

    private int pageCount() {
        return Math.max(1, (itemCount() + PER_PAGE - 1) / PER_PAGE);
    }

    private void turnPage(int delta) {
        int p = Math.max(0, Math.min(pageCount() - 1, page + delta));
        if (p == page) return;
        page = p;
        layoutPage();
    }

    private void layoutPage() {
        tiles.removeAllViews();
        page = Math.min(page, pageCount() - 1);
        int cellW = 800 / COLUMNS, cellH = 400 / ROWS;
        int first = page * PER_PAGE;
        for (int i = first; i < Math.min(itemCount(), first + PER_PAGE); i++) {
            int slot = i - first;
            Tile t = radioTab ? new Tile(this, stations.get(i)) : new Tile(this, playlists.get(i));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(cellW, cellH);
            lp.leftMargin = (slot % COLUMNS) * cellW;
            lp.topMargin = (slot / COLUMNS) * cellH;
            tiles.addView(t, lp);
        }
        pageDots.set(pageCount(), page);
        empty.setText(radioTab ? R.string.empty_radio : R.string.empty_library);
        empty.setVisibility(itemCount() == 0 ? View.VISIBLE : View.GONE);
    }

    private void refreshBadges() {
        for (int i = 0; i < tiles.getChildCount(); i++) ((Tile) tiles.getChildAt(i)).refresh();
    }

    private void rescan() {
        io.execute(() -> {
            try {
                store.ensureLayout();
            } catch (IOException e) {
                Log.e(TAG, "storage not ready: " + e.getMessage());
            }
            List<Playlist> found = Library.visible(
                    Library.scan(store.libraryDir(), store.read(Store.LIBRARY_JSON)));
            List<Station> foundStations = Station.visible(store.stations());
            boolean radioOn = Settings.from(store.read(Store.SETTINGS_JSON)).radioEnabled;
            Log.i(TAG, "library: " + found + (radioOn ? ", stations: " + foundStations : ""));
            main.post(() -> {
                playlists.clear();
                playlists.addAll(found);
                stations.clear();
                stations.addAll(foundStations);
                radioEnabled = radioOn;
                showTabs();
                layoutPage();
            });
        });
    }

    /** Icon with its short label; a ring and a badge when it's what's loaded. */
    private final class Tile extends LinearLayout {
        final Playlist playlist; // one of these two is set
        final Station station;
        final FrameLayout frame;
        final ImageView image;
        final View badge;
        final View offline;

        Tile(Context c, Playlist p) {
            this(c, p, null);
        }

        Tile(Context c, Station st) {
            this(c, null, st);
        }

        private Tile(Context c, Playlist p, Station st) {
            super(c);
            playlist = p;
            station = st;
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_HORIZONTAL);
            setPadding(0, 4, 0, 0);

            frame = new FrameLayout(c);
            frame.setPadding(RING_WIDTH, RING_WIDTH, RING_WIDTH, RING_WIDTH);
            image = new ImageView(c);
            // Round the cover to the ring's radius minus its width, so the ring's inner
            // corners follow the outer ones (and covers look the same unhighlighted).
            image.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View v, android.graphics.Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), RING_RADIUS - RING_WIDTH);
                }
            });
            image.setClipToOutline(true);
            frame.addView(image, new FrameLayout.LayoutParams(ICON_PX, ICON_PX));
            badge = new ImageView(c);
            ((ImageView) badge).setImageResource(R.drawable.ic_play);
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(Ui.ACCENT);
            badge.setBackground(dot);
            badge.setPadding(6, 6, 6, 6);
            frame.addView(badge, new FrameLayout.LayoutParams(44, 44, Gravity.BOTTOM | Gravity.END));
            // Stations only: a "no Wi-Fi" mark while the box is offline (RADIO-SPEC R3).
            ImageView off = new ImageView(c);
            off.setImageResource(R.drawable.ic_wifi_off);
            GradientDrawable offBg = new GradientDrawable();
            offBg.setShape(GradientDrawable.OVAL);
            offBg.setColor(Ui.PANEL);
            off.setBackground(offBg);
            off.setPadding(8, 8, 8, 8);
            offline = off;
            frame.addView(off, new FrameLayout.LayoutParams(44, 44, Gravity.TOP | Gravity.END));
            addView(frame, new LayoutParams(ICON_PX + 12, ICON_PX + 12));

            TextView label = Ui.text(c, 21, Ui.TEXT);
            label.setText(p != null ? p.label : st.label);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setGravity(Gravity.CENTER);
            LayoutParams lp = new LayoutParams(190, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = 2;
            addView(label, lp);

            setOnClickListener(v -> {
                if (playlist != null) {
                    PlayerService.play(HomeActivity.this, playlist.id);
                    if (player == null || !player.blockedNow(playlist)) showPlayerScreen();
                } else if (!online) {
                    Lights.get().errorFlash();
                    showMessage(R.drawable.ic_wifi_off, getString(R.string.no_internet));
                } else {
                    PlayerService.playStation(HomeActivity.this, station.id);
                    if (player == null || !player.blockedNow(station)) showPlayerScreen();
                }
            });
            refresh();
            Bitmap b = p != null ? icons.cached(p) : icons.cached(st);
            if (b != null) image.setImageBitmap(b);
            else io.execute(() -> {
                Bitmap loaded = p != null ? icons.load(p) : icons.load(st);
                main.post(() -> image.setImageBitmap(loaded));
            });
        }

        void refresh() {
            boolean loaded = playlist != null
                    ? snap.playlist != null && snap.playlist.id.equals(playlist.id)
                    : snap.station != null && snap.station.id.equals(station.id);
            if (loaded) {
                GradientDrawable ring = new GradientDrawable();
                ring.setCornerRadius(RING_RADIUS);
                ring.setColor(Ui.ACCENT);
                frame.setBackground(ring);
            } else {
                frame.setBackground(null);
            }
            badge.setVisibility(loaded && snap.playing ? View.VISIBLE : View.GONE);
            offline.setVisibility(station != null && !online ? View.VISIBLE : View.GONE);
            boolean blocked = player != null
                    && (playlist != null ? player.blockedNow(playlist) : player.blockedNow(station));
            setAlpha(blocked || (station != null && !online) ? 0.35f : 1f);
        }
    }

    // --- player ------------------------------------------------------------

    private View buildPlayer() {
        FrameLayout screen = new FrameLayout(this);

        cover = new ImageView(this);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(COVER_PX, COVER_PX);
        clp.setMargins(40, 100, 0, 0);
        screen.addView(cover, clp);

        ImageButton back = button(R.drawable.ic_back, 80, 0, Ui.PANEL);
        back.setOnClickListener(v -> showGrid());
        FrameLayout.LayoutParams back_lp = new FrameLayout.LayoutParams(80, 80);
        back_lp.setMargins(40, 10, 0, 0);
        screen.addView(back, back_lp);

        FrameLayout moonBox = new FrameLayout(this);
        ImageButton moon = button(R.drawable.ic_moon, 72, 16, Ui.PANEL);
        moon.setOnClickListener(v -> {
            if (player != null) player.cycleSleepTimer();
        });
        moonBox.addView(moon, new FrameLayout.LayoutParams(72, 72));
        sleepText = Ui.text(this, 16, 0xFF1E2A3A);
        sleepText.setGravity(Gravity.CENTER);
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setColor(Ui.ACCENT);
        pillBg.setCornerRadius(12);
        sleepText.setBackground(pillBg);
        sleepText.setPadding(6, 2, 6, 2);
        moonBox.addView(sleepText, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END));
        // Above the cover's right edge, opposite the home button.
        FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(84, 84);
        mlp.setMargins(296, 8, 0, 0);
        screen.addView(moonBox, mlp);

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(380, 460);
        rlp.setMargins(405, 10, 0, 0);
        screen.addView(right, rlp);

        // Playlist label (one line: labels are short) and the song title below
        // it; together they take the space the two-line label used to.
        title = Ui.text(this, 30, Ui.TEXT);
        title.setGravity(Gravity.CENTER);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 48);
        tlp.topMargin = 18;
        right.addView(title, tlp);

        song = Ui.text(this, 22, Ui.TEXT_DIM);
        song.setGravity(Gravity.CENTER);
        song.setSingleLine(true);
        song.setEllipsize(TextUtils.TruncateAt.END);
        song.setMarqueeRepeatLimit(-1); // forever, while playing
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 44);
        slp.setMargins(12, 0, 12, 0);
        right.addView(song, slp);

        trackDots = new Ui.DotsView(this);
        right.addView(trackDots, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 24));
        trackText = Ui.text(this, 22, Ui.TEXT_DIM);
        trackText.setGravity(Gravity.CENTER);
        right.addView(trackText, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 30));

        progress = new Ui.BarView(this, Ui.TEXT_DIM);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(320, 8);
        plp.topMargin = 12;
        right.addView(progress, plp);

        LinearLayout transport = new LinearLayout(this);
        transport.setGravity(Gravity.CENTER);
        ImageButton prev = button(R.drawable.ic_prev, 96, 14, 0);
        prev.setOnClickListener(v -> PlayerService.send(this, PlayerService.ACTION_PREV));
        playPause = button(R.drawable.ic_play, 132, 18, Ui.ACCENT);
        playPause.setOnClickListener(v -> PlayerService.send(this, PlayerService.ACTION_TOGGLE));
        ImageButton next = button(R.drawable.ic_next, 96, 14, 0);
        next.setOnClickListener(v -> PlayerService.send(this, PlayerService.ACTION_NEXT));
        transport.addView(prev, new LinearLayout.LayoutParams(96, 96));
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(132, 132);
        pp.setMargins(20, 0, 20, 0);
        transport.addView(playPause, pp);
        transport.addView(next, new LinearLayout.LayoutParams(96, 96));
        LinearLayout.LayoutParams trlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 150);
        trlp.topMargin = 16;
        right.addView(transport, trlp);

        LinearLayout volume = new LinearLayout(this);
        volume.setGravity(Gravity.CENTER);
        ImageButton down = button(R.drawable.ic_vol_down, 80, 16, Ui.PANEL);
        down.setOnClickListener(v -> changeVolume(-VOLUME_STEP));
        volumeBar = new Ui.BarView(this, Ui.ACCENT);
        ImageButton up = button(R.drawable.ic_vol_up, 80, 16, Ui.PANEL);
        up.setOnClickListener(v -> changeVolume(VOLUME_STEP));
        volume.addView(down, new LinearLayout.LayoutParams(80, 80));
        LinearLayout.LayoutParams vb = new LinearLayout.LayoutParams(160, 20);
        vb.setMargins(16, 0, 16, 0);
        volume.addView(volumeBar, vb);
        volume.addView(up, new LinearLayout.LayoutParams(80, 80));
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 90);
        vlp.topMargin = 10;
        right.addView(volume, vlp);

        return screen;
    }

    private ImageButton button(int icon, int size, int padding, int background) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(icon);
        b.setScaleType(ImageView.ScaleType.FIT_CENTER);
        b.setPadding(padding, padding, padding, padding);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(background);
        b.setBackground(bg);
        b.setMinimumWidth(size);
        b.setMinimumHeight(size);
        return b;
    }

    private void showScreenFor(PlayerService.Snapshot s) {
        onPlayerChanged(s);
        if (s.loaded()) {
            // The tab of whatever is loaded, so "back" lands next to it.
            radioTab = s.station != null;
            showTabs();
            showPlayerScreen();
        } else {
            showGrid();
        }
    }

    private void showGrid() {
        playerScreen.setVisibility(View.GONE);
        gridScreen.setVisibility(View.VISIBLE);
        // Show the page holding what's loaded, when it's in this tab.
        if (!radioTab && snap.playlist != null) {
            for (int i = 0; i < playlists.size(); i++) {
                if (playlists.get(i).id.equals(snap.playlist.id)) page = i / PER_PAGE;
            }
        } else if (radioTab && snap.station != null) {
            for (int i = 0; i < stations.size(); i++) {
                if (stations.get(i).id.equals(snap.station.id)) page = i / PER_PAGE;
            }
        }
        layoutPage();
    }

    private void showPlayerScreen() {
        gridScreen.setVisibility(View.GONE);
        playerScreen.setVisibility(View.VISIBLE);
        showVolume();
    }

    private void showPlayer(PlayerService.Snapshot s) {
        if (!s.loaded()) {
            if (playerScreen.getVisibility() == View.VISIBLE) showGrid();
            return;
        }
        if (s.station != null) {
            showStation(s);
            return;
        }
        cover.setAlpha(1f);
        progress.setVisibility(View.VISIBLE);
        trackText.setTextColor(Ui.TEXT_DIM);
        title.setText(s.playlist.label);
        showSong(s);
        int n = s.playlist.tracks.size();
        if (n <= 12) {
            trackDots.setVisibility(View.VISIBLE);
            trackDots.set(n, s.track);
            trackText.setText("");
        } else {
            trackDots.setVisibility(View.INVISIBLE);
            trackText.setText((s.track + 1) + " / " + n);
        }
        playPause.setImageResource(s.playing ? R.drawable.ic_pause : R.drawable.ic_play);
        showProgress(s);
        Bitmap c = covers.cached(s.playlist);
        if (c != null) cover.setImageBitmap(c);
        else {
            Playlist p = s.playlist;
            io.execute(() -> {
                Bitmap loaded = covers.load(p);
                main.post(() -> {
                    if (snap.playlist != null && snap.playlist.id.equals(p.id)) cover.setImageBitmap(loaded);
                });
            });
        }
    }

    /** The radio player (RADIO-SPEC R5): logo, name, "now playing", and the stream's state. */
    private void showStation(PlayerService.Snapshot s) {
        Station st = s.station;
        title.setText(st.label);
        songFor = null; // a playlist shown later sets its own title again
        String now = s.nowPlaying != null ? s.nowPlaying : "";
        if (!now.contentEquals(song.getText())) song.setText(now);
        boolean live = s.radioState == RadioPlayer.State.LIVE;
        setScrolling(live);
        trackDots.setVisibility(View.INVISIBLE);
        progress.setVisibility(View.INVISIBLE);
        String state;
        int colour = Ui.TEXT_DIM;
        switch (s.radioState) {
            case LIVE:
                state = "● LIVE";
                colour = Ui.ACCENT;
                break;
            case CONNECTING:
            case RETRYING:
                state = online ? getString(R.string.connecting) : getString(R.string.no_internet);
                break;
            case FAILED:
                state = online ? getString(R.string.cant_reach) : getString(R.string.no_internet);
                break;
            default:
                state = "";
        }
        trackText.setText(state);
        trackText.setTextColor(colour);
        playPause.setImageResource(s.playing ? R.drawable.ic_pause : R.drawable.ic_play);
        cover.setAlpha(s.radioState == RadioPlayer.State.FAILED ? 0.4f : 1f);
        showProgress(s);
        Bitmap c = covers.cached(st);
        if (c != null) cover.setImageBitmap(c);
        else io.execute(() -> {
            Bitmap loaded = covers.load(st);
            main.post(() -> {
                if (snap.station != null && snap.station.id.equals(st.id)) cover.setImageBitmap(loaded);
            });
        });
    }

    private void setScrolling(boolean on) {
        if (on == songScrolling) return;
        songScrolling = on;
        song.setEllipsize(on ? TextUtils.TruncateAt.MARQUEE : TextUtils.TruncateAt.END);
        song.setHorizontalFadingEdgeEnabled(on); // soft edges while scrolling
        song.setSelected(on); // a marquee only runs while selected
    }

    /**
     * The current song's title: from the cache, else the cleaned-up filename at
     * once and the tag title when it has been read. Long titles scroll while
     * playing and end in "…" when paused.
     */
    private void showSong(PlayerService.Snapshot s) {
        java.io.File f = s.playlist.tracks.get(s.track);
        String path = f.getPath();
        if (!path.equals(songFor)) {
            songFor = path;
            String known = songTitles.get(path);
            song.setText(known != null ? known : TrackTitle.fromFilename(f.getName()));
            if (known == null) {
                io.execute(() -> {
                    String t = TrackTitle.read(f);
                    main.post(() -> {
                        songTitles.put(path, t);
                        if (path.equals(songFor) && !t.contentEquals(song.getText())) song.setText(t);
                    });
                });
            }
        }
        setScrolling(s.playing);
    }

    private void showProgress(PlayerService.Snapshot s) {
        if (s.playlist != null) progress.set(s.durationMs > 0 ? (float) s.positionMs / s.durationMs : 0, 1);
        long left = s.sleepEndsAt == 0 ? 0 : s.sleepEndsAt - System.currentTimeMillis();
        if (left > 0) {
            sleepText.setVisibility(View.VISIBLE);
            sleepText.setText(String.valueOf((left + 59_999) / 60_000));
        } else {
            sleepText.setVisibility(View.GONE);
        }
    }

    // --- volume (caps arrive with the admin settings in M3) ----------------

    private int volumeCap() {
        return player != null ? player.volumeCapIndex() : audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
    }

    private void askPinForAdmin() {
        Settings s = Settings.from(store.read(Store.SETTINGS_JSON));
        if (!s.hasPin()) {
            startActivity(new Intent(this, AdminActivity.class));
            return;
        }
        PinPad[] pad = new PinPad[1];
        pad[0] = new PinPad(this, "Parent PIN", pin -> {
            if (s.checkPin(pin)) {
                pad[0].dismiss();
                startActivity(new Intent(this, AdminActivity.class));
            } else {
                pad[0].setPrompt("Wrong PIN, try again");
            }
        });
        pad[0].show();
    }

    private void changeVolume(int delta) {
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int v = audio.getStreamVolume(AudioManager.STREAM_MUSIC) + delta;
        v = Math.max(0, Math.min(Math.min(max, volumeCap()), v));
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0);
        showVolume();
    }

    private void showVolume() {
        float max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        volumeBar.set(audio.getStreamVolume(AudioManager.STREAM_MUSIC) / max, volumeCap() / max);
    }
}
