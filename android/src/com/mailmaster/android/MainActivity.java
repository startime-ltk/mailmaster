package com.mailmaster.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    public static MailStore store = new MailStore();

    private ListView list;
    private TextView banner;
    private ArrayAdapter<MailMessage> adapter;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        store.init(this);
        banner = findViewById(R.id.banner);
        list = findViewById(R.id.list);
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

    private void setList(List<MailMessage> msgs) {
        adapter = new ArrayAdapter<MailMessage>(this, R.layout.item_message, R.id.tv_subject, msgs) {
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
                return cv;
            }
        };
        list.setAdapter(adapter);
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
