package com.mailmaster.android;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;

/**
 * 自动接收设置（SharedPreferences）+ JobScheduler 周期任务调度。
 *
 * 技术选型：项目为无 AndroidX 的手动构建链，WorkManager 不可用，
 * 故用框架原生 JobScheduler（API 21+）——系统级批处理、省电，
 * setPersisted(true) 让任务在重启后保留。周期任务最小间隔为 15 分钟。
 */
public final class AutoReceivePrefs {

    public static final long INTERVAL_15MIN = 15 * 60 * 1000L;
    public static final long INTERVAL_30MIN = 30 * 60 * 1000L;
    public static final long INTERVAL_1H    = 60 * 60 * 1000L;
    public static final long INTERVAL_3H    = 3 * 60 * 60 * 1000L;
    public static final long[] INTERVALS = {INTERVAL_15MIN, INTERVAL_30MIN, INTERVAL_1H, INTERVAL_3H};

    private static final String SP = "mailmaster_settings";
    private static final String KEY_ENABLED = "auto_receive_enabled";
    private static final String KEY_INTERVAL = "auto_receive_interval";
    private static final int JOB_ID = 1001;

    private AutoReceivePrefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(SP, Context.MODE_PRIVATE);
    }

    /** 自动接收开关（默认开） */
    public static boolean isEnabled(Context c) {
        return sp(c).getBoolean(KEY_ENABLED, true);
    }

    public static void setEnabled(Context c, boolean on) {
        sp(c).edit().putBoolean(KEY_ENABLED, on).apply();
    }

    /** 拉取间隔（默认 15 分钟，即 JobScheduler 周期下限） */
    public static long getInterval(Context c) {
        return sp(c).getLong(KEY_INTERVAL, INTERVAL_15MIN);
    }

    public static void setInterval(Context c, long millis) {
        sp(c).edit().putLong(KEY_INTERVAL, millis).apply();
    }

    /**
     * 按当前设置重排后台任务。先取消再排，保证开关/间隔变更即时生效；
     * 开关关闭或无账户时不排（无账户时拉了也是白跑）。
     */
    public static void syncSchedule(Context ctx) {
        if (ctx == null) return;
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        try {
            js.cancel(JOB_ID);
            if (!isEnabled(ctx)) return;
            if (MainActivity.store == null || MainActivity.store.accounts.isEmpty()) return;
            JobInfo info = new JobInfo.Builder(JOB_ID, new ComponentName(ctx, MailCheckJobService.class))
                    .setPeriodic(getInterval(ctx))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build();
            js.schedule(info);
        } catch (Exception ignored) {
        }
    }
}
