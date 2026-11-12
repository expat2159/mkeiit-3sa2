package android.graphics;
public class BitmapFactory {
 public static class Options{public boolean inJustDecodeBounds;public int outWidth,outHeight;}
 public static Object decodeByteArray(byte[] bytes,int offset,int length,Options options){options.outWidth=1;options.outHeight=1;return null;}
}
