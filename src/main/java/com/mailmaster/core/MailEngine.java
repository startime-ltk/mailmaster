package com.mailmaster.core;

import com.mailmaster.core.config.ProviderPresets;
import com.mailmaster.core.crypto.AesUtil;
import com.mailmaster.core.demo.DemoData;
import com.mailmaster.core.model.EmailAccount;
import com.mailmaster.core.model.EmailMessage;
import com.mailmaster.core.service.AccountStore;
import com.mailmaster.core.service.MailReceiver;
import com.mailmaster.core.service.MailSender;

import java.util.*;
import java.util.concurrent.*;

/**
 * 门面（Facade）：对 UI / 未来 Web / 小程序后端统一暴露能力，隐藏底层细节。
 *
 * 多端预留关键：
 *  - 本类以及 core 下所有类均为纯 Java，不依赖 JavaFX；
 *  - 未来做 Web / 小程序时，只需把本类包成 REST API 即可，逻辑零改动。
 */
public class MailEngine {

    private final AccountStore store;
    private final MailReceiver receiver = new MailReceiver();
    private final MailSender sender = new MailSender();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final List<EmailMessage> inbox = new CopyOnWriteArrayList<>();
    private volatile Runnable onUpdate;

    /**
     * 演示模式开关：开启后用本地示例数据代替真实邮箱，全程不联网。
     * 真实模式（false）下所有行为保持不变。
     */
    private volatile boolean demoMode = false;
    private volatile boolean schedulerStarted = false;
    private volatile List<EmailAccount> demoAccountsCache;

    public MailEngine(String dbPath) {
        this.store = new AccountStore(dbPath);
    }

    public void setOnUpdate(Runnable r) { this.onUpdate = r; }

    /** 是否已录入过真实账户（UI 用它判断要不要自动进入演示模式） */
    public boolean hasRealAccounts() {
        try {
            return !store.loadAll().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isDemoMode() { return demoMode; }

    /** 进入演示模式：清空真实邮件缓存，改用本地示例数据 */
    public void enterDemoMode() {
        this.demoMode = true;
        inbox.clear();
    }

    /** 退出演示模式：回到真实邮箱（需已录入至少一个真实账户） */
    public void exitDemoMode() { this.demoMode = false; }

    /** 演示账户（懒加载 + 缓存，保证多次调用 id 稳定） */
    private List<EmailAccount> demoAccounts() {
        if (demoAccountsCache == null) {
            synchronized (this) {
                if (demoAccountsCache == null) demoAccountsCache = DemoData.accounts();
            }
        }
        return demoAccountsCache;
    }

    /** 启动定时拉取（秒） */
    public void startAutoRefresh(int periodSeconds) {
        // 演示模式不联网；schedulerStarted 防止重复启动定时器
        if (demoMode || schedulerStarted) return;
        schedulerStarted = true;
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                refresh();
            } catch (Exception e) {
                System.err.println("自动刷新失败: " + e.getMessage());
            }
        }, 0, periodSeconds, TimeUnit.SECONDS);
    }

    public void stop() { scheduler.shutdownNow(); }

    /** 添加预设服务商账户（password 为明文，方法内加密后存储） */
    public void addAccount(String email, String providerKey, String password) throws Exception {
        addAccount(email, providerKey, password, email);
    }

    public void addAccount(String email, String providerKey, String password, String displayName) throws Exception {
        ProviderPresets.Preset p = ProviderPresets.get(providerKey);
        EmailAccount a = new EmailAccount();
        a.setEmail(email);
        a.setDisplayName(displayName == null || displayName.isEmpty() ? email : displayName);
        a.setProvider(providerKey);
        a.setUsername(email);
        if (p != null) {
            a.setImapHost(p.imapHost);
            a.setImapPort(p.imapPort);
            a.setSmtpHost(p.smtpHost);
            a.setSmtpPort(p.smtpPort);
            a.setUseStartTls(p.useStartTls);
            a.setAuthType(p.authType);
        }
        a.setEncryptedPassword(AesUtil.encrypt(password));
        store.save(a);
    }

    /** 自定义服务商账户 */
    public void addCustomAccount(EmailAccount a, String password) throws Exception {
        a.setEncryptedPassword(AesUtil.encrypt(password));
        store.save(a);
    }

    public List<EmailAccount> getAccounts() {
        if (demoMode) return new ArrayList<>(demoAccounts());
        return store.loadAll();
    }

    public void removeAccount(String id) {
        store.delete(id);
        inbox.removeIf(m -> m.getAccountId().equals(id));
    }

    /** 拉取所有账户的新邮件并合并到统一收件箱 */
    public synchronized void refresh() throws Exception {
        if (demoMode) {
            inbox.clear();
            inbox.addAll(DemoData.messages(demoAccounts()));
            if (onUpdate != null) onUpdate.run();
            return;
        }
        Map<String, EmailMessage> map = new LinkedHashMap<>();
        for (EmailMessage m : inbox) map.put(m.getId(), m);
        for (EmailAccount a : store.loadAll()) {
            try {
                String pwd = AesUtil.decrypt(a.getEncryptedPassword());
                List<EmailMessage> msgs = receiver.fetchRecent(a, pwd, 30);
                for (EmailMessage m : msgs) map.put(m.getId(), m);
            } catch (Exception e) {
                System.err.println("拉取失败 " + a.getEmail() + ": " + e.getMessage());
            }
        }
        List<EmailMessage> snapshot = new ArrayList<>(map.values());
        inbox.clear();
        inbox.addAll(snapshot);
        if (onUpdate != null) onUpdate.run();
    }

    /** 统一收件箱（按时间倒序） */
    public List<EmailMessage> getInbox() {
        // 演示模式下无需刷新，首次取件时直接铺满示例邮件
        if (demoMode && inbox.isEmpty()) {
            inbox.addAll(DemoData.messages(demoAccounts()));
        }
        List<EmailMessage> sorted = new ArrayList<>(inbox);
        sorted.sort((x, y) -> {
            long a = x.getSentDate() == null ? 0 : x.getSentDate().getTime();
            long b = y.getSentDate() == null ? 0 : y.getSentDate().getTime();
            return Long.compare(b, a);
        });
        return sorted;
    }

    /** 以指定账户发信 */
    public void sendMail(String accountId, String to, String subject, String body) throws Exception {
        if (demoMode) {
            // 演示模式：只打一行日志，绝不真正联网发信
            System.out.println("[演示模式] 模拟发送 -> " + to + " | 主题：" + subject + "（未真正联网）");
            return;
        }
        EmailAccount a = store.loadAll().stream()
                .filter(x -> x.getId().equals(accountId)).findFirst().orElse(null);
        if (a == null) throw new IllegalArgumentException("账户不存在");
        String pwd = AesUtil.decrypt(a.getEncryptedPassword());
        sender.send(a, pwd, to, subject, body);
    }
}
