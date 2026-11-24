package dev.audiobox;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.MediaMetadataRetriever;
import android.util.LruCache;

import java.io.File;

/**
 * Grid icons (APP-SPEC 5.1): icon.png/jpg, else the first track's embedded art,
 * else a coloured tile with the label's first letter. Always decoded at display
 * size: a 3000 px cover at full size would cost ~36 MB on a 512 MB box.
 */
final class Icons {
    private static final int[] TILE_COLOURS = {
            0xFFE57373, 0xFFFFB74D, 0xFFFFD54F, 0xFF81C784,
            0xFF4FC3F7, 0xFF7986CB, 0xFFBA68C8, 0xFFF06292,
    };

    private final int size;
    /** ~8 MB at most: 170 px icons are ~115 kB each. */
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(8 << 20) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    /** Letter-tile font; HomeActivity sets it to the app font. */
    Typeface face = Typeface.DEFAULT_BOLD;

    Icons(int sizePx) {
        this.size = sizePx;
    }

    Bitmap cached(Playlist p) {
        return cache.get(key(p));
    }

    /** Slow (disk + decode); call off the main thread. Never returns null. */
    Bitmap load(Playlist p) {
        String key = key(p);
        Bitmap b = cache.get(key);
        if (b != null) return b;
        b = p.icon != null ? decodeFile(p.icon) : null;
        if (b == null) b = embeddedArt(p.tracks.get(0));
        if (b == null) b = letterTile(p);
        cache.put(key, b);
        return b;
    }

    void clear() {
        cache.evictAll();
    }

    private String key(Playlist p) {
        File f = p.icon != null ? p.icon : p.tracks.get(0);
        return p.id + "|" + f.getPath() + "|" + f.lastModified() + "|" + p.label;
    }

    private Bitmap decodeFile(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0) return null;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sampleSize(o.outWidth, o.outHeight);
        return square(BitmapFactory.decodeFile(f.getPath(), o));
    }

    private Bitmap embeddedArt(File track) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(track.getPath());
            byte[] art = r.getEmbeddedPicture();
            if (art == null) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(art, 0, art.length, o);
            if (o.outWidth <= 0) return null;
            o.inJustDecodeBounds = false;
            o.inSampleSize = sampleSize(o.outWidth, o.outHeight);
            return square(BitmapFactory.decodeByteArray(art, 0, art.length, o));
        } catch (RuntimeException e) {
            return null; // unreadable file: fall back to the letter tile
        } finally {
            try {
                r.release(); // declares IOException in newer SDK stubs
            } catch (Exception ignored) {
                // nothing left to clean up
            }
        }
    }

    private int sampleSize(int w, int h) {
        int s = 1;
        while (Math.min(w, h) / (s * 2) >= size) s *= 2;
        return s;
    }

    /** Centre-crops to a square and scales to the icon size. */
    private Bitmap square(Bitmap src) {
        if (src == null) return null;
        int side = Math.min(src.getWidth(), src.getHeight());
        int x = (src.getWidth() - side) / 2, y = (src.getHeight() - side) / 2;
        Bitmap cropped = Bitmap.createBitmap(src, x, y, side, side);
        Bitmap out = Bitmap.createScaledBitmap(cropped, size, size, true);
        if (cropped != src && cropped != out) cropped.recycle();
        if (src != out) src.recycle();
        return out;
    }

    private Bitmap letterTile(Playlist p) {
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(TILE_COLOURS[Math.abs(p.id.hashCode() % TILE_COLOURS.length)]);
        float r = size * 0.18f;
        c.drawRoundRect(new RectF(0, 0, size, size), r, r, bg);

        String letter = p.label.isEmpty() ? "?" : p.label.substring(0, p.label.offsetByCodePoints(0, 1));
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setColor(Color.WHITE);
        t.setTypeface(face);
        t.setTextSize(size * 0.55f);
        t.setTextAlign(Paint.Align.CENTER);
        float y = size / 2f - (t.descent() + t.ascent()) / 2f;
        c.drawText(letter.toUpperCase(), size / 2f, y, t);
        return b;
    }
}
