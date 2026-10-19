package com.mailmaster.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mailmaster.core.Classifier;
import com.mailmaster.core.MailEngine;
import com.mailmaster.core.config.ProviderPresets;
import com.mailmaster.core.model.EmailAccount;
import com.mailmaster.core.model.EmailMessage;
import com.mailmaster.core.service.AiSettings;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 网页版（Web）入口：把 core 逻辑层包成 REST + 静态页面。
 * 不依赖 JavaFX，移动端浏览器即可访问——这是"多端预留"方案中的 Web 形态。
 *
 * v1.20 起与桌面端/安卓端拉齐的端点：
 *  - 智能分类字段（category）随列表下发；
 *  - 回收站：GET /api/trash、POST /api/trash/restore、DELETE /api/trash/{id}、POST /api/trash/purge-all；
 *  - 草稿箱：GET/POST /api/drafts、DELETE /api/drafts/{id}；
 *  - 已读回写：POST /api/messages/{id}/read；
 *  - 加载更早：GET/POST /api/older；
 *  - 正文重拉：POST /api/messages/{id}/retry；
 *  - AI 分类设置：GET/POST /api/ai、POST /api/ai/test。
 *
 * 运行：mvn compile exec:java            （默认端口 8080）
 * 指定端口：mvn compile exec:java -Dexec.args=9090
 */
