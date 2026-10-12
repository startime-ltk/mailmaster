package com.mailmaster.android;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地邮件缓存（SQLite）。
 *
 * 作用：把「已接收的邮件」持久化到本地，做到——
 *  1. 断网/弱网也能回看历史邮件（离线优先）；
 *  2. 列表秒开，不再每次等待 IMAP 全程拉取；
 *  3. 删除账户时，连同其缓存邮件与附件文件一并清理（见 {@link #deleteAccount}）。
 *
 * 存储约定（影响「覆盖安装 / 更新」时数据是否保留，勿随意改动）：
 *  - 数据库名固定为 "mailmaster_cache.db"，随应用私有目录存放；
 *  - 同包名、同签名覆盖安装时默认保留，卸载才会清空；
 *  - 主键采用 (account_id, msg_id)，其中 msg_id 即 MailMessage.id（形如 accountId-msgNum），全局唯一，天然去重。
 */
public class MailCache extends SQLiteOpenHelper {

    private static final String DB_NAME = "mailmaster_cache.db";
    private static final int DB_VERSION = 1;
    private static final String TABLE = "messages";

    // 单列引用，避免 Activity 泄漏
    private Context ctx;

    private static MailCache INSTANCE;

    /** 应用级单例 */
    public static synchronized MailCache getInstance(Context ctx) {
        if (INSTANCE == null) {
            INSTANCE = new MailCache(ctx.getApplicationContext());
        }
        return INSTANCE;
    }

    private MailCache(Context ctx) {
        super(ctx, DB_NAME, null, DB_VERSION);
        this.ctx = ctx.getApplicationContext();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(
            "CREATE TABLE " + TABLE + " (" +
            " _id INTEGER PRIMARY KEY AUTOINCREMENT," +
            " account_id TEXT NOT NULL," +
            " msg_id TEXT NOT NULL," +
            " account_email TEXT," +
            " frm TEXT," +          // 发件人（含「显示名 <addr>」原样）
            " to_addr TEXT," +
            " subject TEXT," +
            " body TEXT," +
            " html TEXT," +
            " date INTEGER," +
            " unread INTEGER," +
            " category TEXT," +
            " attachments TEXT," +  // Attachment 列表的 JSON 序列化
            " msg_num INTEGER," +
            " UNIQUE(account_id, msg_id))"
        );
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        // v1 暂无需迁移；保留钩子
    }

    /** 批量写入（存在则覆盖，按 account_id+msg_id 去重）。事务提交，避免逐条 IO。 */
    public void upsertAll(List<MailMessage> msgs) {
        if (msgs == null || msgs.isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (MailMessage m : msgs) {
                if (m == null || m.id == null) continue;
                db.insertWithOnConflict(TABLE, null, toValues(m), SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** 取出全部本地邮件，按时间倒序（最新在前）。 */
    public List<MailMessage> getAll() {
        List<MailMessage> out = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = null;
        try {
            c = db.query(TABLE, null, null, null, null, null, "date DESC");
            while (c.moveToNext()) out.add(fromCursor(c));
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    /** 删除某账户的全部缓存邮件，并清理其附件目录。 */
    public void deleteAccount(String accountId) {
        if (accountId == null) return;
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE, "account_id=?", new String[]{accountId});
        // 级联清理落盘的附件文件（防止隐私残留与空间浪费）
        MailService.deleteAccountFiles(ctx, accountId);
    }

    /** 已读回写时同步本地缓存的未读标记。 */
    public void markRead(String accountId, int msgNum) {
        if (accountId == null || msgNum <= 0) return;
        SQLiteDatabase db = getWritableDatabase();
        android.content.ContentValues v = new android.content.ContentValues();
        v.put("unread", 0);
        db.update(TABLE, v, "account_id=? AND msg_num=?",
                new String[]{accountId, String.valueOf(msgNum)});
    }

    /** 清空所有本地缓存（谨慎调用）。 */
    public void clear() {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE, null, null);
    }

    private android.content.ContentValues toValues(MailMessage m) {
        android.content.ContentValues v = new android.content.ContentValues();
        v.put("account_id", m.accountId);
        v.put("msg_id", m.id);
        v.put("account_email", m.accountEmail);
        v.put("frm", m.from);
        v.put("to_addr", m.to);
        v.put("subject", m.subject);
        v.put("body", m.body);
        v.put("html", m.html);
        v.put("date", m.date);
        v.put("unread", m.unread ? 1 : 0);
        v.put("category", m.category);
        v.put("attachments", attachmentsToJson(m.attachments));
        v.put("msg_num", m.msgNum);
        return v;
    }

    private MailMessage fromCursor(Cursor c) {
        MailMessage m = new MailMessage();
        m.accountId = c.getString(c.getColumnIndexOrThrow("account_id"));
        m.id = c.getString(c.getColumnIndexOrThrow("msg_id"));
        m.accountEmail = c.getString(c.getColumnIndexOrThrow("account_email"));
        m.from = c.getString(c.getColumnIndexOrThrow("frm"));
        m.to = c.getString(c.getColumnIndexOrThrow("to_addr"));
        m.subject = c.getString(c.getColumnIndexOrThrow("subject"));
        m.body = c.getString(c.getColumnIndexOrThrow("body"));
        m.html = c.getString(c.getColumnIndexOrThrow("html"));
        m.date = c.getLong(c.getColumnIndexOrThrow("date"));
        m.unread = c.getInt(c.getColumnIndexOrThrow("unread")) != 0;
        m.category = c.getString(c.getColumnIndexOrThrow("category"));
        m.attachments = jsonToAttachments(c.getString(c.getColumnIndexOrThrow("attachments")));
        m.msgNum = c.getInt(c.getColumnIndexOrThrow("msg_num"));
        return m;
    }

    private String attachmentsToJson(List<Attachment> list) {
        if (list == null) return "";
        JSONArray a = new JSONArray();
        for (Attachment at : list) {
            if (at == null) continue;
            try {
                JSONObject o = new JSONObject();
                o.put("name", at.name == null ? "" : at.name);
                o.put("path", at.path == null ? "" : at.path);
                o.put("mime", at.mime == null ? "" : at.mime);
                o.put("size", at.size);
                a.put(o);
            } catch (Exception ignored) {
            }
        }
        return a.toString();
    }

    private List<Attachment> jsonToAttachments(String s) {
        List<Attachment> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        try {
            JSONArray a = new JSONArray(s);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Attachment at = new Attachment();
                at.name = o.optString("name", "");
                at.path = o.optString("path", "");
                at.mime = o.optString("mime", "");
                at.size = o.optLong("size", 0);
                out.add(at);
            }
        } catch (Exception ignored) {
        }
        return out;
    }
}
