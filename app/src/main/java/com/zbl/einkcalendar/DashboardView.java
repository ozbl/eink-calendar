package com.zbl.einkcalendar;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Full-screen, high-contrast dashboard for an e-ink display: header with clock
 * and lunar date, a month grid, and a detail strip. Everything is drawn with
 * plain black ink on white, no animation, no gradients.
 */
final class DashboardView extends View {

    interface Listener {
        void onSettingsRequested();

        void onManualSyncRequested();

        /** Called after a horizontal swipe picks another poster layout. */
        void onPosterStyleChanged(int style);
    }

    private static final int INK = 0xFF000000;
    private static final int PAPER = 0xFFFFFFFF;
    private static final int GRAY = 0xFF666666;
    private static final int LIGHT = 0xFFAAAAAA;
    private static final int GRID = 0xFFCFCFCF;
    private static final int SUBTLE = 0xFF333333;
    private static final int HAIR_LINE = 0xFF8F8F8F;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint boldPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint measurePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint();
    private final Paint fillPaint = new Paint();
    private final Typeface lightTypeface =
            Typeface.create("sans-serif-light", Typeface.NORMAL);

    /**
     * Two full-screen frames (black, then white) before the content is drawn.
     * A 100% screen change is the only way an app can nudge the EPDC into a
     * "GC16" full refresh; this device's driver ignores it, but the sequence
     * costs only ~360 ms and is harmless.
     */
    private static final int FLASH_STEPS = 2;
    private static final int FLASH_STEP_MS = 180;
    private boolean flashing;
    private int flashStep;
    private boolean flashEnabled = true;

    private final Listener listener;
    private final HolidayStore holidays;

    private MonthModel model;

    private int todayYear;
    private int todayMonth;
    private int todayDay;

    private int viewYear;
    private int viewMonth;

    private int selYear;
    private int selMonth;
    private int selDay;

    private long nowMillis;

    private String networkStatus = "离线";
    private String syncStatus = "本机时间";

    private int batteryPercent = -1;
    private boolean batteryCharging = false;

    static final int PAGE_POSTER = 0;
    static final int PAGE_CALENDAR = 1;

    private int page = PAGE_POSTER;

    // Poster page content.
    private final PosterRenderer poster = new PosterRenderer();
    private int posterStyle = 0;

    // Header content (always today).
    private String hMonthYear = "";
    private String hBigDay = "";
    private String hWeekday = "";
    private String hGanzhi = "";
    private String hLunar = "";
    private String hExtra = "";
    private String hClock = "";
    private String hClockSub = "";

    // Detail content (selected day).
    private String dTitle = "";
    private String dLunarGanzhi = "";
    private String dTerm = "";
    private String dFestival = "";
    private String dHoliday = "";
    private final List<String> upcoming = new ArrayList<String>();

    // Geometry.
    private float margin;
    private float headerBottom;
    private float monthBarBottom;
    private float weekdayBottom;
    private float gridBottom;
    private float cellWidth;
    private float cellHeight;
    private float gridLeft;
    private float gridRight;
    private float gridTop;
    private boolean landscape;
    private float cellPad;
    private float cellDaySize;
    private float cellDayBase;
    private float cellSubSize;
    private float cellSubBase;
    private float badgeW;
    private float badgeH;

    private final RectF clockRect = new RectF();
    private final RectF prevArrow = new RectF();
    private final RectF nextArrow = new RectF();
    private final RectF todayButton = new RectF();
    private final RectF menuButton = new RectF();
    private final RectF posterClockRect = new RectF();
    private final RectF posterMenuButton = new RectF();
    private final RectF posterHintRect = new RectF();
    private final RectF calendarHintRect = new RectF();

    private float downX;
    private float downY;
    private boolean gestureHandled;

    DashboardView(Context context, Listener listener, HolidayStore holidays) {
        super(context);
        this.listener = listener;
        this.holidays = holidays;
        paint.setColor(INK);
        boldPaint.setColor(INK);
        boldPaint.setTypeface(Typeface.DEFAULT_BOLD);
        fillPaint.setStyle(Paint.Style.FILL);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setColor(INK);
        setBackgroundColor(PAPER);
        nowMillis = TimeSync.now();
        readToday();
        selYear = todayYear;
        selMonth = todayMonth;
        selDay = todayDay;
        viewYear = todayYear;
        viewMonth = todayMonth;
        model = new MonthModel(viewYear, viewMonth, holidays);
        refreshFlags();
        recomputeHeader();
        recomputeDetail();
        recomputeUpcoming();
    }

    // ------------------------------------------------------------------
    // Public state updates
    // ------------------------------------------------------------------

    void setNetworkStatus(String status) {
        this.networkStatus = status;
        invalidate();
    }

    void setSyncStatus(String status) {
        this.syncStatus = status;
        invalidate();
    }

    /** Battery level shown on every layout; pass a negative percent to hide it. */
    void setBattery(int percent, boolean charging) {
        if (batteryPercent == percent && batteryCharging == charging) {
            return;
        }
        batteryPercent = percent;
        batteryCharging = charging;
        invalidate();
    }

