import java.time.LocalDate;
import ru.mkeiit.schedule.DayStripPosition;

public class DayStripTest {
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    public static void main(String[] args){
        check(DayStripPosition.shift(-140,40)==4,"Long drag moves several dates, not just one day");
        check(DayStripPosition.shift(120,40)==-3,"Right drag reveals previous dates");
        check(DayStripPosition.shift(-15,40)==0,"Short movement snaps back");
        check(DayStripPosition.shift(-25,40)==1,"Ribbon snaps to nearest cell");
        LocalDate first=LocalDate.of(2026,10,19),selected=LocalDate.of(2026,10,21);
        int shift=DayStripPosition.shift(-120,40);
        check(first.plusDays(shift).equals(LocalDate.of(2026,10,22)),"Visible ribbon origin moves by drag distance");
        check(DayStripPosition.tapped(first.plusDays(shift),85,40).equals(LocalDate.of(2026,10,24)),"A tap after scrolling chooses the new visible date");
        check(DayStripPosition.tapped(first,85,40).equals(selected),"Tap chooses the actual visible date");
        check(DayStripPosition.tapped(first,-10,40).equals(first),"Tap at left boundary");
        check(DayStripPosition.tapped(first,280,40).equals(first.plusDays(6)),"Tap at right boundary");
        check(LocalDate.of(2026,10,31).plusDays(DayStripPosition.shift(-40,40)).equals(LocalDate.of(2026,11,1)),"Next month");
        check(LocalDate.of(2027,1,1).plusDays(DayStripPosition.shift(40,40)).equals(LocalDate.of(2026,12,31)),"Previous year");
        check(DayStripPosition.shift(50,0)==0,"Unmeasured strip is safe");
        System.out.println("PASS: continuous ribbon offsets, multiple-day dragging, snap, visible-date taps, month and year transitions");
    }
}
