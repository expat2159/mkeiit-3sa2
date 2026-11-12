package ru.mkeiit.schedule;
import android.content.Context;
import java.io.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
public final class CachedNetwork {
 public interface Server{ConditionalHttpCache.Response get(String url)throws IOException;}
 public static Server server;
 private static ConditionalHttpCache cache;
 public static void reset(File dir,Server source){server=source;cache=new ConditionalHttpCache(dir,(url,headers,limit)->server.get(url));}
 public static ConditionalHttpCache.Resource fetch(Context context,String url,int limit)throws IOException{return cache.get(url,limit);}
 public static Document document(Context context,String url)throws IOException{return document(fetch(context,url,3*1024*1024),url);}
 public static Document document(ConditionalHttpCache.Resource resource,String url)throws IOException{return Jsoup.parse(new ByteArrayInputStream(resource.bytes),"UTF-8",url);}
}
