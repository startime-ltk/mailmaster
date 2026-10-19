package com.mailmaster.android;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.util.Log;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 后台自动收邮件任务（由 JobScheduler 周期性触发）。
 *
 * 流程：
 *  1) 拉取全部账户最近邮件（复用 MailService.fetchAll）；
 *  2) 与本地缓存的 msg_id 做差集 → 得出「新邮件」（回收站/已读不影响判定）；
 *  3) 有新邮件才落盘缓存（MailCache.upsertAll 内部会排除回收站邮件，不会复活）；
 *  4) 发一条摘要通知。
 *
 * 容错：网络失败静默结束（不 reschedule，等下一个周期）；
 * onStopJob 置取消标志，防止任务被系统杀掉后还弹通知。
 */
public class MailCheckJobService extends JobService {

    private static final String TAG = "MailCheckJob";
    private volatile boolean cancelled = false;

    @Override
    public boolean onStartJob(final JobParameters params) {
        cancelled = false;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    checkNewMail();
                } catch (Exception e) {
                    Log.w(TAG, "check failed", e);
                } finally {
                    jobFinished(params, false);
                }
            }
        }).start();
        return true; // 还有异步工作未完成
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        cancelled = true; // 系统取消任务（约束不再满足等）：中断后续动作
        return false;     // 不需要系统重试，下个周期自然再来
    }

    private void checkNewMail() {
        if (!AutoReceivePrefs.isEnabled(this)) return;
        List<MailAccount> accounts = MainActivity.store.accounts;
        if (accounts == null || accounts.isEmpty()) return;

        MailCache cache = MailCache.getInstance(this);
        // 本地已有邮件 id 集合（getAll 已排除回收站，已删的不会被误判为"新"）
        Set<String> known = new HashSet<>();
        for (MailMessage m : cache.getAll()) known.add(m.id);

        List<MailMessage> fresh = MailService.fetchAll(this, accounts);
        if (cancelled || fresh == null) return;

        List<MailMessage> newOnes = new ArrayList<>();
        for (MailMessage m : fresh) {
            if (m != null && !known.contains(m.id)) newOnes.add(m);
        }

        // AI 分类：与手动刷新保持一致，失败则保留本地规则分类，不影响通知
        try {
            AiSettings ai = AiSettings.load(this);
            if (Classifier.aiAvailable(ai)) Classifier.aiClassifyBatch(fresh, ai);
        } catch (Exception ignored) {
        }

        if (!newOnes.isEmpty()) {
            cache.upsertAll(fresh);
        }
        if (cancelled) return;
        if (!newOnes.isEmpty()) {
            MailNotifier.notifyNewMail(this, newOnes);
        }
    }
}
