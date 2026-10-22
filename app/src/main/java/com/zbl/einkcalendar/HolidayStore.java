package com.zbl.einkcalendar;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Downloads and caches China statutory holiday / make-up workday data from timor.tech.
 * All data is cached per year in SharedPreferences so the app works offline.
 */
final class HolidayStore {

    static final class HolidayDay {
        boolean holiday;   // true = statutory rest day
        boolean workday;   // true = make-up working day (调休补班)
        String name;
        String target;
    }

    interface Listener {
        void onHolidayData(boolean changed, String message);
    }

    private static final String PREFS = "eink_calendar";
    private static final String URL_TEMPLATE = "https://timor.tech/api/holiday/year/%d";

    private final Context context;
    private final SharedPreferences prefs;
    private final Map<Integer, Map<String, HolidayDay>> byYear =
            new HashMap<Integer, Map<String, HolidayDay>>();
    private final java.util.Set<Integer> inFlight = new java.util.HashSet<Integer>();

    HolidayStore(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        enableTls12();
    }

    /** Android 4.4 supports TLS 1.2 but leaves it disabled by default. */
    private static void enableTls12() {
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            return;
        }
        try {
            javax.net.ssl.SSLContext sslContext =
                    javax.net.ssl.SSLContext.getInstance("TLS");
            sslContext.init(null, null, null);
            javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(
                    new Tls12SocketFactory(sslContext.getSocketFactory()));
        } catch (Exception ignored) {
        }
    }

    private static final class Tls12SocketFactory extends javax.net.ssl.SSLSocketFactory {
        private final javax.net.ssl.SSLSocketFactory delegate;

        Tls12SocketFactory(javax.net.ssl.SSLSocketFactory delegate) {
            this.delegate = delegate;
        }

        private java.net.Socket patch(java.net.Socket socket) {
            if (socket instanceof javax.net.ssl.SSLSocket) {
                try {
                    javax.net.ssl.SSLSocket ssl = (javax.net.ssl.SSLSocket) socket;
                    java.util.LinkedHashSet<String> protocols =
                            new java.util.LinkedHashSet<String>();
                    for (String p : ssl.getEnabledProtocols()) {
                        protocols.add(p);
                    }
                    protocols.add("TLSv1.2");
                    ssl.setEnabledProtocols(protocols.toArray(new String[protocols.size()]));
                } catch (Exception ignored) {
                }
            }
            return socket;
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return delegate.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return delegate.getSupportedCipherSuites();
        }

        @Override
        public java.net.Socket createSocket() throws java.io.IOException {
            return patch(delegate.createSocket());
        }

        @Override
        public java.net.Socket createSocket(java.net.Socket s, String host, int port,
                                            boolean autoClose) throws java.io.IOException {
            return patch(delegate.createSocket(s, host, port, autoClose));
        }

        @Override
        public java.net.Socket createSocket(String host, int port)
                throws java.io.IOException, java.net.UnknownHostException {
            return patch(delegate.createSocket(host, port));
        }

        @Override
        public java.net.Socket createSocket(String host, int port,
                                            java.net.InetAddress localHost, int localPort)
                throws java.io.IOException, java.net.UnknownHostException {
            return patch(delegate.createSocket(host, port, localHost, localPort));
        }

        @Override
        public java.net.Socket createSocket(java.net.InetAddress host, int port)
                throws java.io.IOException {
            return patch(delegate.createSocket(host, port));
        }

        @Override
        public java.net.Socket createSocket(java.net.InetAddress address, int port,
                                            java.net.InetAddress localAddress, int localPort)
                throws java.io.IOException {
            return patch(delegate.createSocket(address, port, localAddress, localPort));
        }
    }

    static boolean hasNetwork(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    context.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo info = cm == null ? null : cm.getActiveNetworkInfo();
            return info != null && info.isConnected();
        } catch (Exception e) {
            return false;
        }
    }

    /** Load cached data for a year straight from SharedPreferences. */
    synchronized void loadCached(int year) {
        if (byYear.containsKey(year)) {
            return;
        }
        String json = prefs.getString("holiday_" + year, null);
        Map<String, HolidayDay> map = new HashMap<String, HolidayDay>();
        if (json != null) {
            parse(json, year, map);
        }
        byYear.put(year, map);
    }

    synchronized HolidayDay get(int year, int month, int day) {
        Map<String, HolidayDay> map = byYear.get(year);
        if (map == null) {
            loadCached(year);
            map = byYear.get(year);
        }
        String key = String.format(Locale.CHINA, "%02d-%02d", month, day);
        return map.get(key);
    }

    synchronized boolean hasData(int year) {
        Map<String, HolidayDay> map = byYear.get(year);
        return map != null && !map.isEmpty();
    }

    private static void parse(String json, int year, Map<String, HolidayDay> out) {
        try {
            JSONObject root = new JSONObject(json);
            JSONObject holiday = root.optJSONObject("holiday");
            if (holiday == null) {
                return;
            }
            Iterator<String> keys = holiday.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                JSONObject obj = holiday.optJSONObject(key);
                if (obj == null) {
                    continue;
                }
                HolidayDay d = new HolidayDay();
                d.holiday = obj.optBoolean("holiday", false);
                d.name = obj.optString("name", "");
                d.target = obj.optString("target", "");
                d.workday = !d.holiday && d.target != null && d.target.length() > 0;
                // timor returns "MM-dd"; keep it as the map key.
                out.put(key, d);
            }
        } catch (Exception ignored) {
            // Keep whatever parsed so far; callers fall back to week logic.
        }
    }

    /**
     * Fetch a year's data from the network on a background thread, cache it, and
     * report back on the main thread. Skips the request when cached data is fresh
     * (less than 20 hours old) unless force is true.
     */
    void sync(final int year, final boolean force, final Listener listener) {
        long age = System.currentTimeMillis() - prefs.getLong("holiday_ts_" + year, 0);
        if (!force && hasData(year) && age < 20L * 3600L * 1000L) {
            if (listener != null) {
                listener.onHolidayData(false, "使用缓存");
            }
            return;
        }
        if (!hasNetwork(context)) {
            if (listener != null) {
                listener.onHolidayData(false, "离线，使用缓存");
            }
            return;
        }
        synchronized (this) {
            if (inFlight.contains(Integer.valueOf(year))) {
                return;
            }
            inFlight.add(Integer.valueOf(year));
        }
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                final List<String> messages = new ArrayList<String>();
                boolean ok = false;
                for (int y : new int[]{year, year + 1}) {
                    String json = httpGet(String.format(URL_TEMPLATE, y));
                    android.util.Log.i("EinkCal", "holiday " + y + " -> "
                            + (json == null ? "null" : json.length() + " chars"));
                    if (json != null && json.contains("\"holiday\"")) {
                        prefs.edit()
                                .putString("holiday_" + y, json)
                                .putLong("holiday_ts_" + y, System.currentTimeMillis())
                                .commit();
                        Map<String, HolidayDay> map = new HashMap<String, HolidayDay>();
                        parse(json, y, map);
                        synchronized (HolidayStore.this) {
                            byYear.put(y, map);
                        }
                        ok = true;
                        messages.add(y + "年 ok");
                    } else {
                        messages.add(y + "年 失败");
                    }
                }
                final boolean success = ok;
                final String msg = join(messages);
                if (listener != null) {
                    new android.os.Handler(context.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onHolidayData(success, msg);
                        }
                    });
                }
                } finally {
                    synchronized (HolidayStore.this) {
                        inFlight.remove(Integer.valueOf(year));
                    }
                }
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    private static String join(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(" · ");
            }
            sb.append(list.get(i));
        }
        return sb.toString();
    }

    static String httpGet(String urlText) {
        String current = urlText;
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(current);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(7000);
                conn.setReadTimeout(9000);
                conn.setRequestMethod("GET");
                conn.setInstanceFollowRedirects(false);
                conn.setRequestProperty("Accept-Encoding", "identity");
                conn.setRequestProperty("User-Agent", "eink-calendar/1.0");
                int code = conn.getResponseCode();
                if (code >= 300 && code < 400) {
                    String location = conn.getHeaderField("Location");
                    if (location == null) {
                        return null;
                    }
                    if (location.startsWith("/")) {
                        location = url.getProtocol() + "://" + url.getHost() + location;
                    }
                    current = location;
                    continue;
                }
                if (code != 200) {
                    android.util.Log.w("EinkCal", "holiday http " + current + " -> " + code);
                    return null;
                }
                InputStream in = conn.getInputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                return sb.toString();
            } catch (Exception e) {
                android.util.Log.w("EinkCal", "holiday http failed " + current + ": " + e);
                return null;
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }
        return null;
    }
}
