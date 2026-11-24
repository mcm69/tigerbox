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
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The kid UI (APP-SPEC section 7): a paged 4 x 2 grid of playlists, and the player
 * screen. Built in code, sized for the box's 800 x 480 mdpi panel (1 dp = 1 px).
 */
public class HomeActivity extends Activity implements PlayerService.Listener {
    static final String TAG = "audiobox";
    /** Sent by the Mac tool after it changes files (APP-SPEC section 6). */
    public static final String ACTION_RELOAD = "dev.audiobox.RELOAD";

    private static final int COLUMNS = 4, ROWS = 2, PER_PAGE = COLUMNS * ROWS;
    private static final int ICON_PX = 150;
    private static final int COVER_PX = 340;
    private static final int VOLUME_STEP = 5; // STREAM_MUSIC runs 0..100 on this box

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Icons icons = new Icons(ICON_PX);
    private final Icons covers = new Icons(COVER_PX);
    private final List<Playlist> playlists = new ArrayList<>();

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
    // player
    private View playerScreen;
    private ImageView cover;
    private TextView title, trackText;
    private Ui.DotsView trackDots;
    private Ui.BarView progress, volumeBar;
    private ImageButton playPause;
    private TextView sleepText;
    private View bedtimeOverlay;

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
        bedtimeOverlay = buildBedtimeOverlay();
        root.addView(bedtimeOverlay);
        setContentView(root);

        registerReceiver(reload, new IntentFilter(ACTION_RELOAD));
        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        registerReceiver(volumeReceiver, new IntentFilter("android.media.VOLUME_CHANGED_ACTION"));
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
    public void onBlocked(Playlist p) {
        bedtimeOverlay.setVisibility(View.VISIBLE);
        main.removeCallbacks(hideBedtime);
        main.postDelayed(hideBedtime, 3_000);
    }

    private final Runnable hideBedtime = () -> bedtimeOverlay.setVisibility(View.GONE);

    private View buildBedtimeOverlay() {
        LinearLayout o = new LinearLayout(this);
        o.setOrientation(LinearLayout.VERTICAL);
        o.setGravity(Gravity.CENTER);
        o.setBackgroundColor(0xF0101828);
        ImageView moon = new ImageView(this);
        moon.setImageResource(R.drawable.ic_moon);
        moon.setColorFilter(0xFFFFE082);
        o.addView(moon, new LinearLayout.LayoutParams(200, 200));
        TextView t = Ui.text(this, 36, Ui.TEXT);
        t.setText(R.string.bedtime);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, 20, 0, 0);
        o.addView(t);
        o.setVisibility(View.GONE);
        o.setOnClickListener(v -> hideBedtime.run());
        return o;
    }

    @Override
    public void onPlayerChanged(PlayerService.Snapshot s) {
        boolean newPlaylist = s.playlist != null
                && (snap.playlist == null || !snap.playlist.id.equals(s.playlist.id));
        snap = s;
        showPlayer(s);
        if (newPlaylist) layoutPage(); // move the "playing" highlight
        else refreshBadges();
    }

    // --- grid --------------------------------------------------------------

    private View buildGrid() {
        FrameLayout screen = new FrameLayout(this);

        battery = new Ui.BatteryView(this);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(48, 24, Gravity.TOP | Gravity.END);
        blp.setMargins(0, 12, 16, 0);
        screen.addView(battery, blp);

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

    private int pageCount() {
        return Math.max(1, (playlists.size() + PER_PAGE - 1) / PER_PAGE);
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
        for (int i = first; i < Math.min(playlists.size(), first + PER_PAGE); i++) {
            int slot = i - first;
            Tile t = new Tile(this, playlists.get(i));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(cellW, cellH);
            lp.leftMargin = (slot % COLUMNS) * cellW;
            lp.topMargin = (slot / COLUMNS) * cellH;
            tiles.addView(t, lp);
        }
        pageDots.set(pageCount(), page);
        empty.setVisibility(playlists.isEmpty() ? View.VISIBLE : View.GONE);
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
            Log.i(TAG, "library: " + found);
            main.post(() -> {
                playlists.clear();
                playlists.addAll(found);
                layoutPage();
            });
        });
    }

    /** Icon with its short label; a ring and a badge when it's the loaded playlist. */
    private final class Tile extends LinearLayout {
        final Playlist playlist;
        final FrameLayout frame;
        final ImageView image;
        final View badge;

        Tile(Context c, Playlist p) {
            super(c);
            playlist = p;
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_HORIZONTAL);
            setPadding(0, 4, 0, 0);

            frame = new FrameLayout(c);
            frame.setPadding(6, 6, 6, 6);
            image = new ImageView(c);
            frame.addView(image, new FrameLayout.LayoutParams(ICON_PX, ICON_PX));
            badge = new ImageView(c);
            ((ImageView) badge).setImageResource(R.drawable.ic_play);
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(Ui.ACCENT);
            badge.setBackground(dot);
            badge.setPadding(6, 6, 6, 6);
            frame.addView(badge, new FrameLayout.LayoutParams(44, 44, Gravity.BOTTOM | Gravity.END));
            addView(frame, new LayoutParams(ICON_PX + 12, ICON_PX + 12));

            TextView label = Ui.text(c, 21, Ui.TEXT);
            label.setText(p.label);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setGravity(Gravity.CENTER);
            LayoutParams lp = new LayoutParams(190, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = 2;
            addView(label, lp);

            setOnClickListener(v -> {
                PlayerService.play(HomeActivity.this, playlist.id);
                if (player == null || !player.blockedNow(playlist)) showPlayerScreen();
            });
            refresh();
            Bitmap b = icons.cached(p);
            if (b != null) image.setImageBitmap(b);
            else io.execute(() -> {
                Bitmap loaded = icons.load(p);
                main.post(() -> image.setImageBitmap(loaded));
            });
        }

        void refresh() {
            boolean loaded = snap.playlist != null && snap.playlist.id.equals(playlist.id);
            if (loaded) {
                GradientDrawable ring = new GradientDrawable();
                ring.setCornerRadius(18);
                ring.setColor(Ui.ACCENT);
                frame.setBackground(ring);
            } else {
                frame.setBackground(null);
            }
            badge.setVisibility(loaded && snap.playing ? View.VISIBLE : View.GONE);
            setAlpha(player != null && player.blockedNow(playlist) ? 0.35f : 1f);
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

        title = Ui.text(this, 32, Ui.TEXT);
        title.setGravity(Gravity.CENTER);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 90);
        tlp.topMargin = 20;
        title.setGravity(Gravity.CENTER);
        right.addView(title, tlp);

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
        if (s.playlist != null) showPlayerScreen();
        else showGrid();
    }

    private void showGrid() {
        playerScreen.setVisibility(View.GONE);
        gridScreen.setVisibility(View.VISIBLE);
        // Show the page holding the loaded playlist.
        if (snap.playlist != null) {
            for (int i = 0; i < playlists.size(); i++) {
                if (playlists.get(i).id.equals(snap.playlist.id)) page = i / PER_PAGE;
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
        if (s.playlist == null) {
            if (playerScreen.getVisibility() == View.VISIBLE) showGrid();
            return;
        }
        title.setText(s.playlist.label);
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

    private void showProgress(PlayerService.Snapshot s) {
        progress.set(s.durationMs > 0 ? (float) s.positionMs / s.durationMs : 0, 1);
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
