package ru.mkeiit.schedule;

/** Shared palette for native widgets and the custom calendar ribbon. */
public final class AppColors {
    public final boolean dark;
    public final int background,surface,teacherCard,text,muted,accent,onAccent;
    private AppColors(boolean dark,int background,int surface,int teacherCard,int text,int muted,int accent,int onAccent){
        this.dark=dark;this.background=background;this.surface=surface;this.teacherCard=teacherCard;this.text=text;this.muted=muted;this.accent=accent;this.onAccent=onAccent;
    }
    public static boolean darkMode(String mode,boolean systemDark){
        if("dark".equals(mode))return true;
        if("light".equals(mode))return false;
        return systemDark;
    }
    public static AppColors palette(boolean dark){
        return dark?new AppColors(true,0xFF101724,0xFF1B2536,0xFF202E45,0xFFEDF2FC,0xFFB4C0D5,0xFF8EAEFF,0xFF0F1B35)
            :new AppColors(false,0xFFF4F6FB,0xFFFFFFFF,0xFFE6EFFB,0xFF1C2741,0xFF5E6B84,0xFF325ED7,0xFFFFFFFF);
    }
}
