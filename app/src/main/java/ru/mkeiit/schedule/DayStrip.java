package ru.mkeiit.schedule;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.function.Predicate;

/** A moving ribbon of consecutive days. Only this view owns date dragging. */
public final class DayStrip extends View {
    public interface Listener {void selected(LocalDate date,LocalDate first);}
    private final LocalDate first,selected;
    private final Predicate<LocalDate> hasLessons,loaded;
    private final AppColors colors;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density,textSize;
    private final int slop;
    private float downX,downY,offset;
    private boolean dragging,blocked;
    private Listener listener;
    private static final DateTimeFormatter WEEKDAY=DateTimeFormatter.ofPattern("EE",new Locale("ru"));
    public DayStrip(Context context,LocalDate first,LocalDate selected,Predicate<LocalDate> hasLessons,Predicate<LocalDate> loaded){
        super(context);colors=AppAppearance.colors(context);this.first=first;this.selected=selected;this.hasLessons=hasLessons;this.loaded=loaded;
        density=getResources().getDisplayMetrics().density;textSize=12*getResources().getDisplayMetrics().scaledDensity;
        slop=ViewConfiguration.get(context).getScaledTouchSlop();setFocusable(true);setClickable(true);
        setContentDescription("Полоса дней. Выбрано "+selected.format(DateTimeFormatter.ofPattern("d MMMM yyyy",new Locale("ru")))+". Двигайте пальцем влево или вправо, либо нажмите день.");
    }
    public void setListener(Listener listener){this.listener=listener;}
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);float width=getWidth()/7f;if(width<=0)return;
        int start=(int)Math.floor(-offset/width)-1;
        LocalDate highlighted=selected;
        for(int i=start;i<=start+9;i++){
            LocalDate date=first.plusDays(i);float left=i*width+offset;boolean active=date.equals(highlighted);
            paint.setColor(active?colors.accent:colors.surface);
            canvas.drawRoundRect(left+density,0,left+width-density,getHeight(),9*density,9*density,paint);
            paint.setColor(active?colors.onAccent:loaded.test(date)?colors.text:colors.muted);
            paint.setTypeface(active?Typeface.DEFAULT_BOLD:Typeface.DEFAULT);paint.setTextSize(textSize);paint.setTextAlign(Paint.Align.CENTER);
            float center=left+width/2f;
            canvas.drawText(date.format(WEEKDAY),center,getHeight()*.38f,paint);
            canvas.drawText(String.valueOf(date.getDayOfMonth())+(hasLessons.test(date)?" •":""),center,getHeight()*.78f,paint);
        }
    }
    @Override public boolean onTouchEvent(MotionEvent event){
        float x=event.getX(),y=event.getY();
        switch(event.getActionMasked()){
            case MotionEvent.ACTION_DOWN:
                downX=x;downY=y;offset=0;dragging=false;blocked=false;return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                blocked=true;offset=0;invalidate();return true;
            case MotionEvent.ACTION_MOVE:
                if(blocked)return true;
                float dx=x-downX,dy=y-downY;
                if(!dragging && Math.abs(dy)>slop && Math.abs(dy)>=Math.abs(dx)){blocked=true;return true;}
                if(!dragging && Math.abs(dx)>slop && Math.abs(dx)>Math.abs(dy)*1.5f){dragging=true;getParent().requestDisallowInterceptTouchEvent(true);}
                if(dragging){offset=dx;invalidate();}return true;
            case MotionEvent.ACTION_UP:
                getParent().requestDisallowInterceptTouchEvent(false);
                if(!blocked && listener!=null){
                    final LocalDate target,begin;
                    if(dragging){int shift=DayStripPosition.shift(offset,getWidth()/7f);target=selected;begin=first.plusDays(shift);offset=-shift*(getWidth()/7f);invalidate();}
                    else{performClick();target=DayStripPosition.tapped(first,x,getWidth()/7f);begin=first;}
                    post(()->listener.selected(target,begin));
                }else{offset=0;invalidate();}
                dragging=false;return true;
            case MotionEvent.ACTION_CANCEL:
                dragging=false;blocked=true;offset=0;invalidate();getParent().requestDisallowInterceptTouchEvent(false);return true;
        }
        return true;
    }
    @Override public boolean performClick(){super.performClick();return true;}
    @Override public boolean onKeyDown(int keyCode,KeyEvent event){
        if(listener!=null && (keyCode==KeyEvent.KEYCODE_DPAD_LEFT || keyCode==KeyEvent.KEYCODE_DPAD_RIGHT)){
            int shift=keyCode==KeyEvent.KEYCODE_DPAD_RIGHT?1:-1;
            listener.selected(selected,first.plusDays(shift));return true;
        }
        return super.onKeyDown(keyCode,event);
    }
}
