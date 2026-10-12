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
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 应用内更新：从 GitHub Release 拉 update.json -> 比对 versionCode ->
 * 下载 APK -> 调起系统安装器（覆盖安装）。
 *
 * 更新源说明：仓库打 v* tag 时 CI 会在 Release 附一份 update.json
 * （含 versionCode/versionName/apkUrl/apkUrls/notes），App 通过
 * GitHub Releases API 拿到它，无需自建服务器。
 *
 * 提速：GitHub 直连在国内很慢，因此拉取「更新清单」和「下载 APK」都会
 * 先尝试国内加速镜像（ghfast.top / ghproxy.net），任一成功即用，全部失败
 * 才回退直连。连接超时压到 8 秒，避免某个失效镜像卡住整个更新。
 *
 * 比对用的是 versionCode（整数），因此不受 "1.10 字典序小于 1.9" 这类
 * 版本号字符串比较的坑影响。
 */
public final class Updater {

    private static final String REPO = "startime-ltk/mailmaster";
    private static final String RELEASES_API = "https://api.github.com/repos/" + REPO + "/releases/latest";

    /** 国内加速镜像前缀（顺序即优先级）。直连放最后兜底。 */
    private static final String[] MIRROR_PREFIXES = {
        "https://ghfast.top/",
        "https://ghproxy.net/",
        "https://gh.api.99988866.xyz/"
    };

    private static final int CONN_TIMEOUT = 8000;   // 单个源连接超时：快点失败、快点切下一个
    private static final int READ_TIMEOUT = 30000;  // 下载读取超时

    private Updater() {}

    /** GitHub Release 里的更新信息 */
    public static class UpdateInfo {
        public int versionCode;
        public String versionName;
        public String apkUrl;                 // 直连地址（兜底）
        public List<String> apkUrls = new ArrayList<String>(); // 下载地址候选（含镜像，按顺序尝试）
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

    /** 依次尝试各镜像拉取 releases 元数据（含 update.json 的原地址） */
    private static UpdateInfo fetchLatest(Context ctx) throws Exception {
        String releasesJson = null;
        Exception lastMeta = null;
        for (String metaUrl : metadataUrls()) {
            try {
                releasesJson = httpGet(metaUrl, CONN_TIMEOUT, 15000);
                break;
            } catch (Exception e) {
                lastMeta = e;
            }
        }
        if (releasesJson == null) {
            if (lastMeta != null) throw lastMeta;
            throw new Exception("无法获取更新信息");
        }

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

        // 拉 update.json 本身：原地址优先，失败再走镜像
        String meta = null;
        Exception lastU = null;
        for (String u : withMirrors(updateUrl)) {
            try {
                meta = httpGet(u, CONN_TIMEOUT, 20000);
                break;
            } catch (Exception e) {
                lastU = e;
            }
        }
        if (meta == null) {
            if (lastU != null) throw lastU;
            throw new Exception("无法获取更新清单");
        }
        return parseMeta(meta);
    }

    private static UpdateInfo parseMeta(String json) throws Exception {
        JSONObject meta = new JSONObject(json);
        UpdateInfo info = new UpdateInfo();
        info.versionCode = meta.optInt("versionCode", 0);
        info.versionName = meta.optString("versionName", "");
        info.apkUrl = meta.optString("apkUrl", "");
        info.notes = meta.optString("notes", "");
        info.publishedAt = meta.optString("publishedAt", "");
        if (meta.has("apkUrls")) {
            JSONArray arr = meta.getJSONArray("apkUrls");
            for (int i = 0; i < arr.length(); i++) {
                String u = arr.optString(i);
                if (u != null && !u.isEmpty()) info.apkUrls.add(u);
            }
        }
        // 兜底：若清单没给镜像列表，则基于直连地址本地拼出镜像候选
        if (info.apkUrls.isEmpty()) {
            if (!info.apkUrl.isEmpty()) {
                info.apkUrls.add(info.apkUrl);
                for (String p : MIRROR_PREFIXES) info.apkUrls.add(p + info.apkUrl);
            }
        }
        return info;
    }

    /** 原地址 + 各镜像前缀（单前缀，不重复拼接） */
    private static List<String> withMirrors(String rawUrl) {
        List<String> list = new ArrayList<String>();
        list.add(rawUrl);
        for (String p : MIRROR_PREFIXES) list.add(p + rawUrl);
        return list;
    }

    private static List<String> metadataUrls() {
        List<String> list = new ArrayList<String>();
        list.add(RELEASES_API);
        for (String p : MIRROR_PREFIXES) list.add(p + RELEASES_API);
        return list;
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

    private static String httpGet(String url, int connTimeout, int readTimeout) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(connTimeout);
        c.setReadTimeout(readTimeout);
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

    /**
     * 下载 APK 到缓存目录（内部已切子线程）。
     * 依次尝试 info.apkUrls（已含镜像，按顺序），任一成功即止；
     * 全部失败才回调 onError。调用方需先确保有「安装未知来源应用」权限。
     */
    public static void download(final Context ctx, final UpdateInfo info, final Progress p) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                FileOutputStream fos = null;
                InputStream in = null;
                HttpURLConnection c = null;
                Exception lastErr = null;
                List<String> candidates = info.apkUrls;
                if (candidates == null || candidates.isEmpty()) candidates = new ArrayList<String>();
                if (candidates.isEmpty() && info.apkUrl != null && !info.apkUrl.isEmpty()) {
                    candidates.add(info.apkUrl);
                }
                for (String url : candidates) {
                    try {
                        File dir = new File(ctx.getCacheDir(), "updates");
                        dir.mkdirs();
                        File out = new File(dir, "update.apk");
                        if (out.exists()) out.delete();

                        c = (HttpURLConnection) new URL(url).openConnection();
                        c.setConnectTimeout(CONN_TIMEOUT);
                        c.setReadTimeout(READ_TIMEOUT);
                        c.setInstanceFollowRedirects(true);
                        int total = c.getContentLength();
                        in = c.getInputStream();
                        fos = new FileOutputStream(out);
                        byte[] buf = new byte[32768];
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
                        return;
                    } catch (Exception e) {
                        lastErr = e;
                        try { if (fos != null) fos.close(); } catch (Exception ignored) {}
                        try { if (in != null) in.close(); } catch (Exception ignored) {}
                        if (c != null) c.disconnect();
                        fos = null; in = null; c = null;
                    }
                }
                p.onError(lastErr != null ? lastErr.getMessage() : "下载失败");
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
