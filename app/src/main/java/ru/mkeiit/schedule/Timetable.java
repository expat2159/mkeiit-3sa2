package ru.mkeiit.schedule;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.*;
import org.jsoup.nodes.*;

public final class Timetable {
    public static final String SOURCE = "https://mkeiit.ru/?page_id=2699";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.uuuu");
    public static class Week implements Serializable {
        private static final long serialVersionUID = 1L;
        public String url, updated;
        public TreeMap<LocalDate, List<Lesson>> days = new TreeMap<>();
    }
    public static class Lesson implements Serializable {
        private static final long serialVersionUID = 1L;
        public int number, subgroup; // 0 = entire group
        public String subject, room, teacher;
        public Lesson(int n, int g, String s, String r, String t) {
            number=n; subgroup=g; subject=s; room=r; teacher=t;
        }
    }
    public static TreeMap<LocalDate,String> weeks(Document doc) {
        TreeMap<LocalDate,String> result = new TreeMap<>();
        Pattern p = Pattern.compile("/(\\d{2}\\.\\d{2}\\.\\d{2})-\\d{2}\\.\\d{2}\\.\\d{2}/cg\\.htm(?:$|[?#])");
        for (Element a: doc.select("a[href]")) {
            String url = a.absUrl("href");
            Matcher m=p.matcher(url);
            if (m.find() && trusted(url)) {
                String d=m.group(1);
                try { result.put(LocalDate.parse(d.substring(0,6)+"20"+d.substring(6),DATE),url); }
                catch (RuntimeException ignored) { }
            }
        }
        return result;
    }
    public static boolean trusted(String url) {
        try {
            java.net.URI u=java.net.URI.create(url);
            return "https".equals(u.getScheme()) && "mkeiit.ru".equalsIgnoreCase(u.getHost()) && u.getUserInfo()==null && (u.getPort()==-1 || u.getPort()==443);
        } catch(RuntimeException e) { return false; }
    }
    public static String groupUrl(Document doc) {
        for(Element a:doc.select("a[href]")) {
            if(a.text().replaceAll("\\s+", "").equalsIgnoreCase("3СА2")) {
                String url=a.absUrl("href");
                if(trusted(url)) return url;
            }
        }
        throw new IllegalArgumentException("Группа 3СА2 не найдена на сайте");
    }
    public static Week parse(Document doc, String url) {
        Element heading=doc.selectFirst("h1");
        if(heading==null || !heading.text().matches(".*\\b3СА2\\b.*")) throw new IllegalArgumentException("Страница другой группы");
        Element table=doc.selectFirst("table.inf");
        if(table==null) throw new IllegalArgumentException("Изменился формат таблицы расписания");
        Week week=new Week(); week.url=url; week.updated="";
        Matcher updated=Pattern.compile("Обновлено:\\s*(\\d{2}\\.\\d{2}\\.\\d{4}\\s+в\\s+\\d{2}:\\d{2})").matcher(doc.text());
        if(updated.find()) week.updated=updated.group(1);
        LocalDate day=null;
        for(Element row:table.select("tr")) {
            List<Element> cells=new ArrayList<>();
            for(Element child:row.children()) if(child.tagName().equals("td")) cells.add(child);
            if(cells.isEmpty()) continue;
            int i=0;
            Matcher date=Pattern.compile("^(\\d{2}\\.\\d{2}\\.\\d{4})").matcher(cells.get(0).text());
            if(date.find()) {
                day=LocalDate.parse(date.group(1),DATE); i++;
                week.days.put(day,new ArrayList<>());
            }
            if(day==null || i>=cells.size() || !cells.get(i).text().matches("[1-9]\\d?")) continue;
            int number=Integer.parseInt(cells.get(i++).text()), column=1;
            for(;i<cells.size();i++) {
                Element cell=cells.get(i);
                int span=cell.hasAttr("colspan") ? Integer.parseInt(cell.attr("colspan")):1;
                if(span<1 || column+span>3) throw new IllegalArgumentException("Изменились столбцы подгрупп");
                Element subject=cell.selectFirst(".z1");
                String text=cell.text().replace('\u00a0',' ').trim();
                if(!text.isEmpty()) {
                    week.days.get(day).add(new Lesson(number,span==2 ? 0:column,
                        subject==null ? text:subject.text(),cell.select(".z2").text(),cell.select(".z3").text()));
                }
                column+=span;
            }
        }
        if(week.days.size()<1) throw new IllegalArgumentException("Не удалось прочитать даты расписания");
        return week;
    }
}
