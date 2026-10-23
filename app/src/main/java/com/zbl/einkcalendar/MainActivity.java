package com.zbl.einkcalendar;

import android.app.Activity;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity implements DashboardView.Listener {

    private static final String PREFS = "eink_calendar";

    private SharedPreferences prefs;
    private HolidayStore holidays;
    private WeatherStore weather;
    private DashboardView dashboard;

    private final Handler handler = new Handler();
    private boolean running;
    /** Only the first resume after launch gets the clean-up flash. */
    private boolean firstResume = true;
    /**
     * A window flag alone is not enough on this reader: its power service
     * still follows the system "sleep after 30 min" timer, so a real screen
     * wake lock is needed to keep the dashboard lit.
     */
    private PowerManager.WakeLock screenLock;
    private boolean screenReceiverRegistered;
    private long wakeWindowStart = 0L;
    private int wakeAttempts = 0;
    private long lastHolidayAttempt = 0L;

    /** Clears accumulated e-ink ghosting once an hour, exactly on the hour. */
    private final Runnable hourlyFlush = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            dashboard.softRefresh();
            handler.postDelayed(this, delayToNextHour());
        }
    };

    /** Milliseconds from now to the next full hour. */
    private static long delayToNextHour() {
        Calendar cal = Calendar.getInstance();
        long now = cal.getTimeInMillis();
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        cal.add(Calendar.HOUR_OF_DAY, 1);
        return cal.getTimeInMillis() - now;
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            dashboard.updateNow(TimeSync.now());
            maybeSyncTime(false);
            if (weather != null && weather.stale()) {
                weather.sync(false, weatherListener);
            }
            if (HolidayStore.hasNetwork(MainActivity.this)
                    && !holidays.hasData(currentYear())
                    && System.currentTimeMillis() - lastHolidayAttempt > 60000L) {
                syncHolidays(false);
            }
            // Wake up just after the next minute boundary instead of polling
            // every ten seconds; the clock still flips on the minute.
            handler.postDelayed(this,
                    60000L - (System.currentTimeMillis() % 60000L) + 1000L);
        }
    };

    private final BroadcastReceiver connectivityReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (HolidayStore.hasNetwork(MainActivity.this)) {
                dashboard.setNetworkStatus("已联网");
                syncHolidays(false);
                maybeSyncTime(true);
                if (weather != null && weather.stale()) {
                    weather.sync(false, weatherListener);
                }
            } else {
                dashboard.setNetworkStatus("离线");
            }
        }
    };

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateBattery(intent);
        }
    };

    /** Reads level/charging from the sticky ACTION_BATTERY_CHANGED broadcast. */
    private void updateBattery(Intent intent) {
        if (intent == null || dashboard == null) {
            return;
        }
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int percent = (level >= 0 && scale > 0)
                ? Math.round(level * 100f / scale) : -1;
        int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
        dashboard.setBattery(percent, charging);
    }

    /** Pushes the cached/fresh forecast into the dashboard. */
    private void applyWeather() {
        if (dashboard == null || weather == null) {
            return;
        }
        String brief = weather.todayShort();
        dashboard.setWeather(
                weather.todayLine(),
                weather.tomorrowLine(),
                brief.length() == 0 ? "" : "今日天气  " + brief);
    }

    private final WeatherStore.Listener weatherListener = new WeatherStore.Listener() {
        @Override
        public void onWeather(boolean ok, String message) {
            applyWeather();
        }
    };

    /**
     * Last line of defence: some vendor power services switch the panel off
     * with their own timer even while a wake lock is held.  When that happens
     * with "keep screen on" enabled we simply switch it back on.
     */
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                return;
            }
            if (!isKeepScreenOn()) {
                return;
            }
            long now = System.currentTimeMillis();
            if (now - wakeWindowStart > 60000L) {
                wakeWindowStart = now;
                wakeAttempts = 0;
            }
            if (wakeAttempts >= 3) {
                // Something (or someone) keeps forcing the screen off.
                return;
            }
            wakeAttempts++;
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!running || !isKeepScreenOn()) {
                        return;
                    }
                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                            | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                            | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    acquireScreenLock();
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (running && dashboard != null) {
                                // Repaint without flashing: ghosting is cleared
                                // on the hour, not whenever the vendor wakes us.
                                dashboard.invalidate();
                            }
                        }
                    }, 800L);
                }
            }, 400L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        // A dashboard should be usable the moment it is opened, even if the
        // device was asleep or showing the (insecure) keyguard.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        TimeSync.load(prefs);

        holidays = new HolidayStore(this);
        int year = currentYear();
        holidays.loadCached(year);
        holidays.loadCached(year + 1);

        weather = new WeatherStore(this);
        weather.loadCached();

        dashboard = new DashboardView(this, this, holidays);
        dashboard.setNetworkStatus(HolidayStore.hasNetwork(this) ? "已联网" : "离线");
        dashboard.setSyncStatus(TimeSync.isSynced() ? "已校时" : "本机时间");
        dashboard.setFlashEnabled(prefs.getBoolean("flash_on_switch", true));
        int requestedStyle = getIntent().getIntExtra("poster_style", -1);
        if (requestedStyle >= 0 && requestedStyle < PosterRenderer.STYLE_COUNT) {
            prefs.edit().putInt("poster_style", requestedStyle).commit();
        }
        int style = prefs.getInt("poster_style", 0);
        applyOrientationFor(style);
        dashboard.setPosterStyle(style);
        setContentView(dashboard);
        applyWeather();
        applyKeepScreenOn();
        String shotName = getIntent().getStringExtra("shot_name");
        if (shotName != null) {
            if ("calendar".equals(getIntent().getStringExtra("shot_page"))) {
                dashboard.setPage(DashboardView.PAGE_CALENDAR);
            }
            DebugShot.capture(dashboard, shotName);
        }

        // Refresh from cache first, then try the network.
        syncHolidays(false);
        maybeSyncTime(true);
        if (weather.stale()) {
            weather.sync(false, weatherListener);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        running = true;
        handler.post(ticker);
        handler.removeCallbacks(hourlyFlush);
        handler.postDelayed(hourlyFlush, delayToNextHour());
        IntentFilter filter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
        registerReceiver(connectivityReceiver, filter);
        registerReceiver(batteryReceiver,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (!screenReceiverRegistered) {
            registerReceiver(screenReceiver, new IntentFilter(Intent.ACTION_SCREEN_OFF));
            screenReceiverRegistered = true;
        }
        applyKeepScreenOn();
        wakeAttempts = 0;
        wakeWindowStart = System.currentTimeMillis();
        applyOrientationFor(prefs.getInt("poster_style", 0));
        dashboard.setPosterStyle(prefs.getInt("poster_style", 0));
        dashboard.updateNow(TimeSync.now());
        if (firstResume) {
            firstResume = false;
            dashboard.softRefresh();
        } else {
            // A system overlay (root prompt, USB dialog, vendor standby) can
            // pause and resume us at any time.  Repaint quietly instead of
            // flashing: the hourly flush is the one scheduled clean-up.
            dashboard.invalidate();
        }
        syncHolidays(false);
        maybeSyncTime(false);
        if (weather.stale()) {
            weather.sync(false, weatherListener);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        running = false;
        handler.removeCallbacks(ticker);
        handler.removeCallbacks(hourlyFlush);
        if (screenReceiverRegistered) {
            try {
                unregisterReceiver(screenReceiver);
            } catch (Exception ignored) {
            }
            screenReceiverRegistered = false;
        }
        releaseScreenLock();
        try {
            unregisterReceiver(batteryReceiver);
        } catch (Exception ignored) {
        }
        try {
            unregisterReceiver(connectivityReceiver);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onBackPressed() {
        if (dashboard != null && dashboard.page() == DashboardView.PAGE_CALENDAR) {
            dashboard.setPage(DashboardView.PAGE_POSTER);
            return;
        }
        super.onBackPressed();
    }

    private int currentYear() {
        return Integer.parseInt(new SimpleDateFormat("yyyy", Locale.CHINA)
                .format(new Date(TimeSync.now())));
    }

    private void applyKeepScreenOn() {
        boolean keepOn = prefs == null || prefs.getBoolean("keep_screen_on", true);
        if (keepOn) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            acquireScreenLock();
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            releaseScreenLock();
        }
    }

    /**
     * Hold a real screen wake lock.  This reader's power service honours the
     * system sleep timeout even when a window asks to stay on, but a
     * SCREEN_BRIGHT wake lock keeps the panel lit (and ACQUIRE_CAUSES_WAKEUP
     * lets us take the panel back after the vendor still switched it off).
     */
    private void acquireScreenLock() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null) {
            return;
        }
        if (screenLock == null) {
            screenLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                    | PowerManager.ACQUIRE_CAUSES_WAKEUP, "eink-calendar:screen");
            screenLock.setReferenceCounted(false);
        }
        if (!screenLock.isHeld()) {
            try {
                screenLock.acquire();
            } catch (Exception ignored) {
            }
        }
    }

    private void releaseScreenLock() {
        if (screenLock != null && screenLock.isHeld()) {
            try {
                screenLock.release();
            } catch (Exception ignored) {
            }
        }
    }

    private void maybeSyncTime(boolean force) {
        if (!HolidayStore.hasNetwork(this)) {
            dashboard.setNetworkStatus("离线");
            return;
        }
        long age = System.currentTimeMillis() - TimeSync.lastSync();
        if (force || !TimeSync.isSynced() || age > 6L * 3600L * 1000L) {
            dashboard.setSyncStatus("校时中…");
            TimeSync.sync(this, new TimeSync.Listener() {
                @Override
                public void onTimeSync(boolean ok, String message) {
                    dashboard.setSyncStatus(ok ? "已校时" : "本机时间");
                }
            });
        } else {
            dashboard.setSyncStatus("已校时");
        }
    }

    private void syncHolidays(boolean force) {
        final int year = currentYear();
        lastHolidayAttempt = System.currentTimeMillis();
        dashboard.setNetworkStatus(HolidayStore.hasNetwork(this) ? "已联网" : "离线");
        holidays.sync(year, force, new HolidayStore.Listener() {
            @Override
            public void onHolidayData(boolean changed, String message) {
                if (changed) {
                    dashboard.refreshHolidays();
                }
            }
        });
    }

    @Override
    public void onSettingsRequested() {
        showSettingsDialog();
    }

    @Override
    public void onManualSyncRequested() {
        syncHolidays(true);
        maybeSyncTime(true);
        toast("正在校时并更新节假日…");
    }

    @Override
    public void onPosterStyleChanged(int style) {
        prefs.edit().putInt("poster_style", style).commit();
        applyOrientationFor(style);
    }

    /**
     * Landscape layouts (12-15) rotate the whole activity: the month grid,
     * the gestures and the picker all follow the printed orientation.
     */
    private void applyOrientationFor(int style) {
        int want = PosterRenderer.isLandscape(style)
                ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        if (getRequestedOrientation() != want) {
            setRequestedOrientation(want);
        }
    }

    private void showSettingsDialog() {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        int pad = dp(24);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("设置");
        title.setTextColor(Color.BLACK);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setPadding(0, 0, 0, dp(16));
        root.addView(title);

        final TextView keepRow = row("屏幕常亮：" + (isKeepScreenOn() ? "开" : "关"));
        keepRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean next = !isKeepScreenOn();
                prefs.edit().putBoolean("keep_screen_on", next).commit();
                applyKeepScreenOn();
                keepRow.setText("屏幕常亮：" + (next ? "开" : "关"));
            }
        });
        root.addView(keepRow);

        final TextView flashRow = row("翻页时整屏刷新：" + (isFlashOnSwitch() ? "开" : "关"));
        flashRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean next = !isFlashOnSwitch();
                prefs.edit().putBoolean("flash_on_switch", next).commit();
                dashboard.setFlashEnabled(next);
                flashRow.setText("翻页时整屏刷新：" + (next ? "开" : "关"));
            }
        });
        root.addView(flashRow);

        TextView styleRow = row("显示样式：" + PosterRenderer.styleName(
                prefs.getInt("poster_style", 0)));
        styleRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                startActivity(new Intent(MainActivity.this, StylePickerActivity.class));
            }
        });
        root.addView(styleRow);

        TextView syncRow = row("立即校时（网络时间）");
        syncRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dashboard.setSyncStatus("校时中…");
                TimeSync.sync(MainActivity.this, new TimeSync.Listener() {
                    @Override
                    public void onTimeSync(boolean ok, String message) {
                        dashboard.setSyncStatus(ok ? "已校时" : "本机时间");
                        toast(message);
                    }
                });
            }
        });
        root.addView(syncRow);

        TextView holidayRow = row("更新节假日数据（调休/法定假日）");
        holidayRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                syncHolidays(true);
                toast("正在更新节假日数据…");
            }
        });
        root.addView(holidayRow);

        TextView cityRow = row("天气城市：" + weather.city());
        cityRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                showWeatherCityDialog();
            }
        });
        root.addView(cityRow);

        TextView weatherRow = row("更新天气（中国天气网）");
        weatherRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toast("正在获取天气…");
                weather.sync(true, new WeatherStore.Listener() {
                    @Override
                    public void onWeather(boolean ok, String message) {
                        applyWeather();
                        toast(message);
                    }
                });
            }
        });
        root.addView(weatherRow);

        TextView flushRow = row("立即全屏刷新（清除残影）");
        flushRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                dashboard.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        dashboard.fullRefresh();
                    }
                }, 260L);
            }
        });
        root.addView(flushRow);

        TextView info = new TextView(this);
        info.setText("数据源：timor.tech（节假日）· weather.com.cn（天气）\n"
                + "HTTP Date（校时）· 农历/节气/干支：内置离线算法（1888-2100）\n"
                + "当前："
                + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
                .format(new Date(TimeSync.now())));
        info.setTextColor(0xFF555555);
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        info.setPadding(0, dp(18), 0, dp(18));
        root.addView(info);

        TextView close = row("关闭");
        close.setGravity(Gravity.CENTER);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        root.addView(close);

        ScrollView scroll = new ScrollView(this);
        GradientDrawable panel = new GradientDrawable();
        panel.setColor(Color.WHITE);
        panel.setStroke(dp(3), Color.BLACK);
        panel.setCornerRadius(dp(8));
        scroll.setBackground(panel);
        scroll.addView(root);
        dialog.setContentView(scroll);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.WHITE));
            window.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.86f),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    /** City chooser: type any name, or tap one of the common cities. */
    private void showWeatherCityDialog() {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        int pad = dp(24);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("天气城市");
        title.setTextColor(Color.BLACK);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setPadding(0, 0, 0, dp(12));
        root.addView(title);

        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(weather.city());
        input.setHint("输入城市名，如：杭州");
        input.setTextColor(Color.BLACK);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        input.setPadding(dp(12), dp(8), dp(12), dp(8));
        root.addView(input);

        TextView save = row("保存并更新");
        save.setGravity(Gravity.CENTER);
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String name = input.getText().toString().trim();
                if (name.length() == 0) {
                    toast("请输入城市名");
                    return;
                }
                dialog.dismiss();
                setWeatherCity(name);
            }
        });
        root.addView(save);

        TextView listTitle = new TextView(this);
        listTitle.setText("常用城市");
        listTitle.setTextColor(0xFF555555);
        listTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        listTitle.setPadding(dp(4), dp(14), 0, dp(2));
        root.addView(listTitle);

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < WeatherStore.CITIES.length; i++) {
            final String city = WeatherStore.CITIES[i];
            TextView item = row(city);
            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dialog.dismiss();
                    setWeatherCity(city);
                }
            });
            list.addView(item);
        }
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(360)));

        TextView cancel = row("取消");
        cancel.setGravity(Gravity.CENTER);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        root.addView(cancel);

        GradientDrawable panel = new GradientDrawable();
        panel.setColor(Color.WHITE);
        panel.setStroke(dp(3), Color.BLACK);
        panel.setCornerRadius(dp(8));
        ScrollView outer = new ScrollView(this);
        outer.setBackground(panel);
        outer.addView(root);
        dialog.setContentView(outer);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.WHITE));
            window.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.86f),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            // Keep the keyboard down until the user taps the input: the city
            // list is the primary control on an e-ink device.
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        }
        dialog.show();
    }

    private void setWeatherCity(final String name) {
        weather.setCity(name);
        applyWeather();
        toast("正在获取 " + name + " 天气…");
        weather.sync(true, new WeatherStore.Listener() {
            @Override
            public void onWeather(boolean ok, String message) {
                applyWeather();
                toast(message);
            }
        });
    }

    private boolean isKeepScreenOn() {
        return prefs.getBoolean("keep_screen_on", true);
    }

    private boolean isFlashOnSwitch() {
        return prefs.getBoolean("flash_on_switch", true);
    }

    private TextView row(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(Color.BLACK);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        view.setPadding(dp(18), dp(18), dp(18), dp(18));
        GradientDrawable border = new GradientDrawable();
        border.setColor(Color.WHITE);
        border.setStroke(dp(2), Color.BLACK);
        border.setCornerRadius(dp(6));
        view.setBackground(border);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(12);
        view.setLayoutParams(params);
        return view;
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
