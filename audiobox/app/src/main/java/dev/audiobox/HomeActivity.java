package dev.audiobox;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * M0: the playlist grid (APP-SPEC section 7), read-only. Tapping a tile only shows
 * a toast; playback arrives in M1, the player screen and font in M2.
 */
public class HomeActivity extends Activity {
    static final String TAG = "audiobox";
    /** Sent by the Mac tool after it changes files (APP-SPEC section 6). */
    public static final String ACTION_RELOAD = "dev.audiobox.RELOAD";

    private static final int COLUMNS = 4;
    private static final int ICON_PX = 150; // mdpi: 1 dp = 1 px on the box

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Icons icons = new Icons(ICON_PX);
    private final List<Playlist> playlists = new ArrayList<>();

    private Store store;
    private GridView grid;
    private TextView empty;
    private TileAdapter adapter;

    private final BroadcastReceiver reload = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.i(TAG, "RELOAD received");
            icons.clear();
            rescan();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new Store(new File(Environment.getExternalStorageDirectory(), "audiobox"));

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF1E2A3A);

        grid = new GridView(this);
        grid.setNumColumns(COLUMNS);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setVerticalSpacing(16);
        grid.setPadding(24, 24, 24, 24);
        grid.setClipToPadding(false);
        grid.setSelector(android.R.color.transparent);
        adapter = new TileAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, position, id) -> {
            Playlist p = playlists.get(position);
            Toast.makeText(this, p.label + ": " + p.tracks.size() + " tracks", Toast.LENGTH_SHORT).show();
        });
        root.addView(grid);

        empty = new TextView(this);
        empty.setText(R.string.empty_library);
        empty.setTextColor(Color.WHITE);
        empty.setTextSize(24);
        empty.setGravity(Gravity.CENTER);
        empty.setVisibility(View.GONE);
        root.addView(empty, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
        registerReceiver(reload, new IntentFilter(ACTION_RELOAD));
    }

    @Override
    protected void onResume() {
        super.onResume();
        rescan();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(reload);
        io.shutdownNow();
        super.onDestroy();
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
                adapter.notifyDataSetChanged();
                empty.setVisibility(found.isEmpty() ? View.VISIBLE : View.GONE);
            });
        });
    }

    private final class TileAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return playlists.size();
        }

        @Override
        public Playlist getItem(int position) {
            return playlists.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Tile tile = convertView instanceof Tile ? (Tile) convertView : new Tile(HomeActivity.this);
            tile.bind(getItem(position));
            return tile;
        }
    }

    /** Icon with its short label underneath. */
    private final class Tile extends LinearLayout {
        final ImageView image;
        final TextView label;
        String boundId;

        Tile(Context c) {
            super(c);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_HORIZONTAL);
            setPadding(0, 8, 0, 8);
            image = new ImageView(c);
            addView(image, new LayoutParams(ICON_PX, ICON_PX));
            label = new TextView(c);
            label.setTextColor(Color.WHITE);
            label.setTextSize(20);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setGravity(Gravity.CENTER);
            LayoutParams lp = new LayoutParams(ICON_PX + 30, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = 6;
            addView(label, lp);
        }

        void bind(Playlist p) {
            boundId = p.id;
            label.setText(p.label);
            Bitmap b = icons.cached(p);
            image.setImageBitmap(b);
            if (b == null) {
                io.execute(() -> {
                    Bitmap loaded = icons.load(p);
                    main.post(() -> {
                        if (p.id.equals(boundId)) image.setImageBitmap(loaded);
                    });
                });
            }
        }
    }
}
