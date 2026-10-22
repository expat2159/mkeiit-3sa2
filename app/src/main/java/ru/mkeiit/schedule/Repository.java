package ru.mkeiit.schedule;

import android.content.Context;
import android.util.AtomicFile;
import android.graphics.BitmapFactory;
import java.io.*;
import java.time.LocalDate;
import java.util.*;
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
    public static synchronized Snapshot refresh(Context context)throws IOException{
        Snapshot old=read(context);
        try{
            Document index=CachedNetwork.document(context,Timetable.SOURCE);
            TreeMap<LocalDate,String> links=Timetable.weeks(index);
            if(links.isEmpty())throw new IOException("Ссылки на недели не найдены. Возможно, сайт изменился.");
            LocalDate monday=LocalDate.now(java.time.ZoneId.of("Europe/Moscow")).with(java.time.DayOfWeek.MONDAY);
            List<LocalDate> selected=new ArrayList<>();LocalDate previous=links.floorKey(monday),next=links.higherKey(monday);
            if(previous!=null)selected.add(previous);if(next!=null)selected.add(next);if(selected.isEmpty())selected.add(links.lastKey());
            Snapshot result=new Snapshot();List<String> changes=new ArrayList<>();boolean partial=false,groupChanged=false;
            try{
                Bells.Source source=Bells.find(index);ConditionalHttpCache.Resource image=CachedNetwork.fetch(context,source.url,8*1024*1024);
                boolean same=old.bells!=null && old.bells.bytes!=null && Arrays.equals(old.bells.bytes,image.bytes);
                if(!same){
                    BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(image.bytes,0,image.bytes.length,bounds);
                    if(bounds.outWidth<=0 || bounds.outHeight<=0 || (long)bounds.outWidth*bounds.outHeight>25000000)throw new IOException("Не удалось прочитать изображение звонков");
                    changes.add("звонки");
                }
                result.bells=new Bells.Image();result.bells.title=source.title;result.bells.url=source.url;result.bells.fingerprint=image.fingerprint;
                result.bells.bytes=same?old.bells.bytes:image.bytes;result.bells.checked=System.currentTimeMillis();
            }catch(IOException | IllegalArgumentException e){result.bells=old.bells;result.bellsError="Не удалось проверить звонки. Сохранённое изображение остаётся доступным.";partial=true;}
            TreeMap<LocalDate,String> teacherIndexes=new TreeMap<>();
            for(LocalDate start:selected){
                Document groups=CachedNetwork.document(context,links.get(start));
                try{teacherIndexes.put(start,Teachers.listUrl(groups));}catch(IllegalArgumentException ignored){ }
                String url=Timetable.groupUrl(groups);ConditionalHttpCache.Resource resource=CachedNetwork.fetch(context,url,3*1024*1024);
                Timetable.Week cached=oldWeek(old,url),week;
                if(cached!=null && resource.fingerprint.equals(cached.fingerprint))week=cached;
                else{
                    week=Timetable.parse(CachedNetwork.document(resource,url),url);week.fingerprint=resource.fingerprint;
                    if(Timetable.sameDays(cached,week))week.days=cached.days;else groupChanged=true;
                }
                result.weeks.add(week);
            }
            if(groupChanged)changes.add(0,"расписание 3СА2");
            if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();
            result.checked=System.currentTimeMillis();
            try{if(TeacherRepository.discover(context,teacherIndexes))changes.add("список преподавателей");}catch(IOException e){partial=true;}
            TeacherRepository.RefreshResult teachers=TeacherRepository.refreshVisited(context);
            if(teachers.changed>0)changes.add("расписания преподавателей: "+teachers.changed);
            if(teachers.failed>0)partial=true;
            result.syncMessage=changes.isEmpty()?"Изменений нет":"Обновлено: "+join(changes);
            if(partial)result.syncMessage=changes.isEmpty()?"Не всё удалось проверить. Сохранённые данные доступны.":result.syncMessage+". Не всё удалось проверить.";
            AtomicFile file=new AtomicFile(new File(context.getFilesDir(),"schedule.bin"));FileOutputStream stream=file.startWrite();
            try{ObjectOutputStream output=new ObjectOutputStream(stream);output.writeObject(result);output.flush();file.finishWrite(stream);}
            catch(IOException e){file.failWrite(stream);throw e;}
            return result;
        }catch(IllegalArgumentException e){throw new IOException(e.getMessage(),e);}
    }
    private static String join(List<String> values){StringBuilder result=new StringBuilder();for(String value:values){if(result.length()>0)result.append(", ");result.append(value);}return result.toString();}
}
