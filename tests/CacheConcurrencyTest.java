package ru.mkeiit.schedule;
import java.nio.file.Files;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class CacheConcurrencyTest {
    public static void main(String[] args)throws Exception {
        CountDownLatch entered=new CountDownLatch(2),release=new CountDownLatch(1);
        AtomicInteger calls=new AtomicInteger();
        ConditionalHttpCache cache=new ConditionalHttpCache(Files.createTempDirectory("cache-parallel").toFile(),(url,headers,limit)->{
            calls.incrementAndGet();entered.countDown();
            try{if(!release.await(3,TimeUnit.SECONDS))throw new java.io.IOException("timeout");}catch(InterruptedException e){throw new java.io.IOException(e);}
            return new ConditionalHttpCache.Response(200,url.getBytes("UTF-8"));
        });
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try{
            Future<ConditionalHttpCache.Resource> a=pool.submit(()->cache.get("a",100));
            Future<ConditionalHttpCache.Resource> b=pool.submit(()->cache.get("b",100));
            if(!entered.await(2,TimeUnit.SECONDS))throw new AssertionError("Independent URLs did not run concurrently");
            release.countDown();
            if(!new String(a.get().bytes,"UTF-8").equals("a") || !new String(b.get().bytes,"UTF-8").equals("b"))throw new AssertionError("Wrong cached result");
            if(calls.get()!=2)throw new AssertionError("Unexpected request count");
        }finally{release.countDown();pool.shutdownNow();}
        CountDownLatch firstEntered=new CountDownLatch(1),firstRelease=new CountDownLatch(1),secondEntered=new CountDownLatch(1);
        AtomicInteger sameCalls=new AtomicInteger();
        ConditionalHttpCache same=new ConditionalHttpCache(Files.createTempDirectory("cache-same-url").toFile(),(url,headers,limit)->{
            int n=sameCalls.incrementAndGet();
            if(n==1){firstEntered.countDown();try{if(!firstRelease.await(3,TimeUnit.SECONDS))throw new java.io.IOException("timeout");}catch(InterruptedException e){throw new java.io.IOException(e);}return new ConditionalHttpCache.Response(200,"saved".getBytes("UTF-8"));}
            secondEntered.countDown();return new ConditionalHttpCache.Response(304,null);
        });
        ExecutorService samePool=Executors.newFixedThreadPool(2);
        try{
            Future<ConditionalHttpCache.Resource> first=samePool.submit(()->same.get("same",100));
            if(!firstEntered.await(2,TimeUnit.SECONDS))throw new AssertionError("first request did not start");
            Future<ConditionalHttpCache.Resource> second=samePool.submit(()->same.get("same",100));
            if(secondEntered.await(200,TimeUnit.MILLISECONDS))throw new AssertionError("same URL requests overlapped");
            firstRelease.countDown();first.get();
            if(!new String(second.get().bytes,"UTF-8").equals("saved"))throw new AssertionError("same URL cache corrupted");
        }finally{firstRelease.countDown();samePool.shutdownNow();}
        System.out.println("PASS: independent URLs overlap; requests for one URL remain serialized");
    }
}
