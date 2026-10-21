package ru.mkeiit.schedule;

import java.io.Serializable;
import java.util.Locale;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

public final class Bells {
    public static class Image implements Serializable {
        private static final long serialVersionUID=1L;
        public String title, url;
        public byte[] bytes;
        public long checked;
    }
    public static class Source {
        public String title, url;
        Source(String title,String url) {this.title=title;this.url=url;}
    }
    public static Source find(Document document) {
        for(Element section:document.select(".sp-wrap")) {
            Element heading=section.selectFirst(".sp-head");
            if(heading==null) continue;
            String title=heading.text(), lower=title.toLowerCase(Locale.ROOT);
            if(!lower.contains("расписание звонков") || lower.contains("полярн")) continue;
            for(Element a:section.select(".sp-body a[href]")) {
                String url=a.absUrl("href");
                if(Timetable.trusted(url) && isImage(url)) return new Source(title,url);
            }
            for(Element image:section.select(".sp-body img[src]")) {
                String url=image.absUrl("src");
                if(Timetable.trusted(url) && isImage(url)) return new Source(title,url);
            }
        }
        throw new IllegalArgumentException("Не найдена картинка расписания звонков на сайте");
    }
    private static boolean isImage(String url) {
        return java.net.URI.create(url).getPath().toLowerCase(Locale.ROOT).matches(".*\\.(png|jpg|jpeg|webp)");
    }
}
