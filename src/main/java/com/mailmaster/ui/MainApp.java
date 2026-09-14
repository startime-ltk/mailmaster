package com.mailmaster.ui;

import com.mailmaster.core.MailEngine;
import com.mailmaster.core.config.ProviderPresets;
import com.mailmaster.core.model.EmailAccount;
import com.mailmaster.core.model.EmailMessage;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.layout.Priority;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.util.StringConverter;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 邮箱大师桌面端入口（JavaFX）。
 * 本类只做"界面与交互"，所有邮件逻辑都委托给 core.MailEngine —— 这样未来做 Web / 小程序时，
 * 只需复用 MailEngine，不用动这一层。
 */
public class MainApp extends Application {
    private MailEngine engine;
    private final ObservableList<EmailMessage> messages = FXCollections.observableArrayList();
    private TableView<EmailMessage> table;
    private WebView viewer;
    private ComboBox<EmailAccount> fromBox;

    @Override
    public void start(Stage stage) {
        String dbPath = System.getProperty("user.home") + "/.mailmaster/db";
        engine = new MailEngine(dbPath);
        engine.setOnUpdate(() -> Platform.runLater(this::reload));
        engine.startAutoRefresh(60);

        // 顶部工具栏
        Button addBtn = new Button("+ 添加账户");
        addBtn.setOnAction(e -> openAddAccount(stage));
        Button composeBtn = new Button("✎ 写邮件");
        composeBtn.setOnAction(e -> openCompose(stage));
        Button refreshBtn = new Button("⟳ 刷新");
        refreshBtn.setOnAction(e -> {
            try { engine.refresh(); } catch (Exception ex) { alert("刷新失败: " + ex.getMessage()); }
        });
        ToolBar tb = new ToolBar(addBtn, composeBtn, refreshBtn);

        // 统一收件箱表格
        table = new TableView<>();
        TableColumn<EmailMessage, String> cFrom = new TableColumn<>("发件人");
        cFrom.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().getFrom()));
        cFrom.setPrefWidth(200);
        TableColumn<EmailMessage, String> cSubject = new TableColumn<>("主题");
        cSubject.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().getSubject()));
        cSubject.setPrefWidth(320);
        TableColumn<EmailMessage, String> cAccount = new TableColumn<>("账户");
        cAccount.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().getAccountEmail()));
        cAccount.setPrefWidth(140);
        TableColumn<EmailMessage, String> cDate = new TableColumn<>("时间");
        cDate.setCellValueFactory(d -> {
            var dt = d.getValue().getSentDate();
            String s = dt == null ? "" : dt.toInstant().atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"));
            return new SimpleStringProperty(s);
        });
        cDate.setPrefWidth(110);
        table.getColumns().addAll(cFrom, cSubject, cAccount, cDate);
        table.setItems(messages);
        table.setPlaceholder(new Label("暂无邮件，先点左上角“添加账户”"));
        table.getSelectionModel().selectedItemProperty().addListener((o, oldV, m) -> showMessage(m));

        viewer = new WebView();
        viewer.setPrefWidth(420);

        VBox left = new VBox(tb, table);
        left.setSpacing(4);
        VBox.setVgrow(table, Priority.ALWAYS);

        BorderPane root = new BorderPane();
        root.setCenter(left);
        root.setRight(viewer);
        BorderPane.setMargin(viewer, new Insets(4));

        Scene scene = new Scene(root, 1024, 640);
        stage.setTitle("邮箱大师 MailMaster");
        stage.setScene(scene);
        stage.show();

        reload();
    }

    private void reload() {
        messages.setAll(engine.getInbox());
    }

    private void showMessage(EmailMessage m) {
        if (m == null) {
            viewer.getEngine().loadContent("<p style='color:#888'>未选择邮件</p>");
            return;
        }
        String html = m.getBodyHtml() != null
                ? m.getBodyHtml()
                : "<pre style='white-space:pre-wrap;font-family:sans-serif'>" + escape(m.getBodyText()) + "</pre>";
        viewer.getEngine().loadContent(html, "text/html");
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void openAddAccount(Stage owner) {
        Dialog<Void> d = new Dialog<>();
        d.setTitle("添加邮箱账户");
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
                    engine.addAccount(em, provider.getValue(), pw);
                    engine.refresh();
                } catch (Exception ex) { alert("添加失败: " + ex.getMessage()); }
            }
            return null;
        });
        d.showAndWait();
    }

    private void openCompose(Stage owner) {
        Dialog<Void> d = new Dialog<>();
        d.setTitle("写邮件");
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

        VBox box = new VBox(8,
                new Label("发件账户（选哪个邮箱发出）"), fromBox,
                new Label("收件人"), to,
                new Label("主题"), subject,
                new Label("正文"), body);
        box.setPadding(new Insets(10));
        d.getDialogPane().setContent(box);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        d.setResultConverter(bt -> {
            if (bt == ButtonType.OK && fromBox.getValue() != null) {
                try {
                    engine.sendMail(fromBox.getValue().getId(), to.getText().trim(), subject.getText(), body.getText());
                    alert("已发送 ✅");
                } catch (Exception ex) { alert("发送失败: " + ex.getMessage()); }
            }
            return null;
        });
        d.showAndWait();
    }

    private void alert(String msg) {
        new Alert(Alert.AlertType.INFORMATION, msg).showAndWait();
    }

    public static void main(String[] args) { launch(args); }
}
