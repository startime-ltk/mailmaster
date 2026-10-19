package com.mailmaster.ui;

import com.mailmaster.core.Classifier;
import com.mailmaster.core.MailEngine;
import com.mailmaster.core.config.ProviderPresets;
import com.mailmaster.core.model.EmailAccount;
import com.mailmaster.core.model.EmailMessage;
import com.mailmaster.core.service.AiSettings;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.*;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.util.StringConverter;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * 邮箱大师桌面端入口（JavaFX）。
 * 本类只做"界面与交互"，所有邮件逻辑都委托给 core.MailEngine —— 这样未来做 Web / 小程序时，
 * 只需复用 MailEngine，不用动这一层。
 *
 * v1.20 起与安卓端拉齐：分类列 / 未读加粗 / 回收站 / 草稿箱 / 加载更早 / AI 设置 / 已读回写。
 */
public class MainApp extends Application {
    private MailEngine engine;
    private final ObservableList<EmailMessage> messages = FXCollections.observableArrayList();
    private TableView<EmailMessage> table;
    private WebView viewer;
    private ComboBox<EmailAccount> fromBox;
    private Button olderBtn;
    private volatile boolean loadingOlder = false;

    @Override
    public void start(Stage stage) {
        String dbPath = System.getProperty("user.home") + "/.mailmaster/db";
        engine = new MailEngine(dbPath);
        // 本机还没录入任何真实邮箱时，自动进入演示模式：用示例数据把界面铺满，无需授权码
        if (!engine.hasRealAccounts()) {
            engine.enterDemoMode();
        }
        engine.setOnUpdate(() -> Platform.runLater(this::reload));
        engine.startAutoRefresh(60);

        // 顶部工具栏
        Button addBtn = new Button("+ 添加账户");
        addBtn.setOnAction(e -> openAddAccount(stage));
        Button composeBtn = new Button("✎ 写邮件");
        composeBtn.setOnAction(e -> openCompose(stage, null));
        Button refreshBtn = new Button("⟳ 刷新");
        refreshBtn.setOnAction(e -> refreshAsync(stage));
        Button draftsBtn = new Button("📥 草稿箱");
        draftsBtn.setOnAction(e -> openDrafts(stage));
        Button trashBtn = new Button("🗑 回收站");
        trashBtn.setOnAction(e -> openTrash(stage));
        olderBtn = new Button("⏳ 更早邮件");
        olderBtn.setOnAction(e -> loadOlderAsync());
        Button aiBtn = new Button("🤖 AI 设置");
        aiBtn.setOnAction(e -> openAiSettings(stage));
        // 演示模式开关：随时在「示例数据」和「真实邮箱」之间切换
        ToggleButton demoBtn = new ToggleButton("🎭 演示模式");
        demoBtn.setSelected(engine.isDemoMode());
        demoBtn.setOnAction(e -> toggleDemo(demoBtn, stage));
        ToolBar tb = new ToolBar(addBtn, composeBtn, refreshBtn, new Separator(),
                draftsBtn, trashBtn, olderBtn, new Separator(), aiBtn, new Separator(), demoBtn);

        // 统一收件箱表格（含分类列；未读加粗由 rowFactory 处理）
        table = new TableView<>();
        TableColumn<EmailMessage, String> cFrom = new TableColumn<>("发件人");
        cFrom.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().getFrom()));
        cFrom.setPrefWidth(190);
        TableColumn<EmailMessage, String> cSubject = new TableColumn<>("主题");
        cSubject.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().getSubject()));
        cSubject.setPrefWidth(300);
        TableColumn<EmailMessage, String> cCat = new TableColumn<>("分类");
        cCat.setCellValueFactory(d -> new SimpleStringProperty(
                d.getValue().getCategory() == null ? "" : d.getValue().getCategory()));
        cCat.setPrefWidth(64);
        TableColumn<EmailMessage, String> cAccount = new TableColumn<>("账户");
        cAccount.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().getAccountEmail()));
        cAccount.setPrefWidth(150);
        TableColumn<EmailMessage, String> cDate = new TableColumn<>("时间");
        cDate.setCellValueFactory(d -> {
            var dt = d.getValue().getSentDate();
            String s = dt == null ? "" : dt.toInstant().atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"));
            return new SimpleStringProperty(s);
        });
        cDate.setPrefWidth(110);
        table.getColumns().addAll(cFrom, cSubject, cCat, cAccount, cDate);
        table.setItems(messages);
        table.setPlaceholder(new Label("暂无邮件，先点左上角“添加账户”"));
        table.getSelectionModel().selectedItemProperty().addListener((o, oldV, m) -> showMessage(m));

        // 未读加粗：行样式覆盖单元格（字体粗细与文字色均可继承）
        table.setRowFactory(tv -> new TableRow<>() {
            @Override
            protected void updateItem(EmailMessage m, boolean empty) {
                super.updateItem(m, empty);
                setStyle((empty || m == null || m.isRead())
                        ? "" : "-fx-font-weight: bold; -fx-text-fill: #0f172a;");
            }
        });

        // 右键菜单：移入回收站
        MenuItem miTrash = new MenuItem("移入回收站");
        miTrash.setOnAction(e -> {
            EmailMessage m = table.getSelectionModel().getSelectedItem();
            if (m != null) {
                engine.moveToTrash(m.getId());
                alert("已移入回收站（服务器原件不动，可在回收站恢复）");
            }
        });
        table.setContextMenu(new ContextMenu(miTrash));

        viewer = new WebView();
        viewer.setPrefWidth(420);

        VBox left = new VBox(tb, table);
        left.setSpacing(4);
        VBox.setVgrow(table, Priority.ALWAYS);

        BorderPane root = new BorderPane();
        root.setCenter(left);
        root.setRight(viewer);
        BorderPane.setMargin(viewer, new Insets(4));

        javafx.scene.Scene scene = new javafx.scene.Scene(root, 1080, 660);
        stage.setScene(scene);
        stage.show();

        reload();
    }

    private void reload() {
        List<EmailMessage> inbox = engine.getInbox();
        messages.setAll(inbox);
        // 副标题：未读数 + 回收站/草稿角标
        int unread = 0;
        for (EmailMessage m : inbox) if (!m.isRead()) unread++;
        table.getScene().getWindow();
        if (table.getScene() != null) {
            Stage st = (Stage) table.getScene().getWindow();
            st.setTitle("邮箱大师 MailMaster — 未读 " + unread
                    + " · 回收站 " + engine.countTrash()
                    + " · 草稿 " + engine.countDrafts()
                    + (engine.isDemoMode() ? "  —  演示模式（示例数据，未连接真实邮箱）" : ""));
        }
        olderBtn.setDisable(engine.isDemoMode() || loadingOlder || !engine.hasOlderMail());
    }

    private void showMessage(EmailMessage m) {
        if (m == null) {
            viewer.getEngine().loadContent("<p style='color:#888'>未选择邮件</p>");
            return;
        }
        String html = m.getBodyHtml() != null
                ? m.getBodyHtml()
                : "<pre style='white-space:pre-wrap;font-family:sans-serif'>" + escape(m.getBodyText()) + "</pre>";
        // 正文为空给出明确提示（与安卓端一致，不显示一片空白）
        if ((m.getBodyHtml() == null || m.getBodyHtml().trim().isEmpty())
                && (m.getBodyText() == null || m.getBodyText().trim().isEmpty())) {
            html = "<p style='color:#888'>这封邮件的正文没能取到（格式特殊或网络中断）。" +
                   "可关闭后重试，或在手机上用「重新加载正文」单封重拉。</p>";
        }
        viewer.getEngine().loadContent(html, "text/html");
        // 打开即已读：本地缓存 + 异步回写服务器
        if (!m.isRead()) engine.markRead(m.getId());
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /* ------------------------- 异步动作 ------------------------- */

    private void refreshAsync(Stage owner) {
        new Thread(() -> {
            try {
                engine.refresh();
            } catch (Exception ex) {
                Platform.runLater(() -> alert("刷新失败: " + ex.getMessage()));
            }
        }).start();
    }

    private void loadOlderAsync() {
        if (loadingOlder) return;
        loadingOlder = true;
        olderBtn.setDisable(true);
        olderBtn.setText("加载中…");
        new Thread(() -> {
            int n = engine.loadOlder(30);
            Platform.runLater(() -> {
                loadingOlder = false;
                olderBtn.setText("⏳ 更早邮件");
                reload();
                alert(n > 0 ? "已加载更早的 " + n + " 封邮件" : "没有更早的邮件了");
            });
        }).start();
    }

    /* ------------------------- 账户 ------------------------- */

    private void openAddAccount(Stage owner) {
        Dialog<Void> d = new Dialog<>();
        d.setTitle("添加邮箱账户");
        d.initOwner(owner);
        TextField email = new TextField();
        email.setPromptText("邮箱地址，如 123456@qq.com");
        ComboBox<String> provider = new ComboBox<>();
        provider.getItems().addAll(ProviderPresets.keys());
        provider.setValue("QQ");
        TextField pwd = new TextField();
        pwd.setPromptText("授权码 / 应用密码（不是登录密码）");
        Label hint = new Label();
        hint.setWrapText(true);
        Runnable updateHint = () -> {
            ProviderPresets.Preset p = ProviderPresets.get(provider.getValue());
            hint.setText(p == null ? "自定义：请在代码中填写服务器信息，或后续扩展配置" : p.hint);
        };
        provider.setOnAction(e -> updateHint.run());
        updateHint.run();

        VBox box = new VBox(8,
                new Label("邮箱地址"), email,
                new Label("服务商"), provider,
                new Label("凭证（授权码/应用密码）"), pwd,
                hint);
        box.setPadding(new Insets(10));
        d.getDialogPane().setContent(box);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        d.setResultConverter(bt -> {
            if (bt == ButtonType.OK) {
                String em = email.getText().trim();
                String pw = pwd.getText().trim();
                if (em.isEmpty() || pw.isEmpty()) { alert("邮箱和凭证都不能为空"); return null; }
                try {
                    engine.exitDemoMode();   // 录入真实账户后自动回到真实模式
                    engine.addAccount(em, provider.getValue(), pw);
                    engine.startAutoRefresh(60);
                    refreshAsync(owner);
                } catch (Exception ex) {
                    if (!engine.hasRealAccounts()) engine.enterDemoMode(); // 失败则退回演示模式
                    alert("添加失败: " + ex.getMessage());
                }
            }
            return null;
        });
        d.showAndWait();
    }

    /* ------------------------- 写邮件 / 草稿 ------------------------- */

    /**
     * 写邮件对话框。draft 非空时为草稿续写（带 id，发送成功后删草稿）。
     * 三个出口：发送 / 取消（有内容自动存草稿，不打扰）/ 存草稿（明示保存）。
     */
    private void openCompose(Stage owner, String[] draft) {
        Dialog<Void> d = new Dialog<>();
        d.setTitle(draft == null ? "写邮件" : "编辑草稿");
        d.initOwner(owner);
        fromBox = new ComboBox<>();
        fromBox.getItems().addAll(engine.getAccounts());
        fromBox.setConverter(new StringConverter<>() {
            public String toString(EmailAccount a) { return a == null ? "" : a.getEmail(); }
            public EmailAccount fromString(String s) { return null; }
        });
        if (!fromBox.getItems().isEmpty()) fromBox.setValue(fromBox.getItems().get(0));
        TextField to = new TextField(); to.setPromptText("收件人邮箱");
        TextField subject = new TextField(); subject.setPromptText("主题");
        TextArea body = new TextArea(); body.setPrefRowCount(10);

        // 草稿续写：恢复字段与发件账户
        long draftId = -1;
        if (draft != null) {
            draftId = Long.parseLong(draft[0]);
            to.setText(nz(draft[3]));
            subject.setText(nz(draft[4]));
            body.setText(nz(draft[5]));
            for (int i = 0; i < fromBox.getItems().size(); i++) {
                if (fromBox.getItems().get(i).getId().equals(draft[1])) {
                    fromBox.setValue(fromBox.getItems().get(i));
                    break;
                }
            }
        }

        VBox box = new VBox(8,
                new Label("发件账户（选哪个邮箱发出）"), fromBox,
                new Label("收件人"), to,
                new Label("主题"), subject,
                new Label("正文"), body);
        box.setPadding(new Insets(10));
        d.getDialogPane().setContent(box);
        ButtonType SAVE = new ButtonType("存草稿", ButtonBar.ButtonData.LEFT);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, SAVE, ButtonType.CANCEL);
        final long finalDraftId = draftId;
        d.setResultConverter(bt -> {
            if (bt == ButtonType.OK && fromBox.getValue() != null) {
                String t = to.getText().trim();
                if (t.isEmpty()) { alert("收件人不能为空"); return null; }
                try {
                    engine.sendMail(fromBox.getValue().getId(), t, subject.getText(), body.getText());
                    if (finalDraftId > 0) engine.deleteDraft(finalDraftId); // 发送成功删草稿
                    alert(engine.isDemoMode()
                            ? "✅ 演示模式：已模拟发送给 " + t + "\n（未真正联网，仅本地演练）"
                            : "已发送 ✅");
                } catch (Exception ex) { alert("发送失败: " + ex.getMessage()); }
            } else if (bt == SAVE) {
                engine.saveDraft(finalDraftId, fromBox.getValue() == null ? "" : fromBox.getValue().getId(),
                        to.getText(), subject.getText(), body.getText());
                alert("已存入草稿箱");
            } else if (bt == ButtonType.CANCEL) {
                // 有内容自动存草稿（与安卓端「返回即存」一致），静默保存不弹窗
                if (!to.getText().trim().isEmpty() || !subject.getText().trim().isEmpty()
                        || !body.getText().trim().isEmpty()) {
                    engine.saveDraft(finalDraftId, fromBox.getValue() == null ? "" : fromBox.getValue().getId(),
                            to.getText(), subject.getText(), body.getText());
                }
            }
            return null;
        });
        d.showAndWait();
    }

    /** 草稿箱：列表 + 继续编辑 / 删除 / 清空 */
    private void openDrafts(Stage owner) {
        List<String[]> drafts = engine.getDrafts();
        if (drafts.isEmpty()) {
            alert("草稿箱是空的。\n写邮件时点「存草稿」，或直接点取消，都会自动保存。");
            return;
        }
        Dialog<Void> d = new Dialog<>();
        d.setTitle("草稿箱（" + drafts.size() + "）");
        d.initOwner(owner);
        ListView<String> lv = new ListView<>();
        for (String[] x : drafts) {
            String subj = (x[4] == null || x[4].trim().isEmpty()) ? "(无主题)" : x[4].trim();
            String who = (x[3] == null || x[3].trim().isEmpty()) ? "(无收件人)" : x[3].trim();
            lv.getItems().add(subj + "  →  " + who + "    [id " + x[0] + "]");
        }
        lv.setPrefWidth(520);
        lv.setPrefHeight(300);

        Button editBtn = new Button("继续编辑");
        Button delBtn = new Button("删除");
        Button clearBtn = new Button("清空");
        editBtn.setOnAction(e -> {
            int i = lv.getSelectionModel().getSelectedIndex();
            if (i < 0) { alert("先选一封草稿"); return; }
            d.close();
            openCompose(owner, drafts.get(i));
        });
        delBtn.setOnAction(e -> {
            int i = lv.getSelectionModel().getSelectedIndex();
            if (i < 0) { alert("先选一封草稿"); return; }
            engine.deleteDraft(Long.parseLong(drafts.get(i)[0]));
            d.close();
            openDrafts(owner); // 刷新重开
        });
        clearBtn.setOnAction(e -> {
            for (String[] x : engine.getDrafts()) engine.deleteDraft(Long.parseLong(x[0]));
            d.close();
        });
        HBox btns = new HBox(8, editBtn, delBtn, clearBtn);
        VBox box = new VBox(10, new Label("双击或点「继续编辑」续写；发送成功后草稿自动移除"), lv, btns);
        box.setPadding(new Insets(12));
        lv.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && lv.getSelectionModel().getSelectedIndex() >= 0) {
                d.close();
                openCompose(owner, drafts.get(lv.getSelectionModel().getSelectedIndex()));
            }
        });
        d.getDialogPane().setContent(box);
        d.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        d.showAndWait();
    }

    /* ------------------------- 回收站 ------------------------- */

    /** 回收站：恢复 / 彻底删除（二次确认）/ 清空 */
    private void openTrash(Stage owner) {
        List<EmailMessage> trash = engine.getTrash();
        if (trash.isEmpty()) {
            alert("回收站是空的。\n在邮件列表右键「移入回收站」即可软删除（可恢复）。");
            return;
        }
        Dialog<Void> d = new Dialog<>();
        d.setTitle("回收站（" + trash.size() + "）");
        d.initOwner(owner);
        ListView<EmailMessage> lv = new ListView<>(FXCollections.observableArrayList(trash));
        lv.setCellFactory(p -> new ListCell<>() {
            @Override
            protected void updateItem(EmailMessage m, boolean empty) {
                super.updateItem(m, empty);
                setText(empty || m == null ? null
                        : (m.getSubject() == null ? "(无主题)" : m.getSubject())
                          + "  —  " + m.getFrom());
            }
        });
        lv.setPrefWidth(560);
        lv.setPrefHeight(320);

        Button restoreBtn = new Button("恢复");
        Button purgeBtn = new Button("彻底删除");
        Button clearBtn = new Button("清空回收站");
        restoreBtn.setOnAction(e -> {
            EmailMessage m = lv.getSelectionModel().getSelectedItem();
            if (m == null) { alert("先选一封"); return; }
            engine.restore(m.getId());
            d.close();
            openTrash(owner);
        });
        purgeBtn.setOnAction(e -> {
            EmailMessage m = lv.getSelectionModel().getSelectedItem();
            if (m == null) { alert("先选一封"); return; }
            if (confirm("彻底删除「" + (m.getSubject() == null ? "" : m.getSubject())
                    + "」？\n删除后无法恢复。")) {
                engine.purge(m.getId());
                d.close();
                openTrash(owner);
            }
        });
        clearBtn.setOnAction(e -> {
            if (confirm("清空回收站？全部 " + trash.size() + " 封将彻底删除，无法恢复。")) {
                engine.purgeAllTrash();
                d.close();
            }
        });
        HBox btns = new HBox(8, restoreBtn, purgeBtn, clearBtn);
        VBox box = new VBox(10, new Label("软删除只动本地，服务器原件不动——恢复即可回到收件箱"), lv, btns);
        box.setPadding(new Insets(12));
        d.getDialogPane().setContent(box);
        d.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        d.showAndWait();
    }

    /* ------------------------- AI 设置 ------------------------- */

    private void openAiSettings(Stage owner) {
        AiSettings ai = engine.getAiSettings();
        Dialog<Void> d = new Dialog<>();
        d.setTitle("AI 智能分类");
        d.initOwner(owner);
        CheckBox en = new CheckBox("启用 AI 分类（关闭则用本地规则）");
        en.setSelected(ai.enabled);
        TextField key = new TextField(ai.apiKey);
        key.setPromptText("API Key（仅存本机 ~/.mailmaster/ai.properties）");
        TextField base = new TextField(ai.baseUrl);
        base.setPromptText("https://api.deepseek.com/v1");
        TextField model = new TextField(ai.model);
        model.setPromptText("deepseek-chat");
        Button testBtn = new Button("测试连接");
        Label testResult = new Label();

        VBox box = new VBox(8, en,
                new Label("API Key"), key,
                new Label("Base URL（OpenAI 兼容）"), base,
                new Label("模型名"), model,
                testBtn, testResult);
        box.setPadding(new Insets(12));
        testBtn.setOnAction(e -> {
            testResult.setText("测试中…");
            AiSettings tmp = new AiSettings();
            tmp.enabled = true;
            tmp.apiKey = key.getText().trim();
            tmp.baseUrl = base.getText().trim();
            tmp.model = model.getText().trim();
            new Thread(() -> {
                String err = Classifier.testConnection(tmp);
                Platform.runLater(() -> testResult.setText(err == null
                        ? "✅ 连接成功，分类正常" : "❌ " + err));
            }).start();
        });
        d.getDialogPane().setContent(box);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        d.setResultConverter(bt -> {
            if (bt == ButtonType.OK) {
                AiSettings s = new AiSettings();
                s.enabled = en.isSelected();
                s.apiKey = key.getText().trim();
                s.baseUrl = base.getText().trim();
                s.model = model.getText().trim();
                engine.setAiSettings(s);
                refreshAsync(owner); // 用新设置重新分类一遍
            }
            return null;
        });
        d.showAndWait();
    }

    /* ------------------------- 演示模式 ------------------------- */

    /** 演示模式 <-> 真实模式 切换 */
    private void toggleDemo(ToggleButton btn, Stage stage) {
        if (btn.isSelected()) {
            engine.enterDemoMode();
        } else {
            if (!engine.hasRealAccounts()) {
                alert("还没有录入真实邮箱。\n请先点「+ 添加账户」，用授权码录入一个邮箱后，再退出演示模式。");
                btn.setSelected(true);
                return;
            }
            engine.exitDemoMode();
            engine.startAutoRefresh(60); // 退出演示模式后才开始真实轮询
            refreshAsync(stage);
        }
        reload();
    }

    private void alert(String msg) {
        new Alert(Alert.AlertType.INFORMATION, msg).showAndWait();
    }

    private boolean confirm(String msg) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, msg, ButtonType.OK, ButtonType.CANCEL);
        Optional<ButtonType> r = a.showAndWait();
        return r.isPresent() && r.get() == ButtonType.OK;
    }

    private static String nz(String s) {
        return (s == null) ? "" : s;
    }

    public static void main(String[] args) { launch(args); }
}
