package ru.mkeiit.schedule;

import android.content.Context;
import java.io.*;
import org.jsoup.Jsoup;
import org.jsoup.Connection;
import org.jsoup.nodes.Document;

public final class CachedNetwork {
    private static ConditionalHttpCache cache;
    private static synchronized ConditionalHttpCache cache(Context context){
        if(cache==null)cache=new ConditionalHttpCache(new File(context.getFilesDir(),"http-resources"),(url,headers,limit)->{
            if(!Timetable.trusted(url))throw new IOException("Недопустимый адрес расписания");
            Connection.Response response=Jsoup.connect(url).userAgent("MKEIIT-3SA2/1.11 (Android)").timeout(25000).maxBodySize(limit+1)
                .ignoreContentType(true).ignoreHttpErrors(true).followRedirects(false).headers(headers).execute();
            byte[] body=response.bodyAsBytes();
            ConditionalHttpCache.Response result=new ConditionalHttpCache.Response(response.statusCode(),response.statusCode()==200?body:null);
            result.etag=response.header("ETag");result.lastModified=response.header("Last-Modified");result.date=response.header("Date");String type=response.header("Content-Type");
            if(type!=null){java.util.regex.Matcher charset=java.util.regex.Pattern.compile("(?i)charset\\s*=\\s*[\"']?([^\\s;\"']+)").matcher(type);if(charset.find())result.charset=charset.group(1);}
            return result;
        });
        return cache;
    }
    public static ConditionalHttpCache.Resource fetch(Context context,String url,int limit)throws IOException{
        if(!Timetable.trusted(url))throw new IOException("Недопустимый адрес расписания");
        return cache(context).get(url,limit);
    }
    public static Document document(ConditionalHttpCache.Resource resource,String url)throws IOException{
        return Jsoup.parse(new ByteArrayInputStream(resource.bytes),resource.charset,url);
    }
    public static Document document(Context context,String url)throws IOException{return document(fetch(context,url,3*1024*1024),url);}
}
