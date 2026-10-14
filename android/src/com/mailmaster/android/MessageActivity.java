package com.mailmaster.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.webkit.ValueCallback;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.mailmaster.android.MailCache;
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

        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        ((TextView) findViewById(R.id.tv_subject)).setText(msg.subject);

        // 头像与发件人行
        TextView av = findViewById(R.id.tv_avatar);
        av.setText(MainActivity.avatarText(msg.from));
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(msg.unread ? MainActivity.avatarColor(msg.from)
                : getResources().getColor(R.color.avatar_read));
        av.setBackground(circle);

        ((TextView) findViewById(R.id.tv_from)).setText(nvl(msg.from));
        String when = (msg.date > 0)
                ? new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(new Date(msg.date))
                : "";
        ((TextView) findViewById(R.id.tv_date)).setText(when);

        StringBuilder meta = new StringBuilder();
        meta.append("收件人：").append(nvl(msg.to));
        meta.append("  ·  所属邮箱：").append(nvl(msg.accountEmail));
        ((TextView) findViewById(R.id.tv_meta)).setText(meta.toString());

        // 分类胶囊放到标题栏右侧
        TextView barCat = findViewById(R.id.tv_bar_category);
        String c = msg.category;
        if (c == null || c.length() == 0) {
            barCat.setVisibility(View.GONE);
        } else {
            barCat.setVisibility(View.VISIBLE);
            barCat.setText(c);
            GradientDrawable chip = new GradientDrawable();
            float dp = getResources().getDisplayMetrics().density;
            chip.setCornerRadius(10 * dp);
            chip.setColor(getResources().getColor(catColor(c)));
            barCat.setBackground(chip);
        }

        renderAttachments();
        renderBody();
        bindReplyForward();
        bindDelete();
        markRead();
    }

    /** 删除按钮：软删除移入回收站（服务器原件不动，可在回收站恢复） */
    private void bindDelete() {
        findViewById(R.id.btn_delete).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(MessageActivity.this)
                        .setTitle(R.string.trash_move_title)
                        .setMessage(getString(R.string.trash_move_msg, MainActivity.senderLabel(msg.from)))
                        .setPositiveButton(R.string.btn_delete, new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) {
                                try {
                                    MailCache.getInstance(MessageActivity.this)
                                            .softDelete(msg.accountId, msg.id);
                                } catch (Exception ignored) {
                                }
                                Toast.makeText(MessageActivity.this, R.string.toast_moved_trash,
                                        Toast.LENGTH_SHORT).show();
                                // 通知列表页：这封被删了，重新读缓存刷新
                                Intent data = new Intent();
                                data.putExtra("deleted", true);
                                setResult(RESULT_OK, data);
                                finish();
                            }
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }
        });
    }

    /** 附件列表：点一下交给系统里能处理该类型文件的 App 打开 */
    private void renderAttachments() {
        LinearLayout area = findViewById(R.id.attach_area);
        LinearLayout box = findViewById(R.id.attach_box);
        if (!msg.hasAttachment()) {
            area.setVisibility(View.GONE);
            return;
        }
        area.setVisibility(View.VISIBLE);
        box.removeAllViews();
        float dp = getResources().getDisplayMetrics().density;
        for (final Attachment a : msg.attachments) {
            if (a == null) continue;
            TextView tv = new TextView(this);
            tv.setText(a.name + "（" + a.sizeText() + "）");
            tv.setTextColor(getResources().getColor(R.color.text));
            tv.setTextSize(14);
            tv.setPadding((int) (12 * dp), (int) (10 * dp), (int) (12 * dp), (int) (10 * dp));
            GradientDrawable item = new GradientDrawable();
            item.setCornerRadius(8 * dp);
            item.setColor(0xFFF1F4F8);
            tv.setBackground(item);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (box.getChildCount() > 0) {
                lp.topMargin = (int) (6 * dp);
            }
            tv.setLayoutParams(lp);
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
     *
     * 兜底策略（治「只有标题没内容」）：HTML 非空不等于渲染成功——
     * 文档不完整、可见文本全被 style/template 包住时 WebView 会是空白，
     * 而这封邮件的纯文本正文往往是有内容的。旧逻辑「有 HTML 就只显示 HTML、
     * 纯文本永远不登场」会让这类邮件只剩标题。现在加载完成后检测可见文本，
     * 为空则自动回退纯文本；HTML 与纯文本都空时给出明确提示而不是一片空白。
     */
    private void renderBody() {
        final View sv = findViewById(R.id.sv_text);
        final WebView wv = findViewById(R.id.wv_body);
        final TextView bodyTv = (TextView) findViewById(R.id.tv_body);
        final boolean hasHtml = msg.html != null && msg.html.trim().length() > 0;
        final boolean hasText = msg.body != null && msg.body.trim().length() > 0;

        if (hasHtml) {
            sv.setVisibility(View.GONE);
            wv.setVisibility(View.VISIBLE);
            WebSettings ws = wv.getSettings();
            ws.setJavaScriptEnabled(false);
            ws.setBlockNetworkLoads(true);
            ws.setLoadWithOverviewMode(true);
            ws.setUseWideViewPort(true);
            wv.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    if (isFinishing()) return;
                    // 检测 HTML 是否渲染出了可见文本；为空说明渲染失败或整封就是一张图
                    view.evaluateJavascript(
                            "(function(){try{return (document.body?document.body.innerText:'').trim();}"
                                    + "catch(e){return '';}})()",
                            new ValueCallback<String>() {
                                @Override
                                public void onReceiveValue(String value) {
                                    // value 为 JSON 串：可见文本为空时返回 ""（两个引号）；
                                    // 返回 null 表示脚本没执行，保守起见不回退
                                    if (value == null) return;
                                    String t = value.trim();
                                    if (t.equals("\"\"") || t.isEmpty()) {
                                        // 渲染出空白：有纯文本就回退；纯图片邮件则保留 WebView
                                        if (hasText) fallbackToText();
                                    }
                                }
                            });
                }

                @Override
                public void onReceivedError(WebView view, WebResourceRequest request,
                        WebResourceError error) {
                    if (isFinishing()) return;
                    if (request != null && request.isForMainFrame() && hasText) {
                        fallbackToText();
                    }
                }
            });
            wv.loadDataWithBaseURL(null, MailParser.wrapForDisplay(msg.html),
                    "text/html; charset=UTF-8", "UTF-8", null);
        } else if (hasText) {
            wv.setVisibility(View.GONE);
            sv.setVisibility(View.VISIBLE);
            bodyTv.setText(msg.body);
        } else {
            // HTML 与纯文本都为空：加密/签名邮件或格式特殊，给出明确提示而非一片空白
            wv.setVisibility(View.GONE);
            sv.setVisibility(View.VISIBLE);
            bodyTv.setText(R.string.msg_body_unavailable);
        }
    }

    /** WebView 渲染失败/空白时回退到纯文本正文 */
    private void fallbackToText() {
        WebView wv = findViewById(R.id.wv_body);
        View sv = findViewById(R.id.sv_text);
        TextView bodyTv = (TextView) findViewById(R.id.tv_body);
        wv.setVisibility(View.GONE);
        sv.setVisibility(View.VISIBLE);
        bodyTv.setText(msg.body == null ? "" : msg.body);
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

    /** 打开即已读：先改本地状态并同步本地缓存，真实账户再异步回写服务器 */
    private void markRead() {
        if (!msg.unread) return;
        msg.unread = false;
        // 同步更新本地邮件缓存的未读标记，离线回看时角标才准确
        try {
            MailCache.getInstance(MessageActivity.this).markRead(msg.accountId, msg.msgNum);
        } catch (Exception ignored) {
        }
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

    private int catColor(String c) {
        if (Classifier.PROMO.equals(c)) return R.color.cat_promo;
        if (Classifier.SOCIAL.equals(c)) return R.color.cat_social;
        if (Classifier.OTHER.equals(c)) return R.color.cat_other;
        return R.color.cat_important;
    }

    private String nvl(String s) {
        return (s == null) ? "" : s;
    }
}
