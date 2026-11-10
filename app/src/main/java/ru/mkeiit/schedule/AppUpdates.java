package ru.mkeiit.schedule;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.os.Handler;
import android.os.Looper;
import android.net.Uri;
import android.os.Environment;
import java.io.IOException;
import java.util.regex.*;
import org.json.*;
import org.jsoup.Connection;
import org.jsoup.Jsoup;

/** Checks only published releases of this application. */
public final class AppUpdates {
    private static final String REPO="expat2159/mkeiit-3sa2";
    private static final String API="https://api.github.com/repos/"+REPO+"/releases/latest";
    private static final long INTERVAL=24L*60*60*1000;
    private static boolean checking;
    private static final Handler handler=new Handler(Looper.getMainLooper());
    private static Runnable monitor;
    private static Activity monitored;
    private AppUpdates(){}
    static int compare(String a,String b){
        if(!a.matches("v?\\d+(\\.\\d+){0,3}") || !b.matches("v?\\d+(\\.\\d+){0,3}"))return 0;
        String[] x=a.replaceFirst("^v","").split("\\."),y=b.replaceFirst("^v","").split("\\.");
        try{for(int i=0;i<Math.max(x.length,y.length);i++){long n=i<x.length?Long.parseLong(x[i]):0,m=i<y.length?Long.parseLong(y[i]):0;if(n!=m)return n>m?1:-1;}}catch(NumberFormatException e){return 0;}return 0;
    }
    static String assetUrl(String tag,JSONArray assets)throws JSONException{
        String prefix="https://github.com/"+REPO+"/releases/download/"+tag+"/";
        for(int i=0;i<assets.length();i++){
            JSONObject asset=assets.getJSONObject(i);String name=asset.optString("name"),url=asset.optString("browser_download_url");
            if(name.matches("3SA2-raspisanie-[0-9.]+\\.apk") && url.equals(prefix+name))return url;
        }
        return null;
    }
    public static void check(Activity activity,boolean manual){
        synchronized(AppUpdates.class){if(checking)return;checking=true;}
        Context app=activity.getApplicationContext();
        long now=System.currentTimeMillis();long last=app.getSharedPreferences("updates",0).getLong("last_check",0);
        if(!manual && now-last<INTERVAL){synchronized(AppUpdates.class){checking=false;}return;}
        new Thread(()->{
            String version=null,url=null,error=null;boolean current=false;
            try{
                Connection.Response response=Jsoup.connect(API).header("Accept","application/vnd.github+json").userAgent("MKEIIT-3SA2-Android").ignoreContentType(true).ignoreHttpErrors(true).timeout(15000).maxBodySize(512*1024).execute();
                if(response.statusCode()==404){current=true;}
                else if(response.statusCode()!=200)throw new IOException("HTTP "+response.statusCode());
                else{
                    JSONObject release=new JSONObject(response.body());String tag=release.optString("tag_name");
                    String installed=app.getPackageManager().getPackageInfo(app.getPackageName(),0).versionName;
                    if(!tag.matches("v?\\d+(\\.\\d+){0,3}"))throw new IOException("Некорректная версия");
                    if(compare(tag,installed)>0){url=assetUrl(tag,release.getJSONArray("assets"));if(url==null)throw new IOException("APK не найден");version=tag;}
                    else current=true;
                }
                if(version==null)app.getSharedPreferences("updates",0).edit().putLong("last_check",System.currentTimeMillis()).apply();
            }catch(Exception e){error="Не удалось проверить обновление. Проверьте интернет и попробуйте снова.";}
            final String newVersion=version,download=url,problem=error;final boolean upToDate=current;
            activity.runOnUiThread(()->{
                synchronized(AppUpdates.class){checking=false;}
                if(activity.isFinishing()||activity.isDestroyed() || !(activity instanceof MainActivity) || !((MainActivity)activity).isUpdateForeground())return;
                if(newVersion!=null){
                    app.getSharedPreferences("updates",0).edit().putLong("last_check",System.currentTimeMillis()).apply();
                    ((MainActivity)activity).showUpdateDialog("Доступно обновление "+newVersion,"Скачать новую версию приложения?","Позже","Скачать",()->download(activity,download,newVersion));
                }else if(manual && (problem!=null || upToDate))show(activity,"Обновление приложения",problem!=null?problem:"Установлена последняя опубликованная версия.");
            });
        },"app-update-check").start();
    }
    private static void show(Activity activity,String title,String message){
        if(activity instanceof MainActivity)((MainActivity)activity).showUpdateDialog(title,message);
    }
    private static void download(Activity activity,String url,String version){
        try{
            DownloadManager manager=(DownloadManager)activity.getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Request request=new DownloadManager.Request(Uri.parse(url));
            request.setTitle("Расписание 3СА2 "+version).setDescription("Нажмите после загрузки, чтобы установить обновление")
                .setMimeType("application/vnd.android.package-archive").setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(activity,Environment.DIRECTORY_DOWNLOADS,"3SA2-raspisanie-"+version+".apk");
            android.content.SharedPreferences prefs=activity.getSharedPreferences("updates",0);
            long previous=prefs.getLong("download_id",-1);
            if(version.equals(prefs.getString("download_version","")) && previous!=-1){
                try(android.database.Cursor cursor=manager.query(new DownloadManager.Query().setFilterById(previous))){
                    if(cursor!=null && cursor.moveToFirst()){
                        int state=cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                        if(state!=DownloadManager.STATUS_FAILED){
                            track(activity,true);return;
                        }
                    }
                }
            }
            if(previous!=-1)manager.remove(previous);
            long id=manager.enqueue(request);prefs.edit().putLong("download_id",id).putString("download_version",version).putString("download_url",url).remove("offered_id").putBoolean("waiting_permission",false).apply();
            track(activity,false);
        }catch(Exception e){show(activity,"Обновление приложения","Не удалось начать загрузку. Попробуйте снова.");}
    }
    public static void resume(Activity activity){track(activity,false);}
    public static void pause(Activity activity){
        if(monitored!=activity)return;
        if(monitor!=null)handler.removeCallbacks(monitor);monitor=null;monitored=null;
        if(activity instanceof MainActivity)((MainActivity)activity).closeDownloadProgress();
    }
    private static void track(Activity activity,boolean manual){
        if(!(activity instanceof MainActivity) || !((MainActivity)activity).isUpdateForeground() || activity.isFinishing() || activity.isDestroyed())return;
        SharedPreferences prefs=activity.getSharedPreferences("updates",0);long id=prefs.getLong("download_id",-1);
        String version=prefs.getString("download_version","");
        try{if(id<0 || compare(version,activity.getPackageManager().getPackageInfo(activity.getPackageName(),0).versionName)<=0)return;}catch(Exception e){return;}
        pause(activity);monitored=activity;MainActivity screen=(MainActivity)activity;
        monitor=new Runnable(){
            @Override public void run(){
                if(monitored!=activity || activity.isFinishing() || activity.isDestroyed())return;
                DownloadManager manager=(DownloadManager)activity.getSystemService(Context.DOWNLOAD_SERVICE);
                try(android.database.Cursor cursor=manager.query(new DownloadManager.Query().setFilterById(id))){
                    if(cursor==null || !cursor.moveToFirst()){
                        screen.closeDownloadProgress();pause(activity);
                        if(manual)screen.showUpdateDialog("Обновление приложения","Загрузка больше недоступна. Проверьте обновления ещё раз.");return;
                    }
                    int status=cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    if(status==DownloadManager.STATUS_SUCCESSFUL){
                        screen.closeDownloadProgress();pause(activity);
                        boolean waiting=prefs.getBoolean("waiting_permission",false);
                        if(waiting){prefs.edit().putBoolean("waiting_permission",false).apply();if(activity.getPackageManager().canRequestPackageInstalls())install(activity,id);else permissionDialog(activity,id);}
                        else if(prefs.getLong("offered_id",-1)!=id)install(activity,id);
                        else if(manual)screen.showUpdateDialog("Обновление готово","Обновление скачано и готово к установке.","Установить",()->install(activity,id));
                        return;
                    }
                    if(status==DownloadManager.STATUS_FAILED){
                        screen.closeDownloadProgress();pause(activity);
                        String url=prefs.getString("download_url","");
                        screen.showUpdateDialog("Ошибка загрузки","Не удалось скачать обновление. Проверьте интернет и попробуйте снова.","Повторить",()->{if(!url.isEmpty())download(activity,url,version);else check(activity,true);});return;
                    }
                    long done=cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                    long total=cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                    screen.showDownloadProgress(version,done,total);handler.postDelayed(this,500);
                }catch(Exception e){screen.closeDownloadProgress();pause(activity);screen.showUpdateDialog("Ошибка загрузки","Не удалось проверить загрузку. Попробуйте снова.","Повторить",()->check(activity,true));}
            }
        };handler.post(monitor);
    }
    private static void permissionDialog(Activity activity,long id){
        activity.getSharedPreferences("updates",0).edit().putLong("offered_id",id).apply();
        ((MainActivity)activity).showUpdateDialog("Разрешение на установку","Для установки обновления разрешите этому приложению устанавливать приложения из этого источника.","Настройки",()->{
            try{activity.getSharedPreferences("updates",0).edit().putBoolean("waiting_permission",true).apply();activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+activity.getPackageName())));}
            catch(Exception e){activity.getSharedPreferences("updates",0).edit().putBoolean("waiting_permission",false).apply();show(activity,"Не удалось открыть настройки","Разрешите установку из этого источника в настройках телефона и попробуйте ещё раз.");}
        });
    }
    private static void install(Activity activity,long id){
        if(!activity.getPackageManager().canRequestPackageInstalls()){permissionDialog(activity,id);return;}
        try{
            DownloadManager manager=(DownloadManager)activity.getSystemService(Context.DOWNLOAD_SERVICE);Uri uri=manager.getUriForDownloadedFile(id);
            if(uri==null)throw new IOException("APK недоступен");
            Intent intent=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.getSharedPreferences("updates",0).edit().putLong("offered_id",id).putBoolean("waiting_permission",false).apply();activity.startActivity(intent);
        }catch(Exception e){((MainActivity)activity).showUpdateDialog("Не удалось открыть установщик","Откройте загруженное обновление в уведомлениях или повторите проверку.","Повторить",()->check(activity,true));}
    }

}
