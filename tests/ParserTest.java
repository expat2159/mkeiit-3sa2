import ru.mkeiit.schedule.Timetable;
import org.jsoup.Jsoup;
import java.io.File;
import java.time.LocalDate;
import java.util.*;

public class ParserTest {
    static void check(boolean value,String why) { if(!value) throw new AssertionError(why); }
    public static void main(String[] args) throws Exception {
        String base="https://mkeiit.ru/wp-content/uploads/2025/raspisanie/19.10.26-24.10.26/";
        TreeMap<LocalDate,String> links=Timetable.weeks(Jsoup.parse(new File("tests/fixtures/index.html"),null,Timetable.SOURCE));
        check(links.size()==9,"Discover all published weeks");
        check(links.get(LocalDate.of(2026,10,19)).equals(base+"cg.htm"),"Current week URL");
        check(Timetable.groupUrl(Jsoup.parse(new File("tests/fixtures/groups.html"),null,base+"cg.htm")).equals(base+"cg557.htm"),"Find group by text");
        Timetable.Week week=Timetable.parse(Jsoup.parse(new File("tests/fixtures/group-windows1251.html"),null,base+"cg557.htm"),base+"cg557.htm");
        check(week.days.size()==7,"Seven dated days");
        check(week.updated.equals("20.10.2026 в 15:14"),"Site update timestamp");
        List<Timetable.Lesson> monday=week.days.get(LocalDate.of(2026,10,19));
        check(monday.size()==4,"Empty cells skipped, four subgroup lessons");
        check(monday.get(0).number==2 && monday.get(0).subgroup==1,"First subgroup");
        check(monday.get(3).number==4 && monday.get(3).subgroup==2,"Empty first subgroup retains column");
        check(monday.get(0).teacher.equals("Березин Д.В.") && monday.get(0).room.equals("311"),"Windows-1251, teacher and room");
        List<Timetable.Lesson> wednesday=week.days.get(LocalDate.of(2026,10,21));
        check(wednesday.size()==6 && wednesday.get(0).subgroup==0,"Colspan = whole group");
        check(week.days.get(LocalDate.of(2026,10,25)).isEmpty(),"Sunday genuinely empty");
        Timetable.Week next=Timetable.parse(Jsoup.parse(new File("tests/fixtures/next-week.html"),null,base),base);
        check(next.days.firstKey().equals(LocalDate.of(2026,10,26)),"Next week dates");
        check(!Timetable.trusted("http://mkeiit.ru/a") && !Timetable.trusted("https://mkeiit.ru.evil.test/a"),"Source origin restricted");
        check(!Timetable.trusted("https://mkeiit.ru@evil.test/a"),"User info rejected");
        boolean rejected=false;
        try { Timetable.parse(Jsoup.parse("<h1>Группа: 3СА1</h1><table class=inf></table>"),base); }
        catch(IllegalArgumentException e) { rejected=true; }
        check(rejected,"Do not cache another group");
        System.out.println("PASS: real site discovery, charset, 2 weeks, dates, empty cells, subgroups, teachers, rooms, source validation");
    }
}
