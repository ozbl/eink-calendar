package com.zbl.einkcalendar;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Debug-build only helper that renders a view straight into a PNG file.
 *
 * <p>Reading the e-ink panel with {@code screencap} also captures the panel's
 * accumulated ghosting, which looks like a double print in screenshots. Drawing
 * the view into a bitmap bypasses the panel entirely, so the exported images
 * stay crisp and are already in the correct orientation.
 *
 * <p>Usage (debug builds):
 * {@code adb shell am start -n com.zbl.einkcalendar/.MainActivity --es shot_name poster}
 * then pull {@code /sdcard/Android/data/com.zbl.einkcalendar/files/poster.png}.
 */
final class DebugShot {

    private static final String TAG = "DebugShot";
    private static final long DELAY_MS = 1600L;

    private DebugShot() {
    }

    static void capture(final View view, final String name) {
        if (view == null || name == null || name.length() == 0) {
            return;
        }
        Context context = view.getContext();
        if ((context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                save(view, name);
            }
        }, DELAY_MS);
    }

    private static void save(View view, String name) {
        int width = view.getWidth();
        int height = view.getHeight();
        if (width <= 0 || height <= 0) {
            Log.w(TAG, "view not laid out yet: " + width + "x" + height);
            return;
        }
        Bitmap bitmap = null;
        FileOutputStream out = null;
        try {
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            view.draw(new Canvas(bitmap));
            File dir = view.getContext().getExternalFilesDir(null);
            if (dir == null) {
                Log.w(TAG, "no external files dir");
                return;
            }
            if (!dir.exists()) {
                dir.mkdirs();
            }
            File file = new File(dir, name + ".png");
            out = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            out.flush();
            Log.i(TAG, "saved " + file.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "capture failed", e);
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Exception ignored) {
                }
            }
            if (bitmap != null) {
                bitmap.recycle();
            }
        }
    }
}
