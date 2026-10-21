package ru.mkeiit.schedule;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

public final class CalendarDates {
    private CalendarDates() { }
    /** Monday first, with blank padding to complete the first and last rows. */
    public static List<LocalDate> cells(YearMonth month) {
        List<LocalDate> result=new ArrayList<>();
        int offset=month.atDay(1).getDayOfWeek().getValue()-1;
        for(int i=0;i<offset;i++) result.add(null);
        for(int i=1;i<=month.lengthOfMonth();i++) result.add(month.atDay(i));
        while(result.size()%7!=0) result.add(null);
        return result;
    }
    public static LocalDate shiftMonth(LocalDate day,int amount) {
        return day.plusMonths(amount);
    }
}
