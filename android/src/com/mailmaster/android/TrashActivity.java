package com.mailmaster.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
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
 * 回收站：软删除邮件的管理页。
 *
 * 设计约定（与 MailCache v2 对应）：
 *  - 「删除」只是本地软删除（deleted=1），服务器原件不动，误删可完整恢复；
 *  - 「恢复」把邮件放回收件箱；「彻底删除」才清本地数据库行与附件文件，不可恢复；
 *  - 回收站里的邮件不参与服务器同步，不会被下次拉取「复活」（见 MailCache.upsertAll）。
 */
public class TrashActivity extends Activity {

    private ListView list;
    private ArrayAdapter<MailMessage> adapter;
    private final List<MailMessage> trash = new ArrayList<>();
    private View emptyView;
    private TextView clearBtn;
    private TextView subtitle;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_trash);

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

        adapter = new ArrayAdapter<MailMessage>(this, R.layout.item_trash, R.id.tv_subject, trash) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View cv = convertView;
                if (cv == null) {
                    cv = LayoutInflater.from(getContext()).inflate(R.layout.item_trash, parent, false);
                }
                final MailMessage m = getItem(position);
                if (m == null) return cv;

                TextView av = cv.findViewById(R.id.tv_avatar);
                av.setText(MainActivity.avatarText(m.from));
                GradientDrawable circle = new GradientDrawable();
                circle.setShape(GradientDrawable.OVAL);
                circle.setColor(MainActivity.avatarColor(m.from));
                circle.setStroke(dp(2), 0xFFFFFFFF);
                av.setBackground(circle);

                ((TextView) cv.findViewById(R.id.tv_subject)).setText(m.subject);
                ((TextView) cv.findViewById(R.id.tv_from)).setText(m.from);
                String when = (m.date > 0)
                        ? new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(new Date(m.date))
                        : "";
                ((TextView) cv.findViewById(R.id.tv_date))
                        .setText(getString(R.string.trash_deleted_at, when));
                return cv;
            }
        };
        list.setAdapter(adapter);

        // 点按 / 长按都弹「恢复 / 彻底删除」
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int pos, long id) {
                showItemActions(adapter.getItem(pos));
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int pos, long id) {
                showItemActions(adapter.getItem(pos));
                return true;
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadTrash();
    }

    private void loadTrash() {
        trash.clear();
        trash.addAll(MailCache.getInstance(this).getTrash());
        adapter.notifyDataSetChanged();
        subtitle.setText(trash.isEmpty() ? "" : ("共 " + trash.size() + " 封 · 点按可恢复或彻底删除"));
        clearBtn.setVisibility(trash.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** 单封操作：恢复（右）/ 彻底删除（中，二次确认）/ 取消（左） */
    private void showItemActions(final MailMessage m) {
        if (m == null) return;
        new AlertDialog.Builder(this)
                .setTitle(m.subject)
                .setMessage(R.string.trash_item_actions)
                .setPositiveButton(R.string.btn_restore, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        MailCache.getInstance(TrashActivity.this).restore(m.accountId, m.id);
                        Toast.makeText(TrashActivity.this, R.string.toast_restored,
                                Toast.LENGTH_SHORT).show();
                        loadTrash();
                        setResult(RESULT_OK); // 通知列表页可能有邮件恢复
                    }
                })
                .setNegativeButton(R.string.trash_purge, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { confirmPurge(m); }
                })
                .setNeutralButton(android.R.string.cancel, null)
                .show();
    }

    /** 彻底删除单封：二次确认，通过后连附件文件一并清除 */
    private void confirmPurge(final MailMessage m) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.trash_purge_title)
                .setMessage(R.string.trash_purge_msg)
                .setPositiveButton(R.string.trash_purge, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        MailCache.getInstance(TrashActivity.this).purge(m.accountId, m.id);
                        Toast.makeText(TrashActivity.this, R.string.toast_purged,
                                Toast.LENGTH_SHORT).show();
                        loadTrash();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 清空回收站：二次确认后全部彻底删除 */
    private void confirmClearAll() {
        if (trash.isEmpty()) return;
        new AlertDialog.Builder(this)
                .setTitle(R.string.trash_clear_title)
                .setMessage(getString(R.string.trash_clear_msg, trash.size()))
                .setPositiveButton(R.string.trash_clear, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        MailCache.getInstance(TrashActivity.this).purgeAll();
                        Toast.makeText(TrashActivity.this, R.string.toast_trash_cleared,
                                Toast.LENGTH_SHORT).show();
                        loadTrash();
                        setResult(RESULT_OK);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private int dp(int n) {
        return (int) (n * getResources().getDisplayMetrics().density);
    }
}
