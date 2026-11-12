package ru.mkeiit.schedule;

import android.content.Context;
import android.util.AtomicFile;
import android.graphics.BitmapFactory;
import java.io.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import org.jsoup.nodes.Document;

public final class Repository {
    public static final long DAY=24L*60*60*1000;
    // Only other refreshes wait for the network. Readers and atomic commits use a separate short lock.
    private static final ReentrantLock REFRESH_LOCK=new ReentrantLock();
    private static final Object STORE_LOCK=new Object();
    public static class Snapshot implements Serializable {
        private static final long serialVersionUID=1L;
        public long checked;
        public List<Timetable.Week> weeks=new ArrayList<>();
        public Bells.Image bells;
        public String bellsError,syncMessage;
    }
    public static Snapshot read(Context context){
        synchronized(STORE_LOCK){
            try(ObjectInputStream in=new ObjectInputStream(new AtomicFile(new File(context.getFilesDir(),"schedule.bin")).openRead())){return (Snapshot)in.readObject();}
            catch(Exception e){return new Snapshot();}
        }
    }
    private static Snapshot copy(Snapshot data){
        Snapshot result=new Snapshot();result.checked=data.checked;result.weeks=new ArrayList<>(data.weeks);
        result.bells=data.bells;result.bellsError=data.bellsError;result.syncMessage=data.syncMessage;return result;
    }
    private static Timetable.Week oldWeek(Snapshot old,String url){for(Timetable.Week week:old.weeks)if(url.equals(week.url))return week;return null;}
    private static final class WeekResult {
        LocalDate start;String teacherUrl;Timetable.Week week;boolean changed;Exception error;
    }
    private static void replaceWeek(Snapshot snapshot,LocalDate start,Timetable.Week week){
        for(Iterator<Timetable.Week> it=snapshot.weeks.iterator();it.hasNext();){
            Timetable.Week existing=it.next();
            if(existing.url.equals(week.url) || existing.days.containsKey(start))it.remove();
        }
        snapshot.weeks.add(week);
    }
    public static Snapshot refresh(Context context)throws IOException{return refresh(context,null,null,null);}
    public static Snapshot refresh(Context context,Consumer<Snapshot> progress)throws IOException{return refresh(context,null,progress,null);}
    public static Snapshot refresh(Context context,LocalDate preferred,Consumer<Snapshot> progress,Consumer<Teachers.State> teacherProgress)throws IOException{
        try{REFRESH_LOCK.lockInterruptibly();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException();}
        try{return doRefresh(context,preferred,progress,teacherProgress);}finally{REFRESH_LOCK.unlock();}
    }
    private static Snapshot doRefresh(Context context,LocalDate preferred,Consumer<Snapshot> progress,Consumer<Teachers.State> teacherProgress)throws IOException{
        Snapshot old=read(context),result=copy(old);result.syncMessage=null;
        try{
            Document index=CachedNetwork.document(context,Timetable.SOURCE);
            TreeMap<LocalDate,String> links=Timetable.weeks(index);
            if(links.isEmpty())throw new IOException("Ссылки на недели не найдены. Возможно, сайт изменился.");
            LocalDate monday=LocalDate.now(java.time.ZoneId.of("Europe/Moscow")).with(java.time.DayOfWeek.MONDAY);
            List<LocalDate> ordered=RefreshOrder.weeks(links,monday,preferred);
            List<String> changes=new ArrayList<>();boolean partial=false,groupChanged=false;
            ExecutorService pool=Executors.newFixedThreadPool(3);
            ExecutorService bellPool=Executors.newSingleThreadExecutor();
            try{
                Bells.Source bellSource=null;Future<ConditionalHttpCache.Resource> bellRequest=null;
                try{bellSource=Bells.find(index);final String url=bellSource.url;bellRequest=bellPool.submit(()->CachedNetwork.fetch(context,url,8*1024*1024));}catch(IllegalArgumentException ignored){}
                CompletionService<WeekResult> completion=new ExecutorCompletionService<>(pool);
                for(LocalDate start:ordered){
                    final String indexUrl=links.get(start);
                    completion.submit(()->{
                        WeekResult item=new WeekResult();item.start=start;
                        try{
                            Document groups=CachedNetwork.document(context,indexUrl);
                            try{item.teacherUrl=Teachers.listUrl(groups);}catch(IllegalArgumentException ignored){}
                            String url=Timetable.groupUrl(groups);ConditionalHttpCache.Resource resource=CachedNetwork.fetch(context,url,3*1024*1024);
                            Timetable.Week cached=oldWeek(old,url);
                            if(cached!=null && resource.fingerprint.equals(cached.fingerprint))item.week=cached;
                            else{item.week=Timetable.parse(CachedNetwork.document(resource,url),url);item.week.fingerprint=resource.fingerprint;if(Timetable.sameDays(cached,item.week))item.week.days=cached.days;else item.changed=true;}
                        }catch(IOException | IllegalArgumentException e){item.error=e;}
                        return item;
                    });
                }
                TreeMap<LocalDate,String> teacherIndexes=new TreeMap<>();
                for(int i=0;i<ordered.size();i++){
                    WeekResult item=await(completion.take());
                    if(item.teacherUrl!=null)teacherIndexes.put(item.start,item.teacherUrl);
                    if(item.error!=null || item.week==null){partial=true;continue;}
                    replaceWeek(result,item.start,item.week);groupChanged|=item.changed;
                    if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();
                    save(context,result);if(progress!=null)progress.accept(copy(result));
                }
                try{
                    if(bellSource==null || bellRequest==null)throw new IOException("Не найдены звонки");
                    ConditionalHttpCache.Resource image=await(bellRequest);boolean same=old.bells!=null && old.bells.bytes!=null && Arrays.equals(old.bells.bytes,image.bytes);
                    if(!same){BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(image.bytes,0,image.bytes.length,bounds);if(bounds.outWidth<=0 || bounds.outHeight<=0 || (long)bounds.outWidth*bounds.outHeight>25000000)throw new IOException("Не удалось прочитать изображение звонков");changes.add("звонки");}
                    Bells.Image bells=new Bells.Image();bells.title=bellSource.title;bells.url=bellSource.url;bells.fingerprint=image.fingerprint;bells.bytes=same?old.bells.bytes:image.bytes;bells.checked=System.currentTimeMillis();result.bells=bells;result.bellsError=null;
                    save(context,result);if(progress!=null)progress.accept(copy(result));
                }catch(IOException | IllegalArgumentException e){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();result.bells=old.bells;result.bellsError="Не удалось проверить звонки. Сохранённое изображение остаётся доступным.";partial=true;}
                if(groupChanged)changes.add(0,"расписание 3СА2");
                if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();
                if(teacherIndexes.size()==ordered.size()){
                    try{if(TeacherRepository.discover(context,teacherIndexes))changes.add("список преподавателей");if(teacherProgress!=null)teacherProgress.accept(TeacherRepository.read(context));}catch(IOException e){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();partial=true;}
                }else partial=true;
                TeacherRepository.RefreshResult teachers=TeacherRepository.refreshVisited(context,teacherProgress);
                if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();
                if(teachers.changed>0)changes.add("расписания преподавателей: "+teachers.changed);
                if(teachers.failed>0)partial=true;
                result.checked=System.currentTimeMillis();
                result.syncMessage=changes.isEmpty()?"Изменений нет":"Обновлено: "+join(changes);
                if(partial)result.syncMessage=changes.isEmpty()?"Не всё удалось проверить. Сохранённые данные доступны.":result.syncMessage+". Не всё удалось проверить.";
                save(context,result);return copy(result);
            }catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException();}
            finally{pool.shutdownNow();bellPool.shutdownNow();}
        }catch(IllegalArgumentException e){throw new IOException(e.getMessage(),e);}
    }
    private static <T> T await(Future<T> future)throws IOException{
        try{return future.get();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException();}catch(ExecutionException e){Throwable cause=e.getCause();if(cause instanceof IOException)throw (IOException)cause;throw new IOException(cause);}
    }
    private static void save(Context context,Snapshot result)throws IOException{
        synchronized(STORE_LOCK){
            AtomicFile file=new AtomicFile(new File(context.getFilesDir(),"schedule.bin"));FileOutputStream stream=file.startWrite();
            try{ObjectOutputStream output=new ObjectOutputStream(stream);output.writeObject(result);output.flush();file.finishWrite(stream);}
            catch(IOException e){file.failWrite(stream);throw e;}
        }
    }
    private static String join(List<String> values){StringBuilder result=new StringBuilder();for(String value:values){if(result.length()>0)result.append(", ");result.append(value);}return result.toString();}
}
