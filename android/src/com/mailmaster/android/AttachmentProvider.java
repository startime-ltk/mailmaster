package com.mailmaster.android;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.webkit.MimeTypeMap;
import java.io.File;
import java.io.FileNotFoundException;

/**
 * 把应用私有目录里的附件安全地交给其他 App 打开（WPS、相册、浏览器等）。
 *
 * 为什么不用 FileProvider：本项目刻意零第三方依赖（无 AndroidX），
 * 所以直接用原生 ContentProvider 写一个 30 行的极简版本。
 * Android 7.0 起直接把 file:// 甩给别的 App 会抛 FileUriExposedException，
 * 必须走 content:// 并授予临时读取权限。
 */
public class AttachmentProvider extends ContentProvider {

    public static final String AUTHORITY = "com.mailmaster.android.attachments";

    @Override
    public boolean onCreate() {
        return true;
    }

    /** 生成一个可被外部 App 读取的 content:// 地址 */
    public static Uri uriFor(File file) {
        return Uri.parse("content://" + AUTHORITY + "/" + Uri.encode(file.getAbsolutePath()));
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mode != null && mode.contains("w")) {
            throw new FileNotFoundException("read-only: " + uri);
        }
        File f = resolve(uri);
        if (f == null || !f.exists()) {
            throw new FileNotFoundException("not found: " + uri);
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    /**
     * 只允许读取本应用私有目录下的文件。
     * 这是安全底线：拿到临时授权的第三方 App 不能借机读沙箱外的任何东西。
     */
    private File resolve(Uri uri) {
        String seg = uri.getLastPathSegment();
        if (seg == null || seg.isEmpty()) return null;
        File f = new File(Uri.decode(seg));
        try {
            String canonical = f.getCanonicalPath();
            Context c = getContext();
            if (c == null) return null;
            File[] bases = new File[]{
                    c.getFilesDir(),
                    c.getCacheDir(),
                    c.getExternalFilesDir(null)
            };
            for (File base : bases) {
                if (base == null) continue;
                if (canonical.startsWith(base.getCanonicalPath() + File.separator)
                        || canonical.equals(base.getCanonicalPath())) {
                    return f;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    @Override
    public String getType(Uri uri) {
        File f = resolve(uri);
        if (f == null) return "application/octet-stream";
        String ext = MimeTypeMap.getFileExtensionFromUrl(f.getName());
        if (ext != null && !ext.isEmpty()) {
            String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase());
            if (m != null) return m;
        }
        return "application/octet-stream";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
