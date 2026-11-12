package android.util;
import java.io.*;
import java.nio.file.*;
public class AtomicFile {
 private final File file,temp;
 public AtomicFile(File file){this.file=file;this.temp=new File(file.getPath()+".new");}
 public FileInputStream openRead()throws IOException{return new FileInputStream(file);}
 public FileOutputStream startWrite()throws IOException{return new FileOutputStream(temp);}
 public void finishWrite(FileOutputStream out)throws IOException{out.close();Files.move(temp.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING);}
 public void failWrite(FileOutputStream out){try{out.close();}catch(IOException ignored){}temp.delete();}
}
