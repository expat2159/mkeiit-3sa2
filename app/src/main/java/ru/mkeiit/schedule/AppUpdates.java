package ru.mkeiit.schedule;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
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
                app.getSharedPreferences("updates",0).edit().putLong("last_check",System.currentTimeMillis()).apply();
            }catch(Exception e){error="Не удалось проверить обновление. Проверьте интернет и попробуйте снова.";}
            final String newVersion=version,download=url,problem=error;final boolean upToDate=current;
            activity.runOnUiThread(()->{
                synchronized(AppUpdates.class){checking=false;}
                if(activity.isFinishing()||activity.isDestroyed())return;
                if(newVersion!=null){
                    download(activity,download,newVersion);
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
                            show(activity,"Обновление "+version,"Загрузка уже начата. Откройте её в уведомлениях и подтвердите установку.");return;
                        }
                    }
                }
            }
            long id=manager.enqueue(request);prefs.edit().putLong("download_id",id).putString("download_version",version).apply();
            show(activity,"Обновление скачивается","Откройте уведомление о загрузке и подтвердите установку. Если Android запросит разрешение на установку из этого источника, разрешите её.");
        }catch(Exception e){show(activity,"Обновление приложения","Не удалось начать загрузку. Попробуйте снова.");}
    }
}
