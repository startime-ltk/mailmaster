package com.mailmaster.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
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

    private ListView list;
    private TextView banner;
    private EditText search;
    private ArrayAdapter<MailMessage> adapter;
    /** 当前全量邮件（未过滤），搜索时从这里筛选 */
    private List<MailMessage> all = new ArrayList<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        store.init(this);
        banner = findViewById(R.id.banner);
        list = findViewById(R.id.list);
        search = findViewById(R.id.et_search);

        // 列表为空时显示提示（搜索无结果等）
        list.setEmptyView(findViewById(R.id.tv_empty));

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
                it.putExtra("subject", m.subject);
                it.putExtra("from", m.from);
                it.putExtra("to", m.to);
                it.putExtra("date", m.date);
                it.putExtra("body", m.body);
                startActivity(it);
            }
        });
    }

    private void refresh() {
        banner.setVisibility(store.demoMode ? View.VISIBLE : View.GONE);
        if (store.demoMode) {
            setList(DemoData.messages());
            return;
        }
        // 真实模式：先展示示例，后台拉取真实邮件
        setList(DemoData.messages());
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<MailMessage> real = MailService.fetchAll(store.accounts);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setList(real.isEmpty() ? DemoData.messages() : real);
                    }
                });
            }
        }).start();
    }

    /** 保存全量邮件并套用当前搜索条件 */
    private void setList(List<MailMessage> msgs) {
        all = new ArrayList<>();
        if (msgs != null) all.addAll(msgs);
        applyFilter();
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
                ((TextView) cv.findViewById(R.id.tv_subject)).setText(m.subject);
                ((TextView) cv.findViewById(R.id.tv_from)).setText(m.from);
                SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
                String when = (m.date > 0) ? sdf.format(new Date(m.date)) : "";
                ((TextView) cv.findViewById(R.id.tv_date)).setText(when + " · " + m.accountEmail);

                // 智能分类标签
                TextView cat = cv.findViewById(R.id.tv_category);
                String c = m.category;
                if (c == null || c.length() == 0) {
                    cat.setVisibility(View.GONE);
                } else {
                    cat.setVisibility(View.VISIBLE);
                    cat.setText(c);
                    cat.setBackgroundColor(getResources().getColor(colorOf(c)));
                }
                return cv;
            }
        };
        list.setAdapter(adapter);
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
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
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

        final android.widget.EditText email = new android.widget.EditText(this);
        email.setHint(R.string.hint_email);
        email.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        final android.widget.EditText pw = new android.widget.EditText(this);
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
