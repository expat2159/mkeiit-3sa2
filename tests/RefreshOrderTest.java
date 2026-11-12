import java.time.LocalDate;
import java.util.*;
import ru.mkeiit.schedule.RefreshOrder;
public class RefreshOrderTest {
 public static void main(String[] args){
  TreeMap<LocalDate,String> weeks=new TreeMap<>();for(int day:new int[]{2,9,16,23,30})weeks.put(LocalDate.of(2026,11,day),"week");
  List<LocalDate> order=RefreshOrder.weeks(weeks,LocalDate.of(2026,11,9),LocalDate.of(2026,11,17));
  if(!order.equals(Arrays.asList(LocalDate.of(2026,11,16),LocalDate.of(2026,11,9),LocalDate.of(2026,11,23),LocalDate.of(2026,11,30))))throw new AssertionError(order.toString());
  if(!RefreshOrder.weeks(weeks,LocalDate.of(2026,11,9),LocalDate.of(2026,11,5)).contains(LocalDate.of(2026,11,2)))throw new AssertionError("selected past week missing");
  if(!RefreshOrder.weeks(weeks,LocalDate.of(2026,11,9),null).contains(LocalDate.of(2026,11,16)))throw new AssertionError("future week missing");
  System.out.println("PASS: selected and current week prioritised, all future weeks retained");
 }
}
