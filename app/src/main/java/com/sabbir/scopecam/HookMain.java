package com.sabbir.scopecam;

import android.util.Log;

import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Routes hook initialization to Camera1 and Camera2 engines.
 * Installed once per target process.
 */
public class HookMain {

    private static final String TAG = "ScopeCam";
    private static volatile boolean initialized = false;

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        if (initialized) {
            return;
        }
        synchronized (HookMain.class) {
            if (initialized) {
                return;
            }
            initialized = true;
        }

        try {
            Config cfg = Config.load();
            if (!cfg.enabled) {
                Log.i(TAG, "ScopeCam is disabled in config, skipping hooks for "
                        + lpparam.packageName);
                return;
            }
            // Install both engines. Each engine self-detects whether the
            // target app actually uses its camera API.
            Camera1Hook.init(lpparam);
            Camera2Hook.init(lpparam);
            Log.i(TAG, "ScopeCam hooks installed for " + lpparam.packageName);
        } catch (Throwable t) {
            Log.e(TAG, "HookMain init failed: " + t);
        }
    }
}
