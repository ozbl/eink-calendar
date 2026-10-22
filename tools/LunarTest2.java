package com.zbl.einkcalendar;

public class LunarTest2 {
    public static void main(String[] args) {
        int[][] dates = {
                {2026, 2, 17}, {2026, 9, 25}, {2026, 6, 19}, {2026, 10, 19},
                {2026, 10, 18}, {2026, 10, 20}, {2026, 12, 31}, {2027, 2, 5},
        };
        for (int[] d : dates) {
            LunarCalendar.LunarDate l = LunarCalendar.solarToLunar(d[0], d[1], d[2]);
            System.out.println(d[0] + "-" + d[1] + "-" + d[2]
                    + " lunarY=" + l.year + " M=" + l.month + " D=" + l.day + " leap=" + l.leap);
        }
        System.out.println("dayGZ 2026-10-19 = " + LunarCalendar.dayGanZhi(2026, 10, 19));
        System.out.println("dayGZ 2024-02-10 = " + LunarCalendar.dayGanZhi(2024, 2, 10));
        System.out.println("dayGZ 2026-02-17 = " + LunarCalendar.dayGanZhi(2026, 2, 17));
        System.out.println("term 2026-10-08 = " + LunarCalendar.termOfDay(2026, 10, 8));
        System.out.println("term 2026-10-23 = " + LunarCalendar.termOfDay(2026, 10, 23));
    }
}
