package ru.mkeiit.schedule;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Network calls run outside the short lock protecting the teacher cache. */
public final class TeacherRepository {
    private static final ConcurrentHashMap<String,Object> TEACHER_LOCKS=new ConcurrentHashMap<>();
    public static synchronized Teachers.State read(Context context){
        try(ObjectInputStream in=new ObjectInputStream(new AtomicFile(new File(context.getFilesDir(),"teachers.bin")).openRead())){Teachers.State state=(Teachers.State)in.readObject();if(state.catalogueHashes==null)state.catalogueHashes=new HashMap<>();return state;}
        catch(Exception e){return new Teachers.State();}
    }
    private static void save(Context context,Teachers.State state)throws IOException{
        state.revision=Math.max(System.currentTimeMillis(),state.revision+1);
        AtomicFile file=new AtomicFile(new File(context.getFilesDir(),"teachers.bin"));FileOutputStream stream=file.startWrite();
        try{ObjectOutputStream out=new ObjectOutputStream(stream);out.writeObject(state);out.flush();file.finishWrite(stream);}
        catch(IOException e){file.failWrite(stream);throw e;}
    }
    public static boolean discover(Context context,Map<LocalDate,String> urls)throws IOException{
        ExecutorService pool=Executors.newFixedThreadPool(3);
        try{
            if(urls.isEmpty())throw new IOException("Не найдены ссылки на списки преподавателей");
            Map<String,Future<ConditionalHttpCache.Resource>> requests=new HashMap<>();
            for(String url:urls.values())if(!requests.containsKey(url))requests.put(url,pool.submit(()->CachedNetwork.fetch(context,url,3*1024*1024)));
            LinkedHashMap<LocalDate,ConditionalHttpCache.Resource> resources=new LinkedHashMap<>();HashMap<String,String> hashes=new HashMap<>();
            for(Map.Entry<LocalDate,String> week:urls.entrySet()){
                ConditionalHttpCache.Resource resource=await(requests.get(week.getValue()));resources.put(week.getKey(),resource);hashes.put(week.getValue(),resource.fingerprint);
            }
            Teachers.State initial=read(context);LinkedHashMap<String,Teachers.Entry> entries=null;
            if(!hashes.equals(initial.catalogueHashes) || initial.entries.isEmpty()){
                entries=new LinkedHashMap<>();
                for(Map.Entry<LocalDate,String> week:urls.entrySet())for(Teachers.Entry entry:Teachers.entries(CachedNetwork.document(resources.get(week.getKey()),week.getValue()),week.getKey())){
                    Teachers.Entry existing=entries.get(entry.id);if(existing==null)entries.put(entry.id,entry);else existing.urls.putAll(entry.urls);
                }
                if(entries.isEmpty())throw new IOException("Список преподавателей пуст");
            }
            if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();
            synchronized(TeacherRepository.class){
                Teachers.State state=read(context);boolean changed=false;
                if(entries!=null){changed=!Teachers.sameEntries(state.entries,entries);if(changed)state.entries=entries;}
                state.catalogueHashes=hashes;state.checked=System.currentTimeMillis();state.error="";save(context,state);return changed;
            }
        }catch(IOException | IllegalArgumentException e){
            if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();
            synchronized(TeacherRepository.class){Teachers.State state=read(context);state.error="Не удалось проверить список преподавателей. Сохранённые данные доступны.";save(context,state);}
            if(e instanceof IOException)throw (IOException)e;throw new IOException(e.getMessage(),e);
        }finally{pool.shutdownNow();}
    }
    private static boolean sameEntry(Teachers.Entry a,Teachers.Entry b){return a!=null && b!=null && a.name.equals(b.name) && a.urls.equals(b.urls);}
    public static Teachers.State refreshOne(Context context,String id,boolean force)throws IOException{
        Object teacherLock=TEACHER_LOCKS.computeIfAbsent(id,key->new Object());
        synchronized(teacherLock){
            Teachers.Entry entry;Teachers.Schedule old;
            synchronized(TeacherRepository.class){
                Teachers.State state=read(context);entry=state.entries.get(id);
                if(entry==null)throw new IOException("Преподаватель отсутствует в опубликованном списке");
                old=state.schedules.get(id);boolean coversWeeks=old!=null;
                if(old!=null)for(LocalDate start:entry.urls.keySet()){
                    boolean found=false;for(Teachers.Week week:old.weeks)if(week.days.containsKey(start))found=true;if(!found)coversWeeks=false;
                }
                if(!force && old!=null && coversWeeks && !state.errors.containsKey(id) && System.currentTimeMillis()-old.checked<Repository.DAY)return state;
                if(state.visited.add(id))save(context,state);
            }
            try{
                Teachers.Schedule schedule=new Teachers.Schedule();boolean changed=false;
                for(String url:entry.urls.values()){
                    if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();
                    ConditionalHttpCache.Resource resource=CachedNetwork.fetch(context,url,3*1024*1024);
                    Teachers.Week cached=null;if(old!=null)for(Teachers.Week week:old.weeks)if(url.equals(week.url) && entry.name.equals(week.name)){cached=week;break;}
                    Teachers.Week week;
                    if(cached!=null && resource.fingerprint.equals(cached.fingerprint))week=cached;
                    else{week=Teachers.parse(CachedNetwork.document(resource,url),url,entry.name);week.fingerprint=resource.fingerprint;if(Teachers.sameDays(cached,week))week.days=cached.days;else changed=true;}
                    schedule.weeks.add(week);
                }
                if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();
                schedule.lastChanged=changed;schedule.checked=System.currentTimeMillis();
                synchronized(TeacherRepository.class){
                    Teachers.State state=read(context);if(!sameEntry(entry,state.entries.get(id)))throw new IOException("Список преподавателей изменился во время проверки");
                    state.schedules.put(id,schedule);state.errors.remove(id);save(context,state);return state;
                }
            }catch(IOException | IllegalArgumentException e){
                if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();
                synchronized(TeacherRepository.class){Teachers.State state=read(context);state.errors.put(id,"Не удалось обновить расписание преподавателя. Проверьте интернет или попробуйте позже.");save(context,state);}
                if(e instanceof IOException)throw (IOException)e;throw new IOException(e.getMessage(),e);
            }
        }
    }
    private static <T> T await(Future<T> future)throws IOException{
        try{return future.get();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException();}catch(ExecutionException e){Throwable cause=e.getCause();if(cause instanceof IOException)throw (IOException)cause;throw new IOException(cause);}
    }
    private static final class TeacherResult {Teachers.State state;boolean changed;boolean failed;}
    public static class RefreshResult {public int changed,failed;}
    public static RefreshResult refreshVisited(Context context){return refreshVisited(context,null);}
    public static RefreshResult refreshVisited(Context context,Consumer<Teachers.State> progress){
        RefreshResult result=new RefreshResult();List<String> visited=new ArrayList<>(read(context).visited);
        ExecutorService pool=Executors.newFixedThreadPool(3);CompletionService<TeacherResult> completed=new ExecutorCompletionService<>(pool);
        try{
            for(String id:visited)completed.submit(()->{
                TeacherResult item=new TeacherResult();
                try{item.state=refreshOne(context,id,true);Teachers.Schedule schedule=item.state.schedules.get(id);item.changed=schedule!=null && schedule.lastChanged;}
                catch(IOException e){item.failed=true;item.state=read(context);}
                return item;
            });
            for(int i=0;i<visited.size();i++){
                if(Thread.currentThread().isInterrupted()){result.failed++;return result;}
                TeacherResult item=await(completed.take());if(item.failed)result.failed++;if(item.changed)result.changed++;
                if(progress!=null)progress.accept(item.state);
            }
            return result;
        }catch(IOException | InterruptedException e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();result.failed++;return result;}
        finally{pool.shutdownNow();}
    }
}
