package com.zbl.einkcalendar;

import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/** Layout model for one month: 6 rows x 7 columns (Monday first). */
final class MonthModel {

    static final int CELLS = 42;

    static final class DayCell {
        int year;
        int month;
        int day;
        boolean inMonth;
        boolean today;
        boolean selected;
        boolean weekend;
        String lunarLabel = "";
        String festivalLabel;
        String holidayName;
        int badge; // 0 none, 1 rest (休), 2 work (班)

        String key() {
            return String.format(Locale.CHINA, "%04d-%02d-%02d", year, month, day);
        }
    }

    final int year;
    final int month;
    final DayCell[] cells = new DayCell[CELLS];

    MonthModel(int year, int month, HolidayStore holidays) {
        this.year = year;
        this.month = month;
        build(holidays);
    }

    private void build(HolidayStore holidays) {
        int firstDow = LunarCalendar.dayOfWeekMondayFirst(year, month, 1);
        int daysInMonth = LunarCalendar.daysInMonth(year, month);
        int prevYear = month == 1 ? year - 1 : year;
        int prevMonth = month == 1 ? 12 : month - 1;
        int prevDays = LunarCalendar.daysInMonth(prevYear, prevMonth);

        for (int i = 0; i < CELLS; i++) {
            int dayOffset = i - firstDow;
            DayCell cell = new DayCell();
            if (dayOffset < 0) {
                cell.year = prevYear;
                cell.month = prevMonth;
                cell.day = prevDays + dayOffset + 1;
                cell.inMonth = false;
            } else if (dayOffset >= daysInMonth) {
                int nextYear = month == 12 ? year + 1 : year;
                int nextMonth = month == 12 ? 1 : month + 1;
                cell.year = nextYear;
                cell.month = nextMonth;
                cell.day = dayOffset - daysInMonth + 1;
                cell.inMonth = false;
            } else {
                cell.year = year;
                cell.month = month;
                cell.day = dayOffset + 1;
                cell.inMonth = true;
            }
            fill(cell, holidays);
            cells[i] = cell;
        }
    }

    private void fill(DayCell cell, HolidayStore holidays) {
        cell.weekend = LunarCalendar.isWeekend(cell.year, cell.month, cell.day);
        LunarCalendar.LunarDate lunar =
                LunarCalendar.solarToLunar(cell.year, cell.month, cell.day);
        cell.lunarLabel = lunar.day == 1 ? lunar.monthName() : lunar.dayName();

        List<String> festivals =
                LunarCalendar.festivalsOf(cell.year, cell.month, cell.day);
        if (!festivals.isEmpty()) {
            cell.festivalLabel = festivals.get(0);
        }

        HolidayStore.HolidayDay hd = holidays.get(cell.year, cell.month, cell.day);
        if (hd != null) {
            cell.holidayName = hd.name;
            if (hd.holiday) {
                cell.badge = 1;
            } else if (hd.workday) {
                cell.badge = 2;
            }
        }
    }

    void refreshFlags(int todayYear, int todayMonth, int todayDay,
                      int selYear, int selMonth, int selDay) {
        for (DayCell cell : cells) {
            cell.today = cell.year == todayYear && cell.month == todayMonth
                    && cell.day == todayDay;
            cell.selected = cell.year == selYear && cell.month == selMonth
                    && cell.day == selDay;
        }
    }

    DayCell cellAt(int row, int col) {
        int index = row * 7 + col;
        if (index < 0 || index >= CELLS) {
            return null;
        }
        return cells[index];
    }

    /** Week number shown in the header (ISO week of the first day). */
    int weekNumber() {
        return LunarCalendar.isoWeek(year, month, 1);
    }

    static int todayYear() {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(TimeSync.now());
        return cal.get(Calendar.YEAR);
    }

    static int todayMonth() {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(TimeSync.now());
        return cal.get(Calendar.MONTH) + 1;
    }

    static int todayDay() {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(TimeSync.now());
        return cal.get(Calendar.DAY_OF_MONTH);
    }
}
