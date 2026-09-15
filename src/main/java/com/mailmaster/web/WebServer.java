package com.mailmaster.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mailmaster.core.MailEngine;
import com.mailmaster.core.config.ProviderPresets;
import com.mailmaster.core.model.EmailAccount;
import com.mailmaster.core.model.EmailMessage;
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
            if ("/api/status".equals(path) && "GET".equals(method)) {
                sendJson(ex, 200, Map.of("demoMode", engine.isDemoMode(), "hasRealAccounts", engine.hasRealAccounts()));
                return;
            }
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
            if ("/api/inbox".equals(path) && "GET".equals(method)) {
                List<Map<String, Object>> list = engine.getInbox().stream()
                        .map(WebServer::msgDto).collect(Collectors.toList());
                sendJson(ex, 200, Map.of("messages", list));
                return;
            }
            if (path.startsWith("/api/messages/") && "GET".equals(method)) {
                String id = path.substring("/api/messages/".length());
                EmailMessage found = engine.getInbox().stream()
                        .filter(m -> m.getId().equals(id)).findFirst().orElse(null);
                if (found == null) { sendJson(ex, 404, Map.of("error", "not found")); return; }
                sendJson(ex, 200, msgDto(found));
                return;
            }
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
        map.put("bodyHtml", m.getBodyHtml());
        map.put("bodyText", m.getBodyText());
        return map;
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
