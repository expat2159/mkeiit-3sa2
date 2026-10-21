package ru.mkeiit.schedule;

import android.content.Context;
import android.util.AtomicFile;
import android.graphics.BitmapFactory;
import java.io.*;
import java.time.LocalDate;
import java.util.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

public final class Repository {
    public static final long DAY=24L*60*60*1000;
    public static class Snapshot implements Serializable {
        private static final long serialVersionUID=1L;
        public long checked;
        public List<Timetable.Week> weeks=new ArrayList<>();
        public Bells.Image bells;
        public String bellsError;
    }
    public static synchronized Snapshot read(Context context) {
        try(ObjectInputStream in=new ObjectInputStream(new AtomicFile(new File(context.getFilesDir(),"schedule.bin")).openRead())) {
            return (Snapshot)in.readObject();
        } catch(Exception e) { return new Snapshot(); }
    }
    static Document fetch(String url) throws IOException {
        if(!Timetable.trusted(url)) throw new IOException("Недопустимый адрес расписания");
        if(Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
        // Jsoup detects windows-1251 from HTML metadata as well as UTF-8.
        return Jsoup.connect(url).userAgent("MKEIIT-3SA2/1.0 (Android)")
            .timeout(25000).maxBodySize(3*1024*1024).followRedirects(false).get();
    }
    public static synchronized Snapshot refresh(Context context) throws IOException {
        try {
            Document index=fetch(Timetable.SOURCE);
            TreeMap<LocalDate,String> links=Timetable.weeks(index);
            if(links.isEmpty()) throw new IOException("Ссылки на недели не найдены. Возможно, сайт изменился.");
            LocalDate today=LocalDate.now(java.time.ZoneId.of("Europe/Moscow"));
            LocalDate monday=today.with(java.time.DayOfWeek.MONDAY);
            List<LocalDate> selected=new ArrayList<>();
            LocalDate previous=links.floorKey(monday);
            if(previous!=null) selected.add(previous);
            LocalDate next=links.higherKey(monday);
            if(next!=null) selected.add(next);
            if(selected.isEmpty()) selected.add(links.lastKey());
            Snapshot result=new Snapshot();
            try {
                Bells.Source source=Bells.find(index);
                byte[] bytes=Jsoup.connect(source.url).userAgent("MKEIIT-3SA2/1.2 (Android)")
                    .ignoreContentType(true).followRedirects(false).timeout(25000).maxBodySize(8*1024*1024).execute().bodyAsBytes();
                BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
                BitmapFactory.decodeByteArray(bytes,0,bytes.length,bounds);
                if(bytes.length>=8*1024*1024 || bounds.outWidth<=0 || bounds.outHeight<=0 || (long)bounds.outWidth*bounds.outHeight>25000000)
                    throw new IOException("Не удалось прочитать изображение звонков");
                result.bells=new Bells.Image();result.bells.title=source.title;result.bells.url=source.url;
                result.bells.bytes=bytes;result.bells.checked=System.currentTimeMillis();
            } catch(IOException | IllegalArgumentException e) {
                result.bells=read(context).bells;
                result.bellsError="Не удалось обновить звонки. Попробуйте позже; сохранённое изображение остаётся доступным.";
            }
            TreeMap<LocalDate,String> teacherIndexes=new TreeMap<>();
            for(LocalDate start:selected) {
                Document groups=fetch(links.get(start));
                try{teacherIndexes.put(start,Teachers.listUrl(groups));}catch(IllegalArgumentException ignored){ }
                String group=Timetable.groupUrl(groups);
                result.weeks.add(Timetable.parse(fetch(group),group));
            }
            if(Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
            result.checked=System.currentTimeMillis();
            AtomicFile file=new AtomicFile(new File(context.getFilesDir(),"schedule.bin"));
            FileOutputStream stream=file.startWrite();
            try {
                ObjectOutputStream out=new ObjectOutputStream(stream);
                out.writeObject(result); out.flush(); file.finishWrite(stream);
            } catch(Exception e) { file.failWrite(stream); throw e; }
            try{TeacherRepository.discover(context,teacherIndexes);}catch(IOException ignored){ }
            TeacherRepository.refreshVisited(context);
            return result;
        } catch(IllegalArgumentException e) { throw new IOException(e.getMessage(),e); }
    }
}
