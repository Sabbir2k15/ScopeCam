package com.sabbir.scopecam;

import android.hardware.Camera;
import android.util.Log;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Camera1 (android.hardware.Camera) interception.
 *
 * Strategy: wrap the app's PreviewCallback. Every preview frame byte[]
 * is overwritten with our NV21 frame (sized to the camera's preview size)
 * before the app's original callback runs. This covers video calls, QR
 * scanners and any other Camera1 consumer - they all receive preview
 * buffers through onPreviewFrame.
 */
public class Camera1Hook {

    private static final String TAG = "ScopeCam";
    private static volatile boolean installed = false;

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        if (installed) {
            return;
        }
        synchronized (Camera1Hook.class) {
            if (installed) {
                return;
            }
            installed = true;
        }

        try {
            final ClassLoader cl = lpparam.classLoader;

            // Hook both setPreviewCallback overloads at the class level once.
            // The wrapper delegates to the app's original callback.
            XC_MethodHook wrapCallback = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object cb = param.args[0];
                        if (cb instanceof Camera.PreviewCallback
                                && !(cb instanceof ScopeCamCallback)) {
                            param.args[0] = new ScopeCamCallback(
                                    (Camera.PreviewCallback) cb,
                                    (Camera) param.thisObject);
                        }
                    } catch (Throwable t) {
                        Log.e(TAG, "wrap callback failed: " + t.getMessage());
                    }
                }
            };

            XposedHelpers.findAndHookMethod("android.hardware.Camera", cl,
                    "setPreviewCallback", Camera.PreviewCallback.class, wrapCallback);
            XposedHelpers.findAndHookMethod("android.hardware.Camera", cl,
                    "setPreviewCallbackWithBuffer", Camera.PreviewCallback.class, wrapCallback);

            // Log-only hooks for the texture/display path (v1 focuses on byte[]).
            XC_MethodHook logOnly = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Log.i(TAG, "Camera1." + param.method.getName() + " called");
                }
            };
            try {
                XposedHelpers.findAndHookMethod("android.hardware.Camera", cl,
                        "setPreviewTexture", android.graphics.SurfaceTexture.class, logOnly);
            } catch (Throwable ignored) {
            }
            try {
                XposedHelpers.findAndHookMethod("android.hardware.Camera", cl,
                        "setPreviewDisplay", android.view.SurfaceHolder.class, logOnly);
            } catch (Throwable ignored) {
            }

            Log.i(TAG, "Camera1 hooks installed");
        } catch (Throwable t) {
            Log.e(TAG, "Camera1Hook init failed: " + t);
            installed = false; // allow retry in another process
        }
    }

    /**
     * Delegating preview callback: overwrites the frame with our fake
     * NV21 data, then calls the app's original callback so its pipeline
     * keeps working normally.
     */
    private static class ScopeCamCallback implements Camera.PreviewCallback {
        private final Camera.PreviewCallback original;
        private final Camera camera;
        private long frameCount;

        ScopeCamCallback(Camera.PreviewCallback original, Camera camera) {
            this.original = original;
            this.camera = camera;
        }

        @Override
        public void onPreviewFrame(byte[] data, Camera cam) {
            try {
                Config cfg = Config.load();
                if (cfg.enabled && data != null) {
                    Camera camRef = (cam != null) ? cam : camera;
                    int w = 0, h = 0;
                    try {
                        Camera.Parameters params = camRef.getParameters();
                        Camera.Size size = params != null ? params.getPreviewSize() : null;
                        if (size != null) {
                            w = size.width;
                            h = size.height;
                        }
                    } catch (Throwable ignored) {
                    }
                    if (w > 0 && h > 0) {
                        byte[] fake = FrameProvider.get().getFrame(w, h);
                        if (fake != null && fake.length <= data.length) {
                            System.arraycopy(fake, 0, data, 0, fake.length);
                            frameCount++;
                            if ((frameCount % 300) == 1) {
                                Log.i(TAG, "Camera1: replaced " + frameCount
                                        + " frames @" + w + "x" + h);
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                Log.e(TAG, "ScopeCamCallback error: " + t.getMessage());
            }
            try {
                original.onPreviewFrame(data, cam);
            } catch (Throwable t) {
                Log.e(TAG, "delegate to app callback failed: " + t.getMessage());
            }
        }
    }
}
