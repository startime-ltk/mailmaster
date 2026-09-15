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
                MailAccount from = (MailAccount) spinner.getSelectedItem();
                String t = to.getText().toString().trim();
                if (from == null || t.isEmpty()) {
                    Toast.makeText(ComposeActivity.this, R.string.toast_fill_to, Toast.LENGTH_SHORT).show();
                    return;
                }
                boolean ok;
                if (MainActivity.store.demoMode) {
                    ok = true; // 演示模式：仅模拟，不联网
                } else {
                    ok = MailService.send(from, t, subj.getText().toString(), body.getText().toString());
                }
                int msg = ok
                        ? (MainActivity.store.demoMode ? R.string.send_ok : R.string.send_real_ok)
                        : R.string.send_fail;
                Toast.makeText(ComposeActivity.this, msg, Toast.LENGTH_LONG).show();
                if (ok) finish();
            }
        });
    }
}
