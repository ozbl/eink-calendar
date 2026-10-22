package com.zbl.einkcalendar;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.PowerManager;
import android.view.View;
import android.view.WindowManager;

/**
 * Separate screen that lets the user pick a poster layout from thumbnails.
 * The choice is stored right away; {@link MainActivity} applies it on resume.
 */
public class StylePickerActivity extends Activity implements StylePickerView.Listener {

    private static final String PREFS = "eink_calendar";

    private PowerManager.WakeLock screenLock;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);

        if (isKeepScreenOn()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        StylePickerView view = new StylePickerView(this, prefs.getInt("poster_style", 0), this);
        setContentView(view);
        Intent battery = registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery != null) {
            int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            int percent = (level >= 0 && scale > 0)
                    ? Math.round(level * 100f / scale) : -1;
            int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            view.setBattery(percent, status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL);
        }
        String shotName = getIntent().getStringExtra("shot_name");
        if (shotName != null) {
            DebugShot.capture(view, shotName);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isKeepScreenOn()) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                screenLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
                        "eink-calendar:picker");
                screenLock.setReferenceCounted(false);
                try {
                    screenLock.acquire();
                } catch (Exception ignored) {
                }
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (screenLock != null && screenLock.isHeld()) {
            try {
                screenLock.release();
            } catch (Exception ignored) {
            }
        }
    }

    private boolean isKeepScreenOn() {
        return getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean("keep_screen_on", true);
    }

    @Override
    public void onStylePicked(int style) {
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit().putInt("poster_style", style).commit();
        finish();
    }

    @Override
    public void onCancel() {
        finish();
    }
}
