package com.zbl.einkcalendar;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Offline Chinese calendar: lunar date, GanZhi, solar terms and festivals.
 * Lunar tables cover 1888-2100. Solar terms use apparent solar longitude.
 */
final class LunarCalendar {

    static final String[] MONTH_NAMES = {
            "正月", "二月", "三月", "四月", "五月", "六月",
            "七月", "八月", "九月", "十月", "冬月", "腊月"
    };

    static final String[] DAY_NAMES = {
            "初一", "初二", "初三", "初四", "初五", "初六", "初七", "初八", "初九", "初十",
            "十一", "十二", "十三", "十四", "十五", "十六", "十七", "十八", "十九", "二十",
            "廿一", "廿二", "廿三", "廿四", "廿五", "廿六", "廿七", "廿八", "廿九", "三十"
    };

    static final String[] GAN = {"甲", "乙", "丙", "丁", "戊", "己", "庚", "辛", "壬", "癸"};
    static final String[] ZHI = {"子", "丑", "寅", "卯", "辰", "巳", "午", "未", "申", "酉", "戌", "亥"};
    static final String[] ZODIAC = {"鼠", "牛", "虎", "兔", "龙", "蛇", "马", "羊", "猴", "鸡", "狗", "猪"};
    static final String[] WEEK_NAMES = {"一", "二", "三", "四", "五", "六", "日"};

    static final String[] TERM_NAMES = {
            "小寒", "大寒", "立春", "雨水", "惊蛰", "春分", "清明", "谷雨",
            "立夏", "小满", "芒种", "夏至", "小暑", "大暑", "立秋", "处暑",
            "白露", "秋分", "寒露", "霜降", "立冬", "小雪", "大雪", "冬至"
    };

    /** Approximate day of month for each term; used only as a Newton iteration seed. */
    private static final int[] TERM_SEED_DAY = {
            5, 20, 4, 19, 5, 20, 5, 20, 5, 21, 6, 21,
            7, 23, 7, 23, 7, 23, 8, 23, 7, 22, 7, 22
    };

    private static final Map<Integer, Map<Integer, Term>> TERM_CACHE =
            new HashMap<Integer, Map<Integer, Term>>();

    private LunarCalendar() {
    }

    static final class LunarDate {
        int year;
        int month;
        int day;
        boolean leap;

        String monthName() {
            return (leap ? "闰" : "") + MONTH_NAMES[month - 1];
        }

        String dayName() {
            return DAY_NAMES[day - 1];
        }

        String fullName() {
            return monthName() + dayName();
        }
    }

    static final class Term {
        String name;
        int year;
        int month;
        int day;
        int hour;
        int minute;

        String timeText() {
            return String.format(Locale.CHINA, "%d月%d日 %02d:%02d", month, day, hour, minute);
        }

        String shortDate() {
            return String.format(Locale.CHINA, "%d月%d日", month, day);
        }
    }

    // ------------------------------------------------------------------
    // Lunar conversion
    // ------------------------------------------------------------------

    private static int getBitInt(int data, int length, int shift) {
        return (data & (((1 << length) - 1) << shift)) >> shift;
    }

    private static long solarToInt(int y, int m, int d) {
        m = (m + 9) % 12;
        y = y - m / 10;
        return 365L * y + y / 4 - y / 100 + y / 400 + (m * 306 + 5) / 10 + (d - 1);
    }

    private static int[] solarFromInt(long g) {
        long y = (10000 * g + 14780) / 3652425;
        long ddd = g - (365 * y + y / 4 - y / 100 + y / 400);
        if (ddd < 0) {
            y--;
            ddd = g - (365 * y + y / 4 - y / 100 + y / 400);
        }
        long mi = (100 * ddd + 52) / 3060;
        long mm = (mi + 2) % 12 + 1;
        y = y + (mi + 2) / 12;
        long dd = ddd - (mi * 306 + 5) / 10 + 1;
        return new int[]{(int) y, (int) mm, (int) dd};
    }

