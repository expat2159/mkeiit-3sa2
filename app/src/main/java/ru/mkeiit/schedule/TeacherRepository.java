package ru.mkeiit.schedule;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.time.LocalDate;
import java.util.*;

/** All calls run on background executors. Separate cache keeps old group data compatible. */
public final class TeacherRepository {
    public static synchronized Teachers.State read(Context context){
        try(ObjectInputStream in=new ObjectInputStream(new AtomicFile(new File(context.getFilesDir(),"teachers.bin")).openRead())){return (Teachers.State)in.readObject();}
        catch(Exception e){return new Teachers.State();}
    }
    private static void save(Context context,Teachers.State state)throws IOException{
        state.revision=Math.max(System.currentTimeMillis(),state.revision+1);
        AtomicFile file=new AtomicFile(new File(context.getFilesDir(),"teachers.bin"));FileOutputStream stream=file.startWrite();
        try{ObjectOutputStream out=new ObjectOutputStream(stream);out.writeObject(state);out.flush();file.finishWrite(stream);}
        catch(IOException e){file.failWrite(stream);throw e;}
    }
    public static synchronized void discover(Context context,Map<LocalDate,String> urls)throws IOException{
        Teachers.State state=read(context);
        try{
            LinkedHashMap<String,Teachers.Entry> entries=new LinkedHashMap<>();
            for(Map.Entry<LocalDate,String> week:urls.entrySet()){
                for(Teachers.Entry entry:Teachers.entries(Repository.fetch(week.getValue()),week.getKey())){
                    Teachers.Entry existing=entries.get(entry.id);
                    if(existing==null)entries.put(entry.id,entry);else existing.urls.putAll(entry.urls);
                }
            }
            if(entries.isEmpty())throw new IOException("Список преподавателей пуст");
            state.entries=entries;state.checked=System.currentTimeMillis();state.error="";save(context,state);
        }catch(IOException | IllegalArgumentException e){
            state.error="Не удалось обновить список преподавателей. Сохранённые данные доступны.";save(context,state);
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
        state.visited.add(id);save(context,state);
        try{
            Teachers.Schedule schedule=new Teachers.Schedule();
            for(String url:entry.urls.values()){
                if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();
                schedule.weeks.add(Teachers.parse(Repository.fetch(url),url,entry.name));
            }
            if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();
            schedule.checked=System.currentTimeMillis();state.schedules.put(id,schedule);state.errors.remove(id);save(context,state);return state;
        }catch(IOException | IllegalArgumentException e){
            state.errors.put(id,"Не удалось обновить расписание преподавателя. Проверьте интернет или попробуйте позже.");save(context,state);
            if(e instanceof IOException)throw (IOException)e;throw new IOException(e.getMessage(),e);
        }
    }
    public static void refreshVisited(Context context){
        List<String> visited;
        synchronized(TeacherRepository.class){visited=new ArrayList<>(read(context).visited);}
        for(String id:visited){
            if(Thread.currentThread().isInterrupted())return;
            try{refreshOne(context,id,true);}catch(IOException ignored){ }
        }
    }
}
