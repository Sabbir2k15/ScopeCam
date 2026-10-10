package com.sabbir.scopecam;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Singleton that supplies NV21 frames for the requested preview size.
 *
 * Modes (from Config):
 *   video  - decode a local video file in a background thread, loop it.
 *            Uses MediaMetadataRetriever.getFrameAtTime for simplicity and
 *            robustness across devices (no MediaCodec color-format pain).
 *   image  - decode a single image once, return it forever (loop).
 *   stream - download the stream URL to a cache file once, then treat as video.
 *
 * Failure policy: if block_on_fail is true and no source frame is available,
 * a BLACK NV21 frame is returned so the real camera is never leaked.
 * Otherwise null is returned (caller should then leave the buffer untouched).
 */
public class FrameProvider {

    private static final String TAG = "ScopeCam";
    private static volatile FrameProvider instance;

    // Video decode state
    private final LinkedBlockingQueue<byte[]> videoQueue = new LinkedBlockingQueue<>(4);
    private volatile Thread videoThread;
    private volatile boolean videoRunning;
    private volatile String currentVideoPath;
    private volatile int videoW, videoH;

    // Latest good frame (reused when decoder hiccups)
    private volatile byte[] lastFrame;
    private volatile int lastW, lastH;

    // Image mode cache
    private volatile byte[] imageNv21;
    private volatile int imageW, imageH;
    private volatile String cachedImagePath;

    // Black frame cache (block_on_fail)
    private volatile byte[] blackFrame;
    private volatile int blackW, blackH;

    // Stream download state
    private volatile boolean downloadRunning;

    public static FrameProvider get() {
        if (instance == null) {
            synchronized (FrameProvider.class) {
                if (instance == null) {
                    instance = new FrameProvider();
                }
            }
        }
        return instance;
    }

    private FrameProvider() {
    }

