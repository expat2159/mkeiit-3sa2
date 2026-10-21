package ru.mkeiit.schedule;

import android.content.Context;
import android.content.res.Configuration;

public final class AppAppearance {
    private AppAppearance() { }
    public static String mode(Context context){return context.getSharedPreferences("appearance",0).getString("theme","auto");}
    public static AppColors colors(Context context){
        boolean systemDark=(context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
        return AppColors.palette(AppColors.darkMode(mode(context),systemDark));
    }
    public static void setMode(Context context,String mode){context.getSharedPreferences("appearance",0).edit().putString("theme",mode).apply();}
}
