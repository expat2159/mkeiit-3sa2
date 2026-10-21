import ru.mkeiit.schedule.Bells;
import ru.mkeiit.schedule.Timetable;
import org.jsoup.Jsoup;
import java.io.*;

public class BellsTest {
    static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
    public static void main(String[] args)throws Exception{
        Bells.Source source=Bells.find(Jsoup.parse(new File("tests/fixtures/bells-index.html"),null,Timetable.SOURCE));
        check(source.title.contains("2026-2027"),"Correct academic year");
        check(source.url.endsWith("Расписание-звонков-2026-2027.png") && !source.url.contains("724x"),"Full original rather than thumbnail");
        String first="<div class='sp-wrap'><div class='sp-head'>Расписание звонков в полярную ночь 2025-2026</div><div class='sp-body'><a href='/old.png'>старое</a></div></div>";
        String second="<div class='sp-wrap'><div class='sp-head'>Расписание звонков 2026-2027</div><div class='sp-body'><img src='/current.png'></div></div>";
        check(Bells.find(Jsoup.parse(first+second,Timetable.SOURCE)).url.equals("https://mkeiit.ru/current.png"),"Skip old polar-night schedule, accept direct image");
        boolean rejected=false;
        try{Bells.find(Jsoup.parse(second.replace("/current.png","https://evil.test/fake.png"),Timetable.SOURCE));}catch(IllegalArgumentException e){rejected=true;}
        check(rejected,"Reject external image origin");
        Bells.Image original=new Bells.Image();original.title=source.title;original.url=source.url;original.bytes=new byte[]{1,2,3};original.checked=100;
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();new ObjectOutputStream(bytes).writeObject(original);
        Bells.Image restored=(Bells.Image)new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray())).readObject();
        check(restored.checked==100 && restored.bytes[2]==3 && restored.url.equals(source.url),"Offline serialization preserves image and metadata");
        System.out.println("PASS: live-page bell discovery, original image, academic year, old polar-night exclusion, image fallback, origin checks, offline storage");
    }
}
