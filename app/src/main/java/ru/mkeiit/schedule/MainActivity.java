package ru.mkeiit.schedule;

import android.app.*;
import android.os.Bundle;
import android.content.*;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.Editable;
import android.view.*;
import android.widget.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private Repository.Snapshot data=new Repository.Snapshot();
    private final ExecutorService teacherIo=Executors.newSingleThreadExecutor();
    private Teachers.State teacherData=new Teachers.State();
    private String selectedTeacherId,teacherQuery="",teacherProblem="";
    private LocalDate teacherDay=LocalDate.now(ZoneId.of("Europe/Moscow")),teacherStrip;
    private ScrollView teacherScroll;
    private int teacherScrollY;
    private long teacherRequest;
    private boolean teacherLoading;

    private LinearLayout root,content,tabs;
    private LocalDate stripStart;
    private RadioGroup groups;
    private TextView status;
    private Button refresh;
    private int subgroup,selectedTab;
    private LocalDate selectedDay=LocalDate.now(ZoneId.of("Europe/Moscow"));
    private boolean busy;
    private String error="";
    private Bitmap bellsBitmap;
    private long bitmapChecked=-1;
    private int ink,muted,blue;
    private AppColors colors;
    private Dialog settingsDialog;
    private boolean reopenSettings;
    private static final String DEVELOPER_TELEGRAM="https://t.me/expat2159";
    private static final Locale RU=new Locale("ru");
    private int dp(float n){return (int)(getResources().getDisplayMetrics().density*n+.5f);}
    private LocalDate today(){return LocalDate.now(ZoneId.of("Europe/Moscow"));}
    @Override public void onCreate(Bundle state){
        colors=AppAppearance.colors(this);
        setTheme(getResources().getIdentifier(colors.dark?"AppThemeDark":"AppTheme","style",getPackageName()));
        super.onCreate(state);
        ink=colors.text;muted=colors.muted;blue=colors.accent;
        getWindow().setStatusBarColor(colors.background);getWindow().setNavigationBarColor(colors.background);
        int barFlags=getWindow().getDecorView().getSystemUiVisibility();
        int lightBars=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        getWindow().getDecorView().setSystemUiVisibility(colors.dark?barFlags & ~lightBars:barFlags | lightBars);
        subgroup=getPreferences(0).getInt("subgroup",0);
        if(state!=null){selectedDay=LocalDate.parse(state.getString("selectedDay",today().toString()));selectedTab=state.getInt("selectedTab",0);if(state.containsKey("stripStart"))stripStart=LocalDate.parse(state.getString("stripStart"));}
        if(state!=null){selectedTeacherId=state.getString("teacherId");teacherQuery=state.getString("teacherQuery","");teacherScrollY=state.getInt("teacherScrollY",0);teacherDay=LocalDate.parse(state.getString("teacherDay",today().toString()));if(state.containsKey("teacherStrip"))teacherStrip=LocalDate.parse(state.getString("teacherStrip"));}
        root=column();root.setPadding(dp(12),dp(8),dp(12),dp(4));root.setBackgroundColor(colors.background);
        root.setOnApplyWindowInsetsListener((v,insets)->{
            root.setPadding(dp(12),dp(8)+insets.getSystemWindowInsetTop(),dp(12),dp(4)+insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        setContentView(root);
        LinearLayout header=row();
        TextView title=text("3СА2 · МКЭиИТ",19,ink,true);header.addView(title,new LinearLayout.LayoutParams(0,dp(44),1));
        refresh=action(header,"Обновить",()->update());
        action(header,"⋮",()->settings()).setContentDescription("Настройки");root.addView(header);
        status=text("Загрузка…",11,muted,false);status.setMaxLines(2);root.addView(status);
        tabs=row();root.addView(tabs);
        groups=new RadioGroup(this);groups.setOrientation(0);
        String[] names={"Все","Подгр. 1","Подгр. 2"};
        for(int i=0;i<3;i++){
            RadioButton b=new RadioButton(this);b.setId(100+i);b.setText(names[i]);b.setTextSize(13);b.setPadding(0,0,0,0);
            groups.addView(b,new RadioGroup.LayoutParams(0,dp(40),1));
        }
        groups.check(100+subgroup);root.addView(groups);
        groups.setOnCheckedChangeListener((g,id)->{subgroup=id-100;getPreferences(0).edit().putInt("subgroup",subgroup).apply();render();});
        content=column();
        root.addView(content,new LinearLayout.LayoutParams(-1,0,1));
        render();DailyJob.schedule(this);
        if(state!=null && state.getBoolean("showSettings",false))root.post(()->{if(!isDestroyed())settings();});
        io.submit(()->{Repository.Snapshot cached=Repository.read(this);Teachers.State teachers=TeacherRepository.read(this);runOnUiThread(()->{if(isDestroyed())return;data=cached;acceptTeachers(teachers);render();if(needsUpdate())update();else if(selectedTeacherId!=null)loadTeacher(selectedTeacherId);});});
    }
    private boolean needsUpdate(){return System.currentTimeMillis()-data.checked>=Repository.DAY || data.bells==null || teacherData.entries.isEmpty();}
    @Override protected void onResume(){
        super.onResume();
        if(content!=null && !busy)io.submit(()->{
            Repository.Snapshot cached=Repository.read(this);Teachers.State teachers=TeacherRepository.read(this);
            runOnUiThread(()->{if(isDestroyed()||busy)return;if(cached.checked>data.checked)data=cached;acceptTeachers(teachers);render();if(needsUpdate())update();});
        });
    }
    private void update(){
        if(busy)return;busy=true;error="";teacherProblem="";refresh.setEnabled(false);status.setText("Проверяем изменения…");
        final String refreshTeacher=selectedTab==1?selectedTeacherId:null;
        io.submit(()->{
            Repository.Snapshot fresh=null;String problem="";
            try{fresh=Repository.refresh(getApplicationContext());if(refreshTeacher!=null && !TeacherRepository.read(getApplicationContext()).visited.contains(refreshTeacher))TeacherRepository.refreshOne(getApplicationContext(),refreshTeacher,true);}catch(Exception e){problem="Не удалось обновить. Сохранённые данные доступны.";}
            final Repository.Snapshot result=fresh;final String message=problem;final Teachers.State teachers=TeacherRepository.read(getApplicationContext());
            runOnUiThread(()->{if(isDestroyed())return;busy=false;refresh.setEnabled(true);error=message;acceptTeachers(teachers);if(result!=null)data=result;render();if(result!=null && result.syncMessage!=null)Toast.makeText(this,result.syncMessage,Toast.LENGTH_LONG).show();});
        });
    }
    private void render(){
        if(content==null)return;
        if(teacherScroll!=null && teacherScroll.getParent()!=null)teacherScrollY=teacherScroll.getScrollY();
        tabs.removeAllViews();
        String[] names={"Расписание","Преподаватели","Звонки"};
        for(int i=0;i<3;i++){
            final int tab=i;TextView t=text(names[i],15,selectedTab==i?colors.onAccent:blue,true);t.setGravity(Gravity.CENTER);t.setMaxLines(1);t.setHorizontallyScrolling(false);t.setTextSize(i==1?12:15);t.setAutoSizeTextTypeUniformWithConfiguration(9,i==1?12:15,1,android.util.TypedValue.COMPLEX_UNIT_SP);
            t.setBackground(background(selectedTab==i?blue:colors.surface,10));t.setSelected(selectedTab==i);t.setFocusable(true);
            t.setOnClickListener(v->{selectedTab=tab;render();});
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(40),1);lp.setMargins(0,dp(4),dp(4),dp(4));tabs.addView(t,lp);
        }
        groups.setVisibility(selectedTab==0?View.VISIBLE:View.GONE);content.removeAllViews();
        if(!busy){
            String checked=data.checked==0?"Ещё не загружено":"Проверено "+DateTimeFormatter.ofPattern("dd.MM HH:mm",RU).format(Instant.ofEpochMilli(data.checked).atZone(ZoneId.systemDefault()));
            status.setText(error.isEmpty()?checked+(data.checked>0 && System.currentTimeMillis()-data.checked>=Repository.DAY?" · данные старше суток":" · ежедневно"):error);
        }
        if(selectedTab==2){renderBells();return;}
        if(selectedTab==1){renderTeachers();return;}
        renderWeek();
        TextView day=text((selectedDay.equals(today())?"Сегодня · ":"")+selectedDay.format(DateTimeFormatter.ofPattern("EEEE, d MMMM",RU)),15,ink,true);
        content.addView(day,new LinearLayout.LayoutParams(-1,dp(30)));
        Timetable.Week week=weekFor(selectedDay);
        if(week==null){
            TextView message=text(data.weeks.isEmpty()?"Нажмите «Обновить», чтобы загрузить расписание.":"На эту дату нет загруженного расписания. Выберите день с точкой или обновите данные.",16,muted,false);
            content.addView(message,new LinearLayout.LayoutParams(-1,0,1));return;
        }
        List<Timetable.Lesson> lessons=lessonsFor(selectedDay);
        if(lessons.isEmpty()){
            TextView empty=text("Занятий нет"+(subgroup==0?"":" для подгруппы "+subgroup),20,muted,false);empty.setGravity(Gravity.CENTER);
            content.addView(empty,new LinearLayout.LayoutParams(-1,0,1));return;
        }
        LinearLayout columns=row();
        columns.addView(text("№",11,muted,true),new LinearLayout.LayoutParams(dp(28),dp(22)));
        if(subgroup==0){
            columns.addView(text("Подгруппа 1",11,muted,true),new LinearLayout.LayoutParams(0,dp(22),1));
            columns.addView(text("Подгруппа 2",11,muted,true),new LinearLayout.LayoutParams(0,dp(22),1));
        }else columns.addView(text("Предмет · кабинет · преподаватель",11,muted,false));
        content.addView(columns);
        LinearLayout table=column();content.addView(table,new LinearLayout.LayoutParams(-1,0,1));
        int count=6;for(Timetable.Lesson lesson:lessons)count=Math.max(count,lesson.number);
        for(int number=1;number<=count;number++){
            LinearLayout line=row();line.setBackground(background(colors.surface,10));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,0,1);lp.setMargins(0,dp(2),0,dp(2));table.addView(line,lp);
            TextView n=text(String.valueOf(number),15,blue,true);n.setGravity(Gravity.CENTER);line.addView(n,new LinearLayout.LayoutParams(dp(28),-1));
            List<Timetable.Lesson> pair=new ArrayList<>();for(Timetable.Lesson l:lessons)if(l.number==number)pair.add(l);
            boolean common=false;for(Timetable.Lesson l:pair)if(l.subgroup==0)common=true;
            if(subgroup==0 && !common){lessonCell(line,pair,1);lessonCell(line,pair,2);}else lessonCell(line,pair,0);
        }
    }
    private void lessonCell(LinearLayout row,List<Timetable.Lesson> pair,int group){
        StringBuilder value=new StringBuilder();
        for(Timetable.Lesson l:pair)if(group==0||l.subgroup==0||l.subgroup==group){
            if(value.length()>0)value.append("\n");
            value.append(l.subject);if(!l.room.isEmpty())value.append(" · ").append(l.room);
            if(!l.teacher.isEmpty())value.append("\n").append(l.teacher);
        }
        TextView cell=text(value.length()==0?"—":value.toString(),14,value.length()==0?muted:ink,false);
        boolean shared=false;for(Timetable.Lesson l:pair)if(l.subgroup==0)shared=true;
        if(subgroup!=0 || (shared && group==0))cell.setGravity(Gravity.CENTER);
        cell.setPadding(dp(5),dp(2),dp(5),dp(2));cell.setMaxLines(4);cell.setEllipsize(TextUtils.TruncateAt.END);
        cell.setAutoSizeTextTypeUniformWithConfiguration(9,14,1,android.util.TypedValue.COMPLEX_UNIT_SP);
        row.addView(cell,new LinearLayout.LayoutParams(0,-1,1));
    }
    private void renderWeek(){
        if(stripStart==null)stripStart=selectedDay.with(DayOfWeek.MONDAY);
        LinearLayout nav=row();
        action(nav,"‹",()->{stripStart=stripStart.minusWeeks(1);render();}).setContentDescription("Предыдущая неделя");
        TextView month=text(stripStart.plusDays(3).format(DateTimeFormatter.ofPattern("LLLL yyyy",RU))+" ▾",14,ink,true);month.setGravity(Gravity.CENTER);month.setFocusable(true);month.setOnClickListener(v->pickDate());
        nav.addView(month,new LinearLayout.LayoutParams(0,dp(40),1));
        action(nav,"Сегодня",()->{selectedDay=today();stripStart=null;render();});
        action(nav,"›",()->{stripStart=stripStart.plusWeeks(1);render();}).setContentDescription("Следующая неделя");content.addView(nav);
        DayStrip dates=new DayStrip(this,stripStart,selectedDay,date->!lessonsFor(date).isEmpty(),date->weekFor(date)!=null);
        dates.setListener((date,first)->{selectedDay=date;stripStart=first;render();});
        content.addView(dates,new LinearLayout.LayoutParams(-1,dp(44)));
    }
    private void pickDate(){showCalendar(selectedDay,day->{selectedDay=day;stripStart=null;render();});}
    private void showCalendar(LocalDate initial,java.util.function.Consumer<LocalDate> onSelect){
        Dialog dialog=new Dialog(this);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LocalDate[] chosen={initial};YearMonth[] visible={YearMonth.from(initial)};
        LinearLayout panel=column();panel.setPadding(dp(16),dp(16),dp(16),dp(12));panel.setBackground(background(colors.background,24));
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.addView(panel);
        TextView caption=text("Выберите дату",14,muted,false);panel.addView(caption);
        TextView selection=text("",22,ink,true);selection.setPadding(0,dp(8),0,dp(12));panel.addView(selection);
        LinearLayout nav=row();panel.addView(nav);
        LinearLayout grid=column();grid.setPadding(0,dp(8),0,dp(8));panel.addView(grid);
        Runnable[] draw={null};
        draw[0]=()->{
            selection.setText(chosen[0].format(DateTimeFormatter.ofPattern("EEE, d MMMM yyyy",RU)));
            nav.removeAllViews();
            action(nav,"‹",()->{visible[0]=visible[0].minusMonths(1);draw[0].run();}).setContentDescription("Предыдущий месяц");
            TextView month=text(visible[0].atDay(1).format(DateTimeFormatter.ofPattern("LLLL yyyy",RU)),16,ink,true);month.setGravity(Gravity.CENTER);nav.addView(month,new LinearLayout.LayoutParams(0,dp(48),1));
            action(nav,"›",()->{visible[0]=visible[0].plusMonths(1);draw[0].run();}).setContentDescription("Следующий месяц");
            grid.removeAllViews();LinearLayout weekdays=row();grid.addView(weekdays);
            for(String name:new String[]{"пн","вт","ср","чт","пт","сб","вс"}){TextView label=text(name,12,muted,true);label.setGravity(Gravity.CENTER);weekdays.addView(label,new LinearLayout.LayoutParams(0,dp(32),1));}
            List<LocalDate> dates=CalendarDates.cells(visible[0]);
            for(int week=0;week<6;week++){
                LinearLayout line=row();grid.addView(line);
                for(int column=0;column<7;column++){
                    int index=week*7+column;LocalDate date=index<dates.size()?dates.get(index):null;
                    TextView cell=text(date==null?"":String.valueOf(date.getDayOfMonth()),16,ink,false);cell.setGravity(Gravity.CENTER);cell.setIncludeFontPadding(false);
                    LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(42),1);lp.setMargins(dp(1),dp(1),dp(1),dp(1));line.addView(cell,lp);
                    if(date==null)continue;
                    boolean selected=date.equals(chosen[0]);GradientDrawable fill=background(selected?blue:colors.surface,12);
                    if(date.equals(today()) && !selected)fill.setStroke(dp(1),blue);
                    cell.setTextColor(selected?colors.onAccent:ink);if(selected)cell.setTypeface(null,Typeface.BOLD);
                    cell.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x338EAEFF),fill,background(0xFFFFFFFF,12)));
                    cell.setFocusable(true);cell.setContentDescription(date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy",RU)));cell.setSelected(selected);
                    cell.setOnClickListener(v->{chosen[0]=date;draw[0].run();});
                }
            }
        };
        LinearLayout footer=row();panel.addView(footer);
        action(footer,"Сегодня",()->{chosen[0]=today();visible[0]=YearMonth.from(chosen[0]);draw[0].run();});
        footer.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));
        action(footer,"Отмена",()->dialog.dismiss());
        action(footer,"Выбрать",()->{dialog.dismiss();onSelect.accept(chosen[0]);});
        draw[0].run();dialog.setContentView(scroll);dialog.show();
        dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        android.util.DisplayMetrics metrics=getResources().getDisplayMetrics();
        dialog.getWindow().setLayout(Math.min(metrics.widthPixels-dp(24),dp(400)),Math.min(metrics.heightPixels-dp(80),dp(540)));
    }

    private void acceptTeachers(Teachers.State state){if(state.revision>=teacherData.revision)teacherData=state;}
    private void openTeacher(String id){
        selectedTeacherId=id;teacherDay=today();teacherStrip=null;teacherProblem="";render();loadTeacher(id);
    }
    private void loadTeacher(String id){
        final long request=++teacherRequest;teacherLoading=true;teacherProblem="";
        if(selectedTab==1)render();
        teacherIo.submit(()->{
            Teachers.State loaded;String problem="";
            try{loaded=TeacherRepository.refreshOne(getApplicationContext(),id,false);}
            catch(Exception e){loaded=TeacherRepository.read(getApplicationContext());problem="Не удалось загрузить расписание. Проверьте интернет или нажмите «Обновить».";}
            final Teachers.State result=loaded;final String message=problem;
            runOnUiThread(()->{if(isDestroyed() || request!=teacherRequest || !id.equals(selectedTeacherId))return;acceptTeachers(result);teacherLoading=false;teacherProblem=message;render();});
        });
    }
    private void backToTeachers(){selectedTeacherId=null;teacherRequest++;teacherLoading=false;teacherProblem="";render();}
    @Override public void onBackPressed(){if(selectedTab==1 && selectedTeacherId!=null){backToTeachers();return;}super.onBackPressed();}
    private void renderTeachers(){
        if(selectedTeacherId!=null){renderTeacherSchedule();return;}
        if(!busy && !teacherData.error.isEmpty())status.setText(teacherData.error);
        EditText search=new EditText(this);search.setSingleLine(true);search.setTextSize(15);search.setHint("Поиск по фамилии");search.setContentDescription("Поиск преподавателя по фамилии");search.setText(teacherQuery);
        search.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        content.addView(search,new LinearLayout.LayoutParams(-1,dp(44)));
        ScrollView scroll=new ScrollView(this);teacherScroll=scroll;LinearLayout list=column();scroll.addView(list);content.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        fillTeacherList(list);
        final int restore=teacherScrollY;scroll.post(()->{if(scroll.getParent()!=null)scroll.scrollTo(0,restore);});
        search.addTextChangedListener(new TextWatcher(){
            @Override public void beforeTextChanged(CharSequence text,int start,int count,int after){}
            @Override public void onTextChanged(CharSequence text,int start,int before,int count){teacherQuery=text.toString();teacherScrollY=0;fillTeacherList(list);scroll.scrollTo(0,0);}
            @Override public void afterTextChanged(Editable value){}
        });
    }
    private void fillTeacherList(LinearLayout list){
        list.removeAllViews();String query=teacherQuery.trim().toLowerCase(RU).replace('ё','е');int count=0;
        for(Teachers.Entry teacher:teacherData.entries.values()){
            if(!teacher.name.toLowerCase(RU).replace('ё','е').contains(query))continue;
            TextView card=text(teacher.name,16,ink,true);card.setGravity(Gravity.CENTER);card.setPadding(dp(8),dp(8),dp(8),dp(8));card.setBackground(background(colors.teacherCard,8));card.setFocusable(true);card.setContentDescription(teacher.name+". Открыть расписание");card.setOnClickListener(v->{((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(card.getWindowToken(),0);openTeacher(teacher.id);});
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(48));lp.setMargins(0,dp(2),0,dp(2));list.addView(card,lp);count++;
        }
        if(count==0)list.addView(text(teacherData.entries.isEmpty()?(busy?"Загружаем список преподавателей…":"Список ещё не загружен. Нажмите «Обновить»."):"Преподаватели не найдены",16,muted,false));
    }
    private void renderTeacherSchedule(){
        Teachers.Entry entry=teacherData.entries.get(selectedTeacherId);Teachers.Schedule schedule=teacherData.schedules.get(selectedTeacherId);
        LinearLayout header=row();action(header,"‹ К списку",()->backToTeachers());
        String name=entry!=null?entry.name:schedule!=null && !schedule.weeks.isEmpty()?schedule.weeks.get(0).name:"Преподаватель";
        TextView title=text(name,17,ink,true);title.setMaxLines(2);header.addView(title,new LinearLayout.LayoutParams(0,dp(44),1));content.addView(header);
        if(!busy){
            String problem=!teacherProblem.isEmpty()?teacherProblem:teacherData.errors.get(selectedTeacherId);
            String checked=schedule==null?"Расписание ещё не загружено":"Проверено "+DateTimeFormatter.ofPattern("dd.MM HH:mm",RU).format(Instant.ofEpochMilli(schedule.checked).atZone(ZoneId.systemDefault()));
            status.setText(teacherLoading?(schedule==null?"Загружаем расписание преподавателя…":checked+" · проверяем обновления…"):problem!=null && !problem.isEmpty()?problem:checked+(schedule!=null && System.currentTimeMillis()-schedule.checked>=Repository.DAY?" · данные старше суток":""));
        }
        renderTeacherCalendar(schedule);
        TextView date=text((teacherDay.equals(today())?"Сегодня · ":"")+teacherDay.format(DateTimeFormatter.ofPattern("EEEE, d MMMM",RU)),15,ink,true);content.addView(date,new LinearLayout.LayoutParams(-1,dp(30)));
        Teachers.Week week=Teachers.week(schedule,teacherDay);
        if(week==null){TextView message=text(schedule==null?(teacherLoading?"Загружаем расписание…":"Нет сохранённого расписания. Для первой загрузки нужен интернет."):"На эту дату нет загруженного расписания. Выберите отмеченный день или нажмите «Обновить».",16,muted,false);content.addView(message,new LinearLayout.LayoutParams(-1,0,1));return;}
        List<Teachers.Lesson> lessons=week.days.get(teacherDay);
        if(lessons.isEmpty()){TextView empty=text("Занятий нет",20,muted,false);empty.setGravity(Gravity.CENTER);content.addView(empty,new LinearLayout.LayoutParams(-1,0,1));return;}
        LinearLayout columns=row();columns.addView(text("№",11,muted,true),new LinearLayout.LayoutParams(dp(28),dp(22)));columns.addView(text("Группа · предмет · кабинет",11,muted,false));content.addView(columns);
        LinearLayout table=column();content.addView(table,new LinearLayout.LayoutParams(-1,0,1));int count=6;for(Teachers.Lesson lesson:lessons)count=Math.max(count,lesson.number);
        boolean zero=false;for(Teachers.Lesson lesson:lessons)if(lesson.number==0)zero=true;
        for(int number=zero?0:1;number<=count;number++){
            LinearLayout line=row();line.setBackground(background(colors.surface,10));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,0,1);lp.setMargins(0,dp(2),0,dp(2));table.addView(line,lp);
            TextView n=text(String.valueOf(number),15,blue,true);n.setGravity(Gravity.CENTER);line.addView(n,new LinearLayout.LayoutParams(dp(28),-1));StringBuilder value=new StringBuilder();
            for(Teachers.Lesson lesson:lessons)if(lesson.number==number){if(value.length()>0)value.append("\n");if(!lesson.group.isEmpty())value.append(lesson.group).append(" · ");value.append(lesson.subject);if(!lesson.room.isEmpty())value.append("\nКабинет ").append(lesson.room);}
            TextView cell=text(value.length()==0?"—":value.toString(),14,value.length()==0?muted:ink,false);cell.setGravity(Gravity.CENTER);cell.setPadding(dp(5),dp(2),dp(5),dp(2));cell.setAutoSizeTextTypeUniformWithConfiguration(8,14,1,android.util.TypedValue.COMPLEX_UNIT_SP);line.addView(cell,new LinearLayout.LayoutParams(0,-1,1));
        }
    }
    private void renderTeacherCalendar(Teachers.Schedule schedule){
        if(teacherStrip==null)teacherStrip=teacherDay.with(DayOfWeek.MONDAY);
        LinearLayout nav=row();action(nav,"‹",()->{teacherStrip=teacherStrip.minusWeeks(1);render();}).setContentDescription("Предыдущая неделя преподавателя");
        TextView month=text(teacherStrip.plusDays(3).format(DateTimeFormatter.ofPattern("LLLL yyyy",RU))+" ▾",14,ink,true);month.setGravity(Gravity.CENTER);month.setFocusable(true);month.setOnClickListener(v->showCalendar(teacherDay,day->{teacherDay=day;teacherStrip=null;render();}));nav.addView(month,new LinearLayout.LayoutParams(0,dp(40),1));
        action(nav,"Сегодня",()->{teacherDay=today();teacherStrip=null;render();});action(nav,"›",()->{teacherStrip=teacherStrip.plusWeeks(1);render();}).setContentDescription("Следующая неделя преподавателя");content.addView(nav);
        DayStrip strip=new DayStrip(this,teacherStrip,teacherDay,day->!Teachers.lessons(schedule,day).isEmpty(),day->Teachers.week(schedule,day)!=null);strip.setListener((day,first)->{teacherDay=day;teacherStrip=first;render();});content.addView(strip,new LinearLayout.LayoutParams(-1,dp(44)));
    }
    private void renderBells(){
        Bells.Image bells=data.bells;
        if(bells==null||bells.bytes==null){content.addView(text("Нажмите «Обновить» с включённым интернетом, чтобы загрузить звонки.",16,muted,false));return;}
        TextView title=text(bells.title,14,ink,true);title.setMaxLines(2);content.addView(title);
        if(data.bellsError!=null)content.addView(text("Не удалось обновить звонки · сохранённый оригинал",11,muted,false));
        if(System.currentTimeMillis()-bells.checked>=Repository.DAY)content.addView(text("Изображение старше суток",11,muted,false));
        if(bitmapChecked!=bells.checked){bellsBitmap=BitmapFactory.decodeByteArray(bells.bytes,0,bells.bytes.length);bitmapChecked=bells.checked;}
        ZoomImageView image=new ZoomImageView(this,bellsBitmap);image.setBackgroundColor(colors.surface);
        image.setContentDescription("Расписание звонков. Разведите два пальца для увеличения. Нажмите, чтобы открыть на весь экран.");image.setFocusable(true);image.setOnClickListener(v->fullscreenBells());
        content.addView(image,new LinearLayout.LayoutParams(-1,0,1));
        TextView hint=text("Два пальца — масштаб · нажатие — весь экран",11,muted,false);hint.setGravity(Gravity.CENTER);content.addView(hint,new LinearLayout.LayoutParams(-1,dp(24)));
    }
    private void fullscreenBells(){
        Dialog dialog=new Dialog(this,colors.dark?android.R.style.Theme_Material_NoActionBar_Fullscreen:android.R.style.Theme_Material_Light_NoActionBar_Fullscreen);
        LinearLayout box=column();box.setBackgroundColor(colors.surface);
        ZoomImageView image=new ZoomImageView(this,bellsBitmap);image.setContentDescription("Расписание звонков. Два пальца — масштаб, перетаскивание — перемещение.");box.addView(image,new LinearLayout.LayoutParams(-1,0,1));
        action(box,"Закрыть",()->dialog.dismiss());dialog.setContentView(box);dialog.show();dialog.getWindow().setLayout(-1,-1);
    }
    private Timetable.Week weekFor(LocalDate day){for(Timetable.Week week:data.weeks)if(week.days.containsKey(day))return week;return null;}
    private List<Timetable.Lesson> lessonsFor(LocalDate day){
        List<Timetable.Lesson> result=new ArrayList<>();Timetable.Week week=weekFor(day);
        if(week!=null)for(Timetable.Lesson l:week.days.get(day))if(subgroup==0||l.subgroup==0||l.subgroup==subgroup)result.add(l);return result;
    }
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(1);return v;}
    private LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setOrientation(0);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    private TextView text(String value,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setGravity(Gravity.CENTER_VERTICAL);t.setPadding(0,dp(2),0,dp(2));if(bold)t.setTypeface(null,Typeface.BOLD);return t;}
    private GradientDrawable background(int color,int radius){GradientDrawable bg=new GradientDrawable();bg.setColor(color);bg.setCornerRadius(dp(radius));return bg;}
    private Button action(LinearLayout parent,String title,Runnable callback){
        boolean primary=title.equals("Обновить") || title.equals("Выбрать"),iconOnly=title.equals("⋮") || title.equals("‹") || title.equals("›");
        int icon=title.equals("Обновить")?AppIcon.REFRESH:title.equals("⋮")?AppIcon.SETTINGS:title.equals("Закрыть")?AppIcon.CLOSE:title.equals("›")?AppIcon.RIGHT:title.equals("Сегодня")?AppIcon.CALENDAR:AppIcon.LEFT;
        String label=title.equals("‹ К списку")?"К списку":title;
        int foreground=primary?colors.onAccent:blue;
        Button button=new Button(this);button.setText(iconOnly?"":label);button.setTextSize(12);button.setLetterSpacing(0);button.setAllCaps(false);button.setTypeface(null,Typeface.BOLD);button.setGravity(Gravity.CENTER);button.setIncludeFontPadding(false);
        button.setMaxLines(1);button.setHorizontallyScrolling(false);button.setMinWidth(0);button.setMinimumWidth(0);button.setMinHeight(0);button.setMinimumHeight(0);button.setPadding(dp(iconOnly?13:10),0,dp(iconOnly?13:10),0);
        android.content.res.ColorStateList textColors=new android.content.res.ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{muted,foreground});
        button.setTextColor(textColors);button.setBackgroundTintList(null);button.setStateListAnimator(null);button.setElevation(0);
        GradientDrawable normal=background(primary?blue:colors.teacherCard,12);
        if(!primary)normal.setStroke(dp(1),colors.dark?0xFF34445F:0xFFDDE5F3);
        android.graphics.drawable.StateListDrawable states=new android.graphics.drawable.StateListDrawable();states.addState(new int[]{-android.R.attr.state_enabled},background(colors.surface,12));states.addState(new int[]{},normal);
        android.graphics.drawable.RippleDrawable ripple=new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf((foreground & 0x00FFFFFF) | 0x33000000),states,background(0xFFFFFFFF,12));button.setBackground(ripple);
        AppIcon drawable=new AppIcon(icon,foreground);drawable.setBounds(0,0,dp(18),dp(18));drawable.setTintList(textColors);button.setCompoundDrawables(title.equals("Отмена") || title.equals("Выбрать")?null:drawable,null,null,null);button.setCompoundDrawablePadding(iconOnly?0:dp(5));
        String description=title.equals("⋮")?"Настройки":title.equals("‹")?"Предыдущая неделя":title.equals("›")?"Следующая неделя":label;button.setContentDescription(description);button.setTooltipText(description);
        button.setOnClickListener(v->callback.run());LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(iconOnly?dp(44):-2,dp(44));lp.setMargins(dp(2),dp(2),dp(2),dp(2));if(parent.getOrientation()==LinearLayout.VERTICAL)lp.gravity=Gravity.CENTER_HORIZONTAL;parent.addView(button,lp);return button;
    }
    private void open(String url){try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}catch(ActivityNotFoundException e){Toast.makeText(this,"Браузер не установлен",0).show();}}
    private void settings(){
        if(settingsDialog!=null && settingsDialog.isShowing())return;
        final Dialog dialog=new Dialog(this);settingsDialog=dialog;
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel=column();panel.setPadding(dp(20),dp(14),dp(20),dp(14));panel.setBackground(background(colors.background,20));
        LinearLayout heading=row();heading.addView(text("Настройки",21,ink,true),new LinearLayout.LayoutParams(0,dp(48),1));action(heading,"Закрыть",()->dialog.dismiss());panel.addView(heading);
        panel.addView(text("Тема",16,ink,true));
        RadioGroup choices=new RadioGroup(this);choices.setOrientation(1);
        String[] labels={"Авто","Тёмная","Светлая"},modes={"auto","dark","light"};
        String chosen=AppAppearance.mode(this);int checked=0;
        for(int i=0;i<3;i++){
            RadioButton option=new RadioButton(this);option.setId(300+i);option.setText(labels[i]);option.setTextSize(16);option.setTextColor(ink);choices.addView(option,new RadioGroup.LayoutParams(-1,dp(48)));
            if(modes[i].equals(chosen))checked=i;
        }
        choices.check(300+checked);panel.addView(choices);
        choices.setOnCheckedChangeListener((group,id)->{
            String mode=modes[id-300];if(mode.equals(AppAppearance.mode(this)))return;
            AppAppearance.setMode(this,mode);reopenSettings=true;dialog.dismiss();recreate();
        });
        panel.addView(new View(this),new LinearLayout.LayoutParams(-1,0,1));
        TextView author=text("разработчик - expat",14,muted,false);author.setGravity(Gravity.CENTER);panel.addView(author,new LinearLayout.LayoutParams(-1,dp(32)));
        TextView contact=text("Telegram разработчика",15,blue,true);contact.setGravity(Gravity.CENTER);contact.setFocusable(true);contact.setOnClickListener(v->open(DEVELOPER_TELEGRAM));contact.setContentDescription("Telegram разработчика: @expat2159");panel.addView(contact,new LinearLayout.LayoutParams(-1,dp(48)));
        dialog.setContentView(panel);dialog.setOnDismissListener(d->{if(settingsDialog==dialog)settingsDialog=null;});dialog.show();
        android.util.DisplayMetrics metrics=getResources().getDisplayMetrics();
        dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        dialog.getWindow().setLayout(Math.min(metrics.widthPixels-dp(24),dp(400)),Math.min(metrics.heightPixels-dp(80),dp(480)));
    }
    @Override protected void onSaveInstanceState(Bundle out){out.putBoolean("showSettings",reopenSettings || (settingsDialog!=null && settingsDialog.isShowing()));if(stripStart!=null)out.putString("stripStart",stripStart.toString());out.putString("teacherId",selectedTeacherId);out.putString("teacherQuery",teacherQuery);out.putInt("teacherScrollY",teacherScroll!=null && teacherScroll.getParent()!=null?teacherScroll.getScrollY():teacherScrollY);out.putString("teacherDay",teacherDay.toString());if(teacherStrip!=null)out.putString("teacherStrip",teacherStrip.toString());out.putString("selectedDay",selectedDay.toString());out.putInt("selectedTab",selectedTab);super.onSaveInstanceState(out);}
    @Override public void onDestroy(){io.shutdownNow();teacherIo.shutdownNow();if(settingsDialog!=null)settingsDialog.dismiss();super.onDestroy();}
}
