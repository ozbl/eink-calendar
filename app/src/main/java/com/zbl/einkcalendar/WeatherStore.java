package com.zbl.einkcalendar;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Three-day weather forecast for a Chinese city, from 中国天气网
 * (weather.com.cn).  The city name is resolved to a weather.com.cn city code
 * through the site's search endpoint, then the forecast is read from the
 * weather_index endpoint used by the site itself.  Everything is cached in
 * SharedPreferences so the dashboard still shows the last forecast offline.
 */
final class WeatherStore {

    static final class Day {
        String label = "";
        String text = "";
        String full = "";
        String date = "";
        int high = Integer.MIN_VALUE;
        int low = Integer.MIN_VALUE;
    }

    interface Listener {
        void onWeather(boolean ok, String message);
    }

    static final String DEFAULT_CITY = "北京";

    /** Common cities offered in the settings dialog; any other name can be typed. */
    static final String[] CITIES = {
            "北京", "上海", "天津", "重庆",
            "广州", "深圳", "杭州", "南京", "苏州", "宁波", "无锡", "佛山", "东莞",
            "成都", "武汉", "西安", "郑州", "长沙", "沈阳", "大连", "青岛", "济南",
            "哈尔滨", "长春", "石家庄", "太原", "呼和浩特", "合肥", "福州", "厦门",
            "南昌", "南宁", "海口", "贵阳", "昆明", "拉萨", "兰州", "西宁", "银川", "乌鲁木齐"
    };

    /** weather.com.cn city codes for {@link #CITIES}, in the same order. */
    private static final String[] CITY_CODES = {
            "101010100", "101020100", "101030100", "101040100",
            "101280101", "101280601", "101210101", "101190101", "101190401", "101210401",
            "101190201", "101280800", "101281601",
            "101270101", "101200101", "101110101", "101180101", "101250101", "101070101",
            "101070201", "101120201", "101120101",
            "101050101", "101060101", "101090101", "101100101", "101080101", "101220101",
            "101230101", "101230201",
            "101240101", "101300101", "101310101", "101260101", "101290101", "101140101",
            "101160101", "101150101", "101170101", "101130101"
    };

    private static final String PREFS = "eink_calendar";
    private static final String SEARCH_URL = "http://toy1.weather.com.cn/search?cityname=";
    private static final String WEATHER_URL = "http://d1.weather.com.cn/weather_index/%s.html";
    private static final String REFERER = "http://www.weather.com.cn/";
    private static final long MAX_AGE_MS = 30L * 60L * 1000L;

    private static final String KEY_CITY = "weather_city";
    private static final String KEY_CODE = "weather_code";
    private static final String KEY_CACHE = "weather_cache";
    private static final String KEY_TS = "weather_ts";

    private final Context context;
    private final SharedPreferences prefs;
    private final List<Day> days = new ArrayList<Day>();

    private String cityName = DEFAULT_CITY;
    private String cityCode;
    private String currentText = "";
    private int currentTemp = Integer.MIN_VALUE;
    private long updatedAt;
    private boolean inFlight;
    private long generation;