    int page() {
        return page;
    }

    /**
     * Forces a clean full refresh. The panel driver only performs a "GC" flash
     * when the whole screen changes, so we alternate solid black / white frames
     * and then repaint the content. This is the only lever available on this
     * device: the EPDC sysfs node is root-only and there is no vendor API.
     */
    void fullRefresh() {
        if (flashing) {
            return;
        }
        flashStep = FLASH_STEPS;
        flashing = true;
        invalidate();
        postDelayed(flashTick, FLASH_STEP_MS);
    }

    private final Runnable flashTick = new Runnable() {
        @Override
        public void run() {
            flashStep--;
            if (flashStep <= 0) {
                flashing = false;
                invalidate();
                return;
            }
            invalidate();
            postDelayed(this, FLASH_STEP_MS);
        }
    };

    /**
     * Refresh on a user action. Honours the "flash on every change" preference;
     * when it is off the view simply repaints.
     */
    void softRefresh() {
        if (flashEnabled) {
            fullRefresh();
        } else {
            invalidate();
        }
    }

    void setFlashEnabled(boolean enabled) {
        flashEnabled = enabled;
    }

    int posterStyle() {
        return posterStyle;
    }

    /** Selects one of the {@link PosterRenderer#STYLE_COUNT} poster layouts. */
    void setPosterStyle(int style) {
        if (style < 0 || style >= PosterRenderer.STYLE_COUNT) {
            style = 0;
        }
        if (style == posterStyle) {
            return;
        }
        posterStyle = style;
        recomputeHeader();
        if (getWidth() > 0) {
            updatePosterClockRect(getWidth(), getHeight());
        }
        softRefresh();
    }

    /**
     * Moves to the previous/next poster layout (horizontal swipe on the poster
     * page). Wraps around and persists through the listener.
     */
    private void cyclePosterStyle(int delta) {
        int count = PosterRenderer.STYLE_COUNT;
        int next = (posterStyle + delta) % count;
        if (next < 0) {
            next += count;
        }
        if (next == posterStyle) {
            return;
        }
        setPosterStyle(next);
        listener.onPosterStyleChanged(next);
    }

    private void updatePosterClockRect(int w, int h) {
        float[] band = new float[2];
        PosterRenderer.clockBand(posterStyle, band);
        posterClockRect.set(0f, band[0] * h, w, band[1] * h);
    }

    /** Switch between the poster page and the month-calendar page. */
    void setPage(int newPage) {
        int target = newPage == PAGE_CALENDAR ? PAGE_CALENDAR : PAGE_POSTER;
        if (page == target) {
            return;
        }
        page = target;
        if (page == PAGE_POSTER) {
            recomputeHeader();
        }
        softRefresh();
    }

    int viewYear() {
        return viewYear;
    }
    int viewMonth() {
        return viewMonth;
    }

    MonthModel model() {
        return model;
    }

    /** Called by the activity ticker. Returns true when a full redraw is needed. */
    boolean updateNow(long millis) {
        long oldDay = dayStamp(nowMillis);
        long oldMinute = nowMillis / 60000L;
        nowMillis = millis;
        long newDay = dayStamp(nowMillis);
        if (oldDay != newDay) {
            int oldTodayY = todayYear;
            int oldTodayM = todayMonth;
            int oldTodayD = todayDay;
            readToday();
            if (selYear == oldTodayY && selMonth == oldTodayM && selDay == oldTodayD) {
                selYear = todayYear;
                selMonth = todayMonth;
                selDay = todayDay;
            }
            if (viewYear == oldTodayY && viewMonth == oldTodayM) {
                viewYear = todayYear;
                viewMonth = todayMonth;
                model = new MonthModel(viewYear, viewMonth, holidays);
            }
            recomputeHeader();
            recomputeDetail();
            recomputeUpcoming();
            invalidate();
            return true;
        }
        if (nowMillis / 60000L != oldMinute) {
            recomputeHeader();
            if (page == PAGE_POSTER) {
                invalidate((int) posterClockRect.left, (int) posterClockRect.top,
                        (int) posterClockRect.right, (int) posterClockRect.bottom);
            } else {
                invalidate((int) clockRect.left, (int) clockRect.top,
                        (int) clockRect.right, (int) clockRect.bottom);
            }
            return false;
        }
        return false;
    }

