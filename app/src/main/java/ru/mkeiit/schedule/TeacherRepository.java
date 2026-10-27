package ru.mkeiit.schedule;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;

/** All calls run on background executors. Separate cache keeps old group data compatible. */
public final class TeacherRepository {
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
    public static synchronized boolean discover(Context context,Map<LocalDate,String> urls)throws IOException{
        Teachers.State state=read(context);
        try{
            if(urls.isEmpty())throw new IOException("Не найдены ссылки на списки преподавателей");
            LinkedHashMap<LocalDate,ConditionalHttpCache.Resource> resources=new LinkedHashMap<>();HashMap<String,String> hashes=new HashMap<>();
            for(Map.Entry<LocalDate,String> week:urls.entrySet()){
                ConditionalHttpCache.Resource resource=CachedNetwork.fetch(context,week.getValue(),3*1024*1024);resources.put(week.getKey(),resource);hashes.put(week.getValue(),resource.fingerprint);
            }
            boolean changed=false;
            if(!hashes.equals(state.catalogueHashes) || state.entries.isEmpty()){
                LinkedHashMap<String,Teachers.Entry> entries=new LinkedHashMap<>();
                for(Map.Entry<LocalDate,String> week:urls.entrySet()){
                    for(Teachers.Entry entry:Teachers.entries(CachedNetwork.document(resources.get(week.getKey()),week.getValue()),week.getKey())){
                        Teachers.Entry existing=entries.get(entry.id);if(existing==null)entries.put(entry.id,entry);else existing.urls.putAll(entry.urls);
                    }
                }
                if(entries.isEmpty())throw new IOException("Список преподавателей пуст");
                changed=!Teachers.sameEntries(state.entries,entries);if(changed)state.entries=entries;
            }
            state.catalogueHashes=hashes;state.checked=System.currentTimeMillis();state.error="";save(context,state);return changed;
        }catch(IOException | IllegalArgumentException e){
            state.error="Не удалось проверить список преподавателей. Сохранённые данные доступны.";save(context,state);
            if(e instanceof IOException)throw (IOException)e;throw new IOException(e.getMessage(),e);
        }
    }
    public static synchronized Teachers.State refreshOne(Context context,String id,boolean force)throws IOException{
        Teachers.State state=read(context);Teachers.Entry entry=state.entries.get(id);
        if(entry==null)throw new IOException("Преподаватель отсутствует в опубликованном списке");
        Teachers.Schedule old=state.schedules.get(id);
        boolean coversWeeks=old!=null;
        if(old!=null)for(LocalDate start:entry.urls.keySet()){
            boolean found=false;for(Teachers.Week week:old.weeks)if(week.days.containsKey(start))found=true;
            if(!found)coversWeeks=false;
        }
        if(!force && old!=null && coversWeeks && !state.errors.containsKey(id) && System.currentTimeMillis()-old.checked<Repository.DAY)return state;
        if(state.visited.add(id))save(context,state);
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
            schedule.lastChanged=changed;schedule.checked=System.currentTimeMillis();state.schedules.put(id,schedule);state.errors.remove(id);save(context,state);return state;
        }catch(IOException | IllegalArgumentException e){
            state.errors.put(id,"Не удалось обновить расписание преподавателя. Проверьте интернет или попробуйте позже.");save(context,state);
            if(e instanceof IOException)throw (IOException)e;throw new IOException(e.getMessage(),e);
        }
    }
    public static class RefreshResult {public int changed,failed;}
    public static RefreshResult refreshVisited(Context context){
        RefreshResult result=new RefreshResult();Teachers.State snapshot=read(context);
        ExecutorService pool=Executors.newFixedThreadPool(3);
        Map<String,Future<ConditionalHttpCache.Resource>> requests=new LinkedHashMap<>();
        try{
            for(String id:snapshot.visited){Teachers.Entry entry=snapshot.entries.get(id);if(entry!=null)for(String url:entry.urls.values())if(!requests.containsKey(url))requests.put(url,pool.submit(()->CachedNetwork.fetch(context,url,3*1024*1024)));}
            Map<String,ConditionalHttpCache.Resource> fetched=new HashMap<>();
            for(Map.Entry<String,Future<ConditionalHttpCache.Resource>> request:requests.entrySet()){
                try{fetched.put(request.getKey(),request.getValue().get());}
                catch(InterruptedException e){Thread.currentThread().interrupt();result.failed++;return result;}
                catch(ExecutionException e){/* Each teacher retains its old data on failure. */}
            }
            synchronized(TeacherRepository.class){
                Teachers.State state=read(context);
                for(String id:snapshot.visited){
                    Teachers.Entry entry=state.entries.get(id),original=snapshot.entries.get(id);Teachers.Schedule old=state.schedules.get(id);
                    if(entry==null || original==null || !entry.name.equals(original.name) || !entry.urls.equals(original.urls)){result.failed++;continue;}
                    try{
                        Teachers.Schedule schedule=new Teachers.Schedule();boolean changed=false;
                        for(String url:entry.urls.values()){
                            ConditionalHttpCache.Resource resource=fetched.get(url);if(resource==null)throw new IOException("Не удалось проверить файл");
                            Teachers.Week cached=null;if(old!=null)for(Teachers.Week week:old.weeks)if(url.equals(week.url) && entry.name.equals(week.name)){cached=week;break;}
                            Teachers.Week week;
                            if(cached!=null && resource.fingerprint.equals(cached.fingerprint))week=cached;
                            else{week=Teachers.parse(CachedNetwork.document(resource,url),url,entry.name);week.fingerprint=resource.fingerprint;if(Teachers.sameDays(cached,week))week.days=cached.days;else changed=true;}
                            schedule.weeks.add(week);
                        }
                        schedule.lastChanged=changed;schedule.checked=System.currentTimeMillis();state.schedules.put(id,schedule);state.errors.remove(id);if(changed)result.changed++;
                    }catch(IOException | IllegalArgumentException e){state.errors.put(id,"Не удалось обновить расписание преподавателя. Проверьте интернет или попробуйте позже.");result.failed++;}
                }
                try{if(!Thread.currentThread().isInterrupted())save(context,state);}catch(IOException e){result.failed++;}
            }
            return result;
        }finally{pool.shutdownNow();}
    }
}
