package ru.mkeiit.schedule;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.*;
import java.util.*;

/** Per-URL conditional requests. HTTP 304 always reuses a verified local body. */
public final class ConditionalHttpCache {
    public interface Transport {Response get(String url,Map<String,String> headers,int maxBytes)throws IOException;}
    public static final class Response {
        public int status;
        public byte[] body;
        public String etag,lastModified,date,charset;
        public Response(int status,byte[] body){this.status=status;this.body=body;}
    }
    public static final class Resource {
        public final byte[] bytes;
        public final String fingerprint,charset;
        public final boolean downloaded,changed;
        Resource(Record record,boolean downloaded,boolean changed){bytes=record.body;fingerprint=record.hash;charset=record.charset;this.downloaded=downloaded;this.changed=changed;}
    }
    private static final class Record implements Serializable {
        private static final long serialVersionUID=1L;
        String url,hash,etag,modifiedSince,charset;
        byte[] body;
    }
    private final File directory;
    private final Transport transport;
    private final long maxStorage;
    public ConditionalHttpCache(File directory,Transport transport){this(directory,transport,64L*1024*1024);}
    public ConditionalHttpCache(File directory,Transport transport,long maxStorage){this.directory=directory;this.transport=transport;this.maxStorage=maxStorage;}
    public Resource get(String url,int maxBytes)throws IOException{
        synchronized(lockFor(url)){return getLocked(url,maxBytes);}
    }
    private final Object[] locks=new Object[32];
    private Object lockFor(String url){synchronized(locks){int i=(url.hashCode() & 0x7fffffff)%locks.length;if(locks[i]==null)locks[i]=new Object();return locks[i];}}
    private Resource getLocked(String url,int maxBytes)throws IOException{
        if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();
        Record old=read(url,maxBytes);Map<String,String> headers=new LinkedHashMap<>();
        if(old!=null){if(old.etag!=null)headers.put("If-None-Match",old.etag);if(old.modifiedSince!=null)headers.put("If-Modified-Since",old.modifiedSince);}
        Response response=transport.get(url,headers,maxBytes);
        if(response.status==304){
            if(old!=null)return new Resource(old,false,false);
            // A missing/corrupt body must never be considered an unchanged resource.
            response=transport.get(url,Collections.emptyMap(),maxBytes);
        }
        if(response.status!=200)throw new IOException("Ошибка сайта: HTTP "+response.status);
        if(response.body==null || response.body.length>maxBytes)throw new IOException("Некорректный размер ответа сайта");
        if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();
        Record record=new Record();record.url=url;record.body=response.body;record.hash=hash(record.body);record.etag=nonempty(response.etag);record.charset=nonempty(response.charset);
        record.modifiedSince=nonempty(response.lastModified);
        if(record.modifiedSince==null)record.modifiedSince=conservativeSince(response.date);
        save(record);
        return new Resource(record,true,old==null || !record.hash.equals(old.hash));
    }
    /** Some static HTML omits Last-Modified but honors IMS. Use the server's own clock, with a safety margin. */
    static String conservativeSince(String serverDate){
        if(serverDate==null)return null;
        try{
            Instant time=ZonedDateTime.parse(serverDate,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().minusSeconds(2);
            return DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'",Locale.US).withZone(ZoneOffset.UTC).format(time);
        }catch(DateTimeException e){return null;}
    }
    private static String nonempty(String value){return value==null || value.trim().isEmpty()?null:value;}
    private File file(String url)throws IOException{return new File(directory,hash(url.getBytes(java.nio.charset.StandardCharsets.UTF_8))+".http");}
    private Record read(String url,int limit){
        try{
            File file=file(url);
            try(ObjectInputStream input=new ObjectInputStream(new FileInputStream(file))){
                Record record=(Record)input.readObject();
                if(!url.equals(record.url) || record.body==null || record.body.length>limit || !hash(record.body).equals(record.hash))return null;
                file.setLastModified(System.currentTimeMillis());return record;
            }
        }catch(Exception e){return null;}
    }
    private void save(Record record)throws IOException{
        if(!directory.isDirectory() && !directory.mkdirs())throw new IOException("Не удалось создать кэш проверки изменений");
        File target=file(record.url),temporary=File.createTempFile("resource-",".tmp",directory);
        try{
            try(FileOutputStream file=new FileOutputStream(temporary);ObjectOutputStream output=new ObjectOutputStream(file)){output.writeObject(record);output.flush();file.getFD().sync();}
            try{Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
            catch(AtomicMoveNotSupportedException e){Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING);}
        }finally{temporary.delete();}
        prune(target);
    }
    private synchronized void prune(File protectedFile){
        File[] files=directory.listFiles((dir,name)->name.endsWith(".http"));if(files==null)return;
        long total=0;for(File file:files)total+=file.length();if(total<=maxStorage)return;
        Arrays.sort(files,Comparator.comparingLong(File::lastModified));
        for(File file:files){if(total<=maxStorage*3/4)break;if(file.equals(protectedFile))continue;long size=file.length();if(file.delete())total-=size;}
    }
    public static String hash(byte[] bytes)throws IOException{
        try{byte[] digest=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder result=new StringBuilder();for(byte b:digest)result.append(String.format(Locale.ROOT,"%02x",b & 255));return result.toString();}
        catch(java.security.NoSuchAlgorithmException e){throw new IOException(e);}
    }
}
