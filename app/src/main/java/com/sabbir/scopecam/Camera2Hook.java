package com.sabbir.scopecam;

import android.graphics.ImageFormat;
import android.media.Image;
import android.media.ImageReader;
import android.media.ImageWriter;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Camera2 (android.hardware.camera2) interception - v1.
 *
 * Strategy: hook ImageReader.newInstance. When an app creates a YUV
 * ImageReader (the standard way Camera2 video pipelines - e.g. IMO's
 * VideoCapturer2 - receive frames), start a background pump that
 * continuously writes our fake YUV frames into the reader's Surface
 * via ImageWriter. The app's OnImageAvailableListener then fires with
 * our data instead of the real camera's.
 *
 * JPEG readers (format 256) are ignored so photo capture keeps working.
 */
public class Camera2Hook {

    private static final String TAG = "ScopeCam";
    private static volatile boolean installed = false;
    private static final Map<ImageReader, FramePump> pumps = new HashMap<>();

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        if (installed) {
            return;
        }
        synchronized (Camera2Hook.class) {
            if (installed) {
                return;
            }
            installed = true;
        }

        try {
            final ClassLoader cl = lpparam.classLoader;

            XposedHelpers.findAndHookMethod("android.media.ImageReader", cl,
                    "newInstance", int.class, int.class, int.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                ImageReader reader = (ImageReader) param.getResult();
                                if (reader == null) {
                                    return;
                                }
                                int width = (int) param.args[0];
                                int height = (int) param.args[1];
                                int format = (int) param.args[2];
                                // Only YUV readers carry video frames.
                                // JPEG (256) is photo capture - leave it alone.
                                if (format == ImageFormat.YUV_420_888) {
                                    startPump(reader, width, height);
                                }
                            } catch (Throwable t) {
                                Log.e(TAG, "ImageReader hook error: " + t.getMessage());
                            }
                        }
                    });

            Log.i(TAG, "Camera2 hooks installed");
        } catch (Throwable t) {
            Log.e(TAG, "Camera2Hook init failed: " + t);
            installed = false;
        }
    }

    private static synchronized void startPump(ImageReader reader, int width, int height) {
        try {
            if (pumps.containsKey(reader)) {
                return;
            }
            Config cfg = Config.load();
            if (!cfg.enabled) {
                return;
            }
            FramePump pump = new FramePump(reader, width, height);
            pumps.put(reader, pump);
            pump.start();
            Log.i(TAG, "Camera2: started frame pump for " + width + "x" + height);
        } catch (Throwable t) {
            Log.e(TAG, "startPump failed: " + t.getMessage());
        }
    }

    /**
     * Pumps fake YUV frames into the ImageReader's Surface ~30fps.
     * Each queued image triggers the app's OnImageAvailableListener.
     */
    private static class FramePump {
        private final ImageReader reader;
        private final int width;
        private final int height;
        private final ImageWriter writer;
        private final HandlerThread thread;
        private final Handler handler;
        private volatile boolean running;
        private long pumped;

        FramePump(ImageReader reader, int width, int height) {
            this.reader = reader;
            this.width = width;
            this.height = height;
            // 3 buffers is enough for a smooth 30fps pump.
            this.writer = ImageWriter.newInstance(reader.getSurface(), 3);
            this.thread = new HandlerThread("ScopeCam-Pump");
            this.thread.start();
            this.handler = new Handler(thread.getLooper());
        }

        void start() {
            running = true;
            handler.post(pumpRunnable);
        }

        @SuppressWarnings("unused")
        void stop() {
            running = false;
            handler.removeCallbacks(pumpRunnable);
            thread.quitSafely();
            try {
                writer.close();
            } catch (Throwable ignored) {
            }
            synchronized (Camera2Hook.class) {
                pumps.remove(reader);
            }
        }

        private final Runnable pumpRunnable = new Runnable() {
            @Override
            public void run() {
                if (!running) {
                    return;
                }
                long startMs = System.currentTimeMillis();
                try {
                    Config cfg = Config.load();
                    if (cfg.enabled) {
                        byte[] nv21 = FrameProvider.get().getFrame(width, height);
                        if (nv21 != null) {
                            Image image = null;
                            try {
                                image = writer.dequeueInputImage();
                            } catch (Throwable ignored) {
                            }
                            if (image != null) {
                                try {
                                    writeNv21ToImage(nv21, image);
                                    image.setTimestamp(System.nanoTime());
                                    writer.queueInputImage(image);
                                    image = null; // ownership transferred
                                    pumped++;
                                    if ((pumped % 300) == 1) {
                                        Log.i(TAG, "Camera2: pumped " + pumped
                                                + " frames @" + width + "x" + height);
                                    }
                                } finally {
                                    if (image != null) {
                                        try {
                                            image.close();
                                        } catch (Throwable ignored) {
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "pump error: " + t.getMessage());
                }
                if (running) {
                    long elapsed = System.currentTimeMillis() - startMs;
                    long delay = Math.max(5, 33 - elapsed);
                    handler.postDelayed(this, delay);
                }
            }
        };

        /**
         * Copies NV21 bytes into a YUV_420_888 Image, honoring row and
         * pixel strides of every plane.
         */
        private void writeNv21ToImage(byte[] nv21, Image image) {
            Image.Plane[] planes = image.getPlanes();
            if (planes == null || planes.length < 3) {
                return;
            }
            int w = image.getWidth();
            int h = image.getHeight();
            int ySize = w * h;
            if (nv21.length < ySize * 3 / 2) {
                return;
            }

            // --- Y plane ---
            Image.Plane yPlane = planes[0];
            ByteBuffer yBuf = yPlane.getBuffer();
            int yRowStride = yPlane.getRowStride();
            if (yRowStride == w) {
                yBuf.put(nv21, 0, ySize);
            } else {
                for (int row = 0; row < h; row++) {
                    yBuf.position(row * yRowStride);
                    yBuf.put(nv21, row * w, w);
                }
                yBuf.position(0);
            }

            // --- U / V planes (NV21 stores interleaved VU) ---
            Image.Plane uPlane = planes[1];
            Image.Plane vPlane = planes[2];
            ByteBuffer uBuf = uPlane.getBuffer();
            ByteBuffer vBuf = vPlane.getBuffer();
            int uRowStride = uPlane.getRowStride();
            int uPixelStride = uPlane.getPixelStride();
            int vRowStride = vPlane.getRowStride();
            int vPixelStride = vPlane.getPixelStride();

            int nv21UvStart = ySize;
            int uvW = w / 2;
            int uvH = h / 2;
            for (int row = 0; row < uvH; row++) {
                for (int col = 0; col < uvW; col++) {
                    int src = nv21UvStart + row * w + col * 2;
                    byte v = nv21[src];       // NV21: V comes first
                    byte u = nv21[src + 1];   // then U
                    uBuf.put(row * uRowStride + col * uPixelStride, u);
                    vBuf.put(row * vRowStride + col * vPixelStride, v);
                }
            }
        }
    }
}