public class WebServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    static {
        MAPPER.setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    private static MailEngine engine;

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0].trim()) : 8080;
        String dbPath = System.getProperty("user.home") + "/.mailmaster/db";
        engine = new MailEngine(dbPath);
        if (!engine.hasRealAccounts()) {
            engine.enterDemoMode();   // 没有真实账户时自动进入演示模式
        }
        engine.startAutoRefresh(60);  // 演示模式下会自动跳过联网轮询

        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/", WebServer::handle);
        server.setExecutor(null);
        server.start();

        System.out.println("====================================================");
        System.out.println("  邮箱大师 · 网页版已启动");
        System.out.println("  本机访问:  http://localhost:" + port);
        System.out.println("  手机访问:  http://<电脑局域网IP>:" + port + "  （与电脑同一 WiFi）");
        if (engine.isDemoMode()) {
            System.out.println("  [演示模式] 当前为示例数据，无需授权码即可预览");
        }
        System.out.println("====================================================");
    }

    static void handle(HttpExchange ex) {
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if ("/".equals(path) || "/index.html".equals(path)) { returnStatic(ex, "web/index.html", "text/html"); return; }
            if ("/app.js".equals(path)) { returnStatic(ex, "web/app.js", "application/javascript"); return; }
            if ("/style.css".equals(path)) { returnStatic(ex, "web/style.css", "text/css"); return; }
            if (path.startsWith("/api/")) { handleApi(ex, path, method); return; }
            sendJson(ex, 404, Map.of("error", "not found"));
        } catch (Exception e) {
            try { sendJson(ex, 500, Map.of("error", String.valueOf(e.getMessage()))); } catch (Exception ignored) {}
        } finally {
            ex.close();
        }
    }

    @SuppressWarnings("unchecked")
    static void handleApi(HttpExchange ex, String path, String method) throws IOException {
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET,POST,DELETE,OPTIONS");
        ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
        if ("OPTIONS".equals(method)) { send(ex, 204, ""); return; }
        try {
            // ---------- 状态 ----------
            if ("/api/status".equals(path) && "GET".equals(method)) {
                long unread = engine.getInbox().stream().filter(m -> !m.isRead()).count();
                sendJson(ex, 200, Map.of(
                        "demoMode", engine.isDemoMode(),
                        "hasRealAccounts", engine.hasRealAccounts(),
                        "unread", unread,
                        "trashCount", engine.countTrash(),
                        "draftCount", engine.countDrafts(),
                        "hasOlder", engine.hasOlderMail()));
                return;
            }
            // ---------- 账户 ----------
            if ("/api/accounts".equals(path)) {
                if ("GET".equals(method)) {
                    List<Map<String, Object>> list = engine.getAccounts().stream()
                            .map(WebServer::accountDto).collect(Collectors.toList());
                    sendJson(ex, 200, Map.of("accounts", list));
                    return;
                }
                if ("POST".equals(method)) {
                    Map<String, Object> b = readJson(ex);
                    String email = (String) b.get("email");
                    String provider = (String) b.get("provider");
                    String password = (String) b.get("password");
                    String displayName = (String) b.get("displayName");
                    if (email == null || provider == null || password == null) {
                        sendJson(ex, 400, Map.of("error", "缺少 email / provider / password"));
                        return;
                    }
                    if ("GMAIL".equals(provider)) { sendJson(ex, 400, Map.of("error", "Gmail 需 OAuth2，本期未实现")); return; }
                    if ("CUSTOM".equals(provider)) { sendJson(ex, 400, Map.of("error", "自定义服务商请在桌面端添加")); return; }
                    engine.addAccount(email, provider, password, displayName);
                    if (engine.isDemoMode()) engine.exitDemoMode();
                    try { engine.refresh(); } catch (Exception ignored) {}
                    sendJson(ex, 200, Map.of("ok", true));
                    return;
                }
            }
            if (path.startsWith("/api/accounts/") && "DELETE".equals(method)) {
                String id = path.substring("/api/accounts/".length());
                engine.removeAccount(id);
                sendJson(ex, 200, Map.of("ok", true));
                return;
            }
            // ---------- 收件箱 ----------
            if ("/api/inbox".equals(path) && "GET".equals(method)) {
                List<Map<String, Object>> list = engine.getInbox().stream()
                        .map(WebServer::msgDto).collect(Collectors.toList());
                sendJson(ex, 200, Map.of("messages", list));
                return;
            }
            // ---------- 单封操作（先匹配后缀动作，再匹配通用详情） ----------
            if (path.startsWith("/api/messages/") && "POST".equals(method)) {
                String rest = path.substring("/api/messages/".length());
                int slash = rest.lastIndexOf('/');
                if (slash > 0) {
                    String id = rest.substring(0, slash);
                    String action = rest.substring(slash + 1);
                    if ("read".equals(action)) {
                        engine.markRead(id);
                        sendJson(ex, 200, Map.of("ok", true));
                        return;
                    }
                    if ("trash".equals(action)) {
                        engine.moveToTrash(id);
                        sendJson(ex, 200, Map.of("ok", true, "trashCount", engine.countTrash()));
                        return;
                    }
                    if ("retry".equals(action)) {
                        EmailMessage fresh = engine.retryBody(id);
                        if (fresh == null) { sendJson(ex, 502, Map.of("error", "重拉正文失败（网络或服务器暂不可达）")); return; }
                        sendJson(ex, 200, msgDto(fresh));
                        return;
                    }
                }
            }
            if (path.startsWith("/api/messages/") && "GET".equals(method)) {
                String id = path.substring("/api/messages/".length());
                EmailMessage found = engine.getInbox().stream()
                        .filter(m -> m.getId().equals(id)).findFirst().orElse(null);
                if (found == null) { sendJson(ex, 404, Map.of("error", "not found")); return; }
                sendJson(ex, 200, msgDto(found));
                return;
            }
            // ---------- 回收站 ----------
            if ("/api/trash".equals(path) && "GET".equals(method)) {
                List<Map<String, Object>> list = engine.getTrash().stream()
                        .map(WebServer::msgDto).collect(Collectors.toList());
                sendJson(ex, 200, Map.of("messages", list, "count", engine.countTrash()));
                return;
            }
            if ("/api/trash/restore".equals(path) && "POST".equals(method)) {
                Map<String, Object> b = readJson(ex);
                String id = (String) b.get("id");
                if (id == null) { sendJson(ex, 400, Map.of("error", "缺少 id")); return; }
                engine.restore(id);
                sendJson(ex, 200, Map.of("ok", true, "trashCount", engine.countTrash()));
                return;
            }
            if ("/api/trash/purge-all".equals(path) && "POST".equals(method)) {
                int n = engine.purgeAllTrash();
                sendJson(ex, 200, Map.of("ok", true, "purged", n));
                return;
            }
            if (path.startsWith("/api/trash/") && "DELETE".equals(method)) {
                String id = path.substring("/api/trash/".length());
                engine.purge(id);
                sendJson(ex, 200, Map.of("ok", true, "trashCount", engine.countTrash()));
                return;
            }
            // ---------- 草稿箱 ----------
            if ("/api/drafts".equals(path)) {
                if ("GET".equals(method)) {
                    List<Map<String, Object>> list = engine.getDrafts().stream()
                            .map(WebServer::draftDto).collect(Collectors.toList());
                    sendJson(ex, 200, Map.of("drafts", list));
                    return;
                }
                if ("POST".equals(method)) {
                    Map<String, Object> b = readJson(ex);
                    String accountId = (String) b.get("accountId");
                    String to = (String) b.get("to");
                    String subject = (String) b.get("subject");
                    String body = (String) b.get("body");
                    if (accountId == null) { sendJson(ex, 400, Map.of("error", "缺少 accountId")); return; }
                    long id = 0;
                    Object rawId = b.get("id");
                    if (rawId instanceof Number) id = ((Number) rawId).longValue();
                    else if (rawId instanceof String) {
                        try { id = Long.parseLong(((String) rawId).trim()); } catch (NumberFormatException ignored) {}
                    }
                    long saved = engine.saveDraft(id, accountId,
                            to == null ? "" : to, subject == null ? "" : subject, body == null ? "" : body);
                    sendJson(ex, 200, Map.of("ok", true, "id", saved, "draftCount", engine.countDrafts()));
                    return;
                }
            }
            if (path.startsWith("/api/drafts/") && "DELETE".equals(method)) {
                String idStr = path.substring("/api/drafts/".length());
                long id;
                try { id = Long.parseLong(idStr.trim()); } catch (NumberFormatException e) {
                    sendJson(ex, 400, Map.of("error", "草稿 id 不合法")); return;
                }
                engine.deleteDraft(id);
                sendJson(ex, 200, Map.of("ok", true, "draftCount", engine.countDrafts()));
                return;
            }
            // ---------- 发送 ----------
            if ("/api/send".equals(path) && "POST".equals(method)) {
                Map<String, Object> b = readJson(ex);
                String accountId = (String) b.get("accountId");
                String to = (String) b.get("to");
                String subject = (String) b.get("subject");
                String body = (String) b.get("body");
                if (accountId == null || to == null) { sendJson(ex, 400, Map.of("error", "缺少 accountId / to")); return; }
                engine.sendMail(accountId, to, subject == null ? "" : subject, body == null ? "" : body);
                sendJson(ex, 200, Map.of("ok", true, "demo", engine.isDemoMode()));
                return;
            }
            // ---------- 加载更早 ----------
            if ("/api/older".equals(path)) {
                if ("GET".equals(method)) {
                    sendJson(ex, 200, Map.of("hasOlder", engine.hasOlderMail()));
                    return;
                }
                if ("POST".equals(method)) {
                    int loaded = engine.loadOlder(30);
                    sendJson(ex, 200, Map.of("loaded", loaded, "hasOlder", engine.hasOlderMail()));
                    return;
                }
            }
            // ---------- AI 分类设置 ----------
            if ("/api/ai".equals(path)) {
                if ("GET".equals(method)) {
                    sendJson(ex, 200, aiDto(engine.getAiSettings()));
                    return;
                }
                if ("POST".equals(method)) {
                    Map<String, Object> b = readJson(ex);
                    AiSettings cur = engine.getAiSettings();
                    AiSettings s = new AiSettings();
                    s.enabled = Boolean.TRUE.equals(b.get("enabled"));
                    s.baseUrl = str(b.get("baseUrl"), cur.baseUrl);
                    s.model = str(b.get("model"), cur.model);
                    // apiKey 为空或仍是掩码值 → 保留原 key，避免逼用户每次重填
                    String key = (String) b.get("apiKey");
                    if (key == null || key.isBlank() || key.startsWith("****")) s.apiKey = cur.apiKey;
                    else s.apiKey = key.trim();
                    engine.setAiSettings(s);
                    sendJson(ex, 200, aiDto(engine.getAiSettings()));
                    return;
                }
            }
            if ("/api/ai/test".equals(path) && "POST".equals(method)) {
                // 允许只测不存：请求体可带临时设置，否则用当前设置
                Map<String, Object> b = readJson(ex);
                AiSettings test = engine.getAiSettings();
                if (b != null && !b.isEmpty()) {
                    test = new AiSettings();
                    test.enabled = true;
                    test.baseUrl = str(b.get("baseUrl"), engine.getAiSettings().baseUrl);
                    test.model = str(b.get("model"), engine.getAiSettings().model);
                    String key = (String) b.get("apiKey");
                    test.apiKey = (key == null || key.isBlank() || key.startsWith("****"))
                            ? engine.getAiSettings().apiKey : key.trim();
                }
                String result = Classifier.testConnection(test);
                sendJson(ex, 200, Map.of("result", result == null ? "" : result));
                return;
            }
            // ---------- 演示模式 ----------
            if ("/api/demo/toggle".equals(path) && "POST".equals(method)) {
                Map<String, Object> b = readJson(ex);
                boolean on = Boolean.TRUE.equals(b.get("on"));
                if (on) {
                    engine.enterDemoMode();
                } else {
                    if (!engine.hasRealAccounts()) { sendJson(ex, 400, Map.of("error", "无真实账户，无法退出演示模式")); return; }
                    engine.exitDemoMode();
                }
                sendJson(ex, 200, Map.of("demoMode", engine.isDemoMode()));
                return;
            }
            sendJson(ex, 404, Map.of("error", "unknown api: " + method + " " + path));
        } catch (Exception e) {
            sendJson(ex, 500, Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    private static String str(Object o, String def) {
        if (o == null) return def;
        String s = String.valueOf(o);
        return s.isBlank() ? def : s;
    }

    /** 掩码 API Key：只回显末 4 位，前端保存时原样带回即视为"不修改" */
    private static String maskKey(String key) {
        if (key == null || key.isBlank()) return "";
        if (key.length() <= 4) return "****";
        return "****" + key.substring(key.length() - 4);
    }

    static Map<String, Object> aiDto(AiSettings s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", s.enabled);
        m.put("apiKey", maskKey(s.apiKey));
        m.put("apiKeySet", s.apiKey != null && !s.apiKey.isBlank());
        m.put("baseUrl", s.baseUrl);
        m.put("model", s.model);
        return m;
    }

    static Map<String, Object> accountDto(EmailAccount a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("email", a.getEmail());
        m.put("displayName", a.getDisplayName());
        m.put("provider", ProviderPresets.labelOf(a.getProvider()));
        m.put("providerKey", a.getProvider());
        return m;
    }

    static Map<String, Object> msgDto(EmailMessage m) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", m.getId());
        map.put("accountId", m.getAccountId());
        map.put("accountEmail", m.getAccountEmail());
        map.put("from", m.getFrom());
        map.put("to", m.getTo());
        map.put("subject", m.getSubject());
        map.put("sentDate", m.getSentDate() == null ? 0 : m.getSentDate().getTime());
        map.put("read", m.isRead());
        map.put("uid", m.getUid());
        map.put("category", m.getCategory());
        map.put("hasContent", m.hasContent());
        map.put("bodyHtml", m.getBodyHtml());
        map.put("bodyText", m.getBodyText());
        return map;
    }

    /** 草稿 DTO（对应 core 的 String[7]：id, accountId, accountEmail, to, subject, body, updatedAt） */
    static Map<String, Object> draftDto(String[] d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.length > 0 ? d[0] : "");
        m.put("accountId", d.length > 1 ? d[1] : "");
        m.put("accountEmail", d.length > 2 ? d[2] : "");
        m.put("to", d.length > 3 ? d[3] : "");
        m.put("subject", d.length > 4 ? d[4] : "");
        m.put("body", d.length > 5 ? d[5] : "");
        long ts = 0;
        if (d.length > 6) {
            try { ts = Long.parseLong(d[6]); } catch (NumberFormatException ignored) {}
        }
        m.put("updatedAt", ts);
        return m;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> readJson(HttpExchange ex) throws IOException {
        byte[] body = ex.getRequestBody().readAllBytes();
        if (body.length == 0) return Map.of();
        return MAPPER.readValue(body, Map.class);
    }

    static void returnStatic(HttpExchange ex, String resource, String mime) throws IOException {
        try (InputStream in = WebServer.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) { send(ex, 404, "not found"); return; }
            byte[] data = in.readAllBytes();
            ex.getResponseHeaders().add("Content-Type", mime + "; charset=utf-8");
            sendBytes(ex, 200, data);
        }
    }

    static void sendJson(HttpExchange ex, int code, Object obj) throws IOException {
        byte[] b = MAPPER.writeValueAsString(obj).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        sendBytes(ex, code, b);
    }

    static void sendBytes(HttpExchange ex, int code, byte[] b) throws IOException {
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    static void send(HttpExchange ex, int code, String s) throws IOException {
        sendBytes(ex, code, s.getBytes(StandardCharsets.UTF_8));
    }
}
