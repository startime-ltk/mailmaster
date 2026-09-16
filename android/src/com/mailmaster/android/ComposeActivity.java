package com.mailmaster.android;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;

public class ComposeActivity extends Activity {

    private Spinner spinner;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_compose);

        spinner = findViewById(R.id.spinner_from);
        ArrayAdapter<MailAccount> sa = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, MainActivity.store.accounts);
        sa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(sa);

        final EditText to = findViewById(R.id.et_to);
        final EditText subj = findViewById(R.id.et_subject);
        final EditText body = findViewById(R.id.et_body);
        Button send = findViewById(R.id.btn_send);

        send.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final MailAccount from = (MailAccount) spinner.getSelectedItem();
                final String t = to.getText().toString().trim();
                if (from == null || t.isEmpty()) {
                    Toast.makeText(ComposeActivity.this, R.string.toast_fill_to, Toast.LENGTH_SHORT).show();
                    return;
                }
                final String s = subj.getText().toString();
                final String bd = body.getText().toString();

                if (MainActivity.store.demoMode) {
                    // 演示模式：仅模拟，不联网
                    Toast.makeText(ComposeActivity.this, R.string.send_ok, Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }

                // 真实发送必须放到子线程：安卓禁止在主线程联网，
                // 否则会抛 NetworkOnMainThreadException 直接闪退
                send.setEnabled(false);
                Toast.makeText(ComposeActivity.this, R.string.sending, Toast.LENGTH_SHORT).show();
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final boolean ok = MailService.send(from, t, s, bd);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(ComposeActivity.this,
                                        ok ? R.string.send_real_ok : R.string.send_fail,
                                        Toast.LENGTH_LONG).show();
                                send.setEnabled(true);
                                if (ok) ComposeActivity.this.finish();
                            }
                        });
                    }
                }).start();
            }
        });
    }
}
