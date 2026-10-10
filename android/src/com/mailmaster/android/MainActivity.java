package com.mailmaster.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
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
import android.widget.TextView;
import android.widget.Toast;
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

    /** 头像色板：按发件人地址哈希取色，同一个人永远同色 */
    private static final int[] AVATAR_COLORS = {
            0xFFE53935, 0xFF1E88E5, 0xFF43A047, 0xFFFB8C00,
            0xFF8E24AA, 0xFF00897B, 0xFFD81B60, 0xFF6D4C41
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

        bindToolbar();
        bindFab();
        buildChips();

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { applyFilter(); }
            @Override public void afterTextChanged(Editable s) {}
        });

        refresh();

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
    }

    private void bindToolbar() {
        findViewById(R.id.btn_compose).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, ComposeActivity.class));
            }
        });
        findViewById(R.id.btn_refresh).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { refresh(); }
        });
        findViewById(R.id.btn_more).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                PopupMenu pm = new PopupMenu(MainActivity.this, v);
                pm.getMenuInflater().inflate(R.menu.main_menu, pm.getMenu());
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
        if (req == REQ_OPEN) applyFilter();
    }

    private void refresh() {
        if (store.accounts.isEmpty()) {
            setEmptyText(getString(R.string.empty_no_account));
            setList(new ArrayList<MailMessage>());
            return;
        }
        buildAccountSpinner(); // 账户可能变动，重建下拉并保留当前选择
        setEmptyText(getString(R.string.toast_fetch_empty));
        Toast.makeText(this, R.string.toast_refreshing, Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override             public void run() {
                final List<MailMessage> real = MailService.fetchAll(MainActivity.this, store.accounts);
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
                        if (real.isEmpty()) {
                            setList(new ArrayList<MailMessage>());
                            Toast.makeText(MainActivity.this, R.string.toast_fetch_empty, Toast.LENGTH_SHORT).show();
                        } else {
                            setList(real);
                        }
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
            // 1) 模式：收件箱隐藏推广，拦截视图只显推广
            boolean isPromo = Classifier.PROMO.equals(m.category);
            if (curMode.equals("inbox") && isPromo) continue;
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
                av.setBackground(circle);

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
                    chip.setCornerRadius(10 * dp);
                    chip.setColor(getResources().getColor(colorOf(c)));
                    cat.setBackground(chip);
                }
                return cv;
            }
        };
        list.setAdapter(adapter);
        updateSubtitle(finalShown);
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
        if (item.getItemId() == R.id.menu_refresh) { refresh(); return true; }
        if (item.getItemId() == R.id.menu_accounts) { showAccountsDialog(); return true; }
        if (item.getItemId() == R.id.menu_ai) { showAiDialog(); return true; }
        return super.onOptionsItemSelected(item);
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
            del.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    store.removeAccount(a.id);
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

        final String[] presets = {"DeepSeek", "OpenAI", "通义千问", "自定义"};
        ArrayAdapter<String> pa = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, presets);
        pa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        prov.setAdapter(pa);
        int sel = 3;
        if (ai.baseUrl.contains("deepseek")) sel = 0;
        else if (ai.baseUrl.contains("openai.com")) sel = 1;
        else if (ai.baseUrl.contains("dashscope")) sel = 2;
        prov.setSelection(sel);

        prov.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View vv, int pos, long id) {
                if (pos == 0) { base.setText("https://api.deepseek.com/v1"); model.setText("deepseek-chat"); }
                else if (pos == 1) { base.setText("https://api.openai.com/v1"); model.setText("gpt-4o-mini"); }
                else if (pos == 2) { base.setText("https://dashscope.aliyuncs.com/compatible-mode/v1"); model.setText("qwen-plus"); }
                else if (pos == 3) { base.setText(""); model.setText(""); }
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
        b.show();
    }

    private void showAddDialog() {
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle(R.string.add_account_title);

        LinearLayout lay = new LinearLayout(this);
        lay.setOrientation(LinearLayout.VERTICAL);
        lay.setPadding(40, 30, 40, 10);

        final Spinner sp = new Spinner(this);
        final String[] provs = {"QQ", "163", "OUTLOOK", "CUSTOM"};
        ArrayAdapter<String> sa = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, provs);
        sa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(sa);

        final EditText email = new EditText(this);
        email.setHint(R.string.hint_email);
        email.setInputType(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        final EditText pw = new EditText(this);
        pw.setHint(R.string.hint_auth);

        // 自定义邮箱高级字段（默认隐藏）
        final LinearLayout customBox = new LinearLayout(this);
        customBox.setOrientation(LinearLayout.VERTICAL);
        customBox.setVisibility(View.GONE);
        final EditText etName = new EditText(this); etName.setHint(R.string.hint_account_name);
        final EditText etImapH = new EditText(this); etImapH.setHint(R.string.hint_imap_host);
        final EditText etImapP = new EditText(this); etImapP.setHint(R.string.hint_imap_port);
        etImapP.setInputType(InputType.TYPE_CLASS_NUMBER);
        final EditText etSmtpH = new EditText(this); etSmtpH.setHint(R.string.hint_smtp_host);
        final EditText etSmtpP = new EditText(this); etSmtpP.setHint(R.string.hint_smtp_port);
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

        b.setPositiveButton(R.string.btn_save, new android.content.DialogInterface.OnClickListener() {
            @Override public void onClick(android.content.DialogInterface d, int w) {
                String p = provs[sp.getSelectedItemPosition()];
                String em = email.getText().toString().trim();
                String pass = pw.getText().toString().trim();
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
                refresh();
                Toast.makeText(MainActivity.this, R.string.toast_added, Toast.LENGTH_LONG).show();
            }
        });
        b.setNegativeButton("取消", null);
        b.show();
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
