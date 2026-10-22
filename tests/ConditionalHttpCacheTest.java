package ru.mkeiit.schedule;

import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class ConditionalHttpCacheTest {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static ConditionalHttpCache.Response body(String value){
        ConditionalHttpCache.Response r=new ConditionalHttpCache.Response(200,value.getBytes(StandardCharsets.UTF_8));
        r.etag="\"v1\"";r.date="Thu, 22 Oct 2026 05:05:40 GMT";r.charset="windows-1251";return r;
    }
    public static void main(String[] args)throws Exception{
        File dir=Files.createTempDirectory("conditional-test").toFile();
        List<Map<String,String>> sent=new ArrayList<>();
        Deque<ConditionalHttpCache.Response> replies=new ArrayDeque<>();
        ConditionalHttpCache.Transport transport=(url,headers,limit)->{sent.add(new HashMap<>(headers));if(replies.isEmpty())throw new IOException("offline");return replies.remove();};
        ConditionalHttpCache cache=new ConditionalHttpCache(dir,transport);
        replies.add(body("first"));check(cache.get("a",100).changed,"first load");
        replies.add(new ConditionalHttpCache.Response(304,null));
        ConditionalHttpCache.Resource same=cache.get("a",100);
        check(!same.downloaded && !same.changed,"304 must reuse body");
        check("first".equals(new String(same.bytes,StandardCharsets.UTF_8)),"cached content");
        check("\"v1\"".equals(sent.get(1).get("If-None-Match")),"ETag sent");
        check("Thu, 22 Oct 2026 05:05:38 GMT".equals(sent.get(1).get("If-Modified-Since")),"server clock fallback");
        check("windows-1251".equals(same.charset),"charset persisted");
        cache=new ConditionalHttpCache(dir,transport);
        replies.add(new ConditionalHttpCache.Response(304,null));check(!cache.get("a",100).downloaded,"restart preserves cache");
        replies.add(body("first"));check(!cache.get("a",100).changed,"server ignoring validators same body");
        replies.add(body("second"));check(cache.get("a",100).changed,"changed body");
        replies.add(body("other"));cache.get("b",100);check(sent.get(sent.size()-1).isEmpty(),"URLs independent");
        try{cache.get("a",100);throw new AssertionError("offline should fail");}catch(IOException expected){}
        replies.add(new ConditionalHttpCache.Response(304,null));check("second".equals(new String(cache.get("a",100).bytes,StandardCharsets.UTF_8)),"offline preserves body");
        replies.add(new ConditionalHttpCache.Response(304,null));replies.add(body("retry"));
        check(cache.get("missing",100).downloaded,"304 missing body retries");check(sent.get(sent.size()-1).isEmpty(),"retry unconditional");
        replies.add(body("too large"));try{cache.get("a",2);throw new AssertionError("size limit");}catch(IOException expected){}
        replies.add(new ConditionalHttpCache.Response(304,null));check("second".equals(new String(cache.get("a",100).bytes,StandardCharsets.UTF_8)),"oversized response preserves cache");
        for(File f:dir.listFiles())Files.write(f.toPath(),new byte[]{1,2});
        replies.add(body("recovered"));check(cache.get("a",100).changed,"corrupt cache recovered");check(sent.get(sent.size()-1).isEmpty(),"corrupt body has no conditional headers");
        check(ConditionalHttpCache.conservativeSince("invalid")==null,"invalid server date");
        System.out.println("ConditionalHttpCacheTest OK");
    }
}
