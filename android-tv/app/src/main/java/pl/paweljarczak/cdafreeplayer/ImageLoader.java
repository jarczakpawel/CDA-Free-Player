package pl.paweljarczak.cdafreeplayer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ImageLoader {
    private static final long MAX_DISK = 96L * 1024 * 1024;
    private final LruCache<String, Bitmap> mem;
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final File dir;
    private volatile boolean closed;

    public ImageLoader(Context c) {
        int kb = (int) (Runtime.getRuntime().maxMemory() / 1024);
        int cacheKb = Math.min(8192, Math.max(4096, kb / 24));
        mem = new LruCache<String, Bitmap>(cacheKb) {
            @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount() / 1024; }
        };
        dir = new File(c.getCacheDir(), "thumbs");
        dir.mkdirs();
        pool.execute(this::pruneDisk);
    }

    public void load(String url, ImageView v) {
        if (closed) return;
        v.setTag(url);
        if (url == null || url.isEmpty()) {
            v.setImageDrawable(null);
            return;
        }
        Bitmap hit = mem.get(url);
        if (hit != null) {
            v.setImageBitmap(hit);
            return;
        }
        v.setImageDrawable(null);
        pool.execute(() -> {
            if (closed) return;
            try {
                File f = new File(dir, sha1(url) + ".jpg");
                byte[] data;
                if (f.exists()) {
                    data = read(new FileInputStream(f));
                    f.setLastModified(System.currentTimeMillis());
                } else {
                    URLConnection c = new URL(url).openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(10000);
                    data = read(c.getInputStream());
                    File temporary = File.createTempFile("thumb-", ".tmp", dir);
                    try {
                        try (FileOutputStream o = new FileOutputStream(temporary)) { o.write(data); }
                        if (!temporary.renameTo(f)) temporary.delete();
                    } finally { if (temporary.exists()) temporary.delete(); }
                }
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, o);
                int sx = o.outWidth > 0 ? o.outWidth / 360 : 1;
                int sy = o.outHeight > 0 ? o.outHeight / 210 : 1;
                o.inSampleSize = Math.max(1, Math.max(sx, sy));
                o.inJustDecodeBounds = false;
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, o);
                if (b != null && !closed) mem.put(url, b);
                else if (b == null) f.delete();
                Bitmap out = b;
                v.post(() -> {
                    if (!closed && url.equals(v.getTag()) && out != null) v.setImageBitmap(out);
                });
            } catch (Exception ignored) {}
        });
    }

    public void trimForPlayback() { mem.evictAll(); }

    public void clearCache() {
        mem.evictAll();
        pool.execute(() -> {
            try {
                File[] files = dir.listFiles();
                if (files != null) for (File f : files) f.delete();
                dir.mkdirs();
            } catch (Exception ignored) {}
        });
    }

    private void pruneDisk() {
        try {
            File[] files = dir.listFiles();
            if (files == null) return;
            long total = 0;
            for (File f : files) total += f.length();
            if (total <= MAX_DISK) return;
            Arrays.sort(files, Comparator.comparingLong(File::lastModified));
            for (File f : files) {
                if (total <= MAX_DISK * 3 / 4) break;
                long len = f.length();
                if (f.delete()) total -= len;
            }
        } catch (Exception ignored) {}
    }

    private static byte[] read(InputStream in) throws Exception {
        try (InputStream x = in; ByteArrayOutputStream o = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = x.read(buf)) > 0) {
                if (Thread.currentThread().isInterrupted() || o.size() + n > 8 * 1024 * 1024) throw new java.io.IOException("Przerwano pobieranie miniatury");
                o.write(buf, 0, n);
            }
            return o.toByteArray();
        }
    }

    private static String sha1(String s) throws Exception {
        MessageDigest d = MessageDigest.getInstance("SHA-1");
        byte[] b = d.digest(s.getBytes("UTF-8"));
        StringBuilder x = new StringBuilder();
        for (byte z : b) x.append(String.format("%02x", z));
        return x.toString();
    }

    public void shutdown() { closed = true; pool.shutdownNow(); mem.evictAll(); }
}
