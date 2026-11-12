package ru.mkeiit.schedule;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import org.jsoup.Jsoup;

public class RepositoryConcurrencyTest {
 static final DateTimeFormatter SHORT=DateTimeFormatter.ofPattern("dd.MM.yy"),LONG=DateTimeFormatter.ofPattern("dd.MM.uuuu");
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
 static String base(LocalDate date){return "https://mkeiit.ru/wp-content/uploads/2025/raspisanie/"+date.format(SHORT)+"-"+date.plusDays(5).format(SHORT)+"/";}
 static String group(LocalDate date,String subject){return "<h1>Группа: 3СА2</h1><table class=inf><tr><td>"+date.format(LONG)+"</td><td>1</td><td colspan=2><a class=z1>"+subject+"</a></td></tr></table>";}
 static ConditionalHttpCache.Response response(String html){return new ConditionalHttpCache.Response(200,html.getBytes(StandardCharsets.UTF_8));}
 static void serialize(File dir,String file,Object value)throws IOException{try(ObjectOutputStream out=new ObjectOutputStream(new FileOutputStream(new File(dir,file)))){out.writeObject(value);}}
 static void waitOn(CountDownLatch latch)throws IOException{try{if(!latch.await(8,TimeUnit.SECONDS))throw new IOException("test timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException();}}
 static void groupTest()throws Exception{
  File dir=Files.createTempDirectory("schedule-concurrency").toFile();Context ctx=new Context(dir);
  LocalDate current=LocalDate.now(ZoneId.of("Europe/Moscow")).with(DayOfWeek.MONDAY),slow=current.plusWeeks(1),selected=current.plusWeeks(2);
  List<LocalDate> weeks=Arrays.asList(current,slow,selected);StringBuilder index=new StringBuilder("<div class=sp-wrap><div class=sp-head>Расписание звонков</div><div class=sp-body><a href='https://mkeiit.ru/bells.png'>Звонки</a></div></div>");
  for(LocalDate date:weeks)index.append("<a href='").append(base(date)).append("cg.htm'>неделя</a>");
  Repository.Snapshot original=new Repository.Snapshot();Timetable.Week preserved=Timetable.parse(Jsoup.parse(group(slow,"Старая пара")),base(slow)+"cg557.htm");original.weeks.add(preserved);serialize(dir,"schedule.bin",original);
  CountDownLatch slowEntered=new CountDownLatch(1),release=new CountDownLatch(1),priorityReady=new CountDownLatch(1);List<Repository.Snapshot> events=Collections.synchronizedList(new ArrayList<>());List<String> starts=Collections.synchronizedList(new ArrayList<>());
  CachedNetwork.reset(new File(dir,"http"),url->{
   if(url.equals(Timetable.SOURCE))return response(index.toString());if(url.endsWith("bells.png"))return response("image");
   for(LocalDate date:weeks)if(url.equals(base(date)+"cg.htm")){starts.add(date.toString());return response("<a href='cg557.htm'>3СА2</a><a href='cp.htm'>Преподаватели</a>");}
   for(LocalDate date:weeks)if(url.equals(base(date)+"cg557.htm")){if(date.equals(slow)){slowEntered.countDown();waitOn(release);throw new IOException("simulated failure");}return response(group(date,"Новая пара"));}
   if(url.endsWith("cp.htm"))return response("<h1>Список преподавателей</h1><table class=inf><tr><td><a href='cp1.htm'>Первый П.П.</a></td></tr></table>");
   throw new IOException("unknown "+url);
  });
  ExecutorService pool=Executors.newFixedThreadPool(3);
  try{
   long start=System.nanoTime();Future<Repository.Snapshot> first=pool.submit(()->Repository.refresh(ctx,selected,s->{events.add(s);if(s.weeks.stream().anyMatch(w->w.days.containsKey(selected)))priorityReady.countDown();},null));
   check(slowEntered.await(2,TimeUnit.SECONDS),"slow week did not start");
   check(priorityReady.await(2,TimeUnit.SECONDS),"selected week should appear before slow week");
   long earlyMs=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);
   check(!first.isDone(),"refresh should still be waiting for slow week");
   Future<Repository.Snapshot> read=pool.submit(()->Repository.read(ctx));check(read.get(500,TimeUnit.MILLISECONDS).weeks.stream().anyMatch(w->w.days.containsKey(selected)),"reading cache must not wait for the network");
   CountDownLatch secondCallback=new CountDownLatch(1);Future<Repository.Snapshot> second=pool.submit(()->Repository.refresh(ctx,current,s->secondCallback.countDown(),null));
   check(!secondCallback.await(200,TimeUnit.MILLISECONDS),"concurrent refreshes must serialize commits");
   release.countDown();Repository.Snapshot finalState=first.get(5,TimeUnit.SECONDS);second.get(5,TimeUnit.SECONDS);
   check(finalState.weeks.stream().anyMatch(w->w.days.containsKey(slow)&&w.days.get(slow).get(0).subject.equals("Старая пара")),"failed future week must retain old data");
   check(finalState.syncMessage.contains("Не всё удалось проверить"),"partial error should be reported");
   check(finalState.weeks.stream().anyMatch(w->w.days.containsKey(current)),"current week missing");
   check(Repository.read(ctx).weeks.stream().anyMatch(w->w.days.containsKey(selected)),"concurrent refresh must retain selected week");
   CachedNetwork.server=url->{throw new IOException("offline");};
   try{Repository.refresh(ctx);throw new AssertionError("offline refresh should fail");}catch(IOException expected){}
   check(Repository.read(ctx).weeks.stream().anyMatch(w->w.days.containsKey(selected)),"offline failure must preserve saved weeks");
   File empty=Files.createTempDirectory("schedule-empty").toFile();Context emptyContext=new Context(empty);
   CachedNetwork.reset(new File(empty,"http"),url->{throw new IOException("offline");});
   try{Repository.refresh(emptyContext);throw new AssertionError("first offline load should fail");}catch(IOException expected){}
   check(Repository.read(emptyContext).weeks.isEmpty(),"empty offline state should stay empty");
   System.out.println("PASS: selected week visible in "+earlyMs+"ms before slow failure; cache reads and competing refresh remain safe");
  }finally{release.countDown();pool.shutdownNow();}
 }
 static String teacher(LocalDate date,String name){return "<h1>Преподаватель: "+name+"</h1><table class=inf><tr><td>"+date.format(LONG)+"</td><td>1</td><td><a class=z1>3СА2</a><a class=z2>101</a><a class=z3>Математика</a></td></tr></table>";}
 static void teacherTest()throws Exception{
  File dir=Files.createTempDirectory("teacher-concurrency").toFile();Context ctx=new Context(dir);LocalDate date=LocalDate.now(ZoneId.of("Europe/Moscow")).with(DayOfWeek.MONDAY);
  Teachers.State state=new Teachers.State();for(int i=1;i<=3;i++){Teachers.Entry e=new Teachers.Entry();e.id="cp"+i+".htm";e.name="Учитель "+i;e.urls.put(date,base(date)+e.id);state.entries.put(e.id,e);if(i<3)state.visited.add(e.id);}serialize(dir,"teachers.bin",state);
  CountDownLatch slowEntered=new CountDownLatch(1),release=new CountDownLatch(1),fastReady=new CountDownLatch(1);
  CachedNetwork.reset(new File(dir,"http"),url->{if(url.endsWith("cp1.htm")){slowEntered.countDown();waitOn(release);}for(int i=1;i<=3;i++)if(url.endsWith("cp"+i+".htm"))return response(teacher(date,"Учитель "+i));throw new IOException("unknown");});
  ExecutorService pool=Executors.newFixedThreadPool(3);
  try{
   long start=System.nanoTime();Future<TeacherRepository.RefreshResult> refresh=pool.submit(()->TeacherRepository.refreshVisited(ctx,s->{if(s.schedules.containsKey("cp2.htm"))fastReady.countDown();}));
   check(slowEntered.await(2,TimeUnit.SECONDS),"slow teacher did not start");check(fastReady.await(2,TimeUnit.SECONDS),"fast teacher held behind slow teacher");
   long earlyMs=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);check(!refresh.isDone(),"slow teacher should remain in progress");
   Future<Teachers.State> read=pool.submit(()->TeacherRepository.read(ctx));check(read.get(500,TimeUnit.MILLISECONDS).schedules.containsKey("cp2.htm"),"teacher cache read blocked by network");
   Future<Teachers.State> open=pool.submit(()->TeacherRepository.refreshOne(ctx,"cp3.htm",false));check(open.get(2,TimeUnit.SECONDS).schedules.containsKey("cp3.htm"),"different teacher blocked");
   release.countDown();check(refresh.get(5,TimeUnit.SECONDS).failed==0,"teacher refresh failed");Teachers.State saved=TeacherRepository.read(ctx);
   for(int i=1;i<=3;i++)check(saved.schedules.containsKey("cp"+i+".htm"),"concurrent update lost teacher "+i);
   check(saved.visited.contains("cp3.htm"),"concurrent visit lost");
   CachedNetwork.server=url->{if(url.endsWith("cp2.htm"))throw new IOException("offline teacher");for(int i=1;i<=3;i++)if(url.endsWith("cp"+i+".htm"))return response(teacher(date,"Учитель "+i));throw new IOException("unknown");};
   TeacherRepository.RefreshResult partial=TeacherRepository.refreshVisited(ctx);Teachers.State after=TeacherRepository.read(ctx);
   check(partial.failed==1 && after.schedules.containsKey("cp2.htm") && after.errors.containsKey("cp2.htm"),"teacher failure must preserve old schedule and report error");
   System.out.println("PASS: fast teacher visible in "+earlyMs+"ms before slow teacher; concurrent visits and schedules preserved");
  }finally{release.countDown();pool.shutdownNow();}
 }
 static void catalogueTest()throws Exception{
  File dir=Files.createTempDirectory("catalogue-concurrency").toFile();Context ctx=new Context(dir);LocalDate date=LocalDate.now(ZoneId.of("Europe/Moscow")).with(DayOfWeek.MONDAY);
  TreeMap<LocalDate,String> urls=new TreeMap<>();for(int i=0;i<3;i++){LocalDate day=date.plusWeeks(i);urls.put(day,base(day)+"cp.htm");}
  CountDownLatch allEntered=new CountDownLatch(3);
  CachedNetwork.reset(new File(dir,"http"),url->{allEntered.countDown();waitOn(allEntered);return response("<h1>Список преподавателей</h1><table class=inf><tr><td><a href='cp1.htm'>Первый П.П.</a></td></tr></table>");});
  ExecutorService pool=Executors.newSingleThreadExecutor();try{
   Future<Boolean> future=pool.submit(()->TeacherRepository.discover(ctx,urls));
   check(allEntered.await(2,TimeUnit.SECONDS),"catalogues are not checked in parallel");check(future.get(3,TimeUnit.SECONDS),"new catalogue should be detected");
   check(TeacherRepository.read(ctx).entries.get("cp1.htm").urls.size()==3,"catalogue lost weeks");
   System.out.println("PASS: three catalogue requests overlap and preserve all published weeks");
  }finally{pool.shutdownNow();}
 }
 public static void main(String[] args)throws Exception{groupTest();teacherTest();catalogueTest();}
}
