package com.mailmaster.core;

import com.mailmaster.core.config.ProviderPresets;
import com.mailmaster.core.crypto.AesUtil;
import com.mailmaster.core.demo.DemoData;
import com.mailmaster.core.model.EmailAccount;
import com.mailmaster.core.model.EmailMessage;
import com.mailmaster.core.service.AccountStore;
import com.mailmaster.core.service.AiSettings;
import com.mailmaster.core.service.MailReceiver;
import com.mailmaster.core.service.MailSender;
import com.mailmaster.core.service.MessageStore;

import java.io.File;
import java.util.*;
import java.util.concurrent.*;

/**
 * 门面（Facade）：对 UI / Web / 未来小程序后端统一暴露能力，隐藏底层细节。
 *
 * v1.20 起与安卓端对齐的核心能力：
 *  - 邮件本地持久化（H2 MessageStore）：重启不丢、离线可看、列表秒开；
 *  - UID 增量同步：日常只拉新邮件，首次/UIDVALIDITY 变化拉最近 100 封，
 *    更早历史由 loadOlder() 按需翻页；
 *  - 智能分类：本地规则兜底 + AI 批量分类（设置见 AiSettings）；
 *  - 回收站（软删除/恢复/彻底删除）、草稿箱、已读回写服务器。
 *
 * 多端预留关键：本类以及 core 下所有类均为纯 Java，不依赖 JavaFX；
 * 未来做 Web / 小程序时，只需把本类包成 REST API 即可，逻辑零改动。
 */
public class MailEngine {

    private final AccountStore store;
    private final MessageStore msgStore;
    private final MailReceiver receiver = new MailReceiver();
    private final MailSender sender = new MailSender();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private volatile Runnable onUpdate;
    private final File settingsDir;

    /**
     * 演示模式开关：开启后用本地示例数据代替真实邮箱，全程不联网。
     * 真实模式（false）下所有行为保持不变。
     */
    private volatile boolean demoMode = false;
    private volatile boolean schedulerStarted = false;
    private volatile List<EmailAccount> demoAccountsCache;

    /** 演示模式的回收站/已读（内存态，示例数据每次重建，无法真正持久化） */
    private final Set<String> demoDeleted = new HashSet<>();
    private final Set<String> demoRead = new HashSet<>();

    /** AI 分类设置（变更后立即生效，落盘 ~/.mailmaster/ai.properties） */
    private AiSettings aiSettings;

    public MailEngine(String dbPath) {
        this.store = new AccountStore(dbPath);
        this.msgStore = new MessageStore(dbPath);
        File db = new File(dbPath).getAbsoluteFile();
        this.settingsDir = db.getParentFile() != null ? db.getParentFile() : new File(".");
        this.aiSettings = AiSettings.load(settingsDir);
    }

    public void setOnUpdate(Runnable r) { this.onUpdate = r; }

    public AiSettings getAiSettings() { return aiSettings; }

    public void setAiSettings(AiSettings s) {
        this.aiSettings = (s == null) ? new AiSettings() : s;
        this.aiSettings.save(settingsDir);
    }

