package com.mailmaster.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 草稿箱：未发送邮件的管理页。
 *
 * 设计约定（与 DraftStore 对应）：
 *  - 草稿只存本地（独立库 mailmaster_drafts.db），不同步服务器；
 *  - 点按继续编辑（跳 ComposeActivity 带 draftId），发送成功后自动移除草稿；
 *  - 长按删除单封（二次确认），工具栏「清空」删全部。
 */
public class DraftsActivity extends Activity {

    private ListView list;
    private ArrayAdapter<DraftStore.Draft> adapter;
    private final List<DraftStore.Draft> drafts = new ArrayList<>();
    private View emptyView;
    private TextView clearBtn;
    private TextView subtitle;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_drafts);

        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });

        subtitle = findViewById(R.id.tv_subtitle);
        clearBtn = findViewById(R.id.btn_clear);
        clearBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { confirmClearAll(); }
        });

        list = findViewById(R.id.list);
        emptyView = findViewById(R.id.tv_empty);
        list.setEmptyView(emptyView);

        adapter = new ArrayAdapter<DraftStore.Draft>(this, R.layout.item_draft, R.id.tv_subject, drafts) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View cv = convertView;
                if (cv == null) {
                    cv = LayoutInflater.from(getContext()).inflate(R.layout.item_draft, parent, false);
                }
                final DraftStore.Draft d = getItem(position);
                if (d == null) return cv;

                String subj = (d.subject == null || d.subject.trim().isEmpty())
                        ? "(无主题)" : d.subject.trim();
                ((TextView) cv.findViewById(R.id.tv_subject)).setText(subj);
                ((TextView) cv.findViewById(R.id.tv_summary)).setText(d.summaryLine());
                String when = (d.updatedAt > 0)
                        ? new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(new Date(d.updatedAt))
                        : "";
                ((TextView) cv.findViewById(R.id.tv_date))
                        .setText(getString(R.string.drafts_saved_at, when));
                return cv;
            }
        };
        list.setAdapter(adapter);

        // 点按继续编辑；长按删除
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int pos, long id) {
                openDraft(adapter.getItem(pos));
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int pos, long id) {
                confirmDelete(adapter.getItem(pos));
                return true;
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadDrafts();
    }

    private void loadDrafts() {
        drafts.clear();
        drafts.addAll(DraftStore.getInstance(this).getAll());
        adapter.notifyDataSetChanged();
        subtitle.setText(drafts.isEmpty() ? "" : ("共 " + drafts.size() + " 封 · 点按继续编辑"));
        clearBtn.setVisibility(drafts.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** 点按草稿：带 id 跳转写邮件页，退出时自动保存（不删草稿，发送成功才删） */
    private void openDraft(DraftStore.Draft d) {
        if (d == null) return;
        Intent it = new Intent(this, ComposeActivity.class);
        it.putExtra("draftId", d.id);
        startActivity(it);
    }

    /** 长按删除单封：二次确认 */
    private void confirmDelete(final DraftStore.Draft d) {
        if (d == null) return;
        new AlertDialog.Builder(this)
                .setTitle(R.string.drafts_delete_title)
                .setMessage(R.string.drafts_delete_msg)
                .setPositiveButton(R.string.btn_delete, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dia, int w) {
                        DraftStore.getInstance(DraftsActivity.this).delete(d.id);
                        Toast.makeText(DraftsActivity.this, R.string.toast_draft_deleted,
                                Toast.LENGTH_SHORT).show();
                        loadDrafts();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 清空草稿箱：二次确认 */
    private void confirmClearAll() {
        if (drafts.isEmpty()) return;
        new AlertDialog.Builder(this)
                .setTitle(R.string.drafts_clear_title)
                .setMessage(getString(R.string.drafts_clear_msg, drafts.size()))
                .setPositiveButton(R.string.drafts_clear, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        DraftStore store = DraftStore.getInstance(DraftsActivity.this);
                        for (DraftStore.Draft x : new ArrayList<>(drafts)) store.delete(x.id);
                        Toast.makeText(DraftsActivity.this, R.string.toast_drafts_cleared,
                                Toast.LENGTH_SHORT).show();
                        loadDrafts();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
