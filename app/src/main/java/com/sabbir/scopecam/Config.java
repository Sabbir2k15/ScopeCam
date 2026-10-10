package com.sabbir.scopecam;

import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/**
 * Reads /sdcard/DCIM/ScopeCam/config.json written by the UI.
 * Cached in memory; reloaded when the file's modification time changes.
 *
 * Fields:
 *   enabled       - master switch
 *   mode          - "video" | "image" | "stream"
 *   video_path    - absolute path to replacement video
 *   image_path    - absolute path to replacement image
 *   stream_url    - http(s) URL of replacement stream
 *   block_on_fail - if true, emit black frames when no source frame is
 *                   available (blocks the real camera instead of leaking it)
 */
public class Config {

    private static final String TAG = "ScopeCam";
    private static final String CONFIG_PATH = "/sdcard/DCIM/ScopeCam/config.json";

    public boolean enabled = true;
    public String mode = "video";
    public String videoPath = "";
    public String imagePath = "";
    public String streamUrl = "";
    public boolean blockOnFail = true;

    private static volatile Config cached;
    private static volatile long cachedModified = -1;

    public static String getConfigPath() {
        return CONFIG_PATH;
    }

    public static Config load() {
        try {
            File f = new File(CONFIG_PATH);
            if (!f.exists()) {
                if (cached == null) {
                    cached = new Config();
                }
                return cached;
            }
            long mod = f.lastModified();
            if (cached != null && mod == cachedModified) {
                return cached;
            }
            StringBuilder sb = new StringBuilder();
            BufferedReader br = new BufferedReader(new FileReader(f));
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
            br.close();

            JSONObject o = new JSONObject(sb.toString());
            Config c = new Config();
            c.enabled = o.optBoolean("enabled", true);
            c.mode = o.optString("mode", "video");
            c.videoPath = o.optString("video_path", "");
            c.imagePath = o.optString("image_path", "");
            c.streamUrl = o.optString("stream_url", "");
            c.blockOnFail = o.optBoolean("block_on_fail", true);

            cached = c;
            cachedModified = mod;
            Log.i(TAG, "Config loaded: mode=" + c.mode + " enabled=" + c.enabled);
            return c;
        } catch (Throwable t) {
            Log.e(TAG, "Config load failed, using defaults: " + t.getMessage());
            if (cached == null) {
                cached = new Config();
            }
            return cached;
        }
    }

    /** Force reload on next load() call (used after UI saves). */
    public static void invalidate() {
        cachedModified = -1;
    }
}
