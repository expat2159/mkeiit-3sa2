package ru.mkeiit.schedule;
import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Deterministic comparison: one future file is deliberately slow. Not a phone/network benchmark. */
public class RefreshLatencyBenchmark {
 static final DateTimeFormatter SHORT=DateTimeFormatter.ofPattern("dd.MM.yy"),LONG=DateTimeFormatter.ofPattern("dd.MM.uuuu");
 static String base(LocalDate date){return "https://mkeiit.ru/wp-content/uploads/2025/raspisanie/"+date.format(SHORT)+"-"+date.plusDays(5).format(SHORT)+"/";}
 static ConditionalHttpCache.Response response(String html){return new ConditionalHttpCache.Response(200,html.getBytes(StandardCharsets.UTF_8));}
 public static void main(String[] args)throws Exception{
  File dir=Files.createTempDirectory("refresh-benchmark").toFile();Context ctx=new Context(dir);File http=new File(dir,"http");http.mkdirs();
  LocalDate current=LocalDate.now(ZoneId.of("Europe/Moscow")).with(DayOfWeek.MONDAY),slow=current.plusWeeks(1),selected=current.plusWeeks(2);
  List<LocalDate> weeks=Arrays.asList(current,slow,selected);StringBuilder index=new StringBuilder("<div class=sp-wrap><div class=sp-head>Расписание звонков</div><div class=sp-body><a href='https://mkeiit.ru/bells.png'>Звонки</a></div></div>");
  for(LocalDate day:weeks)index.append("<a href='").append(base(day)).append("cg.htm'>неделя</a>");
  CachedNetwork.reset(http,url->{
   if(url.equals(Timetable.SOURCE))return response(index.toString());if(url.endsWith("bells.png"))return response("image");
   for(LocalDate day:weeks)if(url.equals(base(day)+"cg.htm"))return response("<a href='cg557.htm'>3СА2</a><a href='cp.htm'>Преподаватели</a>");
   for(LocalDate day:weeks)if(url.equals(base(day)+"cg557.htm")){
    if(day.equals(slow))try{Thread.sleep(1000);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException();}
    return response("<h1>Группа: 3СА2</h1><table class=inf><tr><td>"+day.format(LONG)+"</td><td>1</td><td colspan=2><a class=z1>Пара</a></td></tr></table>");
   }
   if(url.endsWith("cp.htm"))return response("<h1>Список преподавателей</h1><table class=inf><tr><td><a href='cp1.htm'>Первый П.П.</a></td></tr></table>");
   throw new IOException("unknown "+url);
  });
  long start=System.nanoTime();long[] selectedAt={-1};Consumer<Repository.Snapshot> progress=s->{if(selectedAt[0]<0 && s.weeks.stream().anyMatch(w->w.days.containsKey(selected)))selectedAt[0]=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);};
  try{java.lang.reflect.Method m=Repository.class.getMethod("refresh",Context.class,LocalDate.class,Consumer.class,Consumer.class);m.invoke(null,ctx,selected,progress,null);}
  catch(NoSuchMethodException e){Repository.refresh(ctx,progress);}
  if(selectedAt[0]<0)throw new AssertionError("selected week not shown");
  System.out.println("Selected week visible after "+selectedAt[0]+"ms; full refresh "+TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)+"ms (simulated 1000ms slow file)");
 }
}
