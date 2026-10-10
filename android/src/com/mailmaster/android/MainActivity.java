package com.mailmaster.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
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
    private TextView banner;
    private TextView subtitle;
    private EditText search;
    private ArrayAdapter<MailMessage> adapter;
    /** 账户管理弹窗引用，删除账户后用于关闭并重建 */
    private android.app.AlertDialog accountDialog;
    /** 当前全量邮件（未过滤），搜索时从这里筛选 */
    private List<MailMessage> all = new ArrayList<>();

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
        banner = findViewById(R.id.banner);
        subtitle = findViewById(R.id.tv_subtitle);
        list = findViewById(R.id.list);
        search = findViewById(R.id.et_search);

        // 列表为空时显示提示（搜索无结果等）
        list.setEmptyView(findViewById(R.id.tv_empty));

        bindToolbar();
        bindFab();

        // 输入即筛选：跨账户统一搜索
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                applyFilter();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        refresh();

        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int pos, long id) {
                MailMessage m = adapter.getItem(pos);
                if (m == null) return;
                Intent it = new Intent(MainActivity.this, MessageActivity.class);
                it.putExtra("msg", m);
                // 用 ForResult 打开：详情页标记为已读后，回到列表要立刻生效
                startActivityForResult(it, REQ_OPEN);
            }
        });
    }

    private void bindToolbar() {
        findViewById(R.id.btn_compose).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, ComposeActivity.class));
            }
        });
        findViewById(R.id.btn_refresh).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refresh();
            }
        });
        findViewById(R.id.btn_more).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                PopupMenu pm = new PopupMenu(MainActivity.this, v);
                pm.getMenuInflater().inflate(R.menu.main_menu, pm.getMenu());
                pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                    @Override
                    public boolean onMenuItemClick(MenuItem item) {
                        return onOptionsItemSelected(item); // 复用既有菜单逻辑
                    }
                });
                pm.show();
            }
        });
    }

    private void bindFab() {
        findViewById(R.id.fab).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, ComposeActivity.class));
            }
        });
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_OPEN) applyFilter(); // 已读状态变化，重绘列表
    }

    private void refresh() {
        banner.setVisibility(store.demoMode ? View.VISIBLE : View.GONE);
        if (store.demoMode) {
            // 演示模式：只显示当前仍存在的演示账户邮件，删掉的账户不再出现
            List<MailAccount> demos = new ArrayList<>();
            for (MailAccount a : store.accounts) {
                if (a.id != null && a.id.startsWith("demo-")) demos.add(a);
            }
            List<MailMessage> demo = DemoData.messages(this);
            if (demos.isEmpty()) {
                setList(new ArrayList<MailMessage>());
            } else {
                List<MailMessage> filtered = new ArrayList<>();
                for (MailMessage m : demo) {
                    if (demoAccountExists(demos, m.accountEmail)) filtered.add(m);
                }
                setList(filtered);
            }
            return;
        }
        // 真实模式：绝不再回退到演示邮件；收取为空/失败就显示空列表
        Toast.makeText(this, R.string.toast_refreshing, Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<MailMessage> real = MailService.fetchAll(MainActivity.this, store.accounts);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
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

    /** 该邮箱地址是否仍属于某个演示账户 */
    private boolean demoAccountExists(List<MailAccount> demos, String email) {
        if (email == null) return false;
        for (MailAccount a : demos) {
            if (email.equals(a.email)) return true;
        }
        return false;
    }

    /** 保存全量邮件并套用当前搜索条件 */
    private void setList(List<MailMessage> msgs) {
        all = new ArrayList<>();
        if (msgs != null) all.addAll(msgs);
        applyFilter();
    }

    /** 标题栏副标题：未读数 + 当前模式，一眼看清收件箱状态 */
    private void updateSubtitle() {
        int unread = 0;
        for (MailMessage m : all) {
            if (m.unread) unread++;
        }
        String status = (unread > 0)
                ? getString(R.string.unread_hint) + " " + unread + " 封"
                : "全部已读";
        String mode = store.demoMode ? "演示模式" : (store.accounts.size() + " 个邮箱");
        subtitle.setText(status + " · " + mode);
    }

    /**
     * 跨账户统一搜索：一次搜索所有已录入邮箱，
     * 匹配范围 = 主题 + 发件人 + 正文 + 所属邮箱地址。
     */
    private void applyFilter() {
        String q = search.getText().toString().trim().toLowerCase(Locale.CHINA);
        List<MailMessage> shown = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            MailMessage m = all.get(i);
            if (q.length() == 0 || match(m, q)) shown.add(m);
        }

        adapter = new ArrayAdapter<MailMessage>(this, R.layout.item_message, R.id.tv_subject, shown) {
            @Override
            public View getView(int pos, View cv, android.view.ViewGroup parent) {
                if (cv == null) {
                    cv = getLayoutInflater().inflate(R.layout.item_message, parent, false);
                }
                MailMessage m = getItem(pos);
                float dp = getResources().getDisplayMetrics().density;

                // 头像：发件人首字 + 按地址取色；未读彩色、已读置灰
                TextView av = cv.findViewById(R.id.tv_avatar);
                av.setText(avatarText(m.from));
                GradientDrawable circle = new GradientDrawable();
                circle.setShape(GradientDrawable.OVAL);
                circle.setColor(m.unread ? avatarColor(m.from) : getResources().getColor(R.color.avatar_read));
                av.setBackground(circle);

                // 未读主题加粗、颜色更深；已读常规
                TextView subj = cv.findViewById(R.id.tv_subject);
                subj.setText(m.subject);
                subj.setTypeface(null, m.unread ? Typeface.BOLD : Typeface.NORMAL);
                subj.setTextColor(getResources().getColor(m.unread ? R.color.text : R.color.sub));

                ((TextView) cv.findViewById(R.id.tv_from)).setText(m.from);
                SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
                String when = (m.date > 0) ? sdf.format(new Date(m.date)) : "";
                ((TextView) cv.findViewById(R.id.tv_date)).setText(when + " · " + m.accountEmail);

                // 有附件时显示文档图标 + 数量
                TextView att = cv.findViewById(R.id.tv_attach);
                att.setVisibility(m.hasAttachment() ? View.VISIBLE : View.GONE);
                if (m.hasAttachment()) {
                    att.setText("附件 ×" + m.attachments.size());
                }

                // 智能分类标签：圆角彩色胶囊
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
        updateSubtitle();
    }

    /** 取发件人显示名的首字符（中文一个字、英文一个字母），空名回退邮箱前缀首字母 */
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

    /** 头像颜色 = 色板[hash % 长度]，同一个人在列表任何位置都同色 */
    static int avatarColor(String from) {
        String key = (from == null) ? "" : from;
        int h = key.hashCode();
        if (h == Integer.MIN_VALUE) h = 42; // abs(MIN_VALUE) 会溢出，先转正
        return AVATAR_COLORS[Math.abs(h) % AVATAR_COLORS.length];
    }

    private boolean match(MailMessage m, String q) {
        return (m.subject != null && m.subject.toLowerCase(Locale.CHINA).contains(q))
                || (m.from != null && m.from.toLowerCase(Locale.CHINA).contains(q))
                || (m.body != null && m.body.toLowerCase(Locale.CHINA).contains(q))
                || (m.accountEmail != null && m.accountEmail.toLowerCase(Locale.CHINA).contains(q));
    }

    /** 分类标签配色 */
    private int colorOf(String c) {
        if (Classifier.PROMO.equals(c)) return R.color.cat_promo;
        if (Classifier.SOCIAL.equals(c)) return R.color.cat_social;
        if (Classifier.OTHER.equals(c)) return R.color.cat_other;
        return R.color.cat_important;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.menu_compose) {
            startActivity(new Intent(this, ComposeActivity.class));
            return true;
        }
        if (item.getItemId() == R.id.menu_add) {
            showAddDialog();
            return true;
        }
        if (item.getItemId() == R.id.menu_refresh) {
            refresh();
            return true;
        }
        if (item.getItemId() == R.id.menu_accounts) {
            showAccountsDialog();
            return true;
        }
        if (item.getItemId() == R.id.menu_demo) {
            // 没有真实账户时不切到真实模式，否则会去连示例账户的假服务器空等
            if (store.demoMode && !store.hasRealAccount()) {
                Toast.makeText(this, R.string.toast_need_real_account, Toast.LENGTH_SHORT).show();
                return true;
            }
            store.demoMode = !store.demoMode;
            refresh();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** 管理账户：列出所有已录入账户（含演示），可逐个删除 */
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
            tv.setText(name + a.email + "  ·  " + a.provider);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            tv.setLayoutParams(lp);
            Button del = new Button(this);
            del.setText(R.string.btn_delete);
            del.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    store.removeAccount(a.id);
                    if (accountDialog != null) accountDialog.dismiss();
                    refresh();
                    showAccountsDialog(); // 删完重建列表
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

    private void showAddDialog() {
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle(R.string.add_account_title);

        LinearLayout lay = new LinearLayout(this);
        lay.setOrientation(LinearLayout.VERTICAL);
        lay.setPadding(40, 30, 40, 10);

        final Spinner sp = new Spinner(this);
        final String[] provs = {"QQ", "163", "OUTLOOK"};
        ArrayAdapter<String> sa = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, provs);
        sa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(sa);

        final EditText email = new EditText(this);
        email.setHint(R.string.hint_email);
        email.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        final EditText pw = new EditText(this);
        pw.setHint(R.string.hint_auth);

        lay.addView(sp);
        lay.addView(email);
        lay.addView(pw);
        b.setView(lay);

        b.setPositiveButton(R.string.btn_save, new android.content.DialogInterface.OnClickListener() {
            @Override
            public void onClick(android.content.DialogInterface d, int w) {
                String p = provs[sp.getSelectedItemPosition()];
                MailAccount a = MailAccount.preset(p, email.getText().toString().trim(),
                        pw.getText().toString().trim());
                store.addAccount(MainActivity.this, a);
                refresh();
                Toast.makeText(MainActivity.this, R.string.toast_added, Toast.LENGTH_LONG).show();
            }
        });
        b.setNegativeButton("取消", null);
        b.show();
    }
}
