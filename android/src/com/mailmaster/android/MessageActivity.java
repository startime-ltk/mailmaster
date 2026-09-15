package com.mailmaster.android;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MessageActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_message);

        Intent it = getIntent();
        ((TextView) findViewById(R.id.tv_subject)).setText(it.getStringExtra("subject"));

        StringBuilder meta = new StringBuilder();
        meta.append("发件人：").append(it.getStringExtra("from"));
        meta.append("\n收件人：").append(it.getStringExtra("to"));
        long d = it.getLongExtra("date", 0);
        if (d > 0) {
            meta.append("\n时间：").append(
                    new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(new Date(d)));
        }
        ((TextView) findViewById(R.id.tv_meta)).setText(meta.toString());
        ((TextView) findViewById(R.id.tv_body)).setText(it.getStringExtra("body"));
    }
}
