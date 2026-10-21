package ru.mkeiit.schedule;

import android.app.job.*;
import android.content.*;
import java.util.concurrent.*;

public class DailyJob extends JobService {
    private ExecutorService executor;
    private Future<?> running;
    public static void schedule(Context context) {
        JobScheduler scheduler=(JobScheduler)context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if(scheduler.getPendingJob(302)==null) scheduler.schedule(new JobInfo.Builder(302,new ComponentName(context,DailyJob.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true)
            .setPeriodic(Repository.DAY,2*60*60*1000L).build());
    }
    @Override public boolean onStartJob(JobParameters params) {
        executor=Executors.newSingleThreadExecutor();
        running=executor.submit(()->{
            boolean retry=false;
            try { Repository.refresh(getApplicationContext()); }
            catch(Exception e) { retry=true; }
            if(!Thread.currentThread().isInterrupted()) jobFinished(params,retry);
            executor.shutdown();
        });
        return true;
    }
    @Override public boolean onStopJob(JobParameters params) {
        if(running!=null) running.cancel(true);
        if(executor!=null) executor.shutdownNow();
        return true;
    }
}
