import ru.mkeiit.schedule.AppColors;

public class AppColorsTest {
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    static double component(int value){double c=value/255.0;return c<=.04045?c/12.92:Math.pow((c+.055)/1.055,2.4);}
    static double luminance(int color){return .2126*component((color>>16)&255)+.7152*component((color>>8)&255)+.0722*component(color&255);}
    static double contrast(int a,int b){double x=luminance(a),y=luminance(b);return (Math.max(x,y)+.05)/(Math.min(x,y)+.05);}
    public static void main(String[] args){
        check(AppColors.darkMode("auto",true) && !AppColors.darkMode("auto",false),"Automatic follows phone theme");
        check(AppColors.darkMode("dark",false) && AppColors.darkMode("dark",true),"Dark overrides phone theme");
        check(!AppColors.darkMode("light",false) && !AppColors.darkMode("light",true),"Light overrides phone theme");
        check(AppColors.darkMode("unknown",true) && !AppColors.darkMode(null,false),"Unknown setting falls back to auto");
        for(boolean dark:new boolean[]{false,true}){
            AppColors colors=AppColors.palette(dark);check(colors.dark==dark,"Palette mode");
            check(contrast(colors.text,colors.background)>=4.5,"Readable primary text on page");
            check(contrast(colors.text,colors.surface)>=4.5,"Readable primary text on cards");
            check(contrast(colors.text,colors.teacherCard)>=4.5,"Readable teacher names");
            check(contrast(colors.muted,colors.background)>=4.5,"Readable secondary text");
            check(contrast(colors.accent,colors.surface)>=4.5,"Readable links and buttons");
            check(contrast(colors.onAccent,colors.accent)>=4.5,"Readable selected tab and calendar date");
        }
        System.out.println("PASS: auto/dark/light selection and WCAG AA text contrast in both palettes");
    }
}
