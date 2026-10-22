package com.zbl.einkcalendar;

import java.util.Map;

public class LunarTest {
    public static void main(String[] args) {
        int[][] dates = {
                {2026, 2, 17}, {2026, 9, 25}, {2026, 6, 19}, {2026, 10, 19},
                {2025, 1, 29}, {2024, 2, 10}, {2023, 1, 22}, {2020, 1, 25},
                {2026, 1, 1}, {2026, 12, 31}
        };
        for (int[] d : dates) {
            LunarCalendar.LunarDate l = LunarCalendar.solarToLunar(d[0], d[1], d[2]);
            System.out.println(d[0] + "-" + d[1] + "-" + d[2] + " => " + l.fullName()
                    + " (" + l.year + ", leap=" + l.leap + ") "
                    + LunarCalendar.yearGanZhi(l.year) + LunarCalendar.zodiac(l.year) + "年 "
                    + LunarCalendar.dayGanZhi(d[0], d[1], d[2]) + "日 "
                    + LunarCalendar.festivalsOf(d[0], d[1], d[2]));
        }
        System.out.println("--- terms 2026 ---");
        Map<Integer, LunarCalendar.Term> terms = LunarCalendar.termsOfYear(2026);
        for (LunarCalendar.Term t : terms.values()) {
            System.out.println(t.name + " " + t.year + "-" + t.month + "-" + t.day
                    + " " + t.hour + ":" + t.minute);
        }
    }
}
