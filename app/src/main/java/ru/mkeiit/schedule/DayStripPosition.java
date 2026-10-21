package ru.mkeiit.schedule;

import java.time.LocalDate;

public final class DayStripPosition {
    private DayStripPosition() { }
    public static int shift(float offset,float cellWidth) {
        if(cellWidth<=0)return 0;
        int distance=Math.round(Math.abs(offset)/cellWidth);
        return offset<0?distance:-distance;
    }
    public static LocalDate tapped(LocalDate first,float x,float cellWidth) {
        int index=cellWidth<=0?0:(int)Math.floor(x/cellWidth);
        return first.plusDays(Math.max(0,Math.min(6,index)));
    }
}