    private static long dayStamp(long millis) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(millis);
        return cal.get(Calendar.YEAR) * 10000L
                + (cal.get(Calendar.MONTH) + 1) * 100L
                + cal.get(Calendar.DAY_OF_MONTH);
    }

    private void readToday() {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(nowMillis);
        todayYear = cal.get(Calendar.YEAR);
        todayMonth = cal.get(Calendar.MONTH) + 1;
        todayDay = cal.get(Calendar.DAY_OF_MONTH);
    }

    private void recomputeHeader() {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(nowMillis);
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        int minute = cal.get(Calendar.MINUTE);
        hClock = String.format(Locale.CHINA, "%02d:%02d", hour, minute);
        hClockSub = "第 " + LunarCalendar.isoWeek(todayYear, todayMonth, todayDay) + " 周";
        hMonthYear = String.format(Locale.CHINA, "%d年%d月%d日",
                todayYear, todayMonth, todayDay);
        hBigDay = String.valueOf(todayDay);
        hWeekday = LunarCalendar.weekdayName(todayYear, todayMonth, todayDay);

        LunarCalendar.LunarDate lunar =
                LunarCalendar.solarToLunar(todayYear, todayMonth, todayDay);
        hGanzhi = LunarCalendar.yearGanZhi(lunar.year) + "年 · " + LunarCalendar.zodiac(lunar.year) + "年";
        hLunar = "农历" + lunar.fullName();

        List<String> festivals =
                LunarCalendar.festivalsOf(todayYear, todayMonth, todayDay);
        hExtra = festivals.isEmpty() ? "" : TextUtils.join(" · ", festivals);

        poster.clock = hClock;
        poster.week = hClockSub;
        poster.year = String.valueOf(todayYear);
        poster.month = todayMonth + "月";
        poster.day = hBigDay;
        poster.weekday = hWeekday;
        poster.lunar = hLunar;
        poster.lunarShort = lunar.fullName();
        poster.ganzhi = hGanzhi + " · "
                + LunarCalendar.dayGanZhi(todayYear, todayMonth, todayDay) + "日";
        poster.festival = festivals.isEmpty() ? "无" : TextUtils.join(" · ", festivals);
        poster.sync = syncStatus;
        poster.network = networkStatus;

        int offset = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7;   // Monday = 0
        poster.weekIndex = offset;
        Calendar strip = (Calendar) cal.clone();
        strip.add(Calendar.DAY_OF_MONTH, -offset);
        for (int i = 0; i < 7; i++) {
            poster.weekDates[i] = strip.get(Calendar.DAY_OF_MONTH);
            strip.add(Calendar.DAY_OF_MONTH, 1);
        }

        LunarCalendar.Term term =
                LunarCalendar.termOfDay(todayYear, todayMonth, todayDay);
        int days = 0;
        int[] cursor = {todayYear, todayMonth, todayDay};
        if (term == null) {
            for (int i = 1; i <= 40; i++) {
                cursor = LunarCalendar.addDays(cursor[0], cursor[1], cursor[2], 1);
                LunarCalendar.Term t =
                        LunarCalendar.termOfDay(cursor[0], cursor[1], cursor[2]);
                if (t != null) {
                    term = t;
                    days = i;
                    break;
                }
            }
        }
        if (term == null) {
            poster.termLabel = "下一个节气";
            poster.termName = "—";
            poster.termDate = "";
            poster.termDays = "";
        } else {
            poster.termLabel = days == 0 ? "今日节气" : "下一个节气";
            poster.termName = term.name;
            poster.termDate = term.shortDate();
            poster.termDays = days == 0 ? "今天" : days + "天后";
        }
    }

    private void recomputeDetail() {
        LunarCalendar.LunarDate lunar =
                LunarCalendar.solarToLunar(selYear, selMonth, selDay);
        dTitle = String.format(Locale.CHINA, "%d月%d日 %s",
                selMonth, selDay, LunarCalendar.weekdayName(selYear, selMonth, selDay));
        dLunarGanzhi = "农历" + lunar.fullName() + " · "
                + LunarCalendar.dayGanZhi(selYear, selMonth, selDay) + "日";

        LunarCalendar.Term term = LunarCalendar.termOfDay(selYear, selMonth, selDay);
        if (term != null) {
            dTerm = "节气  " + term.name + "  " + term.timeText();
        } else {
            LunarCalendar.Term next = nextTerm(selYear, selMonth, selDay);
            dTerm = next == null ? "" : "下一个节气  " + next.name + "  " + next.shortDate();
        }

        List<String> festivals = LunarCalendar.festivalsOf(selYear, selMonth, selDay);
        dFestival = festivals.isEmpty() ? "节日  无" : "节日  " + TextUtils.join(" · ", festivals);

        HolidayStore.HolidayDay hd = holidays.get(selYear, selMonth, selDay);
        if (hd != null && hd.holiday) {
            dHoliday = "法定假日  " + hd.name;
        } else if (hd != null && hd.workday) {
            dHoliday = "调休上班  " + hd.name;
        } else if (LunarCalendar.isWeekend(selYear, selMonth, selDay)) {
            dHoliday = "周末休息";
        } else {
            dHoliday = "工作日";
        }
    }

    private LunarCalendar.Term nextTerm(int year, int month, int day) {
        int[] cursor = {year, month, day};
        for (int i = 0; i < 40; i++) {
            cursor = LunarCalendar.addDays(cursor[0], cursor[1], cursor[2], 1);
            LunarCalendar.Term term =
                    LunarCalendar.termOfDay(cursor[0], cursor[1], cursor[2]);
            if (term != null) {
                return term;
            }
        }
        return null;
    }

    private void recomputeUpcoming() {
        upcoming.clear();
        int[] cursor = {todayYear, todayMonth, todayDay};
        for (int i = 0; i < 400 && upcoming.size() < 3; i++) {
            cursor = LunarCalendar.addDays(cursor[0], cursor[1], cursor[2], 1);
            List<String> festivals =
                    LunarCalendar.festivalsOf(cursor[0], cursor[1], cursor[2]);
            if (!festivals.isEmpty()) {
                String name = festivals.get(0);
                if (!upcoming.contains(name)) {
                    upcoming.add(name + "  " + cursor[1] + "月" + cursor[2] + "日 "
                            + (i + 1) + "天后");
                }
            }
        }
        if (upcoming.isEmpty()) {
            upcoming.add("暂无近期节日");
        }
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        landscape = w > h;
        margin = w * 0.032f;
        if (landscape) {
            layoutLandscapeCalendar(w, h);
            return;
        }
        headerBottom = h * 0.235f;
        monthBarBottom = headerBottom + h * 0.048f;
        weekdayBottom = monthBarBottom + h * 0.034f;
        gridBottom = h * 0.795f;
        gridLeft = margin;
        gridRight = w - margin;
        gridTop = weekdayBottom;
        cellWidth = (gridRight - gridLeft) / 7f;
        cellHeight = (gridBottom - gridTop) / 6f;
        cellPad = 14f;
        cellDaySize = 44f;
        cellDayBase = 52f;
        cellSubSize = 32f;
        cellSubBase = 92f;
        badgeW = 46f;
        badgeH = 38f;

        float barCenter = (headerBottom + monthBarBottom) / 2f;
        prevArrow.set(margin, barCenter - 34f, margin + 60f, barCenter + 34f);
        nextArrow.set(w * 0.42f - 20f, barCenter - 34f, w * 0.42f + 40f, barCenter + 34f);
        todayButton.set(w - margin - w * 0.19f, barCenter - 30f, w - margin, barCenter + 30f);
        menuButton.set(w - margin - 84f, 0f, w, h * 0.082f);
        clockRect.set(w * 0.5f, h * 0.045f, w - margin, h * 0.155f);
        updatePosterClockRect(w, h);
        posterMenuButton.set(w - w * 0.060f - 90f, 0f, w, 130f);
        posterHintRect.set(0f, h * 0.93f, w, h);
        calendarHintRect.set(0f, h - 58f, w, h);
    }

    /**
     * Landscape calendar: an info column on the left, the month grid on the
     * right, so all seven columns keep readable cell widths.
     */
    private void layoutLandscapeCalendar(int w, int h) {
        margin = w * 0.033f;
        headerBottom = h * 0.100f;
        monthBarBottom = h * 0.176f;
        weekdayBottom = h * 0.210f;
        gridTop = weekdayBottom;
        gridBottom = h * 0.930f;
        gridLeft = w * 0.415f;
        gridRight = w - margin;
        cellWidth = (gridRight - gridLeft) / 7f;
        cellHeight = (gridBottom - gridTop) / 6f;
        cellPad = 10f;
        cellDaySize = 38f;
        cellDayBase = 46f;
        cellSubSize = 32f;
        cellSubBase = 82f;
        badgeW = 36f;
        badgeH = 30f;

        float barCenter = (headerBottom + monthBarBottom) / 2f;
        prevArrow.set(margin, barCenter - 34f, margin + 56f, barCenter + 34f);
        nextArrow.set(margin + 308f, barCenter - 34f, margin + 364f, barCenter + 34f);
        todayButton.set(w - margin - 150f, barCenter - 26f, w - margin, barCenter + 26f);
        menuButton.set(w - margin - 84f, 0f, w, h * 0.095f);
        clockRect.set(w * 0.5f, h * 0.03f, w - margin, h * 0.16f);
        updatePosterClockRect(w, h);
        posterMenuButton.set(w - w * 0.060f - 90f, 0f, w, 130f);
        posterHintRect.set(0f, h * 0.93f, w, h);
        calendarHintRect.set(0f, h - 58f, w, h);
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        canvas.drawColor(PAPER);

        if (flashing) {
            canvas.drawColor((flashStep & 1) == 0 ? INK : PAPER);
            return;
        }

        if (page == PAGE_POSTER) {
            drawPoster(canvas, w, h);
            return;
        }

        if (landscape) {
            drawCalendarLandscape(canvas, w, h);
            return;
        }

        drawHeader(canvas, w, h);
        drawMonthBar(canvas, w);
        drawWeekdayRow(canvas, w);
        drawGrid(canvas, w);
        drawDetail(canvas, w, h);
    }

    /** "Tear-off calendar" page rendered by {@link PosterRenderer}. */
    private void drawPoster(Canvas canvas, int w, int h) {
        poster.sync = syncStatus;
        poster.network = networkStatus;
        poster.batteryPercent = batteryPercent;
        poster.batteryCharging = batteryCharging;
        poster.draw(canvas, w, h, posterStyle);
    }

    /** "已校时 · 离线 · 82%" — battery tails the status line on the calendar page. */
    private String statusLine() {
        String line = syncStatus + " · " + networkStatus;
        if (batteryPercent >= 0) {
            line += " · " + batteryPercent + "%";
            if (batteryCharging) {
                line += " 充电中";
            }
        }
        return line;
    }

    private void drawHeader(Canvas canvas, int w, int h) {
        // Month / year.
        paint.setColor(INK);
        paint.setTextSize(w * 0.030f);
        paint.setTypeface(Typeface.DEFAULT);
        canvas.drawText(hMonthYear, margin, h * 0.050f, paint);

        // Big day number.
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextSize(w * 0.175f);
        canvas.drawText(hBigDay, margin, h * 0.205f, paint);

        // Middle column.
        float midX = w * 0.30f;
        paint.setTextSize(w * 0.042f);
        canvas.drawText(hWeekday, midX, h * 0.105f, paint);

        paint.setTypeface(Typeface.DEFAULT);
        paint.setTextSize(w * 0.030f);
        canvas.drawText(hGanzhi, midX, h * 0.145f, paint);

        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextSize(w * 0.033f);
        canvas.drawText(hLunar, midX, h * 0.185f, paint);

        paint.setTypeface(Typeface.DEFAULT);
        paint.setTextSize(w * 0.030f);
        if (hExtra.length() > 0) {
            canvas.drawText(hExtra, midX, h * 0.225f, paint);
        }

        // Clock.
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextSize(w * 0.090f);
        paint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(hClock, w - margin, h * 0.115f, paint);

        paint.setTypeface(Typeface.DEFAULT);
        paint.setTextSize(w * 0.030f);
        paint.setColor(SUBTLE);
        canvas.drawText(hClockSub, w - margin, h * 0.155f, paint);
        canvas.drawText(statusLine(), w - margin, h * 0.195f, paint);
        paint.setColor(INK);
        paint.setTextAlign(Paint.Align.LEFT);

        // Menu icon (three rules).
        float mx = w - margin - 28f;
        float my = h * 0.042f;
        float half = 22f;
        linePaint.setStrokeWidth(4f);
        linePaint.setColor(INK);
        canvas.drawLine(mx - half, my - 14f, mx + half, my - 14f, linePaint);
        canvas.drawLine(mx - half, my, mx + half, my, linePaint);
        canvas.drawLine(mx - half, my + 14f, mx + half, my + 14f, linePaint);

        // Separator.
        linePaint.setStrokeWidth(4f);
        canvas.drawLine(margin, headerBottom, w - margin, headerBottom, linePaint);
    }

    private void drawMonthBar(Canvas canvas, int w) {
        linePaint.setStrokeWidth(5f);
        linePaint.setColor(INK);
        // prev chevron
        float cx = prevArrow.centerX();
        float cy = prevArrow.centerY();
        canvas.drawLine(cx + 12f, cy - 20f, cx - 12f, cy, linePaint);
        canvas.drawLine(cx - 12f, cy, cx + 12f, cy + 20f, linePaint);
        // next chevron
        cx = nextArrow.centerX();
        cy = nextArrow.centerY();
        canvas.drawLine(cx - 12f, cy - 20f, cx + 12f, cy, linePaint);
        canvas.drawLine(cx + 12f, cy, cx - 12f, cy + 20f, linePaint);

        paint.setColor(INK);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextSize(w * 0.038f);
        canvas.drawText(String.format(Locale.CHINA, "%d年%d月", viewYear, viewMonth),
                margin + 78f, (headerBottom + monthBarBottom) / 2f + 14f, paint);

        paint.setTypeface(Typeface.DEFAULT);
        paint.setTextSize(w * 0.030f);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("今天", todayButton.centerX(), todayButton.centerY() + 11f, paint);
        paint.setTextAlign(Paint.Align.LEFT);

        linePaint.setStrokeWidth(3f);
        canvas.drawRect(todayButton, linePaint);
    }

    private void drawWeekdayRow(Canvas canvas, int w) {
        paint.setColor(INK);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextSize(w * 0.030f);
        paint.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < 7; i++) {
            float cx = gridLeft + cellWidth * (i + 0.5f);
            canvas.drawText(LunarCalendar.WEEK_NAMES[i], cx,
                    weekdayBottom - (landscape ? 12f : 16f), paint);
        }
        paint.setTextAlign(Paint.Align.LEFT);

        linePaint.setStrokeWidth(2f);
        linePaint.setColor(INK);
        canvas.drawLine(gridLeft, weekdayBottom - 3f, gridRight, weekdayBottom - 3f, linePaint);
    }

    private void drawGrid(Canvas canvas, int w) {
        // Light grid lines.
        linePaint.setStrokeWidth(1f);
        linePaint.setColor(GRID);
        for (int r = 1; r < 6; r++) {
            float y = gridTop + cellHeight * r;
            canvas.drawLine(gridLeft, y, gridRight, y, linePaint);
        }
        for (int c = 1; c < 7; c++) {
            float x = gridLeft + cellWidth * c;
            canvas.drawLine(x, gridTop, x, gridBottom, linePaint);
        }

        for (int r = 0; r < 6; r++) {
            for (int c = 0; c < 7; c++) {
                drawCell(canvas, w, r, c);
            }
        }
    }

    private void drawCell(Canvas canvas, int w, int row, int col) {
        MonthModel.DayCell cell = model.cellAt(row, col);
        if (cell == null) {
            return;
        }
        float left = gridLeft + cellWidth * col;
        float top = gridTop + cellHeight * row;
        float right = left + cellWidth;
        float bottom = top + cellHeight;

        boolean inverted = cell.today;
        if (inverted) {
            fillPaint.setColor(INK);
            canvas.drawRect(left + 3f, top + 3f, right - 3f, bottom - 3f, fillPaint);
        } else if (cell.selected) {
            linePaint.setStrokeWidth(4f);
            linePaint.setColor(INK);
            canvas.drawRect(left + 4f, top + 4f, right - 4f, bottom - 4f, linePaint);
        }

        int mainColor = inverted ? PAPER : (cell.inMonth ? INK : LIGHT);
        paint.setColor(mainColor);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextSize(cell.inMonth ? cellDaySize : cellDaySize - 4f);
        canvas.drawText(String.valueOf(cell.day), left + cellPad,
                top + cellDayBase, paint);

        // Weekend underline.
        if (cell.weekend && cell.inMonth) {
            float numWidth = paint.measureText(String.valueOf(cell.day));
            linePaint.setStrokeWidth(3f);
            linePaint.setColor(inverted ? PAPER : INK);
            canvas.drawLine(left + cellPad, top + cellDayBase + 6f,
                    left + cellPad + numWidth, top + cellDayBase + 6f, linePaint);
        }

        if (cell.inMonth) {
            paint.setTypeface(Typeface.DEFAULT);
            paint.setTextSize(cellSubSize);
            String sub = cell.festivalLabel != null ? cell.festivalLabel : cell.lunarLabel;
            boolean emphasise = cell.festivalLabel != null;
            if (inverted) {
                paint.setColor(PAPER);
            } else if (emphasise) {
                paint.setColor(INK);
            } else {
                paint.setColor(GRAY);
            }
            paint.setTypeface(emphasise ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            float avail = cellWidth - cellPad * 2f;
            measurePaint.set(paint);
            String shown = TextUtils.ellipsize(sub, measurePaint, avail,
                    TextUtils.TruncateAt.END).toString();
            canvas.drawText(shown, left + cellPad, top + cellSubBase, paint);
        }

        // Holiday badge.
        if (cell.badge != 0 && cell.inMonth) {
            float bx = right - badgeW - (landscape ? 5f : 8f);
            float by = top + (landscape ? 7f : 10f);
            fillPaint.setColor(inverted ? PAPER : INK);
            canvas.drawRect(bx, by, bx + badgeW, by + badgeH, fillPaint);
            paint.setTypeface(Typeface.DEFAULT_BOLD);
            paint.setTextSize(cellSubSize);
            paint.setColor(inverted ? INK : PAPER);
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(cell.badge == 1 ? "休" : "班", bx + badgeW / 2f,
                    by + badgeH - 7f, paint);
            paint.setTextAlign(Paint.Align.LEFT);
        }
    }

    private void drawDetail(Canvas canvas, int w, int h) {
        linePaint.setStrokeWidth(3f);
        linePaint.setColor(INK);
        canvas.drawLine(margin, gridBottom, w - margin, gridBottom, linePaint);

        float leftX = margin;
        paint.setColor(INK);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextSize(w * 0.030f);
        canvas.drawText(dTitle, leftX, gridBottom + 46f, paint);

        paint.setTypeface(Typeface.DEFAULT);
        paint.setTextSize(w * 0.030f);
        canvas.drawText(dLunarGanzhi, leftX, gridBottom + 92f, paint);
        canvas.drawText(dTerm, leftX, gridBottom + 138f, paint);
        canvas.drawText(dFestival, leftX, gridBottom + 184f, paint);
        paint.setColor(SUBTLE);
        canvas.drawText(dHoliday, leftX, gridBottom + 230f, paint);

        float rightX = w * 0.52f;
        paint.setColor(INK);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextSize(w * 0.030f);
        canvas.drawText("接下来", rightX, gridBottom + 46f, paint);
        paint.setTypeface(Typeface.DEFAULT);
        paint.setColor(SUBTLE);
        float y = gridBottom + 92f;
        for (String item : upcoming) {
            canvas.drawText(item, rightX, y, paint);
            y += 46f;
        }

        paint.setColor(GRAY);
        paint.setTextSize(w * 0.030f);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("轻触日期查看详情 · 左右滑动切换月份 · 下滑返回月份牌",
                w / 2f, h - 14f, paint);
        paint.setTextAlign(Paint.Align.LEFT);
    }

    // ------------------------------------------------------------------
    // Landscape calendar
    // ------------------------------------------------------------------

    private void drawCalendarLandscape(Canvas canvas, int w, int h) {
        drawHeaderLandscape(canvas, w, h);
        drawMonthBarLandscape(canvas, w);
        drawWeekdayRow(canvas, w);
        drawGrid(canvas, w);
        drawDetailLandscape(canvas, w, h);
    }

    private void drawHeaderLandscape(Canvas canvas, int w, int h) {
        float textSize = w * 0.022f;      // 32px at 1448 wide

        paint.setColor(SUBTLE);
        paint.setTypeface(Typeface.DEFAULT);
        paint.setTextSize(textSize);
        paint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(statusLine(), w - margin - 80f,
                h * 0.062f, paint);
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setColor(INK);

        // Menu icon.
        float mx = w - margin - 28f;
        float my = h * 0.048f;
        float half = 22f;
        linePaint.setStrokeWidth(4f);
        linePaint.setColor(INK);
        canvas.drawLine(mx - half, my - 14f, mx + half, my - 14f, linePaint);
        canvas.drawLine(mx - half, my, mx + half, my, linePaint);
        canvas.drawLine(mx - half, my + 14f, mx + half, my + 14f, linePaint);

        linePaint.setStrokeWidth(4f);
        canvas.drawLine(margin, headerBottom, w - margin, headerBottom, linePaint);
    }

    private void drawMonthBarLandscape(Canvas canvas, int w) {
        linePaint.setStrokeWidth(5f);
        linePaint.setColor(INK);
        float cx = prevArrow.centerX();
        float cy = prevArrow.centerY();
        canvas.drawLine(cx + 12f, cy - 20f, cx - 12f, cy, linePaint);
        canvas.drawLine(cx - 12f, cy, cx + 12f, cy + 20f, linePaint);
        cx = nextArrow.centerX();
        canvas.drawLine(cx - 12f, cy - 20f, cx + 12f, cy, linePaint);
        canvas.drawLine(cx + 12f, cy, cx - 12f, cy + 20f, linePaint);

        paint.setColor(INK);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextSize(w * 0.028f);
        canvas.drawText(String.format(Locale.CHINA, "%d年%d月", viewYear, viewMonth),
                margin + 76f, (headerBottom + monthBarBottom) / 2f + 14f, paint);

        paint.setTypeface(Typeface.DEFAULT);
        paint.setTextSize(w * 0.022f);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("今天", todayButton.centerX(), todayButton.centerY() + 11f, paint);
        paint.setTextAlign(Paint.Align.LEFT);

        linePaint.setStrokeWidth(3f);
        canvas.drawRect(todayButton, linePaint);
    }

    private void drawDetailLandscape(Canvas canvas, int w, int h) {
        float leftX = margin;
        float laneRight = gridLeft - w * 0.035f;

        // Big day + weekday.
        paint.setColor(INK);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextSize(w * 0.104f);
        canvas.drawText(hBigDay, leftX, h * 0.345f, paint);
        float dayWidth = paint.measureText(hBigDay);

        paint.setTypeface(Typeface.DEFAULT);
        paint.setTextSize(w * 0.028f);
        canvas.drawText(hWeekday, leftX + dayWidth + 26f, h * 0.300f, paint);

        linePaint.setStrokeWidth(3f);
        linePaint.setColor(INK);
        canvas.drawLine(margin, h * 0.385f, laneRight, h * 0.385f, linePaint);

        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextSize(w * 0.025f);
        canvas.drawText(hLunar, leftX, h * 0.470f, paint);

        paint.setTypeface(Typeface.DEFAULT);
        paint.setTextSize(w * 0.022f);
        canvas.drawText(hGanzhi, leftX, h * 0.520f, paint);

        linePaint.setColor(HAIR_LINE);
        linePaint.setStrokeWidth(2f);
        canvas.drawLine(margin, h * 0.565f, laneRight, h * 0.565f, linePaint);

        paint.setColor(INK);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextSize(w * 0.062f);
        canvas.drawText(hClock, leftX, h * 0.650f, paint);

        paint.setTypeface(Typeface.DEFAULT);
        paint.setTextSize(w * 0.022f);
        canvas.drawText(hClockSub, leftX, h * 0.695f, paint);

        linePaint.setColor(HAIR_LINE);
        linePaint.setStrokeWidth(2f);
        canvas.drawLine(margin, h * 0.735f, laneRight, h * 0.735f, linePaint);

        paint.setColor(INK);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextSize(w * 0.023f);
        canvas.drawText(dTitle, leftX, h * 0.790f, paint);

        paint.setTypeface(Typeface.DEFAULT);
        paint.setTextSize(w * 0.022f);
        canvas.drawText(dLunarGanzhi, leftX, h * 0.833f, paint);
        canvas.drawText(dTerm, leftX, h * 0.876f, paint);
        canvas.drawText(dFestival + " · " + dHoliday, leftX, h * 0.919f, paint);

        paint.setColor(GRAY);
        paint.setTextSize(w * 0.022f);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("轻触日期查看详情 · 上下滑动切换月份 · 右滑返回月份牌",
                gridLeft + (gridRight - gridLeft) / 2f, h - 22f, paint);
        paint.setTextAlign(Paint.Align.LEFT);
    }

    // ------------------------------------------------------------------
    // Touch
    // ------------------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                gestureHandled = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                // Decide page/layout swipes as soon as the finger has moved:
                // e-ink panels can drop the tail of a gesture during a refresh.
                // In landscape the whole UI is rotated, so the two axes swap.
                if (!gestureHandled) {
                    float mdx = event.getX() - downX;
                    float mdy = event.getY() - downY;
                    float pageAxis = landscape ? mdx : mdy;
                    float styleAxis = landscape ? mdy : mdx;
                    if (Math.abs(pageAxis) > 100f
                            && Math.abs(pageAxis) > Math.abs(styleAxis) * 1.4f) {
                        gestureHandled = true;
                        if (page == PAGE_POSTER) {
                            setPage(PAGE_CALENDAR);
                        } else {
                            setPage(PAGE_POSTER);
                        }
                    } else if (page == PAGE_POSTER
                            && Math.abs(styleAxis) > 100f
                            && Math.abs(styleAxis) > Math.abs(pageAxis) * 1.4f) {
                        gestureHandled = true;
                        cyclePosterStyle(styleAxis < 0f ? 1 : -1);
                    }
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                gestureHandled = false;
                return true;
            case MotionEvent.ACTION_UP:
                float upX = event.getX();
                float upY = event.getY();
                float dx = upX - downX;
                float dy = upY - downY;
                float pageAxis = landscape ? dx : dy;
                float styleAxis = landscape ? dy : dx;
                if (gestureHandled) {
                    gestureHandled = false;
                    return true;
                }

                if (page == PAGE_POSTER) {
                    if (posterMenuButton.contains(downX, downY)) {
                        listener.onSettingsRequested();
                        return true;
                    }
                    if (Math.abs(styleAxis) > 110f
                            && Math.abs(styleAxis) > Math.abs(pageAxis) * 1.3f) {
                        cyclePosterStyle(styleAxis < 0f ? 1 : -1);
                        return true;
                    }
                    if (Math.abs(pageAxis) > 90f || posterHintRect.contains(downX, downY)) {
                        setPage(PAGE_CALENDAR);
                    }
                    return true;
                }

                // Calendar page: a swipe along the "page" axis returns to the poster.
                if (Math.abs(pageAxis) > 120f
                        && Math.abs(pageAxis) > Math.abs(styleAxis) * 1.3f) {
                    setPage(PAGE_POSTER);
                    return true;
                }
                if (calendarHintRect.contains(downX, downY)) {
                    setPage(PAGE_POSTER);
                    return true;
                }
                if (menuButton.contains(downX, downY)) {
                    listener.onSettingsRequested();
                    return true;
                }
                if (todayButton.contains(downX, downY)) {
                    goToToday();
                    return true;
                }
                if (prevArrow.contains(downX, downY)) {
                    changeMonth(-1);
                    return true;
                }
                if (nextArrow.contains(downX, downY)) {
                    changeMonth(1);
                    return true;
                }
                boolean inGrid = downX >= gridLeft && downX <= gridRight
                        && downY >= gridTop && downY <= gridBottom;
                if (inGrid && Math.abs(styleAxis) > 110f
                        && Math.abs(pageAxis) < 110f) {
                    changeMonth(styleAxis < 0 ? 1 : -1);
                    return true;
                }
                if (inGrid) {
                    handleGridTap(downX, downY);
                    return true;
                }
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private void handleGridTap(float x, float y) {
        int col = (int) ((x - gridLeft) / cellWidth);
        int row = (int) ((y - gridTop) / cellHeight);
        if (col < 0 || col > 6 || row < 0 || row > 5) {
            return;
        }
        MonthModel.DayCell cell = model.cellAt(row, col);
        if (cell == null) {
            return;
        }
        selYear = cell.year;
        selMonth = cell.month;
        selDay = cell.day;
        if (!cell.inMonth) {
            viewYear = cell.year;
            viewMonth = cell.month;
            model = new MonthModel(viewYear, viewMonth, holidays);
        }
        refreshFlags();
        recomputeDetail();
        softRefresh();
    }

    private void changeMonth(int delta) {
        int month = viewMonth + delta;
        int year = viewYear;
        if (month < 1) {
            month = 12;
            year--;
        } else if (month > 12) {
            month = 1;
            year++;
        }
        viewYear = year;
        viewMonth = month;
        model = new MonthModel(viewYear, viewMonth, holidays);
        refreshFlags();
        softRefresh();
    }

    private void goToToday() {
        readToday();
        viewYear = todayYear;
        viewMonth = todayMonth;
        selYear = todayYear;
        selMonth = todayMonth;
        selDay = todayDay;
        model = new MonthModel(viewYear, viewMonth, holidays);
        refreshFlags();
        recomputeDetail();
        softRefresh();
    }

    private void refreshFlags() {
        model.refreshFlags(todayYear, todayMonth, todayDay, selYear, selMonth, selDay);
    }

    /** Rebuild the grid after holiday data arrives. */
    void refreshHolidays() {
        MonthModel rebuilt = new MonthModel(viewYear, viewMonth, holidays);
        model = rebuilt;
        refreshFlags();
        recomputeDetail();
        invalidate();
    }
}