    static LunarDate solarToLunar(int year, int month, int day) {
        int index = year - LunarData.BASE_YEAR;
        if (index < 1) {
            index = 1;
        }
        if (index >= LunarData.SOLAR_NEW_YEAR.length) {
            index = LunarData.SOLAR_NEW_YEAR.length - 1;
        }
        int data = (year << 9) | (month << 5) | day;
        if (LunarData.SOLAR_NEW_YEAR[index] > data) {
            index--;
        }
        int solar11 = LunarData.SOLAR_NEW_YEAR[index];
        int ly = getBitInt(solar11, 12, 9);
        int lm = getBitInt(solar11, 4, 5);
        int ld = getBitInt(solar11, 5, 0);
        long offset = solarToInt(year, month, day) - solarToInt(ly, lm, ld);

        int days = LunarData.LUNAR_MONTH_DAYS[index];
        int leap = getBitInt(days, 4, 13);
        int lunarYear = index + LunarData.BASE_YEAR;
        int lunarMonth = 1;
        long lunarDay;
        offset += 1;
        for (int i = 0; i < 13; i++) {
            int dm = getBitInt(days, 1, 12 - i) == 1 ? 30 : 29;
            if (offset > dm) {
                lunarMonth++;
                offset -= dm;
            } else {
                break;
            }
        }
        lunarDay = offset;

        boolean isLeap = false;
        if (leap != 0 && lunarMonth > leap) {
            lunarMonth--;
            if (lunarMonth == leap) {
                isLeap = true;
            }
        }

        LunarDate result = new LunarDate();
        result.year = lunarYear;
        result.month = lunarMonth;
        result.day = (int) lunarDay;
        result.leap = isLeap;
        return result;
    }

    static int[] lunarToSolar(int lunarYear, int lunarMonth, int lunarDay, boolean leap) {
        int index = lunarYear - LunarData.BASE_YEAR;
        if (index < 1 || index >= LunarData.LUNAR_MONTH_DAYS.length) {
            return null;
        }
        int days = LunarData.LUNAR_MONTH_DAYS[index];
        int leapMonth = getBitInt(days, 4, 13);
        int offset = 0;
        int loopEnd;
        if (!leap) {
            if (lunarMonth <= leapMonth || leapMonth == 0) {
                loopEnd = lunarMonth - 1;
            } else {
                loopEnd = lunarMonth;
            }
        } else {
            loopEnd = leapMonth;
        }
        for (int i = 0; i < loopEnd; i++) {
            offset += getBitInt(days, 1, 12 - i) == 1 ? 30 : 29;
        }
        offset += lunarDay;
        int solar11 = LunarData.SOLAR_NEW_YEAR[index];
        int y = getBitInt(solar11, 12, 9);
        int m = getBitInt(solar11, 4, 5);
        int d = getBitInt(solar11, 5, 0);
        return solarFromInt(solarToInt(y, m, d) + offset - 1);
    }

    // ------------------------------------------------------------------
    // GanZhi / zodiac
    // ------------------------------------------------------------------

    static String yearGanZhi(int lunarYear) {
        int g = (lunarYear - 4) % 10;
        int z = (lunarYear - 4) % 12;
        if (g < 0) {
            g += 10;
        }
        if (z < 0) {
            z += 12;
        }
        return GAN[g] + ZHI[z];
    }

    static String zodiac(int lunarYear) {
        int z = (lunarYear - 4) % 12;
        if (z < 0) {
            z += 12;
        }
        return ZODIAC[z];
    }

    static long julianDay(int year, int month, int day) {
        int a = (14 - month) / 12;
        int y = year + 4800 - a;
        int m = month + 12 * a - 3;
        return day + (153 * m + 2) / 5 + 365L * y + y / 4 - y / 100 + y / 400 - 32045;
    }

