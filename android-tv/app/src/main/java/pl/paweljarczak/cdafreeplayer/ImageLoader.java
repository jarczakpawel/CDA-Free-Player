package pl.paweljarczak.cdafreeplayer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Process;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.net.URLConnection;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class ImageLoader {
    private static final long MAX_DISK = 96L * 1024 * 1024;
    private static final int MAX_DOWNLOAD = 8 * 1024 * 1024;
    private static final int TARGET_W = 360;
    private static final int TARGET_H = 210;

    private final LruCache<String, Bitmap> mem;
    private final ThreadPoolExecutor pool;
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<WeakReference<ImageView>>> waiting = new ConcurrentHashMap<>();
    private final File dir;
    private volatile boolean closed;
    private volatile boolean paused;

    public ImageLoader(Context c) {
        int kb = (int) (Runtime.getRuntime().maxMemory() / 1024);
        int cacheKb = Math.min(32 * 1024, Math.max(8 * 1024, kb / 10));
        mem = new LruCache<String, Bitmap>(cacheKb) {
            @Override protected int sizeOf(String k, Bitmap b) { return Math.max(1, b.getByteCount() / 1024); }
        };
        pool = new ThreadPoolExecutor(2, 2, 15, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
            Thread t = new Thread(() -> {
                try { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); } catch (Throwable ignored) {}
                r.run();
            }, "thumb-loader");
            t.setDaemon(true);
            return t;
        });
        pool.allowCoreThreadTimeOut(true);
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
        if (paused) return;

        CopyOnWriteArrayList<WeakReference<ImageView>> mine = new CopyOnWriteArrayList<>();
        mine.add(new WeakReference<>(v));
        CopyOnWriteArrayList<WeakReference<ImageView>> existing = waiting.putIfAbsent(url, mine);
        if (existing != null) {
            existing.add(new WeakReference<>(v));
            return;
        }
        pool.execute(() -> loadOne(url, mine));
    }

    public void cancel(ImageView v) {
        if (v == null) return;
        v.setTag(null);
        v.setImageDrawable(null);
    }

    public void pause() {
        paused = true;
        pool.getQueue().clear();
        waiting.clear();
    }

    public void resume() { paused = false; }

    private void loadOne(String url, CopyOnWriteArrayList<WeakReference<ImageView>> mine) {
        try {
            if (closed || paused || !hasLiveTarget(url)) return;
            File f = new File(dir, sha1(url) + ".jpg");
            if (!f.exists() || f.length() == 0) download(url, f);
            else f.setLastModified(System.currentTimeMillis());
            if (closed || paused || !hasLiveTarget(url)) return;
            Bitmap b = decode(f);
            if (b == null) {
                f.delete();
                return;
            }
            if (!closed) mem.put(url, b);
            deliver(url, b);
        } catch (Exception ignored) {
        } finally {
            waiting.remove(url, mine);
        }
    }

    private boolean hasLiveTarget(String url) {
        CopyOnWriteArrayList<WeakReference<ImageView>> list = waiting.get(url);
        if (list == null) return false;
        for (WeakReference<ImageView> ref : list) {
            ImageView v = ref.get();
            if (v != null && url.equals(v.getTag())) return true;
        }
        return false;
    }

    private void deliver(String url, Bitmap b) {
        CopyOnWriteArrayList<WeakReference<ImageView>> list = waiting.get(url);
        if (list == null) return;
        for (WeakReference<ImageView> ref : list) {
            ImageView v = ref.get();
            if (v == null) continue;
            v.post(() -> {
                if (!closed && !paused && url.equals(v.getTag())) v.setImageBitmap(b);
            });
        }
    }

    private void download(String url, File destination) throws Exception {
        if (closed || paused) return;
        URLConnection c = new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        File temporary = File.createTempFile("thumb-", ".tmp", dir);
        int total = 0;
        try (InputStream in = new BufferedInputStream(c.getInputStream(), 16 * 1024);
             BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(temporary), 16 * 1024)) {
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (closed || paused || Thread.currentThread().isInterrupted() || !hasLiveTarget(url)) throw new java.io.InterruptedIOException();
                total += n;
                if (total > MAX_DOWNLOAD) throw new java.io.IOException("Miniatura jest za duża");
                out.write(buf, 0, n);
            }
        } catch (Exception e) {
            temporary.delete();
            throw e;
        }
        if (!temporary.renameTo(destination)) temporary.delete();
    }

    private static Bitmap decode(File f) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= TARGET_W && bounds.outHeight / (sample * 2) >= TARGET_H) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath(), options);
        if (b == null) return null;
        if (b.getWidth() <= TARGET_W && b.getHeight() <= TARGET_H) return b;
        float scale = Math.min((float) TARGET_W / b.getWidth(), (float) TARGET_H / b.getHeight());
        int w = Math.max(1, Math.round(b.getWidth() * scale));
        int h = Math.max(1, Math.round(b.getHeight() * scale));
        if (w == b.getWidth() && h == b.getHeight()) return b;
        Bitmap scaled = Bitmap.createScaledBitmap(b, w, h, true);
        if (scaled != b) b.recycle();
        return scaled;
    }

    public void trimForPlayback() { mem.evictAll(); }

    public void clearCache() {
        mem.evictAll();
        waiting.clear();
        pool.getQueue().clear();
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

    private static String sha1(String s) throws Exception {
        MessageDigest d = MessageDigest.getInstance("SHA-1");
        byte[] b = d.digest(s.getBytes("UTF-8"));
        StringBuilder x = new StringBuilder();
        for (byte z : b) x.append(String.format("%02x", z));
        return x.toString();
    }

    public void shutdown() {
        closed = true;
        paused = true;
        waiting.clear();
        pool.getQueue().clear();
        pool.shutdownNow();
        mem.evictAll();
    }
}
