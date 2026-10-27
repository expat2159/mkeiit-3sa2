package ru.mkeiit.schedule;

import android.content.Context;
import android.util.AtomicFile;
import android.graphics.BitmapFactory;
import java.io.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.jsoup.nodes.Document;

public final class Repository {
    public static final long DAY=24L*60*60*1000;
    public static class Snapshot implements Serializable {
        private static final long serialVersionUID=1L;
        public long checked;
        public List<Timetable.Week> weeks=new ArrayList<>();
        public Bells.Image bells;
        public String bellsError,syncMessage;
    }
    public static synchronized Snapshot read(Context context){
        try(ObjectInputStream in=new ObjectInputStream(new AtomicFile(new File(context.getFilesDir(),"schedule.bin")).openRead())){return (Snapshot)in.readObject();}
        catch(Exception e){return new Snapshot();}
    }
    private static Timetable.Week oldWeek(Snapshot old,String url){for(Timetable.Week week:old.weeks)if(url.equals(week.url))return week;return null;}
    public static Snapshot refresh(Context context)throws IOException{return refresh(context,null);}
    public static synchronized Snapshot refresh(Context context,Consumer<Snapshot> progress)throws IOException{
        Snapshot old=read(context);
        try{
            Document index=CachedNetwork.document(context,Timetable.SOURCE);
            TreeMap<LocalDate,String> links=Timetable.weeks(index);
            if(links.isEmpty())throw new IOException("Ссылки на недели не найдены. Возможно, сайт изменился.");
            LocalDate monday=LocalDate.now(java.time.ZoneId.of("Europe/Moscow")).with(java.time.DayOfWeek.MONDAY);
            List<LocalDate> selected=new ArrayList<>();LocalDate previous=links.floorKey(monday),next=links.higherKey(monday);
            if(previous!=null)selected.add(previous);if(next!=null)selected.add(next);if(selected.isEmpty())selected.add(links.lastKey());
            Snapshot result=new Snapshot();List<String> changes=new ArrayList<>();boolean partial=false,groupChanged=false;
            ExecutorService pool=Executors.newFixedThreadPool(3);
            try{
            Future<ConditionalHttpCache.Resource> bellRequest=null;Bells.Source bellSource=null;
            try{bellSource=Bells.find(index);final String bellUrl=bellSource.url;bellRequest=pool.submit(()->CachedNetwork.fetch(context,bellUrl,8*1024*1024));}catch(IllegalArgumentException ignored){}
            TreeMap<LocalDate,Future<Document>> groupRequests=new TreeMap<>();
            for(LocalDate start:selected){String link=links.get(start);groupRequests.put(start,pool.submit(()->CachedNetwork.document(context,link)));}
            TreeMap<LocalDate,String> teacherIndexes=new TreeMap<>();
            TreeMap<LocalDate,Future<ConditionalHttpCache.Resource>> weekRequests=new TreeMap<>();TreeMap<LocalDate,String> weekUrls=new TreeMap<>();
            for(LocalDate start:selected){
                Document groups=await(groupRequests.get(start));
                try{teacherIndexes.put(start,Teachers.listUrl(groups));}catch(IllegalArgumentException ignored){}
                String url=Timetable.groupUrl(groups);weekUrls.put(start,url);weekRequests.put(start,pool.submit(()->CachedNetwork.fetch(context,url,3*1024*1024)));
            }
            for(LocalDate start:selected){
                String url=weekUrls.get(start);ConditionalHttpCache.Resource resource=await(weekRequests.get(start));
                Timetable.Week cached=oldWeek(old,url),week;
                if(cached!=null && resource.fingerprint.equals(cached.fingerprint))week=cached;
                else{week=Timetable.parse(CachedNetwork.document(resource,url),url);week.fingerprint=resource.fingerprint;if(Timetable.sameDays(cached,week))week.days=cached.days;else groupChanged=true;}
                result.weeks.add(week);
            }
            result.bells=old.bells;result.bellsError=old.bellsError;result.checked=old.checked;
            if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();
            save(context,result);
            if(progress!=null){Snapshot early=new Snapshot();early.weeks=new ArrayList<>(result.weeks);early.bells=result.bells;early.bellsError=result.bellsError;early.checked=result.checked;progress.accept(early);}
            try{
                Bells.Source source=bellSource;if(source==null || bellRequest==null)throw new IOException("Не найдены звонки");ConditionalHttpCache.Resource image=await(bellRequest);
                boolean same=old.bells!=null && old.bells.bytes!=null && Arrays.equals(old.bells.bytes,image.bytes);
                if(!same){
                    BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(image.bytes,0,image.bytes.length,bounds);
                    if(bounds.outWidth<=0 || bounds.outHeight<=0 || (long)bounds.outWidth*bounds.outHeight>25000000)throw new IOException("Не удалось прочитать изображение звонков");
                    changes.add("звонки");
                }
                result.bellsError=null;result.bells=new Bells.Image();result.bells.title=source.title;result.bells.url=source.url;result.bells.fingerprint=image.fingerprint;
                result.bells.bytes=same?old.bells.bytes:image.bytes;result.bells.checked=System.currentTimeMillis();
            }catch(IOException | IllegalArgumentException e){result.bells=old.bells;result.bellsError="Не удалось проверить звонки. Сохранённое изображение остаётся доступным.";partial=true;}
            if(groupChanged)changes.add(0,"расписание 3СА2");
            if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();
            result.checked=System.currentTimeMillis();
            try{if(TeacherRepository.discover(context,teacherIndexes))changes.add("список преподавателей");}catch(IOException e){partial=true;}
            TeacherRepository.RefreshResult teachers=TeacherRepository.refreshVisited(context);
            if(teachers.changed>0)changes.add("расписания преподавателей: "+teachers.changed);
            if(teachers.failed>0)partial=true;
            result.syncMessage=changes.isEmpty()?"Изменений нет":"Обновлено: "+join(changes);
            if(partial)result.syncMessage=changes.isEmpty()?"Не всё удалось проверить. Сохранённые данные доступны.":result.syncMessage+". Не всё удалось проверить.";
            save(context,result);
            return result;
            }finally{pool.shutdownNow();}
        }catch(IllegalArgumentException e){throw new IOException(e.getMessage(),e);}
    }
    private static <T> T await(Future<T> future)throws IOException{
        try{return future.get();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException();}catch(ExecutionException e){Throwable cause=e.getCause();if(cause instanceof IOException)throw (IOException)cause;throw new IOException(cause);}
    }
    private static void save(Context context,Snapshot result)throws IOException{
        AtomicFile file=new AtomicFile(new File(context.getFilesDir(),"schedule.bin"));FileOutputStream stream=file.startWrite();
        try{ObjectOutputStream output=new ObjectOutputStream(stream);output.writeObject(result);output.flush();file.finishWrite(stream);}
        catch(IOException e){file.failWrite(stream);throw e;}
    }
    private static String join(List<String> values){StringBuilder result=new StringBuilder();for(String value:values){if(result.length()>0)result.append(", ");result.append(value);}return result.toString();}
}
