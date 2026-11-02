package com.mailmaster.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import com.mailmaster.android.MailCache;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageInfo;
import android.widget.ScrollView;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    public static MailStore store = new MailStore();

    private static final int REQ_OPEN = 2001;

    private ListView list;
    private TextView subtitle;
    private EditText search;
    private ArrayAdapter<MailMessage> adapter;
    private android.app.AlertDialog accountDialog;

    /** 当前全量邮件（未过滤），搜索/筛选时从这里派生 */
    private List<MailMessage> all = new ArrayList<>();
    /** 疑似推广邮件总数（用于拦截视图角标），每次拉取刷新 */
    private int promoTotal = 0;

    /** 筛选状态 */
    private String curMode = "inbox";     // inbox（收件箱，自动拦截推广）| block（疑似骚扰拦截）
    private String curAccount = "all";    // all | 具体账户 id
    private String curCat = "all";        // all | 重要 | 社交 | 其他

    /** chip 引用，用于切换选中态 */
    private TextView chipInbox, chipBlock;
    private TextView chipAll, chipImportant, chipSocial, chipOther;
    private Spinner spinnerAccount;

    /** 底部「加载更早的邮件」入口（挂载后 parent 为 ListView） */
    private View footerMore;
    private boolean loadingOlder = false;

    /** 最近一次刷新的结果摘要，供「诊断信息」读取——出问题时不用再靠猜 */
    private static String lastFetchSummary = "本次启动尚未刷新";
    /** 是否正在联网刷新（诊断面板据此提示“等一会再看”） */
    private static volatile boolean refreshing = false;
    /** 首次启动的“还没有账户”引导只弹一次，避免每次进 App 都打扰 */
    private boolean guideShown = false;

    /** 头像色板：按发件人地址哈希取色，同一个人永远同色 */
    private static final int[] AVATAR_COLORS = {
            0xFF3B82F6, 0xFF10B981, 0xFFF59E0B, 0xFFEF4444,
            0xFF8B5CF6, 0xFF06B6D4, 0xFFEC4899, 0xFFF97316
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        store.init(this);
        subtitle = findViewById(R.id.tv_subtitle);
        list = findViewById(R.id.list);
        search = findViewById(R.id.et_search);

        list.setEmptyView(findViewById(R.id.tv_empty));

        // 底部翻页入口：有更早的邮件时才显示（见 updateFooter）
        footerMore = getLayoutInflater().inflate(R.layout.item_load_more, list, false);
        footerMore.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { loadOlder(); }
        });

        bindToolbar();
        bindFab();
        buildChips();

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { applyFilter(); }
            @Override public void afterTextChanged(Editable s) {}
        });

        refresh();

        // 点击分账户通知直达：若 Intent 带了 accountId，切到该账户的筛选视图
        applyAccountFromIntent(getIntent());

        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int pos, long id) {
                MailMessage m = adapter.getItem(pos);
                if (m == null) return;
                Intent it = new Intent(MainActivity.this, MessageActivity.class);
                it.putExtra("msg", m);
                startActivityForResult(it, REQ_OPEN);
            }
        });

        // 长按邮件项：移入回收站（软删除，可恢复）
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int pos, long id) {
                confirmMoveToTrash(adapter.getItem(pos));
                return true;
            }
        });

        // Android 13+ 发通知需运行时授权（不给权限则新邮件提醒不可见）
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                    REQ_NOTIFY_PERM);
        }
        // 按用户设置启动/停止后台自动收邮件任务
        AutoReceivePrefs.syncSchedule(this);
    }

    /** 通知点击直达（SINGLE_TOP 复用实例时走这里） */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applyAccountFromIntent(intent);
    }

    /**
     * 通知点击直达：把列表切到指定账户（B5 分账户通知的落地动作）。
     * 账户已不存在（删了）则忽略，保持当前视图。
     */
    private void applyAccountFromIntent(Intent it) {
        if (it == null) return;
        final String accId = it.getStringExtra("accountId");
        if (accId == null || accId.isEmpty()) return;
        for (int i = 0; i < store.accounts.size(); i++) {
            if (store.accounts.get(i).id.equals(accId)) {
                curAccount = accId;
                if (spinnerAccount != null) spinnerAccount.setSelection(i + 1); // 触发监听→applyFilter
                else applyFilter();
                return;
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 后台任务可能已拉回新邮件：有缓存就刷新列表（空缓存交给首次 refresh，避免空态闪跳）
        try {
            List<MailMessage> cached = MailCache.getInstance(this).getAll();
            if (!cached.isEmpty()) setList(cached);
        } catch (Exception ignored) {
        }
        updateFooter();
    }

    @Override
    public void onRequestPermissionsResult(int rc, String[] perms, int[] results) {
        super.onRequestPermissionsResult(rc, perms, results);
        if (rc == REQ_NOTIFY_PERM) {
            boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
            if (!granted) {
                Toast.makeText(this, R.string.no_notify_perm, Toast.LENGTH_LONG).show();
            }
        }
    }

    /** 长按删除确认：移入回收站（本地软删除，服务器原件不动，可在回收站恢复） */
    private void confirmMoveToTrash(final MailMessage m) {
        if (m == null) return;
        new AlertDialog.Builder(this)
                .setTitle(R.string.trash_move_title)
                .setMessage(getString(R.string.trash_move_msg, senderLabel(m.from)))
                .setPositiveButton(R.string.btn_delete, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        MailCache.getInstance(MainActivity.this).softDelete(m.accountId, m.id);
                        Toast.makeText(MainActivity.this, R.string.toast_moved_trash, Toast.LENGTH_SHORT).show();
                        // 本地即时移除该封，无需重新联网拉取
                        all.remove(m);
                        applyFilter();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 发件人展示名：优先显示名，退化到邮箱地址 */
    public static String senderLabel(String from) {
        if (from == null || from.trim().isEmpty()) return "(未知发件人)";
        String s = from.trim();
        int lt = s.indexOf('<');
        if (lt > 0) {
            String name = s.substring(0, lt).trim();
            if (!name.isEmpty()) return name;
        }
        return s;
    }

    /** 自动接收设置：开关 + 检查频率（保存后立即重排后台任务） */
    private void showAutoReceiveDialog() {
        View v = getLayoutInflater().inflate(R.layout.dialog_auto_receive, null);
        final Switch sw = v.findViewById(R.id.sw_enabled);
        final Spinner sp = v.findViewById(R.id.sp_interval);
        sw.setChecked(AutoReceivePrefs.isEnabled(this));
        String[] labels = {
                getString(R.string.interval_15min),
                getString(R.string.interval_30min),
                getString(R.string.interval_1h),
                getString(R.string.interval_3h)};
        ArrayAdapter<String> ad = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, labels);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);
        long cur = AutoReceivePrefs.getInterval(this);
        int sel = 0;
        for (int i = 0; i < AutoReceivePrefs.INTERVALS.length; i++) {
            if (AutoReceivePrefs.INTERVALS[i] == cur) sel = i;
        }
        sp.setSelection(sel);

        new AlertDialog.Builder(this)
                .setTitle(R.string.menu_auto_receive)
                .setView(v)
                .setPositiveButton(R.string.btn_save, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        AutoReceivePrefs.setEnabled(MainActivity.this, sw.isChecked());
                        int pos = sp.getSelectedItemPosition();
                        if (pos >= 0 && pos < AutoReceivePrefs.INTERVALS.length) {
                            AutoReceivePrefs.setInterval(MainActivity.this, AutoReceivePrefs.INTERVALS[pos]);
                        }
                        // 开关/间隔变更后重新调度，即时生效
                        AutoReceivePrefs.syncSchedule(MainActivity.this);
                        Toast.makeText(MainActivity.this, R.string.auto_receive_saved,
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void bindToolbar() {
        findViewById(R.id.btn_compose).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, ComposeActivity.class));
            }
        });
        findViewById(R.id.btn_refresh).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { refresh(true); }
        });
        findViewById(R.id.btn_more).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                PopupMenu pm = new PopupMenu(MainActivity.this, v);
                pm.getMenuInflater().inflate(R.menu.main_menu, pm.getMenu());
                // 回收站菜单项带数量角标（0 封时显示纯标题）
                MenuItem trashItem = pm.getMenu().findItem(R.id.menu_trash);
                int trashCount = MailCache.getInstance(MainActivity.this).countTrash();
                trashItem.setTitle(trashCount > 0
                        ? getString(R.string.menu_trash_count, trashCount)
                        : getString(R.string.menu_trash));
                // 草稿箱菜单项带数量角标
                MenuItem draftsItem = pm.getMenu().findItem(R.id.menu_drafts);
                int draftCount = DraftStore.getInstance(MainActivity.this).count();
                draftsItem.setTitle(draftCount > 0
                        ? getString(R.string.menu_drafts_count, draftCount)
                        : getString(R.string.menu_drafts));
                pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                    @Override public boolean onMenuItemClick(MenuItem item) {
                        return onOptionsItemSelected(item);
                    }
                });
                pm.show();
            }
        });
    }

    private void bindFab() {
        findViewById(R.id.fab).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, ComposeActivity.class));
            }
        });
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_OPEN) {
            if (data != null && data.getBooleanExtra("deleted", false)) {
                // 详情页删除了邮件：重新读本地缓存（已自动过滤回收站）
                setList(MailCache.getInstance(MainActivity.this).getAll());
            } else {
                applyFilter();
            }
        } else if (req == REQ_TRASH) {
            // 回收站里可能恢复了邮件：重新读本地缓存刷新列表
            setList(MailCache.getInstance(MainActivity.this).getAll());
        } else if (req == REQ_INSTALL_PERM && pendingUpdate != null) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                    || getPackageManager().canRequestPackageInstalls()) {
                downloadAndInstall(pendingUpdate);
            } else {
                Toast.makeText(this, R.string.no_install_perm, Toast.LENGTH_SHORT).show();
            }
            pendingUpdate = null;
        }
    }

    private void refresh() {
        refresh(false);
    }

    /**
     * @param fromUser 用户主动触发（点刷新/下拉）。主动触发时即使之前点过「稍后」也要再提示一次，
     *                 否则用户会以为「点了没反应 = 收不到邮件」。
     */
    private void refresh(boolean fromUser) {
        if (store.accounts.isEmpty()) {
            // 以前这里直接 return：用户点刷新毫无反应，还以为“收不到邮件”。
            // 账户没加进来是收不到信最常见的原因，必须明确说出来并给出入口。
            setEmptyText(getString(R.string.empty_no_account));
            setList(new ArrayList<MailMessage>());
            lastFetchSummary = "账户列表为空，未发起网络请求";
            if (fromUser || !guideShown) {
                guideShown = true;
                new AlertDialog.Builder(this)
                        .setTitle(R.string.no_account_title)
                        .setMessage(R.string.no_account_msg)
                        .setPositiveButton(R.string.menu_add_account,
                                new DialogInterface.OnClickListener() {
                                    @Override public void onClick(DialogInterface d, int w) {
                                        showAddDialog();
                                    }
                                })
                        .setNegativeButton("稍后", null)
                        .show();
            }
            return;
        }
        buildAccountSpinner(); // 账户可能变动，重建下拉并保留当前选择
        setEmptyText(getString(R.string.toast_fetch_empty));
        Toast.makeText(this, R.string.toast_refreshing, Toast.LENGTH_SHORT).show();
        refreshing = true;
        new Thread(new Runnable() {
            @Override             public void run() {
              try {
                // 1) 先读本地缓存即时展示（离线可用、列表秒开）
                final List<MailMessage> cached = MailCache.getInstance(MainActivity.this).getAll();
                if (!cached.isEmpty()) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() { setList(cached); }
                    });
                }
                // 2) 后台增量拉取新邮件（只取 UID 水位之后的新邮件，不再重拉全邮箱）
                //    同时带回每账户失败原因：以前只写 Logcat，用户只看到“没邮件”，无从排查
                final MailService.FetchOutcome outcome =
                        MailService.fetchAllWithErrors(MainActivity.this, store.accounts);
                final List<MailMessage> real = outcome.messages;
                // AI 分类：子线程内调用，不阻塞 UI；失败则保留本地规则，不中断
                try {
                    AiSettings ai = AiSettings.load(MainActivity.this);
                    if (Classifier.aiAvailable(ai)) {
                        Classifier.aiClassifyBatch(real, ai);
                    }
                } catch (Exception ignored) {
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        refreshing = false;
                        lastFetchSummary = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
                                .format(new Date())
                                + " 拉到 " + real.size() + " 封"
                                + (outcome.errors.isEmpty() ? "，无错误" : "，失败 " + outcome.errors.size() + " 个账户");
                        if (!real.isEmpty()) {
                            // 落盘本地缓存，下次离线也能看
                            MailCache cache = MailCache.getInstance(MainActivity.this);
                            int written = cache.upsertAll(real);
                            // 增量模式下 real 只是「新邮件」，必须回读全量缓存再上屏，
                            // 否则列表会被替换成仅这几封，历史邮件全部消失
                            List<MailMessage> now = cache.getAll();
                            setList(now);
                            if (now.isEmpty()) {
                                // 服务器明明给了邮件、本地却一封没有：这是数据层异常，
                                // 必须说出来，否则用户只看到空白页，以为「没收到」。
                                showFetchErrors(java.util.Collections.singletonList(
                                        "从服务器拉到 " + real.size() + " 封，但本地只写入 " + written
                                                + " 条且读回为空。请点「诊断信息 → 复制」把内容发给我"));
                            }
                        } else if (cached.isEmpty() && outcome.errors.isEmpty()) {
                            setList(new ArrayList<MailMessage>());
                            Toast.makeText(MainActivity.this,
                                    "已连接 " + store.accounts.size() + " 个账户，服务器返回 0 封邮件",
                                    Toast.LENGTH_LONG).show();
                        } else if (!cached.isEmpty()) {
                            // 网络拉取为空（断网/无新邮件）但本地有缓存：保留缓存显示
                            Toast.makeText(MainActivity.this, R.string.toast_offline_cache, Toast.LENGTH_SHORT).show();
                        }
                        // 有失败账户：弹窗把真实原因说清楚（比“没收到邮件”有用得多）
                        if (!outcome.errors.isEmpty()) {
                            showFetchErrors(outcome.errors);
                        }
                    }
                });
              } catch (Throwable t) {
                // 刷新线程里任何未预料的异常都不能静默吞掉：
                // 否则用户看到的就是「点了刷新，什么也没发生」。
                android.util.Log.e("MainActivity", "refresh crashed", t);
                final String m = (t.getMessage() == null) ? t.toString() : t.getMessage();
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        refreshing = false;
                        lastFetchSummary = "刷新异常：" + m;
                        showFetchErrors(java.util.Collections.singletonList("刷新过程异常：" + m));
                    }
                });
              } finally {
                refreshing = false;
              }
            }
        }).start();
    }

    /** 收信失败原因弹窗：账户 + 人话版原因 + 排查步骤 */
    private void showFetchErrors(List<String> errors) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < errors.size(); i++) {
            if (i > 0) sb.append("\n");
            sb.append("• ").append(errors.get(i));
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.fetch_failed_title)
                .setMessage(sb.toString() + "\n\n" + getString(R.string.fetch_failed_tips))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /** 诊断信息：把「为什么收不到」需要的事实一次性摊开，可一键复制发人 */
    private void showDiagnostics() {
        final MailCache cache = MailCache.getInstance(this);
        final StringBuilder sb = new StringBuilder();
        String ver = "?";
        long vcode = -1;
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            ver = pi.versionName;
            vcode = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception ignored) {
        }
        sb.append("版本：").append(ver).append(" (").append(vcode).append(")\n");
        sb.append("系统：Android ").append(Build.VERSION.RELEASE)
                .append(" / SDK ").append(Build.VERSION.SDK_INT).append("\n");
        sb.append("账户数：").append(store.accounts.size()).append("\n");
        for (MailAccount a : store.accounts) {
            sb.append("\n▸ ").append(a.email).append("\n");
            sb.append("  类型：").append(a.provider).append("  ")
                    .append(a.imapHost).append(":").append(a.imapPort)
                    .append(a.useStartTls ? " (STARTTLS)" : " (SSL)").append("\n");
            sb.append("  授权码：").append(a.password == null ? 0 : a.password.length()).append(" 位\n");
            MailCache.SyncState st = cache.getSyncState(a.id);
            if (st == null) {
                sb.append("  同步水位：无（从未成功拉取过）\n");
            } else {
                sb.append("  水位：UIDVALIDITY=").append(st.uidValidity)
                        .append(" / min=").append(st.minUid)
                        .append(" / max=").append(st.maxUid)
                        .append(" / 更早=").append(st.hasOlder ? "有" : "无").append("\n");
            }
        }
        sb.append("\n本地缓存：").append(cache.getAll().size()).append(" 封\n");
        sb.append("刷新状态：").append(refreshing ? "正在刷新…（等 30 秒再复制）" : lastFetchSummary).append("\n");

        final TextView tv = new TextView(this);
        tv.setText(sb.toString());
        tv.setTextSize(13);
        tv.setTextIsSelectable(true);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad, pad, pad);

        ScrollView sv = new ScrollView(this);
        sv.addView(tv);

        AlertDialog diagDlg = new AlertDialog.Builder(this)
                .setTitle(R.string.diag_title)
                .setView(sv)
                .setPositiveButton(R.string.diag_test, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        runConnectionTest(tv, sb.toString());
                    }
                })
                .setNeutralButton(R.string.diag_copy, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cm != null) {
                            cm.setPrimaryClip(ClipData.newPlainText("MailMaster 诊断", tv.getText().toString()));
                            Toast.makeText(MainActivity.this, R.string.diag_copied, Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton(R.string.diag_close, null)
                .create();
        diagDlg.show();
        // 打开即自动测一轮：用户不用再找按钮，直接看结果、点复制
        runConnectionTest(tv, sb.toString());
    }

    /** 真正建一次连接：能立刻分辨「网络不通 / 授权码错 / 服务器风控 / 其实是好的」 */
    private void runConnectionTest(final TextView tv, final String base) {
        final List<MailAccount> accs = new ArrayList<>(store.accounts);
        if (accs.isEmpty()) {
            tv.setText(base + "\n\n（没有账户可测，先添加账户）");
            return;
        }
        tv.setText(base + "\n\n正在测试连接，最长约 30 秒…");
        new Thread(new Runnable() {
            @Override public void run() {
                final StringBuilder r = new StringBuilder(base).append("\n\n—— 连接测试 ——");
                for (MailAccount a : accs) {
                    r.append("\n\n▸ ").append(a.email).append("\n  ")
                            .append(MailService.testConnection(MainActivity.this, a));
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        tv.setText(r.toString());
                    }
                });
            }
        }).start();
    }

    /** 保存全量邮件并套用当前筛选条件 */
    private void setList(List<MailMessage> msgs) {
        all = new ArrayList<>();
        if (msgs != null) all.addAll(msgs);
        promoTotal = 0;
        for (MailMessage m : all) {
            if (Classifier.PROMO.equals(m.category)) promoTotal++;
        }
        refreshFilterUI();
        applyFilter();
    }

    /**
     * 底部「加载更早的邮件」入口的显隐。
     * 有任一账户的水位标记 has_older=1 且当前未在加载时挂上 footer，否则移除——
     * 直接 setVisibility(GONE) 在部分系统版本上仍会占一行，移除/挂载最稳。
     */
    private void updateFooter() {
        if (footerMore == null) return;
        boolean show;
        if (loadingOlder) {
            ((TextView) footerMore).setText(R.string.loading_older);
            show = true;
        } else {
            ((TextView) footerMore).setText(R.string.load_more_older);
            try {
                show = MailCache.getInstance(MainActivity.this).hasOlderMail();
            } catch (Exception e) {
                show = false;
            }
        }
        boolean attached = footerMore.getParent() != null;
        if (show && !attached) {
            list.addFooterView(footerMore, null, true);
        } else if (!show && attached) {
            list.removeFooterView(footerMore);
        }
    }

    /** 底部翻页：按账户并发拉取更早的一批邮件，AI 分类后并入本地缓存 */
    private void loadOlder() {
        if (loadingOlder || store.accounts.isEmpty()) return;
        loadingOlder = true;
        updateFooter();
        new Thread(new Runnable() {
            @Override public void run() {
                final List<MailMessage> older =
                        MailService.fetchOlder(MainActivity.this, store.accounts, MailService.OLDER_PAGE);
                // AI 分类：与手动刷新一致，失败则保留本地规则
                try {
                    AiSettings ai = AiSettings.load(MainActivity.this);
                    if (Classifier.aiAvailable(ai)) Classifier.aiClassifyBatch(older, ai);
                } catch (Exception ignored) {
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        loadingOlder = false;
                        if (!older.isEmpty()) {
                            MailCache cache = MailCache.getInstance(MainActivity.this);
                            cache.upsertAll(older);
                            setList(cache.getAll());
                            Toast.makeText(MainActivity.this, R.string.toast_loaded_older,
                                    Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(MainActivity.this, R.string.no_older,
                                    Toast.LENGTH_SHORT).show();
                        }
                        updateFooter();
                    }
                });
            }
        }).start();
    }

    private void setEmptyText(String text) {
        TextView t = findViewById(R.id.tv_empty_text);
        if (t != null) t.setText(text);
    }

    /** 标题栏副标题：按当前视图统计未读 / 拦截数 */
    private void updateSubtitle(List<MailMessage> shown) {
        int unread = 0;
        for (MailMessage m : shown) if (m.unread) unread++;
        String status;
        if (curMode.equals("block")) {
            status = "拦截 " + shown.size() + " 封疑似推广";
        } else {
            status = (unread > 0) ? (getString(R.string.unread_hint) + " " + unread + " 封") : "全部已读";
        }
        String mode = store.accounts.isEmpty() ? "未添加邮箱" : (store.accounts.size() + " 个邮箱");
        subtitle.setText(status + " · " + mode);
    }

    /**
     * 统一筛选：模式（收件箱拦截推广 / 拦截视图只显推广）
     * + 账户 + 分类 + 关键词搜索，四者取交集。
     */
    private void applyFilter() {
        String q = search.getText().toString().trim().toLowerCase(Locale.CHINA);
        List<MailMessage> shown = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            MailMessage m = all.get(i);
            // 1) 模式：收件箱显示全部，推广只打标签不隐藏——
            //    隐藏的代价太大：邮件其实收到了，用户看到的却是空列表。
            //    「疑似骚扰拦截」视图仍然只看推广，作为筛选器保留。
            boolean isPromo = Classifier.PROMO.equals(m.category);
            if (curMode.equals("block") && !isPromo) continue;
            // 2) 账户
            if (!curAccount.equals("all") && !curAccount.equals(m.accountId)) continue;
            // 3) 分类（仅收件箱模式生效；拦截视图本身就是推广）
            if (curMode.equals("inbox") && !curCat.equals("all") && !curCat.equals(m.category)) continue;
            // 4) 搜索
            if (q.length() > 0 && !match(m, q)) continue;
            shown.add(m);
        }

        if (shown.isEmpty()) {
            if (q.length() > 0) setEmptyText(getString(R.string.search_no_result));
            else if (curMode.equals("block")) setEmptyText(getString(R.string.empty_block));
            else setEmptyText(getString(R.string.empty_inbox));
        }

        final List<MailMessage> finalShown = shown;
        adapter = new ArrayAdapter<MailMessage>(this, R.layout.item_message, R.id.tv_subject, shown) {
            @Override
            public View getView(int pos, View cv, android.view.ViewGroup parent) {
                if (cv == null) cv = getLayoutInflater().inflate(R.layout.item_message, parent, false);
                MailMessage m = getItem(pos);
                float dp = getResources().getDisplayMetrics().density;

                TextView av = cv.findViewById(R.id.tv_avatar);
                av.setText(avatarText(m.from));
                GradientDrawable circle = new GradientDrawable();
                circle.setShape(GradientDrawable.OVAL);
                circle.setColor(m.unread ? avatarColor(m.from) : getResources().getColor(R.color.avatar_read));
                circle.setStroke((int) (2 * dp), 0xFFFFFFFF);
                av.setBackground(circle);

                View dot = cv.findViewById(R.id.unread_dot);
                dot.setVisibility(m.unread ? View.VISIBLE : View.GONE);

                TextView subj = cv.findViewById(R.id.tv_subject);
                subj.setText(m.subject);
                subj.setTypeface(null, m.unread ? Typeface.BOLD : Typeface.NORMAL);
                subj.setTextColor(getResources().getColor(m.unread ? R.color.text : R.color.sub));

                ((TextView) cv.findViewById(R.id.tv_from)).setText(m.from);
                SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
                String when = (m.date > 0) ? sdf.format(new Date(m.date)) : "";
                ((TextView) cv.findViewById(R.id.tv_date)).setText(when + " · " + m.accountEmail);

                TextView att = cv.findViewById(R.id.tv_attach);
                att.setVisibility(m.hasAttachment() ? View.VISIBLE : View.GONE);
                if (m.hasAttachment()) att.setText("附件 ×" + m.attachments.size());

                TextView cat = cv.findViewById(R.id.tv_category);
                String c = m.category;
                if (c == null || c.length() == 0) {
                    cat.setVisibility(View.GONE);
                } else {
                    cat.setVisibility(View.VISIBLE);
                    cat.setText(c);
                    GradientDrawable chip = new GradientDrawable();
                    chip.setCornerRadius(12 * dp);
                    chip.setColor(getResources().getColor(colorOf(c)));
                    cat.setBackground(chip);
                }
                return cv;
            }
        };
        list.setAdapter(adapter);
        updateSubtitle(finalShown);
        // 列表数据变化后同步底部翻页入口（首屏 100 封之外通常还有更早邮件）
        updateFooter();
    }

    static String avatarText(String from) {
        String name = (from == null) ? "" : from.trim();
        int lt = name.indexOf('<');
        if (lt > 0) name = name.substring(0, lt).trim();
        if (name.isEmpty()) {
            int at = (from == null) ? -1 : from.indexOf('@');
            if (at > 0) name = from.substring(0, at);
        }
        if (name.isEmpty()) return "#";
        return new String(Character.toChars(name.codePointAt(0))).toUpperCase(Locale.CHINA);
    }

    static int avatarColor(String from) {
        String key = (from == null) ? "" : from;
        int h = key.hashCode();
        if (h == Integer.MIN_VALUE) h = 42;
        return AVATAR_COLORS[Math.abs(h) % AVATAR_COLORS.length];
    }

    private boolean match(MailMessage m, String q) {
        return (m.subject != null && m.subject.toLowerCase(Locale.CHINA).contains(q))
                || (m.from != null && m.from.toLowerCase(Locale.CHINA).contains(q))
                || (m.body != null && m.body.toLowerCase(Locale.CHINA).contains(q))
                || (m.accountEmail != null && m.accountEmail.toLowerCase(Locale.CHINA).contains(q));
    }

    private int colorOf(String c) {
        if (Classifier.PROMO.equals(c)) return R.color.cat_promo;
        if (Classifier.SOCIAL.equals(c)) return R.color.cat_social;
        if (Classifier.OTHER.equals(c)) return R.color.cat_other;
        return R.color.cat_important;
    }

    /* ------------------------- 账户下拉 ------------------------- */

    private void buildAccountSpinner() {
        if (spinnerAccount == null) spinnerAccount = findViewById(R.id.spinner_account);
        List<String> items = new ArrayList<>();
        items.add(getString(R.string.filter_account_all));
        for (MailAccount a : store.accounts) items.add(a.label());
        ArrayAdapter<String> sa = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, items);
        sa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerAccount.setAdapter(sa);

        // 当前选择若已失效（账户被删）则回退到全部
        int sel = 0;
        if (!curAccount.equals("all")) {
            for (int i = 0; i < store.accounts.size(); i++) {
                if (store.accounts.get(i).id.equals(curAccount)) { sel = i + 1; break; }
            }
            if (sel == 0) curAccount = "all";
        }
        final int finalSel = sel;
        spinnerAccount.setSelection(finalSel);
        spinnerAccount.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                curAccount = (position == 0) ? "all" : store.accounts.get(position - 1).id;
                applyFilter();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    /* ------------------------- 分类 chip ------------------------- */

    private int dp(int n) {
        return (int) (n * getResources().getDisplayMetrics().density);
    }

    /** 对话框输入框统一卡片化样式 */
    private void styleInput(EditText e) {
        e.setBackgroundResource(R.drawable.bg_search);
        e.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        e.setLayoutParams(lp);
    }

    private void styleInput(Spinner s) {
        s.setBackgroundResource(R.drawable.bg_search);
        s.setPadding(dp(14), dp(4), dp(14), dp(4));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        s.setLayoutParams(lp);
    }

    private TextView makeChip(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setPadding(dp(12), dp(7), dp(12), dp(7));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        tv.setLayoutParams(lp);
        tv.setBackgroundResource(R.drawable.bg_chip);
        tv.setTextColor(getResources().getColor(R.color.sub));
        return tv;
    }

    private void setChipSelected(TextView tv, boolean sel) {
        tv.setBackgroundResource(sel ? R.drawable.bg_chip_sel : R.drawable.bg_chip);
        tv.setTextColor(sel ? 0xFFFFFFFF : getResources().getColor(R.color.sub));
    }

    private void buildChips() {
        LinearLayout row = findViewById(R.id.chip_row);
        row.removeAllViews();

        chipInbox = makeChip(getString(R.string.filter_inbox));
        chipInbox.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                curMode = "inbox";
                refreshFilterUI();
                applyFilter();
            }
        });
        row.addView(chipInbox);

        chipBlock = makeChip(getString(R.string.filter_block));
        chipBlock.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                curMode = "block";
                refreshFilterUI();
                applyFilter();
            }
        });
        row.addView(chipBlock);

        // 分隔
        TextView gap = new TextView(this);
        gap.setLayoutParams(new LinearLayout.LayoutParams(dp(4), LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(gap);

        // 分类 chip（仅收件箱模式有意义；拦截视图本身就是推广）
        chipAll = makeChip(getString(R.string.filter_all));
        chipAll.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { curCat = "all"; refreshFilterUI(); applyFilter(); }
        });
        row.addView(chipAll);

        chipImportant = makeChip(getString(R.string.filter_important));
        chipImportant.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { curCat = Classifier.IMPORTANT; refreshFilterUI(); applyFilter(); }
        });
        row.addView(chipImportant);

        chipSocial = makeChip(getString(R.string.filter_social));
        chipSocial.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { curCat = Classifier.SOCIAL; refreshFilterUI(); applyFilter(); }
        });
        row.addView(chipSocial);

        chipOther = makeChip(getString(R.string.filter_other));
        chipOther.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { curCat = Classifier.OTHER; refreshFilterUI(); applyFilter(); }
        });
        row.addView(chipOther);

        refreshFilterUI();
    }

    private void refreshFilterUI() {
        setChipSelected(chipInbox, curMode.equals("inbox"));
        setChipSelected(chipBlock, curMode.equals("block"));
        chipBlock.setText(getString(R.string.filter_block) + " (" + promoTotal + ")");

        boolean inbox = curMode.equals("inbox");
        int vis = inbox ? View.VISIBLE : View.GONE;
        chipAll.setVisibility(vis);
        chipImportant.setVisibility(vis);
        chipSocial.setVisibility(vis);
        chipOther.setVisibility(vis);

        setChipSelected(chipAll, inbox && curCat.equals("all"));
        setChipSelected(chipImportant, inbox && curCat.equals(Classifier.IMPORTANT));
        setChipSelected(chipSocial, inbox && curCat.equals(Classifier.SOCIAL));
        setChipSelected(chipOther, inbox && curCat.equals(Classifier.OTHER));
    }

    /* ------------------------- 菜单 ------------------------- */

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.menu_compose) {
            startActivity(new Intent(this, ComposeActivity.class));
            return true;
        }
        if (item.getItemId() == R.id.menu_add) { showAddDialog(); return true; }
        if (item.getItemId() == R.id.menu_refresh) { refresh(true); return true; }
        if (item.getItemId() == R.id.menu_accounts) { showAccountsDialog(); return true; }
        if (item.getItemId() == R.id.menu_trash) {
            startActivityForResult(new Intent(this, TrashActivity.class), REQ_TRASH);
            return true;
        }
        if (item.getItemId() == R.id.menu_drafts) {
            startActivity(new Intent(this, DraftsActivity.class));
            return true;
        }
        if (item.getItemId() == R.id.menu_auto_receive) { showAutoReceiveDialog(); return true; }
        if (item.getItemId() == R.id.menu_ai) { showAiDialog(); return true; }
        if (item.getItemId() == R.id.menu_diagnostics) { showDiagnostics(); return true; }
        if (item.getItemId() == R.id.menu_check_update) { checkUpdate(); return true; }
        return super.onOptionsItemSelected(item);
    }

    /* ------------------------- 应用内更新 ------------------------- */

    private static final int REQ_INSTALL_PERM = 2003;
    private static final int REQ_TRASH = 2004;
    private static final int REQ_NOTIFY_PERM = 3001;
    private Updater.UpdateInfo pendingUpdate;

    private void checkUpdate() {
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage(getString(R.string.update_checking));
        pd.setCancelable(false);
        pd.show();
        Updater.checkUpdate(this, new Updater.CheckCallback() {
            @Override
            public void onResult(final Updater.UpdateInfo info, final boolean hasUpdate, final String err) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        pd.dismiss();
                        if (err != null) {
                            Toast.makeText(MainActivity.this,
                                    getString(R.string.update_fail, err), Toast.LENGTH_LONG).show();
                            return;
                        }
                        if (!hasUpdate || info == null) {
                            Toast.makeText(MainActivity.this,
                                    getString(R.string.update_latest, Updater.currentVersionName(MainActivity.this)),
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        showUpdateDialog(info);
                    }
                });
            }
        });
    }

    private void showUpdateDialog(final Updater.UpdateInfo info) {
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle(getString(R.string.update_found_title, info.versionName));
        String msg = (info.notes != null && !info.notes.isEmpty()) ? info.notes : "有新版本可用";
        b.setMessage(msg);
        b.setPositiveButton(R.string.update_now, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int w) { startUpdate(info); }
        });
        b.setNegativeButton(R.string.update_later, null);
        b.show();
    }

    private void startUpdate(final Updater.UpdateInfo info) {
        // 安卓 8+ 需要「安装未知来源应用」权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !getPackageManager().canRequestPackageInstalls()) {
            Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
            i.setData(Uri.parse("package:" + getPackageName()));
            pendingUpdate = info;
            startActivityForResult(i, REQ_INSTALL_PERM);
            return;
        }
        downloadAndInstall(info);
    }

    private void downloadAndInstall(final Updater.UpdateInfo info) {
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setMax(100);
        pd.setMessage(getString(R.string.updating));
        pd.setCancelable(false);
        pd.show();
        Updater.download(this, info, new Updater.Progress() {
            @Override
            public void onProgress(int percent) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        pd.setMessage(getString(R.string.updating) + "（加速镜像 " + percent + "%）");
                        pd.setProgress(percent);
                    }
                });
            }
            @Override
            public void onDone(final File apk) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        pd.dismiss();
                        Updater.install(MainActivity.this, apk);
                    }
                });
            }
            @Override
            public void onError(final String err) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        pd.dismiss();
                        Toast.makeText(MainActivity.this,
                                getString(R.string.download_fail, err), Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private void showAccountsDialog() {
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle(R.string.manage_accounts_title);
        LinearLayout lay = new LinearLayout(this);
        lay.setOrientation(LinearLayout.VERTICAL);
        lay.setPadding(40, 20, 40, 20);
        if (store.accounts.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.manage_accounts_empty);
            lay.addView(empty);
        }
        for (final MailAccount a : new ArrayList<>(store.accounts)) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            TextView tv = new TextView(this);
            String name = (a.displayName != null && !a.displayName.isEmpty()) ? a.displayName + " " : "";
            String prov = ("CUSTOM".equals(a.provider)) ? "自定义" : a.provider;
            tv.setText(name + a.label() + "  ·  " + prov);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            tv.setLayoutParams(lp);
            Button del = new Button(this);
            del.setText(R.string.btn_delete);
            del.setBackgroundResource(R.drawable.btn_outline);
            del.setTextColor(0xFFEF4444);
            del.setPadding(dp(16), dp(6), dp(16), dp(6));
            del.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    store.removeAccount(a.id);
                    // 账户没了：级联清理其草稿，并重排后台任务（无账户时会自动停排）
                    DraftStore.getInstance(MainActivity.this).deleteAccount(a.id);
                    AutoReceivePrefs.syncSchedule(MainActivity.this);
                    if (accountDialog != null) accountDialog.dismiss();
                    curAccount = "all";
                    refresh();
                    showAccountsDialog();
                }
            });
            row.addView(tv);
            row.addView(del);
            lay.addView(row);
        }
        b.setView(lay);
        b.setNegativeButton("关闭", null);
        accountDialog = b.show();
    }

    private void showAiDialog() {
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle(R.string.ai_settings_title);
        View v = getLayoutInflater().inflate(R.layout.dialog_ai_settings, null);
        final CheckBox en = v.findViewById(R.id.ai_enabled);
        final Spinner prov = v.findViewById(R.id.ai_provider);
        final EditText key = v.findViewById(R.id.ai_key);
        final EditText base = v.findViewById(R.id.ai_base);
        final EditText model = v.findViewById(R.id.ai_model);

        final AiSettings ai = AiSettings.load(this);
        en.setChecked(ai.enabled);
        key.setText(ai.apiKey);
        base.setText(ai.baseUrl);
        model.setText(ai.model);

        final String[] presets = {"DeepSeek", "OpenAI", "通义千问", "豆包（火山方舟）", "自定义"};
        ArrayAdapter<String> pa = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, presets);
        pa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        prov.setAdapter(pa);
        int sel = 4;
        if (ai.baseUrl.contains("deepseek")) sel = 0;
        else if (ai.baseUrl.contains("openai.com")) sel = 1;
        else if (ai.baseUrl.contains("dashscope")) sel = 2;
        else if (ai.baseUrl.contains("volces.com")) sel = 3;
        prov.setSelection(sel);

        prov.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View vv, int pos, long id) {
                if (pos == 0) { base.setText("https://api.deepseek.com/v1"); model.setText("deepseek-chat"); }
                else if (pos == 1) { base.setText("https://api.openai.com/v1"); model.setText("gpt-4o-mini"); }
                else if (pos == 2) { base.setText("https://dashscope.aliyuncs.com/compatible-mode/v1"); model.setText("qwen-plus"); }
                else if (pos == 3) { base.setText("https://ark.cn-beijing.volces.com/api/v3"); model.setText("doubao-seed-2-1-lite-260915"); }
                else if (pos == 4) { base.setText(""); model.setText(""); }
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });

        b.setView(v);
        b.setPositiveButton(R.string.btn_save, new android.content.DialogInterface.OnClickListener() {
            @Override public void onClick(android.content.DialogInterface d, int w) {
                AiSettings s = new AiSettings();
                s.enabled = en.isChecked();
                s.apiKey = key.getText().toString().trim();
                s.baseUrl = base.getText().toString().trim();
                s.model = model.getText().toString().trim();
                s.save(MainActivity.this);
                Toast.makeText(MainActivity.this, R.string.ai_saved, Toast.LENGTH_SHORT).show();
            }
        });
        b.setNegativeButton("取消", null);
        final AlertDialog dlg = b.create();
        dlg.show();
        Button testBtn = dlg.findViewById(R.id.ai_test);
        if (testBtn != null) {
            testBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View vv) {
                    AiSettings tmp = new AiSettings();
                    tmp.enabled = true;
                    tmp.apiKey = key.getText().toString().trim();
                    tmp.baseUrl = base.getText().toString().trim();
                    tmp.model = model.getText().toString().trim();
                    testBtn.setEnabled(false);
                    testBtn.setText("测试中…");
                    new Thread(new Runnable() {
                        @Override public void run() {
                            final String err = Classifier.testConnection(tmp);
                            runOnUiThread(new Runnable() {
                                @Override public void run() {
                                    testBtn.setEnabled(true);
                                    testBtn.setText(R.string.ai_test);
                                    if (err == null) {
                                        Toast.makeText(MainActivity.this, R.string.ai_test_ok, Toast.LENGTH_SHORT).show();
                                    } else {
                                        String msg = (err.length() > 120) ? (err.substring(0, 120) + "…") : err;
                                        Toast.makeText(MainActivity.this,
                                            getString(R.string.ai_test_fail, msg), Toast.LENGTH_LONG).show();
                                    }
                                }
                            });
                        }
                    }).start();
                }
            });
        }
    }

    private void showAddDialog() {
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle(R.string.add_account_title);

        LinearLayout lay = new LinearLayout(this);
        lay.setOrientation(LinearLayout.VERTICAL);
        lay.setPadding(40, 30, 40, 10);

        final Spinner sp = new Spinner(this);
        styleInput(sp);
        final String[] provs = {"QQ", "163", "OUTLOOK", "CUSTOM"};
        ArrayAdapter<String> sa = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, provs);
        sa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(sa);

        final EditText email = new EditText(this);
        email.setHint(R.string.hint_email);
        email.setInputType(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        styleInput(email);
        final EditText pw = new EditText(this);
        pw.setHint(R.string.hint_auth);
        styleInput(pw);

        // 自定义邮箱高级字段（默认隐藏）
        final LinearLayout customBox = new LinearLayout(this);
        customBox.setOrientation(LinearLayout.VERTICAL);
        customBox.setVisibility(View.GONE);
        final EditText etName = new EditText(this); etName.setHint(R.string.hint_account_name); styleInput(etName);
        final EditText etImapH = new EditText(this); etImapH.setHint(R.string.hint_imap_host); styleInput(etImapH);
        final EditText etImapP = new EditText(this); etImapP.setHint(R.string.hint_imap_port); styleInput(etImapP);
        etImapP.setInputType(InputType.TYPE_CLASS_NUMBER);
        final EditText etSmtpH = new EditText(this); etSmtpH.setHint(R.string.hint_smtp_host); styleInput(etSmtpH);
        final EditText etSmtpP = new EditText(this); etSmtpP.setHint(R.string.hint_smtp_port); styleInput(etSmtpP);
        etSmtpP.setInputType(InputType.TYPE_CLASS_NUMBER);
        final CheckBox chkTls = new CheckBox(this); chkTls.setText(R.string.label_ssl);
        customBox.addView(etName); customBox.addView(etImapH); customBox.addView(etImapP);
        customBox.addView(etSmtpH); customBox.addView(etSmtpP); customBox.addView(chkTls);

        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                customBox.setVisibility(position == 3 ? View.VISIBLE : View.GONE);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        lay.addView(sp); lay.addView(email); lay.addView(pw); lay.addView(customBox);
        b.setView(lay);
        b.setNegativeButton("取消", null);
        b.setPositiveButton(R.string.btn_save, null);

        final AlertDialog dlg = b.create();
        dlg.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override public void onShow(DialogInterface d) {
                dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        String p = provs[sp.getSelectedItemPosition()];
                        String em = email.getText().toString().trim();
                        String pass = pw.getText().toString().trim();
                        // 以前这里完全不校验：邮箱和授权码都空着也能「保存成功」，
                        // 列表里多出一个永远收不到信的假账户，用户还以为是网络问题。
                        if (em.isEmpty() || !em.contains("@") || em.startsWith("@") || em.endsWith("@")) {
                            Toast.makeText(MainActivity.this, R.string.err_email_invalid, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (pass.isEmpty()) {
                            Toast.makeText(MainActivity.this, R.string.err_auth_empty, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if ("CUSTOM".equals(p) && etImapH.getText().toString().trim().isEmpty()) {
                            Toast.makeText(MainActivity.this, R.string.err_imap_empty, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        for (MailAccount ex : store.accounts) {
                            if (em.equalsIgnoreCase(ex.email)) {
                                Toast.makeText(MainActivity.this, R.string.err_account_exists, Toast.LENGTH_SHORT).show();
                                return;
                            }
                        }
                        MailAccount a;
                        if ("CUSTOM".equals(p)) {
                            String ih = etImapH.getText().toString().trim();
                            String sh = etSmtpH.getText().toString().trim();
                            boolean tls = chkTls.isChecked();
                            int ip = parsePort(etImapP.getText().toString(), tls ? 143 : 993);
                            int sp2 = parsePort(etSmtpP.getText().toString(), tls ? 587 : 465);
                            a = MailAccount.presetCustom(em, pass, ih, ip, sh, sp2, tls);
                            a.name = etName.getText().toString().trim();
                        } else {
                            a = MailAccount.preset(p, em, pass);
                        }
                        store.addAccount(MainActivity.this, a);
                        curAccount = "all";
                        dlg.dismiss();
                        refresh();
                        // 新账户有了，后台任务重新排上
                        AutoReceivePrefs.syncSchedule(MainActivity.this);
                        Toast.makeText(MainActivity.this, R.string.toast_added, Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
        dlg.show();
    }

    private int parsePort(String s, int fallback) {
        try {
            int v = Integer.parseInt(s.trim());
            return (v > 0 && v <= 65535) ? v : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }
}
