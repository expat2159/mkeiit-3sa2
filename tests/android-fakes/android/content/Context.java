package android.content;
import java.io.File;
public class Context {private final File dir;public Context(File dir){this.dir=dir;}public File getFilesDir(){return dir;}}
