package ru.mkeiit.schedule;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/** Fits the original image; pinch around the fingers, then drag within its bounds. */
public final class ZoomImageView extends View {
    private final Bitmap bitmap;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private float fit=1,zoom=1,x,y;
    private boolean multiple;
    private final ScaleGestureDetector scale;
    private final GestureDetector gestures;
    public ZoomImageView(Context context,Bitmap bitmap){
        super(context);this.bitmap=bitmap;setFocusable(true);
        scale=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            @Override public boolean onScale(ScaleGestureDetector detector){
                float next=Math.max(1,Math.min(6,zoom*detector.getScaleFactor()));
                float ratio=next/zoom;
                x=detector.getFocusX()-(detector.getFocusX()-x)*ratio;
                y=detector.getFocusY()-(detector.getFocusY()-y)*ratio;
                zoom=next;constrain();invalidate();return true;
            }
        });
        gestures=new GestureDetector(context,new GestureDetector.SimpleOnGestureListener(){
            @Override public boolean onDown(MotionEvent event){return true;}
            @Override public boolean onScroll(MotionEvent first,MotionEvent last,float dx,float dy){
                if(!scale.isInProgress() && zoom>1){x-=dx;y-=dy;constrain();invalidate();}return true;
            }
            @Override public boolean onSingleTapUp(MotionEvent event){if(!multiple && zoom==1)performClick();return true;}
        });
        // A tap remains a tap; two quick taps must not initiate one-finger zoom.
        scale.setQuickScaleEnabled(false);
    }
    @Override public boolean performClick(){super.performClick();return true;}
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){
        if(bitmap==null)return;
        fit=Math.min((float)w/bitmap.getWidth(),(float)h/bitmap.getHeight());zoom=1;
        x=(w-bitmap.getWidth()*fit)/2;y=(h-bitmap.getHeight()*fit)/2;
    }
    private void constrain(){
        if(bitmap==null)return;
        float w=bitmap.getWidth()*fit*zoom,h=bitmap.getHeight()*fit*zoom;
        x=w<=getWidth()?(getWidth()-w)/2:Math.max(getWidth()-w,Math.min(0,x));
        y=h<=getHeight()?(getHeight()-h)/2:Math.max(getHeight()-h,Math.min(0,y));
    }
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);if(bitmap==null)return;
        int save=canvas.save();canvas.translate(x,y);canvas.scale(fit*zoom,fit*zoom);canvas.drawBitmap(bitmap,0,0,paint);canvas.restoreToCount(save);
    }
    @Override public boolean onTouchEvent(MotionEvent event){
        if(event.getActionMasked()==MotionEvent.ACTION_DOWN)multiple=false;
        if(event.getPointerCount()>1)multiple=true;
        scale.onTouchEvent(event);gestures.onTouchEvent(event);
        if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(event.getActionMasked()!=MotionEvent.ACTION_UP && event.getActionMasked()!=MotionEvent.ACTION_CANCEL);
        return true;
    }
}