    /**
     * Returns an NV21 frame of exactly width*height*3/2 bytes, or null.
     * Called on camera callback threads - must be fast and never throw.
     */
    public byte[] getFrame(int width, int height) {
        try {
            Config cfg = Config.load();
            if (!cfg.enabled || width <= 0 || height <= 0) {
                return null;
            }
            String mode = cfg.mode != null ? cfg.mode : "video";
            byte[] frame = null;

            if ("image".equals(mode)) {
                frame = getImageFrame(width, height, cfg.imagePath);
            } else if ("stream".equals(mode)) {
                String local = getStreamCachePath(cfg.streamUrl);
                if (local != null) {
                    frame = getVideoFrame(width, height, local);
                }
            } else {
                frame = getVideoFrame(width, height, cfg.videoPath);
            }

            if (frame != null) {
                return frame;
            }
            // Reuse last good frame if size matches.
            byte[] lf = lastFrame;
            if (lf != null && lastW == width && lastH == height) {
                return lf;
            }
            if (cfg.blockOnFail) {
                return getBlackFrame(width, height);
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Video mode
    // ------------------------------------------------------------------

    private byte[] getVideoFrame(int w, int h, String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        ensureVideoDecoder(path, w, h);
        byte[] frame = videoQueue.poll();
        if (frame != null) {
            lastFrame = frame;
            lastW = w;
            lastH = h;
            return frame;
        }
        return null;
    }

    private synchronized void ensureVideoDecoder(String path, int w, int h) {
        if (videoRunning && path.equals(currentVideoPath) && w == videoW && h == videoH) {
            return;
        }
        stopVideoDecoder();
        File f = new File(path);
        if (!f.exists() || f.length() == 0) {
            return;
        }
        videoRunning = true;
        videoW = w;
        videoH = h;
        currentVideoPath = path;
        videoQueue.clear();
        videoThread = new Thread(() -> decodeLoop(path, w, h), "ScopeCam-VideoDecode");
        videoThread.setDaemon(true);
        videoThread.start();
        Log.i(TAG, "Video decoder started: " + path + " @" + w + "x" + h);
    }

    private synchronized void stopVideoDecoder() {
        videoRunning = false;
        Thread t = videoThread;
        videoThread = null;
        currentVideoPath = null;
        if (t != null) {
            try {
                t.interrupt();
                t.join(500);
            } catch (Throwable ignored) {
            }
        }
    }

    private void decodeLoop(String path, int w, int h) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(path);
            String durStr = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION);
            long durationMs = 10000;
            try {
                durationMs = Long.parseLong(durStr);
            } catch (Throwable ignored) {
            }
            if (durationMs <= 0) {
                durationMs = 10000;
            }

            // ~15 fps, loop forever.
            final long frameIntervalUs = 66666L;
            long timeUs = 0;
            while (videoRunning && !Thread.currentThread().isInterrupted()) {
                Bitmap bmp = null;
                try {
                    bmp = retriever.getFrameAtTime(timeUs,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                } catch (Throwable ignored) {
                }
                if (bmp != null) {
                    byte[] nv21 = bitmapToNv21(bmp, w, h);
                    bmp.recycle();
                    // Bound the queue; drop oldest when full.
                    while (videoQueue.size() >= 4) {
                        videoQueue.poll();
                    }
                    videoQueue.offer(nv21);
                }
                timeUs += frameIntervalUs;
                if (timeUs >= durationMs * 1000L) {
                    timeUs = 0; // loop
                }
                try {
                    Thread.sleep(15);
                } catch (InterruptedException e) {
                    break;
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Video decodeLoop error: " + t.getMessage());
        } finally {
            try {
                retriever.release();
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------------
    // Image mode
    // ------------------------------------------------------------------

    private byte[] getImageFrame(int w, int h, String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        byte[] cached = imageNv21;
        if (cached != null && imageW == w && imageH == h && path.equals(cachedImagePath)) {
            lastFrame = cached;
            lastW = w;
            lastH = h;
            return cached;
        }
        try {
            Bitmap bmp = BitmapFactory.decodeFile(path);
            if (bmp == null) {
                Log.e(TAG, "Image decode failed: " + path);
                return null;
            }
            byte[] nv21 = bitmapToNv21(bmp, w, h);
            bmp.recycle();
            imageNv21 = nv21;
            imageW = w;
            imageH = h;
            cachedImagePath = path;
            lastFrame = nv21;
            lastW = w;
            lastH = h;
            Log.i(TAG, "Image decoded: " + path + " @" + w + "x" + h);
            return nv21;
        } catch (Throwable t) {
            Log.e(TAG, "getImageFrame error: " + t.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Stream mode (download once, then video mode)
    // ------------------------------------------------------------------

    private String getStreamCachePath(String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        try {
            File cacheDir = new File("/sdcard/DCIM/ScopeCam/cache");
            //noinspection ResultOfMethodCallIgnored
            cacheDir.mkdirs();
            File cached = new File(cacheDir, "stream_" + Math.abs(url.hashCode()) + ".mp4");
            if (cached.exists() && cached.length() > 4096) {
                return cached.getAbsolutePath();
            }
            if (!downloadRunning) {
                downloadRunning = true;
                final String u = url;
                final File out = cached;
                new Thread(() -> {
                    try {
                        downloadFile(u, out);
                        Log.i(TAG, "Stream cached: " + out.getAbsolutePath());
                    } catch (Throwable t) {
                        Log.e(TAG, "Stream download failed: " + t.getMessage());
                    } finally {
                        downloadRunning = false;
                    }
                }, "ScopeCam-Download").start();
            }
            return null; // not ready yet; caller falls back to black/last frame
        } catch (Throwable t) {
            return null;
        }
    }

    private void downloadFile(String urlStr, File out) throws Exception {
        HttpURLConnection conn = null;
        InputStream in = null;
        FileOutputStream fos = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.connect();
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new Exception("HTTP " + conn.getResponseCode());
            }
            in = conn.getInputStream();
            fos = new FileOutputStream(out);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                fos.write(buf, 0, n);
            }
            fos.flush();
        } finally {
            try {
                if (in != null) in.close();
            } catch (Throwable ignored) {
            }
            try {
                if (fos != null) fos.close();
            } catch (Throwable ignored) {
            }
            if (conn != null) conn.disconnect();
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private byte[] getBlackFrame(int w, int h) {
        byte[] bf = blackFrame;
        if (bf == null || blackW != w || blackH != h) {
            int ySize = w * h;
            bf = new byte[ySize * 3 / 2];
            // Y = 16 (black), U/V = 128 (neutral) -> true black in NV21.
            for (int i = 0; i < ySize; i++) bf[i] = 16;
            for (int i = ySize; i < bf.length; i++) bf[i] = (byte) 128;
            blackFrame = bf;
            blackW = w;
            blackH = h;
        }
        return bf;
    }

    /**
     * Converts a Bitmap to NV21, scaled to outW x outH.
     * outW and outH should be even (YUV 4:2:0 requirement); odd values are
     * rounded down to avoid buffer overruns.
     */
    public static byte[] bitmapToNv21(Bitmap src, int outW, int outH) {
        outW = outW & ~1;
        outH = outH & ~1;
        if (outW <= 0) outW = 2;
        if (outH <= 0) outH = 2;

        Bitmap scaled = Bitmap.createScaledBitmap(src, outW, outH, true);
        int[] argb = new int[outW * outH];
        scaled.getPixels(argb, 0, outW, 0, 0, outW, outH);
        if (scaled != src) {
            scaled.recycle();
        }

        int ySize = outW * outH;
        byte[] nv21 = new byte[ySize * 3 / 2];
        int yIndex = 0;
        int uvIndex = ySize;

        for (int j = 0; j < outH; j++) {
            for (int i = 0; i < outW; i++) {
                int pixel = argb[j * outW + i];
                int r = (pixel >> 16) & 0xff;
                int g = (pixel >> 8) & 0xff;
                int b = pixel & 0xff;

                int y = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                nv21[yIndex++] = (byte) (y < 0 ? 0 : Math.min(255, y));

                // NV21 stores interleaved VU for each 2x2 block.
                if ((j & 1) == 0 && (i & 1) == 0) {
                    int u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                    int v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                    nv21[uvIndex++] = (byte) (v < 0 ? 0 : Math.min(255, v));
                    nv21[uvIndex++] = (byte) (u < 0 ? 0 : Math.min(255, u));
                }
            }
        }
        return nv21;
    }
}
