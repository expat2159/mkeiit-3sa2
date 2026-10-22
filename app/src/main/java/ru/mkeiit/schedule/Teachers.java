package ru.mkeiit.schedule;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.*;
import org.jsoup.nodes.*;

public final class Teachers {
    public static class Entry implements Serializable {
        private static final long serialVersionUID=1L;
        public String id,name;
        public TreeMap<LocalDate,String> urls=new TreeMap<>();
    }
    public static class Lesson implements Serializable {
        private static final long serialVersionUID=1L;
        public int number;
        public String group,subject,room;
        Lesson(int number,String group,String subject,String room){this.number=number;this.group=group;this.subject=subject;this.room=room;}
    }
    public static class Week implements Serializable {
        private static final long serialVersionUID=1L;
        public String name,url,updated,fingerprint;
        public TreeMap<LocalDate,List<Lesson>> days=new TreeMap<>();
    }
    public static class Schedule implements Serializable {
        private static final long serialVersionUID=1L;
        public long checked;
        public boolean lastChanged;
        public List<Week> weeks=new ArrayList<>();
    }
    public static class State implements Serializable {
        private static final long serialVersionUID=1L;
        public long checked,revision;
        public String error="";
        public HashMap<String,String> catalogueHashes=new HashMap<>();
        public LinkedHashMap<String,Entry> entries=new LinkedHashMap<>();
        public HashMap<String,Schedule> schedules=new HashMap<>();
        public HashMap<String,String> errors=new HashMap<>();
        public LinkedHashSet<String> visited=new LinkedHashSet<>();
    }
    public static String listUrl(Document groupIndex){
        for(Element a:groupIndex.select("a[href]")){
            String url=a.absUrl("href");
            if(Timetable.trusted(url) && java.net.URI.create(url).getPath().endsWith("/cp.htm"))return url;
        }
        throw new IllegalArgumentException("На сайте не найден список преподавателей");
    }
    public static List<Entry> entries(Document document,LocalDate week){
        Element heading=document.selectFirst("h1"),table=document.selectFirst("table.inf");
        if(heading==null || !heading.text().contains("Список преподавателей") || table==null)throw new IllegalArgumentException("Изменился список преподавателей");
        List<Entry> result=new ArrayList<>();Set<String> found=new HashSet<>();
        for(Element a:table.select("a[href]")){
            String url=a.absUrl("href");if(!Timetable.trusted(url))continue;
            String path=java.net.URI.create(url).getPath();String id=path.substring(path.lastIndexOf('/')+1);
            if(!id.matches("cp\\d+\\.htm") || a.text().trim().isEmpty() || !found.add(id))continue;
            Entry entry=new Entry();entry.id=id;entry.name=a.text().trim();entry.urls.put(week,url);result.add(entry);
        }
        if(result.isEmpty())throw new IllegalArgumentException("Список преподавателей пуст");
        return result;
    }
    /** Expands both rowspan and colspan, keeping source-cell identity to avoid duplicate lessons. */
    private static List<List<Element>> grid(Element table){
        List<List<Element>> grid=new ArrayList<>();int r=0;
        for(Element row:table.select("tr")){
            if(r>=512)throw new IllegalArgumentException("Слишком большая таблица преподавателя");
            while(grid.size()<=r)grid.add(new ArrayList<>());
            int c=0;
            for(Element cell:row.children()){
                if(!cell.tagName().equals("td") && !cell.tagName().equals("th"))continue;
                while(c<grid.get(r).size() && grid.get(r).get(c)!=null)c++;
                int rows=span(cell,"rowspan"),columns=span(cell,"colspan");
                if(c+columns>32 || r+rows>512)throw new IllegalArgumentException("Изменился размер таблицы преподавателя");
                for(int y=r;y<r+rows;y++){
                    while(grid.size()<=y)grid.add(new ArrayList<>());
                    List<Element> line=grid.get(y);while(line.size()<c+columns)line.add(null);
                    for(int x=c;x<c+columns;x++){if(line.get(x)!=null)throw new IllegalArgumentException("Пересекающиеся ячейки расписания");line.set(x,cell);}
                }
                c+=columns;
            }
            r++;
        }
        return grid;
    }
    private static int span(Element cell,String attr){int n=cell.hasAttr(attr)?Integer.parseInt(cell.attr(attr)):1;if(n<1 || n>100)throw new IllegalArgumentException("Некорректная ячейка");return n;}
    public static Week parse(Document document,String url,String expectedName){
        Element heading=document.selectFirst("h1"),table=document.selectFirst("table.inf");
        if(heading==null || !heading.text().contains(expectedName) || !heading.text().contains("Преподаватель") || table==null)throw new IllegalArgumentException("Не та страница преподавателя или изменился её формат");
        Week week=new Week();week.name=expectedName;week.url=url;week.updated="";
        Matcher updated=Pattern.compile("Обновлено:\\s*(\\d{2}\\.\\d{2}\\.\\d{4}\\s+в\\s+\\d{2}:\\d{2})").matcher(document.text());
        if(updated.find())week.updated=updated.group(1);
        Pattern date=Pattern.compile("^(\\d{2}\\.\\d{2}\\.\\d{4})");DateTimeFormatter format=DateTimeFormatter.ofPattern("dd.MM.uuuu");
        for(List<Element> row:grid(table)){
            if(row.size()<3 || row.get(0)==null || row.get(1)==null)continue;
            Matcher match=date.matcher(row.get(0).text());if(!match.find() || !row.get(1).text().matches("[0-9]\\d?"))continue;
            LocalDate day=LocalDate.parse(match.group(1),format);int number=Integer.parseInt(row.get(1).text());
            if(!week.days.containsKey(day))week.days.put(day,new ArrayList<>());
            Set<Element> seen=Collections.newSetFromMap(new IdentityHashMap<Element,Boolean>());
            for(int column=2;column<row.size();column++){
                Element cell=row.get(column);if(cell==null || !seen.add(cell) || cell.text().replace('\u00a0',' ').trim().isEmpty())continue;
                String subject=cell.select(".z3").text(),group=cell.select(".z1").text(),room=cell.select(".z2").text();
                week.days.get(day).add(new Lesson(number,group,subject.isEmpty()?cell.text():subject,room));
            }
        }
        if(week.days.isEmpty())throw new IllegalArgumentException("В расписании преподавателя не найдены даты");
        return week;
    }
    public static boolean sameDays(Week a,Week b){
        if(a==null || b==null || !a.days.keySet().equals(b.days.keySet()))return false;
        for(LocalDate day:a.days.keySet()){
            List<Lesson> left=a.days.get(day),right=b.days.get(day);if(left.size()!=right.size())return false;
            for(int i=0;i<left.size();i++){Lesson x=left.get(i),y=right.get(i);if(x.number!=y.number || !Objects.equals(x.group,y.group) || !Objects.equals(x.subject,y.subject) || !Objects.equals(x.room,y.room))return false;}
        }
        return true;
    }
    public static boolean sameEntries(LinkedHashMap<String,Entry> a,LinkedHashMap<String,Entry> b){
        if(!new ArrayList<>(a.keySet()).equals(new ArrayList<>(b.keySet())))return false;
        for(String id:a.keySet()){Entry x=a.get(id),y=b.get(id);if(!Objects.equals(x.name,y.name) || !x.urls.equals(y.urls))return false;}
        return true;
    }
    public static Week week(Schedule schedule,LocalDate day){if(schedule!=null)for(Week week:schedule.weeks)if(week.days.containsKey(day))return week;return null;}
    public static List<Lesson> lessons(Schedule schedule,LocalDate day){Week w=week(schedule,day);return w==null?Collections.emptyList():w.days.get(day);}
}
