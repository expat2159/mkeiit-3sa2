package ru.mkeiit.schedule;

import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Small outline icons, drawn consistently across Android and EMUI fonts. */
public final class AppIcon extends Drawable {
    public static final int REFRESH=0,SETTINGS=1,CLOSE=2,LEFT=3,RIGHT=4,CALENDAR=5;
    private final int kind,color;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private ColorStateList tint;
    private int alpha=255;
    public AppIcon(int kind,int color){this.kind=kind;this.color=color;}
    @Override public void draw(Canvas canvas){
        int save=canvas.save();Rect bounds=getBounds();canvas.translate(bounds.left,bounds.top);canvas.scale(bounds.width()/24f,bounds.height()/24f);
        paint.setColor(tint==null?color:tint.getColorForState(getState(),color));paint.setAlpha(alpha);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.8f);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
        Path path=new Path();
        switch(kind){
            case REFRESH:
                canvas.drawArc(4,4,20,20,45,285,false,paint);path.moveTo(20,4);path.lineTo(20,10);path.lineTo(14,10);canvas.drawPath(path,paint);break;
            case SETTINGS:
                for(int i=0;i<32;i++){double angle=i*Math.PI/16;float radius=(i%4==0 || i%4==3)?9:7.2f;float x=12+(float)Math.cos(angle)*radius,y=12+(float)Math.sin(angle)*radius;if(i==0)path.moveTo(x,y);else path.lineTo(x,y);}path.close();canvas.drawPath(path,paint);canvas.drawCircle(12,12,3,paint);break;
            case CLOSE:
                canvas.drawLine(7,7,17,17,paint);canvas.drawLine(17,7,7,17,paint);break;
            case LEFT:
                path.moveTo(14,6);path.lineTo(8,12);path.lineTo(14,18);canvas.drawPath(path,paint);break;
            case RIGHT:
                path.moveTo(10,6);path.lineTo(16,12);path.lineTo(10,18);canvas.drawPath(path,paint);break;
            case CALENDAR:
                canvas.drawRoundRect(4,5,20,21,3,3,paint);canvas.drawLine(4,10,20,10,paint);canvas.drawLine(8,3,8,7,paint);canvas.drawLine(16,3,16,7,paint);canvas.drawCircle(12,15,1,paint);break;
        }
        canvas.restoreToCount(save);
    }
    @Override public void setTintList(ColorStateList tint){this.tint=tint;invalidateSelf();}
    @Override public boolean isStateful(){return true;}
    @Override protected boolean onStateChange(int[] state){invalidateSelf();return true;}
    @Override public void setAlpha(int alpha){this.alpha=alpha;invalidateSelf();}
    @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}
    @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}
