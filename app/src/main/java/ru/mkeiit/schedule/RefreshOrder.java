package ru.mkeiit.schedule;

import java.time.LocalDate;
import java.util.*;

/** Give the selected week and current week priority while retaining every published future week. */
public final class RefreshOrder {
    private RefreshOrder(){}
    public static List<LocalDate> weeks(NavigableMap<LocalDate,String> links,LocalDate monday,LocalDate selected){
        LinkedHashSet<LocalDate> result=new LinkedHashSet<>();
        if(selected!=null){LocalDate start=links.floorKey(selected);if(start!=null && !selected.isAfter(start.plusDays(6)))result.add(start);}
        LocalDate current=links.floorKey(monday);if(current!=null)result.add(current);
        result.addAll(links.tailMap(monday,false).keySet());return new ArrayList<>(result);
    }
}
