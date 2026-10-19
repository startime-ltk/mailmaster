package com.mailmaster.android;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class ComposeActivity extends Activity {

    private static final int REQ_ATTACH = 1001;

    private Spinner spinner;
    private final List<File> files = new ArrayList<>();

    /** 正在编辑的草稿 id；-1 表示新邮件（非草稿续写） */
    private long draftId = -1;
    private EditText etTo, etSubj, etBody;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_compose);

        spinner = findViewById(R.id.spinner_from);
        ArrayAdapter<MailAccount> sa = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, MainActivity.store.accounts);
        sa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(sa);
        // 回复/转发时默认用收件账户，而不是列表里的第一个
        preselectAccount();

        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                autoSaveDraft();   // 返回即存草稿：写了一半退出不丢
                finish();
            }
        });

        etTo = findViewById(R.id.et_to);
        etSubj = findViewById(R.id.et_subject);
        etBody = findViewById(R.id.et_body);
        final TextView clear = findViewById(R.id.btn_clear_attach);
        Button send = findViewById(R.id.btn_send);

        // 草稿续写：带 draftId 进入时恢复各字段（附件不随草稿保存，见类注释）
        draftId = getIntent().getLongExtra("draftId", -1);
        if (draftId > 0) {
            DraftStore.Draft d = DraftStore.getInstance(this).get(draftId);
            if (d != null) {
                etTo.setText(nz(d.to));
                etSubj.setText(nz(d.subject));
                etBody.setText(nz(d.body));
                selectAccount(d.accountId);
            } else {
                draftId = -1; // 草稿已被删（如另一台操作），按新邮件处理
            }
        }

        Intent it = getIntent();
        String mode = it.getStringExtra("mode");
        MailMessage ref = (MailMessage) it.getSerializableExtra("msg");
        if (ref != null) {
            if ("reply".equals(mode)) {
                etTo.setText(ref.fromAddress());
                etSubj.setText(ref.replySubject());
                etBody.setText(ref.quoted());
                etBody.setSelection(0); // 光标停在开头，方便直接写正文
            } else if ("forward".equals(mode)) {
                etSubj.setText(ref.forwardSubject());
                etBody.setText(ref.quoted());
                etBody.setSelection(0);
            }
        }

        findViewById(R.id.btn_attach).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickAttachment();
            }
        });
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                files.clear();
                refreshAttachLabel();
            }
        });
        refreshAttachLabel();

        // 存草稿：立即落盘并退出（明示保存，区别于返回时的自动保存）
        findViewById(R.id.btn_save_draft).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveDraft(true);
                finish();
            }
        });

        send.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final MailAccount from = (MailAccount) spinner.getSelectedItem();
                final String t = etTo.getText().toString().trim();
                if (from == null || t.isEmpty()) {
                    Toast.makeText(ComposeActivity.this, R.string.toast_fill_to, Toast.LENGTH_SHORT).show();
                    return;
                }
                final String s = etSubj.getText().toString();
                final String bd = etBody.getText().toString();
                final List<File> atts = new ArrayList<>(files);

                // 真实发送必须放到子线程：安卓禁止在主线程联网，
                // 否则会抛 NetworkOnMainThreadException 直接闪退
                v.setEnabled(false);
                Toast.makeText(ComposeActivity.this, R.string.sending, Toast.LENGTH_SHORT).show();
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final boolean ok = MailService.send(from, t, s, bd, atts);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(ComposeActivity.this,
                                        ok ? R.string.send_real_ok : R.string.send_fail,
                                        Toast.LENGTH_LONG).show();
                                v.setEnabled(true);
                                if (ok) {
                                    // 发送成功：若这是草稿续写，把草稿从草稿箱移除
                                    if (draftId > 0) {
                                        DraftStore.getInstance(ComposeActivity.this).delete(draftId);
                                    }
                                    ComposeActivity.this.finish();
                                }
                            }
                        });
                    }
                }).start();
            }
        });
    }

    /** 系统返回键同样走「自动存草稿」，与工具栏返回一致 */
    @Override
    public void onBackPressed() {
        autoSaveDraft();
        super.onBackPressed();
    }

    /**
     * 自动保存草稿（返回时调用）：
     *  - 收件人/主题/正文有任一非空 → 存盘（新建或按 draftId 覆盖）；
     *  - 全部为空且原是草稿续写 → 用户清空了内容，删掉这封草稿；
     *  - 全部为空的新邮件 → 什么都不做，不产生空草稿。
     */
    private void autoSaveDraft() {
        String t = etTo.getText().toString().trim();
        String s = etSubj.getText().toString().trim();
        String b = etBody.getText().toString().trim();
        boolean empty = t.isEmpty() && s.isEmpty() && b.isEmpty();
        if (empty) {
            if (draftId > 0) DraftStore.getInstance(this).delete(draftId);
            return;
        }
        saveDraft(false);
    }

    /** 明示保存：写库并提示（用于「存草稿」按钮） */
    private void saveDraft(boolean toast) {
        MailAccount from = (MailAccount) spinner.getSelectedItem();
        DraftStore.Draft d = new DraftStore.Draft();
        d.id = draftId;
        d.accountId = (from != null) ? from.id : "";
        d.accountEmail = (from != null) ? from.email : "";
        d.to = etTo.getText().toString();
        d.subject = etSubj.getText().toString();
        d.body = etBody.getText().toString();
        draftId = DraftStore.getInstance(this).save(d);
        if (toast) {
            Toast.makeText(this, R.string.toast_draft_saved, Toast.LENGTH_SHORT).show();
        }
    }

    /** 按账户 id 选中发件账户（草稿续写时恢复原发件人） */
    private void selectAccount(String accountId) {
        if (accountId == null || accountId.isEmpty()) return;
        List<MailAccount> accs = MainActivity.store.accounts;
        for (int i = 0; i < accs.size(); i++) {
            if (accs.get(i).id.equals(accountId)) {
                spinner.setSelection(i);
                return;
            }
        }
    }

    private static String nz(String s) {
        return (s == null) ? "" : s;
    }

    private void preselectAccount() {
        MailMessage ref = (MailMessage) getIntent().getSerializableExtra("msg");
        if (ref == null) return;
        List<MailAccount> accs = MainActivity.store.accounts;
        for (int i = 0; i < accs.size(); i++) {
            if (accs.get(i).id.equals(ref.accountId)) {
                spinner.setSelection(i);
                return;
            }
        }
    }

    private void pickAttachment() {
        try {
            Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            it.addCategory(Intent.CATEGORY_OPENABLE);
            it.setType("*/*");
            it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(Intent.createChooser(it, getString(R.string.btn_attach)), REQ_ATTACH);
        } catch (Exception e) {
            Toast.makeText(this, R.string.toast_attach_pick_fail, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_ATTACH || res != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        File f = copyToOutbox(uri);
        if (f == null) {
            Toast.makeText(this, R.string.toast_attach_pick_fail, Toast.LENGTH_SHORT).show();
            return;
        }
        files.add(f);
        refreshAttachLabel();
    }

    /** 把用户选中的文件复制到自己的私有目录，发信时才有稳定路径可读 */
    private File copyToOutbox(Uri uri) {
        InputStream in = null;
        FileOutputStream os = null;
        try {
            String name = queryName(uri);
            if (name == null || name.isEmpty()) name = "attachment.bin";
            name = name.replace("/", "_").replace("\\", "_");
            File dir = new File(getCacheDir(), "outbox");
            if (!dir.exists()) dir.mkdirs();
            File out = new File(dir, name);
            in = getContentResolver().openInputStream(uri);
            if (in == null) return null;
            os = new FileOutputStream(out);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
            }
            return out;
        } catch (Exception e) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) {}
            if (os != null) try { os.close(); } catch (Exception ignored) {}
        }
    }

    private String queryName(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) {}
        }
        return uri.getLastPathSegment();
    }

    private void refreshAttachLabel() {
        TextView tv = findViewById(R.id.tv_attachments);
        TextView clear = findViewById(R.id.btn_clear_attach);
        if (files.isEmpty()) {
            tv.setVisibility(View.GONE);
            clear.setVisibility(View.GONE);
            return;
        }
        StringBuilder sb = new StringBuilder(getString(R.string.label_attachments)).append("：");
        for (int i = 0; i < files.size(); i++) {
            if (i > 0) sb.append("、");
            sb.append(files.get(i).getName());
        }
        tv.setText(sb.toString());
        tv.setVisibility(View.VISIBLE);
        clear.setVisibility(View.VISIBLE);
    }
}
