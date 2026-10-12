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
 * 仅供「应用内更新」使用：把下载到应用私有缓存目录的 APK
 * 通过 content:// 临时授权给系统安装器读取（安卓 7+ 禁止直接甩 file://）。
 *
 * 与 AttachmentProvider 同理，零第三方依赖，且只允许访问本应用私有目录，
 * 第三方安装器无法借临时授权读到沙箱外的任何东西。
 */
public class UpdateProvider extends ContentProvider {

    public static final String AUTHORITY = "com.mailmaster.android.update";

    @Override
    public boolean onCreate() {
        return true;
    }

    /** 生成一个可被系统安装器读取的 content:// 地址 */
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
     * 只允许读取本应用私有目录（files / cache / 外部 files）下的文件。
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
                String bp = base.getCanonicalPath();
                if (canonical.equals(bp) || canonical.startsWith(bp + File.separator)) {
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
        if (f != null && f.getName().toLowerCase().endsWith(".apk")) {
            return "application/vnd.android.package-archive";
        }
        String ext = MimeTypeMap.getFileExtensionFromUrl(f == null ? "" : f.getName());
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
