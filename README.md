# 邮箱大师 MailMaster

多邮箱聚合桌面客户端（JavaFX）。把多个邮箱录入后，统一查看所有邮箱的收件箱，并可以**任选邮箱地址发信**。

> 本项目为轻量化学习 / 参赛作品，定位与"糖伴 SugarPal"一致：Java + 本地存储、不商业化。

## 功能
- 多账户录入（QQ / 163 / Outlook / 自定义）
- **统一收件箱**：聚合所有邮箱新邮件，按时间倒序展示
- 写邮件时**任选发件账户**发出
- 凭证 **AES 加密**本地存储，绝不明文
- 定时自动刷新（默认 60 秒）

## 技术栈
- Java 20 + JavaFX 20（界面）
- `javax.mail` 1.6.2（IMAP 收信 / SMTP 发信）
- H2 本地数据库（账户 + 邮件缓存）
- JDK 自带 AES 加密凭证

## 架构（为"多端"预留）
```
core（纯 Java，不依赖任何 UI）         ← 多端共享
 ├─ model      账户 / 邮件 模型
 ├─ config     服务商连接预设（QQ/163/Outlook/Gmail）
 ├─ crypto     AES 加密
 ├─ service    H2 存储 / IMAP 收 / SMTP 发
 └─ MailEngine 门面，统一对外
ui（JavaFX，本期实现）
 └─ 仅做界面与交互，全部逻辑委托 core
```
**多端预留关键**：所有邮件逻辑只在 `core`，UI 只是壳。未来做 Web / 小程序时，
把 `core` 包成后端 API（Spring Boot 或 JDK HttpServer）即可复用全部逻辑，不用重写。

## 如何添加邮箱
### QQ 邮箱
1. 浏览器登录 [mail.qq.com](https://mail.qq.com)，右上角头像 → **设置** → **账号与安全**
2. 找到 **POP3/IMAP/SMTP/Exchange/CardDAV 服务** 区域，点击 **开启**
3. 按页面提示用绑定的手机号**发短信验证**（或微信/QQ 扫码）
4. 验证通过后弹出 **16 位授权码**，立即复制保存（**只显示一次**）
5. 软件点"添加账户" → 选 QQ → 邮箱填完整地址、密码栏填这串**授权码**（不是 QQ 密码）

### 163 邮箱（@163 / @126 / @yeah / @188 同理）
1. 浏览器登录 [mail.163.com](https://mail.163.com)，顶部 **设置** → **POP3/SMTP/IMAP**
2. 开启 **IMAP/SMTP 服务**
3. 手机短信验证后生成 **16 位授权码**（只显示一次，**有效期约 180 天**）
4. 软件点"添加账户" → 选 163 → 填邮箱与**授权码**（不是网易登录密码）

### Outlook / Hotmail
1. 登录 [account.microsoft.com](https://account.microsoft.com) → **安全** → **高级安全选项**
2. 先开启 **两步验证**（无 2FA 无法生成应用密码）
3. 在"应用密码"处 **创建新应用密码**，得到一串 16 位密码
4. 软件选 Outlook，邮箱填地址、密码栏填**应用密码**（SMTP 自动用 587 + STARTTLS）

> 通用提醒：授权码/应用密码 **≠ 邮箱登录密码**；拿到后**只显示一次**，立即复制保存；
> QQ 改密码会使授权码失效需重生成，163 授权码有效期约 180 天。

### Gmail（本期暂未实现）
Gmail 需 OAuth2 授权且国内需代理，`ProviderPresets` 已留接口，后续版本接入。

## 构建与运行
```bash
cd mailmaster
mvn clean javafx:run        # 启动桌面端
mvn clean package          # 打包（可用 jpackage 进一步生成安装包）
```

## 安全提示
- 凭证以 AES 加密存于 `~/.mailmaster/db.mv.db`
- 生产环境请设置环境变量 `MAILMASTER_AES_KEY`（≥16 字节），否则回退内置默认密钥（仅开发用）
- 切勿把含真实凭证的数据库文件提交到代码仓库

## 多端扩展路线（规划）
- **Web**：用 Spring Boot 包裹 `core` 暴露 REST（`/accounts`、`/messages`、`/send`），前端任意框架调用
- **小程序**：`core` 部署到服务端（小程序不能直连 IMAP），小程序 UI 调 HTTPS API（需备案域名）
- 三端共享 `core` 的模型、存储格式与加密方案

## 提交到 GitHub（首次）
```bash
# 1. 在 GitHub 新建名为 mailmaster 的空仓库（不要勾选自动创建 README / .gitignore）
# 2. 进入本项目目录
cd mailmaster
# 3. 设置你自己的 git 身份（push 前必须改）
git config user.name  "你的名字"
git config user.email "你的邮箱"
# 4. 关联远程并推送
git remote add origin https://github.com/你的用户名/mailmaster.git
git branch -M main
git push -u origin main
```
> `.gitignore` 已排除 `target/`（编译产物）和 `*.mv.db`（本地凭证数据库），不会误提交。
> 切勿把含真实邮箱凭证的数据库文件提交到仓库；首次运行会在 `~/.mailmaster/` 生成数据库。