    static String dayGanZhi(int year, int month, int day) {
        long jdn = julianDay(year, month, day);
        int g = (int) ((jdn + 9) % 10);
        int z = (int) ((jdn + 1) % 12);
        return GAN[g] + ZHI[z];
    }

    // ------------------------------------------------------------------
    // Solar terms
    // ------------------------------------------------------------------

    private static double sunLongitude(double jd) {
        double t = (jd - 2451545.0) / 36525.0;
        double l0 = 280.46646 + 36000.76983 * t + 0.0003032 * t * t;
        double m = Math.toRadians(357.52911 + 35999.05029 * t - 0.0001537 * t * t);
        double c = (1.914602 - 0.004817 * t - 0.000014 * t * t) * Math.sin(m)
                + (0.019993 - 0.000101 * t) * Math.sin(2 * m)
                + 0.000289 * Math.sin(3 * m);
        double omega = Math.toRadians(125.04 - 1934.136 * t);
        double lambda = l0 + c - 0.00569 - 0.00478 * Math.sin(omega);
        lambda %= 360.0;
        if (lambda < 0) {
            lambda += 360.0;
        }
        return lambda;
    }

    private static double termJulianDay(int year, int index) {
        double target = (285.0 + 15.0 * index) % 360.0;
        int month = index / 2 + 1;
        int day = TERM_SEED_DAY[index];
        double jd = 2440587.5 + daysFromEpoch(year, month, day) + 0.5;
        for (int i = 0; i < 8; i++) {
            double diff = target - sunLongitude(jd);
            while (diff > 180) {
                diff -= 360;
            }
            while (diff < -180) {
                diff += 360;
            }
            jd += diff * 365.2422 / 360.0;
        }
        return jd;
    }

    private static long daysFromEpoch(int year, int month, int day) {
        long a = (14 - month) / 12;
        long y = year + 4800 - a;
        long m = month + 12 * a - 3;
        long jdn = day + (153 * m + 2) / 5 + 365L * y + y / 4 - y / 100 + y / 400 - 32045;
        return jdn - 2440588L;
    }

    static Map<Integer, Term> termsOfYear(int year) {
        Map<Integer, Term> cached = TERM_CACHE.get(year);
        if (cached != null) {
            return cached;
        }
        Map<Integer, Term> map = new HashMap<Integer, Term>();
        Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        for (int i = 0; i < 24; i++) {
            double jd = termJulianDay(year, i);
            long millis = Math.round((jd - 2440587.5) * 86400000.0) + 8L * 3600000L;
            utc.setTimeInMillis(millis);
            Term term = new Term();
            term.name = TERM_NAMES[i];
            term.year = utc.get(Calendar.YEAR);
            term.month = utc.get(Calendar.MONTH) + 1;
            term.day = utc.get(Calendar.DAY_OF_MONTH);
            term.hour = utc.get(Calendar.HOUR_OF_DAY);
            term.minute = utc.get(Calendar.MINUTE);
            map.put(term.month * 100 + term.day, term);
        }
        TERM_CACHE.put(year, map);
        return map;
    }

    static Term termOfDay(int year, int month, int day) {
        return termsOfYear(year).get(month * 100 + day);
    }

    // ------------------------------------------------------------------
    // Festivals
    // ------------------------------------------------------------------

    static String solarFestival(int month, int day) {
        switch (month * 100 + day) {
            case 101:
                return "元旦";
            case 214:
                return "情人节";
            case 308:
                return "妇女节";
            case 312:
                return "植树节";
            case 401:
                return "愚人节";
            case 501:
                return "劳动节";
            case 504:
                return "青年节";
            case 601:
                return "儿童节";
            case 701:
                return "建党节";
            case 801:
                return "建军节";
            case 910:
                return "教师节";
            case 1001:
                return "国庆节";
            case 1224:
                return "平安夜";
            case 1225:
                return "圣诞节";
            default:
                return null;
        }
    }

