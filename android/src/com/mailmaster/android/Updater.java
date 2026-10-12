package com.mailmaster.android;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Scanner;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 应用内更新：从 GitHub Release 拉 update.json -> 比对 versionCode ->
 * 下载 APK -> 调起系统安装器（覆盖安装）。
 *
 * 更新源说明：仓库打 v* tag 时 CI 会在 Release 附一份 update.json
 * （含 versionCode/versionName/apkUrl/notes），App 通过 GitHub Releases API
 * 拿到它，无需自建服务器。
 *
 * 比对用的是 versionCode（整数，CI 用 github.run_number 保证单调递增），
 * 因此不受 "1.10 字典序小于 1.9" 这类版本号字符串比较的坑影响。
 */
public final class Updater {

    private static final String REPO = "startime-ltk/mailmaster";
    private static final String RELEASES_API = "https://api.github.com/repos/" + REPO + "/releases/latest";

    private Updater() {}

    /** GitHub Release 里的更新信息 */
    public static class UpdateInfo {
        public int versionCode;
        public String versionName;
        public String apkUrl;
        public String notes;
        public String publishedAt;
    }

    public interface CheckCallback {
        void onResult(UpdateInfo info, boolean hasUpdate, String err);
    }

    public interface Progress {
        void onProgress(int percent);
        void onDone(File apk);
        void onError(String err);
    }

    /** 拉取并比对最新版本（内部已切子线程） */
    public static void checkUpdate(final Context ctx, final CheckCallback cb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    UpdateInfo info = fetchLatest(ctx);
                    int cur = currentVersionCode(ctx);
                    boolean has = info != null && info.versionCode > cur;
                    cb.onResult(info, has, null);
                } catch (Exception e) {
                    cb.onResult(null, false, e != null ? e.getMessage() : "unknown");
                }
            }
        }).start();
    }

    private static UpdateInfo fetchLatest(Context ctx) throws Exception {
        String releasesJson = httpGet(RELEASES_API);
        JSONObject rel = new JSONObject(releasesJson);
        String updateUrl = null;
        if (rel.has("assets")) {
            JSONArray arr = rel.getJSONArray("assets");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject a = arr.getJSONObject(i);
                if ("update.json".equals(a.optString("name"))) {
                    updateUrl = a.optString("browser_download_url");
                    break;
                }
            }
        }
        if (updateUrl == null || updateUrl.isEmpty()) {
            throw new Exception("尚未发布带更新清单的版本");
        }
        JSONObject meta = new JSONObject(httpGet(updateUrl));
        UpdateInfo info = new UpdateInfo();
        info.versionCode = meta.optInt("versionCode", 0);
        info.versionName = meta.optString("versionName", "");
        info.apkUrl = meta.optString("apkUrl", "");
        info.notes = meta.optString("notes", "");
        info.publishedAt = meta.optString("publishedAt", "");
        return info;
    }

    @SuppressWarnings("deprecation")
    private static int currentVersionCode(Context ctx) {
        try {
            PackageManager pm = ctx.getPackageManager();
            PackageInfo pi = pm.getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    @SuppressWarnings("deprecation")
    public static String currentVersionName(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private static String httpGet(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setInstanceFollowRedirects(true);
        int code = c.getResponseCode();
        InputStream in = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
        Scanner s = new Scanner(in, "UTF-8").useDelimiter("\\A");
        String body = s.hasNext() ? s.next() : "";
        s.close();
        c.disconnect();
        if (code < 200 || code >= 300) {
            throw new Exception("HTTP " + code);
        }
        return body;
    }

    /** 下载 APK 到缓存目录（内部已切子线程；调用方需先确保有安装权限） */
    public static void download(final Context ctx, final UpdateInfo info, final Progress p) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                FileOutputStream fos = null;
                InputStream in = null;
                HttpURLConnection c = null;
                try {
                    File dir = new File(ctx.getCacheDir(), "updates");
                    dir.mkdirs();
                    File out = new File(dir, "update.apk");
                    if (out.exists()) out.delete();

                    c = (HttpURLConnection) new URL(info.apkUrl).openConnection();
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(30000);
                    int total = c.getContentLength();
                    in = c.getInputStream();
                    fos = new FileOutputStream(out);
                    byte[] buf = new byte[8192];
                    int n, got = 0;
                    while ((n = in.read(buf)) > 0) {
                        fos.write(buf, 0, n);
                        got += n;
                        if (total > 0) p.onProgress((int) (got * 100L / total));
                    }
                    fos.close();
                    in.close();
                    c.disconnect();
                    p.onDone(out);
                } catch (Exception e) {
                    p.onError(e != null ? e.getMessage() : "unknown");
                } finally {
                    try { if (fos != null) fos.close(); } catch (Exception ignored) {}
                    try { if (in != null) in.close(); } catch (Exception ignored) {}
                    if (c != null) c.disconnect();
                }
            }
        }).start();
    }

    /** 调起系统安装器（调用方需确保已有「安装未知来源应用」权限） */
    public static void install(Context ctx, File apk) {
        Uri uri = UpdateProvider.uriFor(apk);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
    }
}
