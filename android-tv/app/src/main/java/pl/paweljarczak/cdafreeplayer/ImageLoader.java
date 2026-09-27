package pl.paweljarczak.cdafreeplayer;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.os.Process;
import android.util.LruCache;
import android.util.Size;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class ImageLoader {
    private static final long MAX_DISK = 384L * 1024 * 1024;
    private static final long MIN_FREE_DISK = 512L * 1024 * 1024;
    private static final long PRUNE_FLOOR = 48L * 1024 * 1024;
    private static final int MAX_DOWNLOAD = 8 * 1024 * 1024;
    private static final int TARGET_W = 360;
    private static final int TARGET_H = 210;
    private static final long TOUCH_INTERVAL_MS = 30L * 60 * 1000;
    private static final long MAX_AGE_MS = 24L * 60 * 60 * 1000;

    private final LruCache<String, Bitmap> mem;
    private final ThreadPoolExecutor pool;
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<WeakReference<ImageView>>> waiting = new ConcurrentHashMap<>();
    private final File dir;
    private final AtomicLong diskBytes = new AtomicLong(-1);
    private final AtomicBoolean pruneQueued = new AtomicBoolean();
    private volatile boolean closed;
    private volatile boolean paused;

    public ImageLoader(Context c) {
        int kb = (int) (Runtime.getRuntime().maxMemory() / 1024);
        int cacheKb = Math.min(32 * 1024, Math.max(8 * 1024, kb / 10));
        mem = new LruCache<String, Bitmap>(cacheKb) {
            @Override protected int sizeOf(String k, Bitmap b) { return Math.max(1, b.getByteCount() / 1024); }
        };
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        if (am != null) am.getMemoryInfo(memory);
        int workers = memory.totalMem > 0 && memory.totalMem <= 3L * 1024 * 1024 * 1024 ? 1 : 2;
        pool = new ThreadPoolExecutor(workers, workers, 15, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
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
        pruneQueued.set(true);
        pool.execute(() -> {
            try { pruneDisk(); } finally { pruneQueued.set(false); }
        });
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
        pruneQueued.set(false);
        waiting.clear();
    }

    public void resume() { paused = false; }

    private void loadOne(String url, CopyOnWriteArrayList<WeakReference<ImageView>> mine) {
        try {
            if (closed || paused || !hasLiveTarget(url)) return;
            File f = new File(dir, sha1(url) + ".jpg");
            if (!f.exists() || f.length() == 0) download(url, f);
            else {
                long now = System.currentTimeMillis();
                if (now - f.lastModified() > TOUCH_INTERVAL_MS) f.setLastModified(now);
            }
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
        if (!temporary.renameTo(destination)) {
            temporary.delete();
            return;
        }
        long known = diskBytes.get();
        if (known >= 0) diskBytes.addAndGet(destination.length());
        maybeSchedulePrune();
    }

    private static Bitmap decode(File f) {
        try {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(f), (decoder, info, source) -> {
                Size size = info.getSize();
                int w = size.getWidth(), h = size.getHeight();
                if (w > 0 && h > 0) {
                    float scale = Math.min((float) TARGET_W / w, (float) TARGET_H / h);
                    if (scale < 1f) {
                        decoder.setTargetSize(Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)));
                    }
                }
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                decoder.setMemorySizePolicy(ImageDecoder.MEMORY_POLICY_LOW_RAM);
            });
        } catch (Exception e) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), options);
        }
    }

    public void trimForPlayback() { mem.evictAll(); }

    public void clearCache() {
        mem.evictAll();
        waiting.clear();
        pool.getQueue().clear();
        pruneQueued.set(false);
        pool.execute(() -> {
            try {
                File[] files = dir.listFiles();
                if (files != null) for (File f : files) f.delete();
                dir.mkdirs();
                diskBytes.set(0);
            } catch (Exception ignored) {}
        });
    }

    private void maybeSchedulePrune() {
        if (closed) return;
        long known = diskBytes.get();
        long limit = diskLimit(Math.max(0, known));
        long usable = dir.getUsableSpace();
        boolean lowSpace = usable > 0 && usable < MIN_FREE_DISK;
        if ((known >= 0 && known > limit) || lowSpace) {
            if (pruneQueued.compareAndSet(false, true)) {
                pool.execute(() -> {
                    try { pruneDisk(); } finally { pruneQueued.set(false); }
                });
            }
        }
    }

    private long diskLimit(long currentBytes) {
        long usable = dir.getUsableSpace();
        if (usable <= 0) return MAX_DISK;
        long safeBudget = usable + currentBytes - MIN_FREE_DISK;
        if (safeBudget <= 0) return 0;
        return Math.min(MAX_DISK, safeBudget);
    }

    private void pruneDisk() {
        try {
            File[] files = dir.listFiles();
            if (files == null) { diskBytes.set(0); return; }
            long now = System.currentTimeMillis();
            long cutoff = now - MAX_AGE_MS;
            long total = 0;
            for (File f : files) {
                if (!f.isFile()) continue;
                if (f.getName().startsWith("thumb-") && f.getName().endsWith(".tmp")) {
                    if (f.lastModified() <= 0 || f.lastModified() < now - 60L * 60 * 1000) f.delete();
                    continue;
                }
                if (f.lastModified() > 0 && f.lastModified() < cutoff && f.delete()) continue;
                total += f.length();
            }
            files = dir.listFiles();
            if (files == null) { diskBytes.set(0); return; }
            long limit = diskLimit(total);
            if (total > limit) {
                Arrays.sort(files, Comparator.comparingLong(File::lastModified));
                long target = limit <= PRUNE_FLOOR ? limit : Math.max(PRUNE_FLOOR, limit * 7 / 8);
                long protectAfter = now - 10L * 60 * 1000;
                for (int pass = 0; pass < 2 && total > target; pass++) {
                    for (File f : files) {
                        if (total <= target) break;
                        if (!f.isFile()) continue;
                        if (f.getName().startsWith("thumb-") && f.getName().endsWith(".tmp")) continue;
                        if (pass == 0 && f.lastModified() >= protectAfter) continue;
                        long len = f.length();
                        if (f.delete()) total -= len;
                    }
                }
            }
            long actual = 0;
            File[] current = dir.listFiles();
            if (current != null) {
                for (File f : current) {
                    if (!f.isFile()) continue;
                    if (f.getName().startsWith("thumb-") && f.getName().endsWith(".tmp")) continue;
                    actual += f.length();
                }
            }
            diskBytes.set(Math.max(0, actual));
        } catch (Exception ignored) {}
    }

    private static String sha1(String s) throws Exception {
        MessageDigest d = MessageDigest.getInstance("SHA-1");
        byte[] b = d.digest(s.getBytes("UTF-8"));
        char[] out = new char[b.length * 2];
        final char[] hex = "0123456789abcdef".toCharArray();
        for (int i = 0; i < b.length; i++) {
            int v = b[i] & 0xff;
            out[i * 2] = hex[v >>> 4];
            out[i * 2 + 1] = hex[v & 0x0f];
        }
        return new String(out);
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
