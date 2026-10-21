import ru.mkeiit.schedule.Teachers;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import java.io.*;
import java.time.LocalDate;
import java.util.*;

public class TeachersTest {
    private static final String ROOT="https://mkeiit.ru/wp-content/uploads/2025/raspisanie/";
    private static final String CURRENT="19.10.26-24.10.26",NEXT="26.10.26-31.10.26";
    private static Document page(String week,String name)throws Exception{return Jsoup.parse(new File("tests/fixtures/teachers/"+week+"-"+name),null,ROOT+week+"/"+name);}
    private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    public static void main(String[] args)throws Exception{
        LocalDate monday=LocalDate.of(2026,10,19),wednesday=monday.plusDays(2);
        List<Teachers.Entry> list=Teachers.entries(page(CURRENT,"cp.htm"),monday);
        check(list.size()>60,"Full college list rather than only 3SA2 teachers");
        check(list.get(0).name.equals("Аммосова Г.В."),"Site order and charset");
        Teachers.Entry berezin=null,drobotov=null;
        for(Teachers.Entry entry:list){if(entry.name.equals("Березин Д.В."))berezin=entry;if(entry.name.equals("Дроботов В.Н."))drobotov=entry;}
        check(berezin!=null && drobotov!=null,"Both teachers discovered by name");
        check(berezin.urls.get(monday).endsWith("/cp19.htm"),"Actual teacher URL from catalogue");
        check(Teachers.listUrl(Jsoup.parse(new File("tests/fixtures/groups.html"),null,ROOT+CURRENT+"/cg.htm")).equals(ROOT+CURRENT+"/cp.htm"),"Catalogue link discovered from navigation");
        Teachers.Week b=Teachers.parse(page(CURRENT,"cp19.htm"),berezin.urls.get(monday),berezin.name);
        check(b.days.size()==7,"Seven dates expanded from rowspans");
        check(b.days.get(wednesday).size()==6,"Colspan does not duplicate teacher lessons");
        Teachers.Lesson third=b.days.get(wednesday).get(2);
        check(third.number==3 && third.group.equals("3СА2") && third.subject.equals("МДК.02.01") && third.room.equals("308"),"Group, subject and room have teacher-specific meanings");
        check(b.days.get(wednesday).get(0).group.equals("3СА1"),"Includes other groups");
        check(b.days.get(monday.plusDays(6)).isEmpty(),"Empty Sunday is distinct from absent dates");
        Teachers.Week d=Teachers.parse(page(CURRENT,"cp25.htm"),drobotov.urls.get(monday),drobotov.name);
        check(d.days.get(wednesday).size()==6 && d.days.get(wednesday).get(4).number==5 && d.days.get(wednesday).get(4).group.equals("3СА2") && d.days.get(wednesday).get(4).room.equals("108"),"Drobotov schedule includes 3SA2 and other groups");
        check(d.days.get(wednesday).get(0).group.equals("2СА2"),"Do not confuse free group periods with free teacher periods");
        Teachers.Week next=Teachers.parse(page(NEXT,"cp19.htm"),ROOT+NEXT+"/cp19.htm",berezin.name);
        check(next.days.firstKey().equals(monday.plusWeeks(1)),"Next published week");
        Teachers.Schedule schedule=new Teachers.Schedule();schedule.weeks.add(b);schedule.weeks.add(next);schedule.checked=123;
        check(Teachers.week(schedule,monday.minusWeeks(1))==null,"Unknown date is not a day off");
        Teachers.State state=new Teachers.State();for(Teachers.Entry entry:list)state.entries.put(entry.id,entry);state.visited.add(berezin.id);state.schedules.put(berezin.id,schedule);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(ObjectOutputStream out=new ObjectOutputStream(bytes)){out.writeObject(state);}
        Teachers.State saved=(Teachers.State)new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray())).readObject();
        check(saved.entries.size()==list.size() && saved.visited.size()==1 && saved.schedules.get(berezin.id).checked==123,"Offline catalogue, visited set and schedules survive serialization");
        boolean rejected=false;try{Teachers.parse(page(CURRENT,"cp19.htm"),ROOT+CURRENT+"/cp19.htm",drobotov.name);}catch(IllegalArgumentException e){rejected=true;}check(rejected,"Reject another teacher's page");
        String html="<h1>Преподаватель: Тест Т.Т.</h1><table class=inf><tr><td rowspan=2>21.10.2026<br>Ср</td><td>1</td><td rowspan=2><a class=z1>3СА2</a><a class=z2>311</a><a class=z3>УП.01</a></td><td><a class=z1>3СА1</a><a class=z2>108</a><a class=z3>МДК</a></td></tr><tr><td>2</td><td></td></tr></table>";
        Teachers.Week mixed=Teachers.parse(Jsoup.parse(html),ROOT+CURRENT+"/cp99.htm","Тест Т.Т.");
        check(mixed.days.get(wednesday).size()==3,"Preserve simultaneous cells and vertically merged lesson");
        check(mixed.days.get(wednesday).get(2).number==2,"Merged lesson applies to both pair numbers");
        System.out.println("PASS: full teacher catalogue, two real teachers and weeks, groups/subjects/rooms, merged cells, parallel lessons, empty days, offline cache and page identity");
    }
}
