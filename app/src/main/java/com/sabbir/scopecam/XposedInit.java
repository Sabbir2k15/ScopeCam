package com.sabbir.scopecam;

import android.util.Log;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Xposed module entry point. Declared in assets/xposed_init.
 * LSPosed loads this class and calls handleLoadPackage for every app
 * in the module's scope.
 */
public class XposedInit implements IXposedHookLoadPackage {

    private static final String TAG = "ScopeCam";
    private static final String MY_PACKAGE = "com.sabbir.scopecam";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        String pkg = lpparam.packageName;
        if (pkg == null || pkg.isEmpty()) {
            return;
        }
        // Never hook ourselves - would cause recursion.
        if (MY_PACKAGE.equals(pkg)) {
            return;
        }
        // Skip core Android processes.
        if ("android".equals(pkg) || "com.android.systemui".equals(pkg)) {
            return;
        }

        try {
            Log.i(TAG, "Loading into " + pkg);
            HookMain.init(lpparam);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to init hooks for " + pkg + ": " + t);
        }
    }
}
