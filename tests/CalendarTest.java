import ru.mkeiit.schedule.CalendarDates;
import java.time.*;
import java.util.*;

public class CalendarTest {
    static void check(boolean value,String why) {if(!value) throw new AssertionError(why);}
    public static void main(String[] args) {
        for(int year=2024;year<=2032;year++) for(int month=1;month<=12;month++) {
            YearMonth ym=YearMonth.of(year,month);
            List<LocalDate> cells=CalendarDates.cells(ym);
            check(cells.size()%7==0 && cells.size()<=42,"Complete calendar rows");
            int count=0;
            for(int i=0;i<cells.size();i++) if(cells.get(i)!=null) {
                LocalDate day=cells.get(i);count++;
                check(day.getDayOfMonth()==count,"Each date appears in order exactly once");
                check(day.getDayOfWeek().getValue()==i%7+1,"Correct weekday, Monday first");
            }
            check(count==ym.lengthOfMonth(),"All dates included");
        }
        check(CalendarDates.shiftMonth(LocalDate.of(2026,1,31),1).equals(LocalDate.of(2026,2,28)),"Short month clamped");
        check(CalendarDates.shiftMonth(LocalDate.of(2024,1,31),1).equals(LocalDate.of(2024,2,29)),"Leap day");
        check(CalendarDates.shiftMonth(LocalDate.of(2026,12,21),1).equals(LocalDate.of(2027,1,21)),"Next year");
        check(CalendarDates.shiftMonth(LocalDate.of(2026,1,21),-1).equals(LocalDate.of(2025,12,21)),"Previous year");
        System.out.println("PASS: 108 calendar months, weekday alignment, leap days, short months, year navigation");
    }
}
