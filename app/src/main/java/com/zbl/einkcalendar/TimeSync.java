package com.zbl.einkcalendar;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.SystemClock;

import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Keeps the displayed time in sync with an internet time source by storing an
 * offset against the system clock. The system clock itself is never modified.
 * Uses the HTTP "Date" response header, which works on old Android versions
 * without TLS or NTP.
 */
final class TimeSync {

    interface Listener {
        void onTimeSync(boolean ok, String message);
    }

    private static final String PREFS = "eink_calendar";
    private static final String[] URLS = {
            "http://www.baidu.com",
            "http://www.taobao.com",
            "http://www.qq.com"
    };

    private static long offsetMillis = 0L;
    private static long lastSyncMillis = 0L;
    private static volatile boolean syncing = false;

    private TimeSync() {
    }

    static void load(SharedPreferences prefs) {
        offsetMillis = prefs.getLong("time_offset", 0L);
        lastSyncMillis = prefs.getLong("time_last_sync", 0L);
    }

    static long now() {
        return System.currentTimeMillis() + offsetMillis;
    }

    static long offset() {
        return offsetMillis;
    }

    static long lastSync() {
        return lastSyncMillis;
    }

    static boolean isSynced() {
        return lastSyncMillis > 0L;
    }

    static boolean isSyncing() {
        return syncing;
    }

    static void sync(final Context context, final Listener listener) {
        if (syncing) {
            return;
        }
        if (!HolidayStore.hasNetwork(context)) {
            if (listener != null) {
                listener.onTimeSync(false, "离线，使用本机时间");
            }
            return;
        }
        syncing = true;
        final SharedPreferences prefs =
                context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                String message = "校时失败";
                boolean ok = false;
                for (String urlText : URLS) {
                    long before = SystemClock.elapsedRealtime();
                    Long serverMillis = fetchDateHeader(urlText);
                    long after = SystemClock.elapsedRealtime();
                    if (serverMillis != null) {
                        long latency = after - before;
                        long guess = System.currentTimeMillis() + latency / 2;
                        offsetMillis = serverMillis - guess;
                        lastSyncMillis = System.currentTimeMillis();
                        prefs.edit()
                                .putLong("time_offset", offsetMillis)
                                .putLong("time_last_sync", lastSyncMillis)
                                .commit();
                        ok = true;
                        message = "已校时";
                        break;
                    }
                }
                syncing = false;
                final boolean success = ok;
                final String finalMessage = message;
                if (listener != null) {
                    new Handler(context.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onTimeSync(success, finalMessage);
                        }
                    });
                }
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    private static Long fetchDateHeader(String urlText) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlText);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            conn.setRequestMethod("HEAD");
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "eink-calendar/1.0");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 400) {
                return null;
            }
            long date = conn.getHeaderFieldDate("Date", 0L);
            if (date <= 0L) {
                String raw = conn.getHeaderField("Date");
                if (raw != null) {
                    date = parseHttpDate(raw);
                }
            }
            if (date > 0L) {
                return Long.valueOf(date);
            }
        } catch (Exception ignored) {
            // try the next endpoint
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return null;
    }

    private static long parseHttpDate(String raw) {
        String[] patterns = {
                "EEE, dd MMM yyyy HH:mm:ss zzz",
                "EEE, dd MMM yyyy HH:mm:ss Z"
        };
        for (String pattern : patterns) {
            try {
                SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
                format.setTimeZone(TimeZone.getTimeZone("GMT"));
                Date d = format.parse(raw);
                if (d != null) {
                    return d.getTime();
                }
            } catch (Exception ignored) {
                // try next pattern
            }
        }
        return 0L;
    }
}
