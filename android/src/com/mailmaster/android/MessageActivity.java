package com.mailmaster.android;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MessageActivity extends Activity {

    private MailMessage msg;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_message);

        msg = (MailMessage) getIntent().getSerializableExtra("msg");
        if (msg == null) {
            finish();
            return;
        }

        ((TextView) findViewById(R.id.tv_subject)).setText(msg.subject);

        StringBuilder meta = new StringBuilder();
        meta.append("发件人：").append(nvl(msg.from));
        meta.append("\n收件人：").append(nvl(msg.to));
        meta.append("\n所属邮箱：").append(nvl(msg.accountEmail));
        if (msg.date > 0) {
            meta.append("\n时间：").append(
                    new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(new Date(msg.date)));
        }
        ((TextView) findViewById(R.id.tv_meta)).setText(meta.toString());

        renderAttachments();
        renderBody();
        bindReplyForward();
        markRead();
    }

    /** 附件列表：点一下交给系统里能处理该类型文件的 App 打开 */
    private void renderAttachments() {
        TextView title = findViewById(R.id.tv_attach_title);
        LinearLayout box = findViewById(R.id.attach_box);
        if (!msg.hasAttachment()) {
            title.setVisibility(View.GONE);
            return;
        }
        title.setVisibility(View.VISIBLE);
        box.removeAllViews();
        for (final Attachment a : msg.attachments) {
            if (a == null) continue;
            TextView tv = new TextView(this);
            tv.setText(a.name + "（" + a.sizeText() + "）");
            tv.setTextColor(getResources().getColor(R.color.text));
            tv.setTextSize(14);
            tv.setPadding(0, 10, 0, 10);
            tv.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0);
            tv.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openAttachment(a);
                }
            });
            box.addView(tv);
        }
    }

    private void openAttachment(Attachment a) {
        File f = new File(a.path);
        if (!f.exists()) {
            Toast.makeText(this, R.string.toast_attach_pick_fail, Toast.LENGTH_SHORT).show();
            return;
        }
        Uri uri = AttachmentProvider.uriFor(f);
        Intent it = new Intent(Intent.ACTION_VIEW);
        String mime = a.mime;
        if (TextUtils.isEmpty(mime) || "application/octet-stream".equals(mime)) {
            mime = getContentResolver().getType(uri);
        }
        it.setDataAndType(uri, mime == null ? "application/octet-stream" : mime);
        it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(it, a.name));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.toast_no_app, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 有 HTML 就用 WebView 按原排版渲染，纯文本邮件回退 TextView。
     * WebView 一律禁止加载远程资源——邮件里的追踪像素正是靠它统计你何时打开邮件的，
     * 一个「本地优先」的客户端理应默认把它掐掉。
     */
    private void renderBody() {
        View sv = findViewById(R.id.sv_text);
        WebView wv = findViewById(R.id.wv_body);
        if (msg.html != null && msg.html.trim().length() > 0) {
            sv.setVisibility(View.GONE);
            wv.setVisibility(View.VISIBLE);
            WebSettings ws = wv.getSettings();
            ws.setJavaScriptEnabled(false);
            ws.setBlockNetworkLoads(true);
            ws.setLoadWithOverviewMode(true);
            ws.setUseWideViewPort(true);
            wv.loadDataWithBaseURL(null, MailParser.wrapForDisplay(msg.html),
                    "text/html; charset=UTF-8", "UTF-8", null);
        } else {
            wv.setVisibility(View.GONE);
            sv.setVisibility(View.VISIBLE);
            ((TextView) findViewById(R.id.tv_body)).setText(nvl(msg.body));
        }
    }

    private void bindReplyForward() {
        findViewById(R.id.btn_reply).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent it = new Intent(MessageActivity.this, ComposeActivity.class);
                it.putExtra("mode", "reply");
                it.putExtra("msg", msg);
                startActivity(it);
            }
        });
        findViewById(R.id.btn_forward).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent it = new Intent(MessageActivity.this, ComposeActivity.class);
                it.putExtra("mode", "forward");
                it.putExtra("msg", msg);
                startActivity(it);
            }
        });
    }

    /** 打开即已读：先改本地状态，真实账户再异步回写服务器 */
    private void markRead() {
        if (!msg.unread) return;
        msg.unread = false;
        setResult(RESULT_OK);
        final MailAccount acc = findAccount();
        if (acc == null || acc.id.startsWith("demo-") || msg.msgNum <= 0) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                MailService.markRead(acc, msg.msgNum);
            }
        }).start();
    }

    private MailAccount findAccount() {
        try {
            for (MailAccount a : MainActivity.store.accounts) {
                if (a.id.equals(msg.accountId)) return a;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String nvl(String s) {
        return (s == null) ? "" : s;
    }
}