    WeatherStore(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    synchronized void loadCached() {
        cityName = prefs.getString(KEY_CITY, DEFAULT_CITY);
        if (cityName == null || cityName.trim().length() == 0) {
            cityName = DEFAULT_CITY;
        }
        cityCode = prefs.getString(KEY_CODE, null);
        updatedAt = prefs.getLong(KEY_TS, 0L);
        parseCache(prefs.getString(KEY_CACHE, null));
    }

    synchronized String city() {
        return cityName;
    }

    synchronized boolean hasData() {
        return !days.isEmpty();
    }

    synchronized long updatedAt() {
        return updatedAt;
    }

    /** True when there is no forecast yet, or the cached one is over 30 min old. */
    synchronized boolean stale() {
        return days.isEmpty() || System.currentTimeMillis() - updatedAt > MAX_AGE_MS;
    }

    /** "多云 9~17℃" — the compact form used in the poster footer. */
    synchronized String todayShort() {
        if (days.isEmpty()) {
            return "";
        }
        return shortText(days.get(0));
    }

    /** "今天 多云 9~17℃" */
    synchronized String todayLine() {
        if (days.isEmpty()) {
            return "";
        }
        String text = shortText(days.get(0));
        return text.length() == 0 ? "" : "今天 " + text;
    }

    /** "明天 小雨 11~17℃" */
    synchronized String tomorrowLine() {
        if (days.size() < 2) {
            return "";
        }
        String text = shortText(days.get(1));
        return text.length() == 0 ? "" : "明天 " + text;
    }

    private String shortText(Day day) {
        String name = day.full;
        if (name.length() == 0) {
            name = day.text;
        }
        if (name.length() == 0 && day == days.get(0)) {
            name = currentText;
        }
        StringBuilder sb = new StringBuilder();
        if (name.length() > 0) {
            sb.append(name).append(' ');
        }
        if (day.low != Integer.MIN_VALUE && day.high != Integer.MIN_VALUE) {
            sb.append(day.low).append('~').append(day.high).append('℃');
        } else if (day.high != Integer.MIN_VALUE) {
            sb.append(day.high).append('℃');
        }
        return sb.toString().trim();
    }

    /** Changes the city, drops the old code/cache, and leaves fetching to the caller. */
    synchronized void setCity(String name) {
        String next = name == null ? "" : name.trim();
        if (next.length() == 0) {
            next = DEFAULT_CITY;
        }
        if (next.equals(cityName) && cityCode != null && !days.isEmpty()) {
            return;
        }
        cityName = next;
        cityCode = null;
        days.clear();
        currentText = "";
        currentTemp = Integer.MIN_VALUE;
        updatedAt = 0L;
        generation++;
        prefs.edit()
                .putString(KEY_CITY, cityName)
                .remove(KEY_CODE)
                .remove(KEY_CACHE)
                .remove(KEY_TS)
                .commit();
    }

    /**
     * Refreshes the forecast in the background and reports on the main thread.
     * With force=false the cache is used while it is younger than 30 minutes.
     */
    void sync(final boolean force, final Listener listener) {
        if (!force && !stale()) {
            post(listener, false, "使用缓存");
            return;
        }
        if (!HolidayStore.hasNetwork(context)) {
            post(listener, false, "离线，无法更新天气");
            return;
        }
        final long gen;
        final String requestedCity;
        synchronized (this) {
            if (inFlight) {
                return;
            }
            inFlight = true;
            gen = generation;
            requestedCity = cityName;
        }
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                boolean superseded = false;
                String message = "天气获取失败";
                try {
                    String code = cityCode;
                    if (code == null || code.length() == 0) {
                        code = codeForCity(requestedCity);
                        if (code == null) {
                            code = resolveCity(requestedCity);
                        }
                        if (code != null) {
                            synchronized (WeatherStore.this) {
                                if (gen != generation) {
                                    superseded = true;
                                } else {
                                    cityCode = code;
                                    prefs.edit().putString(KEY_CODE, code).commit();
                                }
                            }
                        }
                    }
                    if (superseded) {
                        message = "城市已更改";
                    } else if (code == null) {
                        message = "找不到城市：" + requestedCity;
                    } else {
                        String body = httpGet(
                                String.format(Locale.US, WEATHER_URL, code), "GBK");
                        Parsed parsed = parseWeather(body);
                        if (parsed == null || parsed.days.isEmpty()) {
                            message = "天气数据解析失败";
                        } else {
                            boolean applied = false;
                            synchronized (WeatherStore.this) {
                                if (gen == generation && requestedCity.equals(cityName)) {
                                    currentText = parsed.current;
                                    currentTemp = parsed.temp;
                                    days.clear();
                                    days.addAll(parsed.days);
                                    updatedAt = System.currentTimeMillis();
                                    applied = true;
                                }
                            }
                            if (!applied) {
                                message = "城市已更改";
                            } else {
                                prefs.edit()
                                        .putString(KEY_CACHE, buildCache(parsed))
                                        .putLong(KEY_TS, updatedAt)
                                        .commit();
                                ok = true;
                                message = "天气已更新";
                            }
                        }
                    }
                } catch (Exception e) {
                    android.util.Log.w("EinkCal", "weather sync failed: " + e);
                } finally {
                    synchronized (WeatherStore.this) {
                        inFlight = false;
                    }
                }
                post(listener, ok, message);
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    // ------------------------------------------------------------------
    // Network
    // ------------------------------------------------------------------

    /** Resolves a common city from the built-in table, or null if it is not listed. */
    private static String codeForCity(String name) {
        if (name == null) {
            return null;
        }
        for (int i = 0; i < CITIES.length && i < CITY_CODES.length; i++) {
            if (CITIES[i].equals(name)) {
                return CITY_CODES[i];
            }
        }
        return null;
    }

    /** Resolves "杭州" (or any city/district name) to a 9-digit weather.com.cn code. */
    private static String resolveCity(String name) {
        if (name == null || name.length() == 0) {
            return null;
        }
        try {
            String body = httpGet(SEARCH_URL + URLEncoder.encode(name, "UTF-8"), "UTF-8");
            if (body == null) {
                return null;
            }
            body = body.trim();
            if (body.startsWith("(")) {
                body = body.substring(1);
            }
            if (body.endsWith(")")) {
                body = body.substring(0, body.length() - 1);
            }
            JSONArray array = new JSONArray(body);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                String ref = item.optString("ref", "");
                int tilde = ref.indexOf('~');
                if (tilde > 0) {
                    String code = ref.substring(0, tilde);
                    if (code.length() >= 9) {
                        return code;
                    }
                }
            }
        } catch (Exception e) {
            android.util.Log.w("EinkCal", "weather city lookup failed: " + e);
        }
        return null;
    }

    private static String httpGet(String urlText, String charset) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlText);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setRequestProperty("Referer", REFERER);
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android) eink-calendar/1.0");
            int code = conn.getResponseCode();
            if (code != 200) {
                android.util.Log.w("EinkCal", "weather http " + urlText + " -> " + code);
                return null;
            }
            InputStream in = conn.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, charset));
            StringBuilder sb = new StringBuilder();
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) > 0) {
                sb.append(buffer, 0, read);
            }
            reader.close();
            return sb.toString();
        } catch (Exception e) {
            android.util.Log.w("EinkCal", "weather http failed " + urlText + ": " + e);
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    // ------------------------------------------------------------------
    // Parsing: the endpoint replies with JS variable assignments in GBK.
    // Example: var dataSK ={"weather":"晴","temp":"16"}; ... var fc ={"f":[...]}
    // ------------------------------------------------------------------

    private static final class Parsed {
        String current = "";
        int temp = Integer.MIN_VALUE;
        final List<Day> days = new ArrayList<Day>();
    }

    private static Parsed parseWeather(String body) {
        if (body == null) {
            return null;
        }
        Parsed out = new Parsed();
        try {
            JSONObject sk = new JSONObject(extractVar(body, "var dataSK"));
            out.current = sk.optString("weather", "");
            out.temp = parseInt(sk.optString("temp", ""));
        } catch (Exception ignored) {
        }
        try {
            JSONObject fc = new JSONObject(extractVar(body, "var fc"));
            JSONArray list = fc.optJSONArray("f");
            for (int i = 0; list != null && i < list.length() && i < 3; i++) {
                JSONObject item = list.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                Day day = new Day();
                day.label = i == 0 ? "今天" : (i == 1 ? "明天" : "后天");
                day.date = item.optString("fi", "");
                day.text = weatherName(item.optString("fa", ""));
                String night = weatherName(item.optString("fb", ""));
                if (night.length() > 0 && day.text.length() > 0 && !night.equals(day.text)) {
                    day.full = day.text + "转" + night;
                } else {
                    day.full = day.text;
                }
                day.high = parseInt(item.optString("fc", ""));
                day.low = parseInt(item.optString("fd", ""));
                out.days.add(day);
            }
        } catch (Exception e) {
            android.util.Log.w("EinkCal", "weather parse failed: " + e);
        }
        return out;
    }

    private static String extractVar(String body, String marker) {
        int at = body.indexOf(marker);
        if (at < 0) {
            return null;
        }
        int start = body.indexOf('{', at);
        if (start < 0) {
            return null;
        }
        int end = body.indexOf(';', start);
        if (end < 0) {
            end = body.length();
        }
        return body.substring(start, end).trim();
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return Integer.MIN_VALUE;
        }
    }

    /** weather.com.cn icon code -> Chinese condition. */
    private static String weatherName(String code) {
        if (code == null) {
            return "";
        }
        String c = code.trim();
        if (c.length() == 0) {
            return "";
        }
        if (c.equals("00")) return "晴";
        if (c.equals("01")) return "多云";
        if (c.equals("02")) return "阴";
        if (c.equals("03")) return "阵雨";
        if (c.equals("04")) return "雷阵雨";
        if (c.equals("05")) return "雷阵雨伴冰雹";
        if (c.equals("06")) return "雨夹雪";
        if (c.equals("07")) return "小雨";
        if (c.equals("08")) return "中雨";
        if (c.equals("09")) return "大雨";
        if (c.equals("10")) return "暴雨";
        if (c.equals("11")) return "大暴雨";
        if (c.equals("12")) return "特大暴雨";
        if (c.equals("13")) return "阵雪";
        if (c.equals("14")) return "小到中雪";
        if (c.equals("15")) return "中到大雪";
        if (c.equals("16")) return "大到暴雪";
        if (c.equals("17")) return "暴雪";
        if (c.equals("18")) return "雾";
        if (c.equals("19")) return "冻雨";
        if (c.equals("20")) return "沙尘暴";
        if (c.equals("21")) return "小到中雨";
        if (c.equals("22")) return "中到大雨";
        if (c.equals("23")) return "大到暴雨";
        if (c.equals("24")) return "暴雨到大暴雨";
        if (c.equals("25")) return "大暴雨到特大暴雨";
        if (c.equals("26")) return "小到中雪";
        if (c.equals("27")) return "中到大雪";
        if (c.equals("28")) return "大到暴雪";
        if (c.equals("29")) return "浮尘";
        if (c.equals("30")) return "扬沙";
        if (c.equals("31")) return "强沙尘暴";
        if (c.equals("53")) return "霾";
        return "";
    }

    // ------------------------------------------------------------------
    // Cache
    // ------------------------------------------------------------------

    private String buildCache(Parsed parsed) {
        try {
            JSONObject root = new JSONObject();
            root.put("current", parsed.current);
            root.put("temp", parsed.temp);
            JSONArray array = new JSONArray();
            for (Day day : parsed.days) {
                JSONObject item = new JSONObject();
                item.put("label", day.label);
                item.put("text", day.text);
                item.put("full", day.full);
                item.put("date", day.date);
                item.put("high", day.high);
                item.put("low", day.low);
                array.put(item);
            }
            root.put("days", array);
            return root.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private void parseCache(String json) {
        days.clear();
        currentText = "";
        currentTemp = Integer.MIN_VALUE;
        if (json == null) {
            return;
        }
        try {
            JSONObject root = new JSONObject(json);
            currentText = root.optString("current", "");
            currentTemp = root.optInt("temp", Integer.MIN_VALUE);
            JSONArray array = root.optJSONArray("days");
            for (int i = 0; array != null && i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                Day day = new Day();
                day.label = item.optString("label", "");
                day.text = item.optString("text", "");
                day.full = item.optString("full", "");
                day.date = item.optString("date", "");
                day.high = item.optInt("high", Integer.MIN_VALUE);
                day.low = item.optInt("low", Integer.MIN_VALUE);
                days.add(day);
            }
        } catch (Exception ignored) {
        }
    }

    private static void post(final Listener listener, final boolean ok, final String message) {
        if (listener == null) {
            return;
        }
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                listener.onWeather(ok, message);
            }
        });
    }
}