    static String lunarFestival(LunarDate lunar) {
        if (lunar.leap) {
            return null;
        }
        switch (lunar.month * 100 + lunar.day) {
            case 101:
                return "春节";
            case 115:
                return "元宵节";
            case 202:
                return "龙抬头";
            case 505:
                return "端午节";
            case 707:
                return "七夕";
            case 715:
                return "中元节";
            case 815:
                return "中秋节";
            case 909:
                return "重阳节";
            case 1208:
                return "腊八节";
            case 1223:
                return "小年";
            default:
                return null;
        }
    }

    static String weekFestival(int year, int month, int day) {
        Calendar cal = Calendar.getInstance();
        cal.set(year, month - 1, day, 12, 0, 0);
        int dow = cal.get(Calendar.DAY_OF_WEEK);
        if (month == 5 && dow == Calendar.SUNDAY && day >= 8 && day <= 14) {
            return "母亲节";
        }
        if (month == 6 && dow == Calendar.SUNDAY && day >= 15 && day <= 21) {
            return "父亲节";
        }
        if (month == 11 && dow == Calendar.THURSDAY && day >= 22 && day <= 28) {
            return "感恩节";
        }
        return null;
    }

    static boolean isChuXi(int year, int month, int day) {
        LunarDate lunar = solarToLunar(year, month, day);
        if (lunar.leap || lunar.month != 12) {
            return false;
        }
        int[] next = addDays(year, month, day, 1);
        LunarDate nextLunar = solarToLunar(next[0], next[1], next[2]);
        return !nextLunar.leap && nextLunar.month == 1 && nextLunar.day == 1;
    }

    /** All festival names for a day, most important first. */
    static List<String> festivalsOf(int year, int month, int day) {
        List<String> list = new ArrayList<String>();
        LunarDate lunar = solarToLunar(year, month, day);
        String lunarFestival = lunarFestival(lunar);
        if (lunarFestival != null) {
            list.add(lunarFestival);
        }
        if (isChuXi(year, month, day)) {
            list.add("除夕");
        }
        Term term = termOfDay(year, month, day);
        if (term != null) {
            list.add(term.name);
        }
        String solar = solarFestival(month, day);
        if (solar != null) {
            list.add(solar);
        }
        String week = weekFestival(year, month, day);
        if (week != null) {
            list.add(week);
        }
        return list;
    }

    // ------------------------------------------------------------------
    // Calendar helpers
    // ------------------------------------------------------------------

    static int dayOfWeekMondayFirst(int year, int month, int day) {
        Calendar cal = Calendar.getInstance();
        cal.set(year, month - 1, day, 12, 0, 0);
        return (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7;
    }

    static int isoWeek(int year, int month, int day) {
        Calendar cal = Calendar.getInstance();
        cal.setFirstDayOfWeek(Calendar.MONDAY);
        cal.setMinimalDaysInFirstWeek(4);
        cal.set(year, month - 1, day, 12, 0, 0);
        return cal.get(Calendar.WEEK_OF_YEAR);
    }

    static boolean isWeekend(int year, int month, int day) {
        int dow = dayOfWeekMondayFirst(year, month, day);
        return dow >= 5;
    }

    static int daysInMonth(int year, int month) {
        Calendar cal = Calendar.getInstance();
        cal.set(year, month - 1, 1, 12, 0, 0);
        return cal.getActualMaximum(Calendar.DAY_OF_MONTH);
    }

    static int[] addDays(int year, int month, int day, int delta) {
        Calendar cal = Calendar.getInstance();
        cal.set(year, month - 1, day, 12, 0, 0);
        cal.add(Calendar.DAY_OF_MONTH, delta);
        return new int[]{cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1,
                cal.get(Calendar.DAY_OF_MONTH)};
    }

    static String weekdayName(int year, int month, int day) {
        return "星期" + WEEK_NAMES[dayOfWeekMondayFirst(year, month, day)];
    }

    static String dateKey(int year, int month, int day) {
        return String.format(Locale.CHINA, "%04d-%02d-%02d", year, month, day);
    }
}