    /** 是否已录入过真实账户（UI 用它判断要不要自动进入演示模式） */
    public boolean hasRealAccounts() {
        try {
            return !store.loadAll().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isDemoMode() { return demoMode; }

    /** 进入演示模式：示例数据铺满界面，全程不联网 */
    public void enterDemoMode() {
        this.demoMode = true;
        demoDeleted.clear();
        demoRead.clear();
        notifyUpdate();
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
        msgStore.deleteAccount(id);   // 级联清理：邮件缓存 + 水位 + 草稿
        notifyUpdate();
    }

    /**
     * 同步所有账户（UID 增量）。
     * 每账户独立 try：单账户失败（密码错/超时）只记日志，不影响其他账户。
     */
    public synchronized void refresh() throws Exception {
        if (demoMode) {
            notifyUpdate();
            return;
        }
        for (EmailAccount a : store.loadAll()) {
            try {
                syncAccount(a);
            } catch (Exception e) {
                System.err.println("拉取失败 " + a.getEmail() + ": " + e.getMessage());
            }
        }
        notifyUpdate();
    }

    /** 单账户增量同步（三模式：首次窗口 / UIDVALIDITY 变化重拉 / 日常增量） */
    private void syncAccount(EmailAccount a) throws Exception {
        String pwd = AesUtil.decrypt(a.getEncryptedPassword());
        MessageStore.SyncState st = msgStore.getSyncState(a.getId());

        List<EmailMessage> fresh;
        if (st == null) {
            // 首次同步：拉最近 FIRST_WINDOW 封
            MailReceiver.FetchResult r = receiver.fetchWindow(a, pwd, MailReceiver.FIRST_WINDOW);
            msgStore.resetSyncState(a.getId(), r.uidValidity, r.minUid, r.maxUid, r.hasOlder);
            fresh = r.messages;
        } else {
            MailReceiver.FetchResult r = receiver.fetchNewer(a, pwd, st.maxUid);
            if (r.uidValidity != st.uidValidity) {
                // 服务器重建过邮箱：旧 UID 作废，全量重拉并【重置】水位（不与旧世代合并）
                MailReceiver.FetchResult w = receiver.fetchWindow(a, pwd, MailReceiver.FIRST_WINDOW);
                msgStore.resetSyncState(a.getId(), w.uidValidity, w.minUid, w.maxUid, w.hasOlder);
                fresh = w.messages;
            } else if (!r.messages.isEmpty()) {
                msgStore.saveSyncState(a.getId(), r.uidValidity, r.maxUid, -1);
                fresh = r.messages;
            } else {
                fresh = Collections.emptyList();
            }
        }

        if (!fresh.isEmpty()) {
            // 本地规则先分类，AI 再覆盖（失败保留本地结果）
            for (EmailMessage m : fresh) {
                if (m.getCategory() == null || m.getCategory().isEmpty()) {
                    m.setCategory(Classifier.classify(m.getFrom(), m.getSubject(), m.getBodyText()));
                }
            }
            if (Classifier.aiAvailable(aiSettings)) {
                Classifier.aiClassifyBatch(fresh, aiSettings);
            }
            msgStore.upsertAll(fresh);
        }
    }

    /** 向前翻页：拉取比本地最小 UID 更早的 count 封，并入缓存 */
    public int loadOlder(int count) {
        if (demoMode) return 0;
        int loaded = 0;
        for (EmailAccount a : store.loadAll()) {
            try {
                String pwd = AesUtil.decrypt(a.getEncryptedPassword());
                MessageStore.SyncState st = msgStore.getSyncState(a.getId());
                if (st == null || st.minUid <= 1) continue;
                MailReceiver.FetchResult r = receiver.fetchRange(a, pwd,
                        Math.max(1, st.minUid - count), st.minUid - 1);
                if (r.messages.isEmpty()) {
                    // 一封没有：到底
                    msgStore.saveSyncState(a.getId(), st.uidValidity, st.minUid, 0);
                    continue;
                }
                for (EmailMessage m : r.messages) {
                    if (m.getCategory() == null || m.getCategory().isEmpty()) {
                        m.setCategory(Classifier.classify(m.getFrom(), m.getSubject(), m.getBodyText()));
                    }
                }
                if (Classifier.aiAvailable(aiSettings)) {
                    Classifier.aiClassifyBatch(r.messages, aiSettings);
                }
                msgStore.upsertAll(r.messages);
                // 拉满一页且没到 UID 1 → 可能还有更早；否则收敛
                int hasOlder = (r.messages.size() >= count && r.minUid > 1) ? -1 : 0;
                msgStore.saveSyncState(a.getId(), st.uidValidity, r.minUid, hasOlder);
                loaded += r.messages.size();
            } catch (Exception e) {
                System.err.println("翻页失败 " + a.getEmail() + ": " + e.getMessage());
            }
        }
        notifyUpdate();
        return loaded;
    }

    /** 是否还有更早的邮件（UI 显示「加载更早」入口） */
    public boolean hasOlderMail() {
        return !demoMode && msgStore.hasOlderMail();
    }

    /** 统一收件箱（按时间倒序，不含回收站） */
    public List<EmailMessage> getInbox() {
        if (demoMode) {
            List<EmailMessage> all = new ArrayList<>();
            for (EmailMessage m : DemoData.messages(demoAccounts())) {
                if (demoDeleted.contains(m.getId())) continue;
                if (demoRead.contains(m.getId())) m.setRead(true);
                all.add(m);
            }
            all.sort((x, y) -> {
                long a = x.getSentDate() == null ? 0 : x.getSentDate().getTime();
                long b = y.getSentDate() == null ? 0 : y.getSentDate().getTime();
                return Long.compare(b, a);
            });
            return all;
        }
        return msgStore.getAll();
    }

    /** 回收站列表（按删除时间倒序） */
    public List<EmailMessage> getTrash() {
        if (demoMode) {
            List<EmailMessage> out = new ArrayList<>();
            for (EmailMessage m : DemoData.messages(demoAccounts())) {
                if (demoDeleted.contains(m.getId())) out.add(m);
            }
            return out;
        }
        return msgStore.getTrash();
    }

    public int countTrash() {
        if (demoMode) return demoDeleted.size();
        return msgStore.countTrash();
    }

    /** 移入回收站（软删除，服务器原件不动） */
    public void moveToTrash(String msgId) {
        if (demoMode) { demoDeleted.add(msgId); notifyUpdate(); return; }
        EmailMessage m = findInbox(msgId);
        if (m != null) msgStore.softDelete(m.getAccountId(), m.getId());
        notifyUpdate();
    }

    /** 从回收站恢复 */
    public void restore(String msgId) {
        if (demoMode) { demoDeleted.remove(msgId); notifyUpdate(); return; }
        EmailMessage m = findInList(msgStore.getTrash(), msgId);
        if (m != null) msgStore.restore(m.getAccountId(), m.getId());
        notifyUpdate();
    }

    /** 彻底删除一封（不可恢复） */
    public void purge(String msgId) {
        if (demoMode) { demoDeleted.remove(msgId); notifyUpdate(); return; }
        EmailMessage m = findInList(msgStore.getTrash(), msgId);
        if (m != null) msgStore.purge(m.getAccountId(), m.getId());
        notifyUpdate();
    }

    /** 清空回收站 */
    public int purgeAllTrash() {
        if (demoMode) { int n = demoDeleted.size(); demoDeleted.clear(); notifyUpdate(); return n; }
        int n = msgStore.purgeAll();
        notifyUpdate();
        return n;
    }

    /**
     * 打开即已读：本地缓存立即更新，再异步回写服务器（按 UID）。
     * 换个设备打开不会再变未读——这是「真实客户端」与 Demo 的分水岭。
     */
    public void markRead(String msgId) {
        if (msgId == null) return;
        if (demoMode) { demoRead.add(msgId); notifyUpdate(); return; }
        EmailMessage m = findInbox(msgId);
        if (m == null || m.isRead()) return;
        m.setRead(true);
        msgStore.markRead(m.getAccountId(), m.getUid());
        notifyUpdate();
        // 服务器回写放后台线程，避免 UI 卡在网络等待上
        final EmailAccount acc = findAccount(m.getAccountId());
        final long uid = m.getUid();
        if (acc == null || uid <= 0) return;
        new Thread(() -> {
            try {
                receiver.markRead(acc, AesUtil.decrypt(acc.getEncryptedPassword()), uid);
            } catch (Exception e) {
                System.err.println("已读回写服务器失败: " + e.getMessage());
            }
        }).start();
    }

    /** 单封重拉正文（治「只有标题没内容」），成功则覆盖缓存并返回新邮件 */
    public EmailMessage retryBody(String msgId) {
        if (demoMode) return null;
        EmailMessage m = findInbox(msgId);
        if (m == null || m.getUid() <= 0) return null;
        EmailAccount acc = findAccount(m.getAccountId());
        if (acc == null) return null;
        try {
            EmailMessage fresh = receiver.fetchByUid(acc,
                    AesUtil.decrypt(acc.getEncryptedPassword()), m.getUid());
            if (fresh != null && fresh.hasContent()) {
                fresh.setCategory(m.getCategory());   // 保留已有分类
                fresh.setRead(m.isRead());            // 保留已读态
                msgStore.upsertAll(Collections.singletonList(fresh));
                notifyUpdate();
                return fresh;
            }
        } catch (Exception e) {
            System.err.println("重拉正文失败: " + e.getMessage());
        }
        return null;
    }

    // ---------------- 草稿箱 ----------------

    /** 保存草稿（id>0 覆盖，否则新建），返回草稿 id */
    public long saveDraft(long id, String accountId, String to, String subject, String body) {
        String email = "";
        for (EmailAccount a : getAccounts()) {
            if (a.getId().equals(accountId)) { email = a.getEmail(); break; }
        }
        return msgStore.saveDraft(id, accountId, email, to, subject, body);
    }

    /** 草稿列表（String[7]：id, accountId, accountEmail, to, subject, body, updatedAt） */
    public List<String[]> getDrafts() {
        return msgStore.getDrafts();
    }

    public void deleteDraft(long id) { msgStore.deleteDraft(id); }

    public int countDrafts() { return msgStore.countDrafts(); }

    // ---------------- 发送 ----------------

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

    // ---------------- 内部工具 ----------------

    private EmailMessage findInbox(String msgId) {
        return findInList(msgStore.getAll(), msgId);
    }

    private EmailMessage findInList(List<EmailMessage> list, String msgId) {
        for (EmailMessage m : list) {
            if (m.getId().equals(msgId)) return m;
        }
        return null;
    }

    private EmailAccount findAccount(String accountId) {
        for (EmailAccount a : store.loadAll()) {
            if (a.getId().equals(accountId)) return a;
        }
        return null;
    }

    private void notifyUpdate() {
        Runnable r = onUpdate;
        if (r != null) r.run();
    }
}
