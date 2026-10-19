package com.mailmaster.android;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;

/**
 * 草稿箱（本地 SQLite，独立库文件 mailmaster_drafts.db）。
 *
 * 与邮件缓存分库存放：草稿是「用户正在写的内容」，生命周期与邮件缓存不同
 * （邮件缓存可随账户删除/版本迁移清理，草稿必须稳如老狗）。
 *
 * 存储策略：
 *  - 每账户的草稿用行内 account_id 标识，列表按更新时间倒序（最新写的在前）；
 *  - 编辑已有草稿按 id 覆盖（UPSERT），不会越存越多；
 *  - 发送成功后调用 {@link #delete} 移除对应草稿。
 */
public class DraftStore extends SQLiteOpenHelper {

    private static final String DB_NAME = "mailmaster_drafts.db";
    private static final int DB_VERSION = 1;
    private static final String TABLE = "drafts";

    private static DraftStore INSTANCE;

    public static synchronized DraftStore getInstance(Context ctx) {
        if (INSTANCE == null) {
            INSTANCE = new DraftStore(ctx.getApplicationContext());
        }
        return INSTANCE;
    }

    private DraftStore(Context ctx) {
        super(ctx, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(
            "CREATE TABLE " + TABLE + " (" +
            " id INTEGER PRIMARY KEY AUTOINCREMENT," +
            " account_id TEXT NOT NULL," +
            " account_email TEXT," +
            " to_addr TEXT," +
            " subject TEXT," +
            " body TEXT," +
            " updated_at INTEGER NOT NULL)"
        );
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        // 首版即终版范式：后续如加列在此追加 ALTER，并保留既有草稿
    }

    /** 新建或更新草稿（id>0 时覆盖，否则插入）。返回草稿 id。 */
    public long save(Draft d) {
        if (d == null) return -1;
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("account_id", d.accountId);
        v.put("account_email", d.accountEmail);
        v.put("to_addr", d.to);
        v.put("subject", d.subject);
        v.put("body", d.body);
        v.put("updated_at", System.currentTimeMillis());
        if (d.id > 0) {
            db.update(TABLE, v, "id=?", new String[]{String.valueOf(d.id)});
            return d.id;
        }
        return db.insert(TABLE, null, v);
    }

    /** 全部草稿，按更新时间倒序（最新写的在前） */
    public List<Draft> getAll() {
        List<Draft> out = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = null;
        try {
            c = db.query(TABLE, null, null, null, null, null, "updated_at DESC");
            while (c.moveToNext()) {
                Draft d = new Draft();
                d.id = c.getLong(c.getColumnIndexOrThrow("id"));
                d.accountId = c.getString(c.getColumnIndexOrThrow("account_id"));
                d.accountEmail = c.getString(c.getColumnIndexOrThrow("account_email"));
                d.to = c.getString(c.getColumnIndexOrThrow("to_addr"));
                d.subject = c.getString(c.getColumnIndexOrThrow("subject"));
                d.body = c.getString(c.getColumnIndexOrThrow("body"));
                d.updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at"));
                out.add(d);
            }
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    public Draft get(long id) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = null;
        try {
            c = db.query(TABLE, null, "id=?", new String[]{String.valueOf(id)},
                    null, null, null);
            if (c.moveToFirst()) {
                Draft d = new Draft();
                d.id = c.getLong(c.getColumnIndexOrThrow("id"));
                d.accountId = c.getString(c.getColumnIndexOrThrow("account_id"));
                d.accountEmail = c.getString(c.getColumnIndexOrThrow("account_email"));
                d.to = c.getString(c.getColumnIndexOrThrow("to_addr"));
                d.subject = c.getString(c.getColumnIndexOrThrow("subject"));
                d.body = c.getString(c.getColumnIndexOrThrow("body"));
                d.updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at"));
                return d;
            }
            return null;
        } finally {
            if (c != null) c.close();
        }
    }

    public void delete(long id) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE, "id=?", new String[]{String.valueOf(id)});
    }

    /** 删除某账户的全部草稿（账户被移除时级联清理） */
    public void deleteAccount(String accountId) {
        if (accountId == null) return;
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE, "account_id=?", new String[]{accountId});
    }

    public int count() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT COUNT(*) FROM " + TABLE, null);
            return (c.moveToFirst()) ? c.getInt(0) : 0;
        } catch (Exception e) {
            return 0;
        } finally {
            if (c != null) c.close();
        }
    }

    /** 草稿模型（Plain Object，不 Serializable——只在应用内流转，不进 Intent） */
    public static class Draft {
        public long id = -1;          // -1 表示尚未存盘的新草稿
        public String accountId;
        public String accountEmail;
        public String to;
        public String subject;
        public String body;
        public long updatedAt;

        /** 列表副标题：收件人 + 摘要（主题为空时显示「(无主题)」） */
        public String summaryLine() {
            String who = (to == null || to.trim().isEmpty()) ? "(无收件人)" : to.trim();
            String subj = (subject == null || subject.trim().isEmpty()) ? "(无主题)" : subject.trim();
            String snip = (body == null) ? "" : body.trim().replace("\n", " ");
            if (snip.length() > 40) snip = snip.substring(0, 40) + "…";
            return "发给 " + who + " · " + subj + (snip.isEmpty() ? "" : " · " + snip);
        }
    }
}
