package com.zbl.einkcalendar;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.TextUtils;

import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Draws the "poster" (月份牌) page in one of several layouts.
 *
 * The class is self contained so that both the dashboard and the style picker
 * can render it: the picker simply scales the canvas down to thumbnail size.
 * Coordinates are fractions of the canvas size so that any scale looks right.
 *
 * Typographic rules used by every layout:
 *   - every date and every time is set in bold;
 *   - no text smaller than 32px (at the 1072px reference side);
 *   - secondary text is a dark grey (#3A3A3A), never light grey;
 *   - blocks are spaced by at least 25% of the font size above/below them.
 *
 * Layouts 0-11 are portrait (1072x1448); layouts 12-15 are landscape
 * (1448x1072) and make the whole activity rotate.
 */
public class PosterRenderer {

    public static final int STYLE_COUNT = 16;

    /** First layout that is drawn in landscape orientation. */
    public static final int FIRST_LANDSCAPE = 12;

    public static final int REF_W = 1072;
    public static final int REF_H = 1448;
    public static final int REF_LAND_W = 1448;
    public static final int REF_LAND_H = 1072;

    public static boolean isLandscape(int style) {
        return style >= FIRST_LANDSCAPE && style < STYLE_COUNT;
    }

    private static final int INK = 0xFF000000;
    private static final int PAPER = 0xFFFFFFFF;
    private static final int GRAY = 0xFF3A3A3A;
    private static final int HAIR = 0xFF8F8F8F;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface regular = Typeface.DEFAULT;
    private final Typeface boldType = Typeface.DEFAULT_BOLD;

    // ---- content ------------------------------------------------------
    public String year = "2026";
    public String month = "10月";
    public String day = "20";
    public String weekday = "星期二";
    public String week = "第 43 周";
    public String lunar = "农历九月十一";
    public String lunarShort = "九月十一";
    public String ganzhi = "丙午年 · 马年 · 丁卯日";
    public String clock = "10:41";
    public String sync = "已校时";
    public String network = "已联网";
    public String termLabel = "下一个节气";
    public String termName = "霜降";
    public String termDate = "10月23日";
    public String termDays = "3天后";
    public String festival = "无";

    // week strip used by style 8
    public final int[] weekDates = new int[7];
    public int weekIndex = 2;

    private static final String[] WEEK_NAMES = {"一", "二", "三", "四", "五", "六", "日"};

    private float W, H, S;

    public static String styleName(int style) {
        switch (style) {
            case 0: return "01 时钟主角";
            case 1: return "02 日期主角";
            case 2: return "03 大钟横排";
            case 3: return "04 时钟信息栏";
            case 4: return "05 时钟页脚";
            case 5: return "06 三分栏";
            case 6: return "07 中号时钟";
            case 7: return "08 一周条";
            case 8: return "09 居中日期";
            case 9: return "10 左右双栏";
            case 10: return "11 三栏页脚";
            case 11: return "12 明细清单";
            case 12: return "13 横·边框钟";
            case 13: return "14 横·日期主角";
            case 14: return "15 横·时钟主角";
            case 15: return "16 横·双栏";
            default: return "01 时钟主角";
        }
    }

    /** Fills every field from the current date/time (used for previews). */
    public void fillToday() {
        long now = TimeSync.now();
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        int y = cal.get(Calendar.YEAR);
        int m = cal.get(Calendar.MONTH) + 1;
        int d = cal.get(Calendar.DAY_OF_MONTH);

        year = String.valueOf(y);
        month = m + "月";
        day = String.valueOf(d);
        weekday = LunarCalendar.weekdayName(y, m, d);
        week = "第 " + LunarCalendar.isoWeek(y, m, d) + " 周";
        clock = String.format(Locale.CHINA, "%02d:%02d",
                cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE));

        LunarCalendar.LunarDate ld = LunarCalendar.solarToLunar(y, m, d);
        lunarShort = ld.fullName();
        lunar = "农历" + lunarShort;
        ganzhi = LunarCalendar.yearGanZhi(ld.year) + "年 · "
                + LunarCalendar.zodiac(ld.year) + "年 · "
                + LunarCalendar.dayGanZhi(y, m, d) + "日";

        List<String> festivals = LunarCalendar.festivalsOf(y, m, d);
        festival = festivals.isEmpty() ? "无" : TextUtils.join(" · ", festivals);

        LunarCalendar.Term term = LunarCalendar.termOfDay(y, m, d);
        int days = 0;
        int[] cursor = {y, m, d};
        if (term == null) {
            for (int i = 1; i <= 40; i++) {
                cursor = LunarCalendar.addDays(cursor[0], cursor[1], cursor[2], 1);
                LunarCalendar.Term next =
                        LunarCalendar.termOfDay(cursor[0], cursor[1], cursor[2]);
                if (next != null) {
                    term = next;
                    days = i;
                    break;
                }
            }
        }
        termLabel = days == 0 ? "今日节气" : "下一个节气";
        termName = term == null ? "-" : term.name;
        termDate = term == null ? "" : term.shortDate();
        termDays = days == 0 ? "今天" : days + "天后";

        int offset = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7;   // Monday = 0
        weekIndex = offset;
        Calendar c2 = (Calendar) cal.clone();
        c2.add(Calendar.DAY_OF_MONTH, -offset);
        for (int i = 0; i < 7; i++) {
            weekDates[i] = c2.get(Calendar.DAY_OF_MONTH);
            c2.add(Calendar.DAY_OF_MONTH, 1);
        }
    }

    /**
     * Vertical band (fractions of height) that holds the big clock, used to
     * repaint only the clock when the minute changes.
     */
    public static void clockBand(int style, float[] out) {
        switch (style) {
            case 0: out[0] = 0.27f; out[1] = 0.47f; break;
            case 1: out[0] = 0.73f; out[1] = 0.87f; break;
            case 2: out[0] = 0.11f; out[1] = 0.32f; break;
            case 3: out[0] = 0.25f; out[1] = 0.46f; break;
            case 4: out[0] = 0.24f; out[1] = 0.45f; break;
            case 5: out[0] = 0.11f; out[1] = 0.31f; break;
            case 6: out[0] = 0.72f; out[1] = 0.86f; break;
            case 7: out[0] = 0.78f; out[1] = 0.92f; break;
            case 8: out[0] = 0.74f; out[1] = 0.88f; break;
            case 9: out[0] = 0.79f; out[1] = 0.92f; break;
            case 10: out[0] = 0.79f; out[1] = 0.85f; break;
            case 11: out[0] = 0.78f; out[1] = 0.92f; break;
            case 12: out[0] = 0.27f; out[1] = 0.58f; break;
            case 13: out[0] = 0.72f; out[1] = 0.83f; break;
            case 14: out[0] = 0.22f; out[1] = 0.58f; break;
            default: out[0] = 0.18f; out[1] = 0.35f; break;
        }
    }

    public void draw(Canvas canvas, int w, int h, int style) {
        W = w;
        H = h;
        S = Math.min(w, h) / 1072f;
        canvas.drawColor(PAPER);
        switch (style) {
            case 0: s01(canvas); break;
            case 1: s02(canvas); break;
            case 2: s03(canvas); break;
            case 3: s04(canvas); break;
            case 4: s05(canvas); break;
            case 5: s06(canvas); break;
            case 6: s07(canvas); break;
            case 7: s08(canvas); break;
            case 8: s09(canvas); break;
            case 9: s10(canvas); break;
            case 10: s11(canvas); break;
            case 11: s12(canvas); break;
            case 12: l01(canvas); break;
            case 13: l02(canvas); break;
            case 14: l03(canvas); break;
            default: l04(canvas); break;
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private void t(Canvas c, String s, float fx, float fy, float sizePx,
                   int color, Typeface tf, Paint.Align align) {
        paint.setTypeface(tf);
        paint.setTextSize(sizePx * S);
        paint.setColor(color);
        paint.setTextAlign(align);
        c.drawText(s, fx * W, fy * H, paint);
    }

    private void left(Canvas c, String s, float fx, float fy, float px, int color, boolean bold) {
        t(c, s, fx, fy, px, color, bold ? boldType : regular, Paint.Align.LEFT);
    }

    private void right(Canvas c, String s, float fx, float fy, float px, int color, boolean bold) {
        t(c, s, fx, fy, px, color, bold ? boldType : regular, Paint.Align.RIGHT);
    }

    private void center(Canvas c, String s, float fx, float fy, float px, int color, boolean bold) {
        t(c, s, fx, fy, px, color, bold ? boldType : regular, Paint.Align.CENTER);
    }

    private void hr(Canvas c, float fx1, float fx2, float fy, float widthPx) {
        hr(c, fx1, fx2, fy, widthPx, INK);
    }

    private void hr(Canvas c, float fx1, float fx2, float fy, float widthPx, int color) {
        line.setColor(color);
        line.setStrokeWidth(widthPx * S);
        c.drawLine(fx1 * W, fy * H, fx2 * W, fy * H, line);
    }

    private void vr(Canvas c, float fx, float fy1, float fy2, float widthPx) {
        vr(c, fx, fy1, fy2, widthPx, INK);
    }

    private void vr(Canvas c, float fx, float fy1, float fy2, float widthPx, int color) {
        line.setColor(color);
        line.setStrokeWidth(widthPx * S);
        c.drawLine(fx * W, fy1 * H, fx * W, fy2 * H, line);
    }

    private void menu(Canvas c) {
        menu(c, 0.945f, 0.043f);
    }

    private void menu(Canvas c, float fx, float fy) {
        float mx = fx * W;
        float my = fy * H;
        line.setColor(INK);
        line.setStrokeWidth(5f * S);
        float half = 22f * S;
        for (int i = -1; i <= 1; i++) {
            float y = my + i * 13f * S;
            c.drawLine(mx - half, y, mx + half, y, line);
        }
    }

    /** Draws a text run and returns the x it ends at (same baseline). */
    private float leftThen(Canvas c, String s, float fx, float fy, float px, int color,
                           boolean bold) {
        t(c, s, fx, fy, px, color, bold ? boldType : regular, Paint.Align.LEFT);
        paint.setTypeface(bold ? boldType : regular);
        paint.setTextSize(px * S);
        return fx * W + paint.measureText(s);
    }

    // ------------------------------------------------------------------
    // 01 时钟主角 —— 大钟居中偏上，日期与农历在下方左右分栏
    // ------------------------------------------------------------------
    private void s01(Canvas c) {
        left(c, year, 0.05f, 0.050f, 34, GRAY, true);
        menu(c);

        left(c, month, 0.05f, 0.150f, 104, INK, true);
        right(c, weekday, 0.95f, 0.108f, 44, INK, true);
        right(c, week, 0.95f, 0.152f, 32, GRAY, false);

        left(c, clock, 0.05f, 0.455f, 340, INK, true);

        hr(c, 0.05f, 0.95f, 0.560f, 3f);

        left(c, day, 0.05f, 0.750f, 200, INK, true);
        vr(c, 0.268f, 0.632f, 0.766f, 2f);
        left(c, lunar, 0.305f, 0.685f, 48, INK, true);
        left(c, ganzhi, 0.305f, 0.735f, 32, GRAY, false);

        hr(c, 0.05f, 0.95f, 0.805f, 1f, HAIR);
        left(c, termLabel, 0.05f, 0.862f, 32, GRAY, false);
        left(c, termName + "  " + termDate + "  " + termDays, 0.245f, 0.862f, 32, INK, true);
        left(c, "今日节日", 0.05f, 0.915f, 32, GRAY, false);
        left(c, festival, 0.245f, 0.915f, 32, INK, true);
        right(c, sync + " · " + network, 0.95f, 0.915f, 32, GRAY, false);
    }

    // ------------------------------------------------------------------
    // 02 日期主角 —— 巨号日期居中，时间在下方
    // ------------------------------------------------------------------
    private void s02(Canvas c) {
        left(c, year, 0.05f, 0.050f, 34, GRAY, true);
        menu(c);

        left(c, month, 0.05f, 0.140f, 96, INK, true);
        right(c, weekday, 0.95f, 0.105f, 42, INK, true);
        right(c, week, 0.95f, 0.152f, 32, GRAY, false);

        center(c, day, 0.5f, 0.500f, 520, INK, true);

        center(c, lunar, 0.5f, 0.590f, 46, INK, true);
        center(c, ganzhi, 0.5f, 0.640f, 32, GRAY, false);

        hr(c, 0.16f, 0.84f, 0.700f, 2f);
        center(c, clock, 0.5f, 0.855f, 220, INK, true);
        center(c, sync + " · " + network, 0.5f, 0.922f, 32, GRAY, false);
    }

    // ------------------------------------------------------------------
    // 03 大钟横排 —— 巨钟在上，日期与星期横向排列
    // ------------------------------------------------------------------
    private void s03(Canvas c) {
        left(c, year + " · " + month, 0.05f, 0.050f, 34, GRAY, true);
        menu(c);

        center(c, clock, 0.5f, 0.300f, 350, INK, true);

        hr(c, 0.05f, 0.95f, 0.392f, 3f);

        left(c, day, 0.05f, 0.560f, 200, INK, true);
        vr(c, 0.270f, 0.452f, 0.575f, 2f);
        left(c, weekday, 0.315f, 0.515f, 42, INK, true);
        left(c, week, 0.315f, 0.565f, 32, GRAY, false);

        hr(c, 0.05f, 0.95f, 0.635f, 2f);

        left(c, lunar, 0.05f, 0.720f, 44, INK, true);
        left(c, ganzhi, 0.05f, 0.772f, 32, GRAY, false);

        vr(c, 0.620f, 0.668f, 0.868f, 1f, HAIR);
        left(c, termLabel, 0.660f, 0.690f, 32, GRAY, false);
        left(c, termName, 0.660f, 0.752f, 36, INK, true);
        left(c, termDate, 0.660f, 0.806f, 32, INK, true);
        left(c, termDays, 0.660f, 0.855f, 32, GRAY, false);

        hr(c, 0.05f, 0.95f, 0.890f, 1f, HAIR);
        left(c, "今日节日  " + festival, 0.05f, 0.930f, 32, GRAY, false);
        right(c, sync + " · " + network, 0.95f, 0.930f, 32, GRAY, false);
    }

    // ------------------------------------------------------------------
    // 04 时钟信息栏 —— 巨钟 + 右侧农历信息栏
    // ------------------------------------------------------------------
    private void s04(Canvas c) {
        left(c, year, 0.05f, 0.050f, 34, GRAY, true);
        left(c, month, 0.165f, 0.080f, 78, INK, true);
        menu(c);

        left(c, weekday, 0.05f, 0.150f, 42, INK, true);
        right(c, week, 0.95f, 0.196f, 32, GRAY, false);

        center(c, clock, 0.5f, 0.440f, 340, INK, true);

        hr(c, 0.05f, 0.95f, 0.512f, 3f);

        left(c, day, 0.05f, 0.720f, 210, INK, true);
        vr(c, 0.288f, 0.612f, 0.736f, 2f);
        left(c, lunar, 0.322f, 0.656f, 44, INK, true);
        left(c, ganzhi, 0.322f, 0.708f, 32, GRAY, false);

        hr(c, 0.05f, 0.95f, 0.788f, 1f, HAIR);
        left(c, "今日节日", 0.05f, 0.845f, 32, GRAY, false);
        left(c, festival, 0.300f, 0.845f, 32, INK, true);
        left(c, termLabel, 0.05f, 0.905f, 32, GRAY, false);
        left(c, termName + "  " + termDate + "  " + termDays, 0.300f, 0.905f, 32, INK, true);
    }

    // ------------------------------------------------------------------
    // 05 时钟页脚 —— 钟在上，日期居中，信息页脚贴底
    // ------------------------------------------------------------------
    private void s05(Canvas c) {
        left(c, year, 0.05f, 0.050f, 34, GRAY, true);
        menu(c);
        left(c, month, 0.05f, 0.140f, 92, INK, true);
        right(c, weekday, 0.95f, 0.105f, 42, INK, true);
        right(c, week, 0.95f, 0.152f, 32, GRAY, false);

        center(c, clock, 0.5f, 0.430f, 340, INK, true);

        hr(c, 0.05f, 0.95f, 0.508f, 3f);

        left(c, day, 0.05f, 0.690f, 200, INK, true);
        vr(c, 0.274f, 0.585f, 0.706f, 2f);
        left(c, lunar, 0.310f, 0.630f, 44, INK, true);
        left(c, ganzhi, 0.310f, 0.682f, 32, GRAY, false);

        hr(c, 0.05f, 0.95f, 0.758f, 2f);

        left(c, "今日节日", 0.05f, 0.842f, 32, GRAY, false);
        left(c, festival, 0.200f, 0.842f, 32, INK, true);
        right(c, sync + " · " + network, 0.95f, 0.842f, 32, GRAY, false);

        left(c, termLabel, 0.05f, 0.910f, 32, GRAY, false);
        left(c, termName, 0.285f, 0.910f, 32, INK, true);
        left(c, termDate, 0.435f, 0.910f, 32, INK, true);
        right(c, "(" + termDays + ")", 0.95f, 0.910f, 32, GRAY, false);
    }

    // ------------------------------------------------------------------
    // 06 三分栏 —— 20 / 星期二 / 第 43 周 三栏，下接农历与节气
    // ------------------------------------------------------------------
    private void s06(Canvas c) {
        left(c, year + " · " + month, 0.05f, 0.050f, 34, GRAY, true);
        menu(c);

        center(c, clock, 0.5f, 0.295f, 340, INK, true);

        hr(c, 0.05f, 0.95f, 0.385f, 3f);

        left(c, day, 0.05f, 0.545f, 200, INK, true);
        vr(c, 0.274f, 0.437f, 0.560f, 2f);
        left(c, weekday, 0.312f, 0.500f, 42, INK, true);
        vr(c, 0.560f, 0.437f, 0.560f, 2f);
        left(c, week, 0.600f, 0.500f, 40, INK, true);

        hr(c, 0.05f, 0.95f, 0.618f, 2f);

        left(c, lunar, 0.05f, 0.722f, 44, INK, true);
        left(c, ganzhi, 0.05f, 0.774f, 32, GRAY, false);

        vr(c, 0.610f, 0.668f, 0.872f, 1f, HAIR);
        left(c, termLabel, 0.650f, 0.680f, 32, GRAY, false);
        left(c, termName, 0.650f, 0.738f, 36, INK, true);
        left(c, termDate, 0.650f, 0.788f, 32, INK, true);
        left(c, termDays, 0.650f, 0.836f, 32, GRAY, false);

        hr(c, 0.05f, 0.95f, 0.878f, 1f, HAIR);
        left(c, "今日节日  " + festival, 0.05f, 0.916f, 32, GRAY, false);
        right(c, sync + " · " + network, 0.95f, 0.916f, 32, GRAY, false);
    }

    // ------------------------------------------------------------------
    // 07 中号时钟 —— 日期为主，中号时钟在下
    // ------------------------------------------------------------------
    private void s07(Canvas c) {
        left(c, year, 0.05f, 0.050f, 34, GRAY, true);
        menu(c);
        left(c, month, 0.05f, 0.140f, 96, INK, true);
        right(c, weekday, 0.95f, 0.105f, 42, INK, true);
        right(c, week, 0.95f, 0.152f, 32, GRAY, false);

        center(c, day, 0.5f, 0.500f, 480, INK, true);

        center(c, lunar, 0.5f, 0.596f, 46, INK, true);
        center(c, ganzhi, 0.5f, 0.646f, 32, GRAY, false);

        hr(c, 0.16f, 0.84f, 0.702f, 2f);
        center(c, clock, 0.5f, 0.850f, 210, INK, true);
        center(c, sync + " · " + network, 0.5f, 0.918f, 32, GRAY, false);
    }

    // ------------------------------------------------------------------
    // 08 一周条 —— 顶部七日历条，下方日期与时间
    // ------------------------------------------------------------------
    private void s08(Canvas c) {
        left(c, month, 0.05f, 0.095f, 92, INK, true);
        left(c, year, 0.260f, 0.095f, 34, GRAY, true);
        menu(c);

        float startX = 0.080f;
        float step = 0.1410f;
        for (int i = 0; i < 7; i++) {
            float x = startX + step * i;
            center(c, WEEK_NAMES[i], x, 0.168f, 32, GRAY, false);
            if (i == weekIndex) {
                fill.setColor(INK);
                c.drawCircle(x * W, 0.214f * H, 28f * S, fill);
                center(c, String.valueOf(weekDates[i]), x, 0.224f, 32, PAPER, true);
            } else {
                center(c, String.valueOf(weekDates[i]), x, 0.224f, 32, INK, true);
            }
        }

        hr(c, 0.05f, 0.95f, 0.262f, 2f);

        left(c, weekday, 0.05f, 0.330f, 42, INK, true);
        right(c, week, 0.95f, 0.326f, 32, GRAY, false);

        left(c, day, 0.05f, 0.560f, 380, INK, true);

        left(c, lunar, 0.05f, 0.640f, 46, INK, true);
        left(c, ganzhi, 0.05f, 0.694f, 32, GRAY, false);

        hr(c, 0.05f, 0.95f, 0.740f, 3f);

        left(c, clock, 0.05f, 0.900f, 200, INK, true);
        vr(c, 0.585f, 0.800f, 0.925f, 1f, HAIR);
        left(c, sync, 0.625f, 0.848f, 32, GRAY, false);
        left(c, network, 0.625f, 0.905f, 32, GRAY, false);
    }

    // ------------------------------------------------------------------
    // 09 居中日期 —— 日期居中，两侧短横线
    // ------------------------------------------------------------------
    private void s09(Canvas c) {
        left(c, year, 0.05f, 0.050f, 34, GRAY, true);
        left(c, month, 0.05f, 0.140f, 90, INK, true);
        right(c, week, 0.95f, 0.090f, 32, GRAY, false);
        right(c, weekday, 0.95f, 0.140f, 42, INK, true);
        menu(c);

        hr(c, 0.05f, 0.95f, 0.178f, 2f);

        center(c, day, 0.5f, 0.480f, 440, INK, true);
        hr(c, 0.080f, 0.235f, 0.368f, 2f);
        hr(c, 0.765f, 0.920f, 0.368f, 2f);

        center(c, lunar, 0.5f, 0.578f, 46, INK, true);
        center(c, ganzhi, 0.5f, 0.628f, 32, GRAY, false);

        hr(c, 0.16f, 0.84f, 0.692f, 2f);
        center(c, clock, 0.5f, 0.855f, 200, INK, true);
        center(c, sync + " · " + network, 0.5f, 0.922f, 32, GRAY, false);
    }

    // ------------------------------------------------------------------
    // 10 左右双栏 —— 竖线分栏，左日期钟，右星期节气
    // ------------------------------------------------------------------
    private void s10(Canvas c) {
        vr(c, 0.600f, 0.050f, 0.950f, 1f, HAIR);

        left(c, year, 0.05f, 0.050f, 32, GRAY, true);
        left(c, month, 0.045f, 0.112f, 84, INK, true);
        hr(c, 0.045f, 0.235f, 0.130f, 2f);

        left(c, weekday, 0.650f, 0.095f, 42, INK, true);
        left(c, week, 0.650f, 0.143f, 32, GRAY, false);
        hr(c, 0.640f, 0.950f, 0.215f, 1f, HAIR);

        left(c, day, 0.045f, 0.455f, 400, INK, true);

        left(c, lunar, 0.045f, 0.572f, 42, INK, true);
        left(c, ganzhi, 0.045f, 0.624f, 32, GRAY, false);

        hr(c, 0.045f, 0.550f, 0.700f, 3f);

        left(c, clock, 0.045f, 0.900f, 190, INK, true);

        left(c, termLabel, 0.650f, 0.300f, 32, GRAY, false);
        left(c, termName, 0.650f, 0.360f, 38, INK, true);
        left(c, termDate, 0.650f, 0.412f, 32, INK, true);
        left(c, termDays, 0.650f, 0.460f, 32, GRAY, false);

        hr(c, 0.640f, 0.950f, 0.530f, 1f, HAIR);
        left(c, "今日节日", 0.650f, 0.610f, 32, GRAY, false);
        left(c, festival, 0.650f, 0.670f, 32, INK, true);

        hr(c, 0.640f, 0.950f, 0.740f, 1f, HAIR);
        left(c, sync, 0.650f, 0.830f, 32, GRAY, false);
        left(c, network, 0.650f, 0.885f, 32, GRAY, false);
    }

    // ------------------------------------------------------------------
    // 11 三栏页脚 —— 日期居中，底部时间 / 节日 / 节气 三栏
    // ------------------------------------------------------------------
    private void s11(Canvas c) {
        left(c, month, 0.05f, 0.105f, 96, INK, true);
        left(c, year, 0.250f, 0.105f, 34, GRAY, true);
        right(c, weekday, 0.95f, 0.105f, 42, INK, true);
        menu(c);

        center(c, day, 0.5f, 0.440f, 480, INK, true);
        center(c, lunar, 0.5f, 0.535f, 44, INK, true);
        center(c, ganzhi, 0.5f, 0.583f, 32, GRAY, false);

        hr(c, 0.05f, 0.95f, 0.640f, 2f);

        vr(c, 0.335f, 0.700f, 0.940f, 1f, HAIR);
        vr(c, 0.665f, 0.700f, 0.940f, 1f, HAIR);

        left(c, "时间", 0.05f, 0.755f, 32, GRAY, false);
        left(c, clock, 0.05f, 0.848f, 70, INK, true);
        left(c, sync + " · " + network, 0.05f, 0.915f, 32, GRAY, false);

        left(c, "今日节日", 0.375f, 0.755f, 32, GRAY, false);
        left(c, festival, 0.375f, 0.848f, 46, INK, true);

        left(c, termLabel, 0.705f, 0.755f, 32, GRAY, false);
        left(c, termName + " · " + termDays, 0.705f, 0.843f, 40, INK, true);
        left(c, termDate, 0.705f, 0.915f, 32, INK, true);
    }

    // ------------------------------------------------------------------
    // 12 明细清单 —— 日期在上，信息逐行列表
    // ------------------------------------------------------------------
    private void s12(Canvas c) {
        left(c, year + " · " + month, 0.05f, 0.050f, 34, GRAY, true);
        menu(c);

        left(c, day, 0.05f, 0.250f, 250, INK, true);
        right(c, weekday, 0.95f, 0.170f, 42, INK, true);
        right(c, week, 0.95f, 0.218f, 32, GRAY, false);

        hr(c, 0.05f, 0.95f, 0.290f, 2f);

        left(c, "农历", 0.05f, 0.380f, 32, GRAY, false);
        left(c, lunarShort, 0.270f, 0.380f, 32, INK, true);
        hr(c, 0.05f, 0.95f, 0.420f, 1f, HAIR);

        left(c, "干支", 0.05f, 0.465f, 32, GRAY, false);
        left(c, ganzhi, 0.270f, 0.465f, 32, INK, true);
        hr(c, 0.05f, 0.95f, 0.505f, 1f, HAIR);

        left(c, "今日节日", 0.05f, 0.550f, 32, GRAY, false);
        left(c, festival, 0.270f, 0.550f, 32, INK, true);
        hr(c, 0.05f, 0.95f, 0.590f, 1f, HAIR);

        left(c, termLabel, 0.05f, 0.635f, 32, GRAY, false);
        left(c, termName, 0.270f, 0.635f, 32, INK, true);
        left(c, termDate + " (" + termDays + ")", 0.470f, 0.635f, 32, INK, false);

        hr(c, 0.05f, 0.95f, 0.675f, 2f);

        left(c, clock, 0.05f, 0.905f, 220, INK, true);
        vr(c, 0.600f, 0.765f, 0.945f, 1f, HAIR);
        left(c, sync, 0.650f, 0.855f, 32, GRAY, false);
        left(c, network, 0.650f, 0.915f, 32, GRAY, false);
    }

    // ------------------------------------------------------------------
    // Landscape layouts. The canvas is 1448x1072 and S == 1, so the numbers
    // below are pixels at that reference.
    // ------------------------------------------------------------------

    private void rect(Canvas c, float fx1, float fy1, float fx2, float fy2) {
        Paint.Style old = line.getStyle();
        line.setStyle(Paint.Style.STROKE);
        c.drawRect(fx1 * W, fy1 * H, fx2 * W, fy2 * H, line);
        line.setStyle(old);
    }

    /** "10.20" style date built from the month/day fields. */
    private String monthDot() {
        String m = month.replace("月", "");
        String d = day.length() < 2 ? "0" + day : day;
        return m + "." + d;
    }

    // 13 横·边框钟 —— 细边框卡片，超大时钟居中，底栏 日期 | 农历 | 节气
    private void l01(Canvas c) {
        line.setColor(INK);
        line.setStrokeWidth(2.5f * S);
        rect(c, 0.020f, 0.020f, 0.980f, 0.980f);
        line.setColor(HAIR);
        line.setStrokeWidth(1f * S);
        rect(c, 0.032f, 0.032f, 0.968f, 0.968f);

        left(c, year, 0.05f, 0.105f, 34, GRAY, true);
        left(c, month, 0.05f, 0.210f, 84, INK, true);
        right(c, weekday, 0.895f, 0.109f, 44, INK, true);
        right(c, week, 0.895f, 0.168f, 32, GRAY, false);
        menu(c, 0.935f, 0.058f);

        hr(c, 0.05f, 0.95f, 0.265f, 3f);

        center(c, clock, 0.5f, 0.587f, 370, INK, true);

        hr(c, 0.05f, 0.95f, 0.655f, 3f);

        left(c, monthDot(), 0.05f, 0.828f, 64, INK, true);
        vr(c, 0.250f, 0.730f, 0.888f, 2f);
        left(c, lunar, 0.295f, 0.803f, 42, INK, true);
        left(c, ganzhi, 0.295f, 0.868f, 32, GRAY, false);
        vr(c, 0.660f, 0.730f, 0.888f, 2f);
        left(c, termName, 0.700f, 0.803f, 44, INK, true);
        left(c, termDate, 0.700f, 0.868f, 32, GRAY, false);
    }

    // 14 横·日期主角 —— 顶部细线，超大日期居中，底栏 时间 | 农历 | 节气
    private void l02(Canvas c) {
        left(c, year + " 年 " + month, 0.05f, 0.078f, 36, INK, true);
        hr(c, 0.265f, 0.685f, 0.060f, 2f);
        right(c, weekday, 0.895f, 0.082f, 44, INK, true);
        right(c, week, 0.895f, 0.142f, 32, GRAY, false);
        menu(c, 0.935f, 0.058f);

        center(c, monthDot(), 0.5f, 0.590f, 450, INK, true);

        hr(c, 0.05f, 0.95f, 0.660f, 3f);

        left(c, clock, 0.05f, 0.815f, 80, INK, true);
        vr(c, 0.240f, 0.745f, 0.885f, 2f);
        left(c, lunar, 0.290f, 0.790f, 40, INK, true);
        left(c, ganzhi, 0.290f, 0.855f, 32, GRAY, false);
        vr(c, 0.660f, 0.745f, 0.885f, 2f);
        left(c, termName, 0.700f, 0.790f, 40, INK, true);
        left(c, termDate, 0.700f, 0.855f, 32, GRAY, false);
    }

    // 15 横·时钟主角 —— 与 14 同骨架，但时钟是主角、日期退到底栏
    private void l03(Canvas c) {
        left(c, year + " 年 " + month, 0.05f, 0.078f, 36, INK, true);
        hr(c, 0.265f, 0.685f, 0.060f, 2f);
        right(c, weekday, 0.895f, 0.082f, 44, INK, true);
        right(c, week, 0.895f, 0.142f, 32, GRAY, false);
        menu(c, 0.935f, 0.058f);

        center(c, clock, 0.5f, 0.590f, 450, INK, true);

        hr(c, 0.05f, 0.95f, 0.660f, 3f);

        left(c, monthDot(), 0.05f, 0.815f, 80, INK, true);
        vr(c, 0.240f, 0.745f, 0.885f, 2f);
        left(c, lunar, 0.290f, 0.790f, 40, INK, true);
        left(c, ganzhi, 0.290f, 0.855f, 32, GRAY, false);
        vr(c, 0.660f, 0.745f, 0.885f, 2f);
        left(c, termName, 0.700f, 0.790f, 40, INK, true);
        left(c, termDate, 0.700f, 0.855f, 32, GRAY, false);
    }

    // 16 横·双栏 —— 左：大日期 + 农历；右：时钟 / 节气 / 节日 / 状态 细线分区
    private void l04(Canvas c) {
        left(c, year + " 年 " + month, 0.05f, 0.078f, 36, INK, true);
        hr(c, 0.285f, 0.760f, 0.060f, 2f);
        right(c, weekday, 0.880f, 0.082f, 44, INK, true);
        right(c, week, 0.880f, 0.142f, 32, GRAY, false);
        menu(c, 0.935f, 0.058f);

        vr(c, 0.545f, 0.115f, 0.930f, 2f);

        float end = leftThen(c, day, 0.05f, 0.560f, 420, INK, true);
        left(c, "日", end / W + 0.006f, 0.560f, 110, INK, true);
        left(c, lunar, 0.05f, 0.720f, 48, INK, true);
        left(c, ganzhi, 0.05f, 0.800f, 34, GRAY, false);

        left(c, clock, 0.585f, 0.340f, 170, INK, true);
        hr(c, 0.585f, 0.945f, 0.400f, 2f);
        left(c, termLabel, 0.585f, 0.462f, 32, GRAY, false);
        left(c, termName, 0.585f, 0.522f, 50, INK, true);
        left(c, termDate, 0.585f, 0.592f, 34, INK, false);
        hr(c, 0.585f, 0.945f, 0.650f, 2f);
        left(c, "今日节日  " + festival, 0.585f, 0.760f, 34, INK, true);
        hr(c, 0.585f, 0.945f, 0.820f, 1f, HAIR);
        left(c, sync + " · " + network, 0.585f, 0.900f, 34, GRAY, false);
    }
}
