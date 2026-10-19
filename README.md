# 邮箱大师 MailMaster

多邮箱聚合桌面客户端（JavaFX）。把多个邮箱录入后，统一查看所有邮箱的收件箱，并可以**任选邮箱地址发信**。

> 本项目为轻量化学习 / 参赛作品：Java + 本地存储、定位不商业化，重在把"多邮箱聚合"的工程链路跑通。

## 功能
- 多账户录入（QQ / 163 / Outlook / 自定义）
- **统一收件箱**：聚合所有邮箱新邮件，按时间倒序展示
- 写邮件时**任选发件账户**发出
- 凭证 **AES 加密**本地存储，绝不明文
- 定时自动刷新（默认 60 秒）
- **UID 增量同步**：日常只拉新邮件；首次/邮箱重建拉最近 100 封；更早历史「加载更早」按需翻页
- **智能分类**：本地规则兜底 + AI 批量分类（OpenAI 兼容接口，自填 Key，失败自动回退本地规则）
- **回收站**：软删除 / 恢复 / 彻底删除 / 清空
- **草稿箱**：写邮件退出自动保存、续写、发送后自动清除
- **已读回写服务器**：打开即已读，换设备不再变未读
- **单封重拉正文**：治「只有标题没内容」

## 技术栈
- Java 20 + JavaFX 20（界面）
- `javax.mail` 1.6.2（IMAP 收信 / SMTP 发信）
- H2 本地数据库（账户 + 邮件缓存）
- JDK 自带 AES 加密凭证

## 架构（为"多端"预留）
```
core（纯 Java，不依赖任何 UI）         ← 多端共享
 ├─ model      账户 / 邮件 模型（含 UID / 分类）
 ├─ config     服务商连接预设（QQ/163/Outlook/Gmail）
 ├─ crypto     AES 加密
 ├─ service    H2 存储（账户+邮件缓存+水位+草稿）/ IMAP 收 / SMTP 发 / AI 设置
 ├─ Classifier 智能分类（本地规则 + AI 批量）
 └─ MailEngine 门面，统一对外
ui（JavaFX，本期实现）
 └─ 仅做界面与交互，全部逻辑委托 core
web（JDK HttpServer，本期实现）
 └─ 把 core 包成 REST + 静态页面，手机浏览器直接访问
```
**多端预留关键**：所有邮件逻辑只在 `core`，UI 只是壳。桌面端、网页版共用同一套 `core`，
与安卓端（`android/`）保持同构：`MessageStore ↔ MailCache v3`、`MailReceiver ↔ MailService`、`Classifier ↔ Classifier`。

## 如何添加邮箱
### QQ 邮箱
> ⚠️ 授权码**只能在「电脑版完整网页邮箱」里生成**：手机 QQ 邮箱 App、手机打开的简化版网页**都没有这个入口**
> （App 内只能管理/停用已有授权码，不能首次开启服务）。
> 若手边只有手机，走这两条路（推荐按顺序试）：
>
> **① QQ 邮箱 App 内直接生成（最省事）**
> 左上角 **头像** → 点你的 **账号** 那一行 → **安全管理** → **设备管理** → **新增/生成授权码** → 短信验证 → 复制。
> 若这里没有"生成"按钮，说明 **IMAP/SMTP 服务还没开启**，请走 ②。
>
> **② 手机浏览器强行加载电脑版（解决"看不到选项"的关键）**
> - 安卓 Chrome / Edge / QQ浏览器：右上角 `⋮` → 勾选 ☑️ **「桌面版网站」**
> - iPhone Safari：点地址栏左侧 **「AA」** → **「请求桌面网站」**
>
> 页面会变成宽屏电脑界面；若仍为精简版，留意页面**底部或右下角的「标准版」「电脑版」**字样，点一下切换。
> 进入电脑版后：右上角 **设置（齿轮）** → **账号与安全**（旧版叫「账户」）→ 下滑找到
> **「POP3/IMAP/SMTP/Exchange/CardDAV 服务」** → **先点「开启」** → 才会出现 **「生成授权码」** → 短信验证 → 复制 16 位码。
>
> **③ 实在不行**：借一台电脑 / 学校机房或图书馆电子阅览室操作，全程约 3 分钟。

1. 浏览器登录 [mail.qq.com](https://mail.qq.com)，右上角头像 → **设置** → **账号与安全**
2. 找到 **POP3/IMAP/SMTP/Exchange/CardDAV 服务** 区域，点击 **开启**
3. 按页面提示用绑定的手机号**发短信验证**（或微信/QQ 扫码）
4. 验证通过后弹出 **16 位授权码**，立即复制保存（**只显示一次**）
5. 软件点"添加账户" → 选 QQ → 邮箱填完整地址、密码栏填这串**授权码**（不是 QQ 密码）

### 163 邮箱（@163 / @126 / @yeah / @188 同理）
> ⚠️ 同样是**电脑版完整网页**才行；手机打开若始终是简化版，用浏览器「桌面版网站」模式即可。

1. 浏览器登录 [mail.163.com](https://mail.163.com)，顶部 **设置** → **POP3/SMTP/IMAP**
2. 开启 **IMAP/SMTP 服务**
3. 手机短信验证后生成 **16 位授权码**（只显示一次，**有效期约 180 天**）
4. 软件点"添加账户" → 选 163 → 填邮箱与**授权码**（不是网易登录密码）

### Outlook / Hotmail
1. 登录 [account.microsoft.com](https://account.microsoft.com) → **安全** → **高级安全选项**
2. 先开启 **两步验证**（无 2FA 无法生成应用密码）
3. 在"应用密码"处 **创建新应用密码**，得到一串 16 位密码
4. 软件选 Outlook，邮箱填地址、密码栏填**应用密码**（SMTP 自动用 587 + STARTTLS）

> 通用提醒（排错按顺序看）：
> 1. **顺序不能反**：必须**先开启 IMAP/SMTP 服务**，页面**才会出现「生成授权码」按钮**。
>    找不到生成按钮，99% 是因为服务还关着。
> 2. 授权码/应用密码 **≠ 邮箱登录密码**；生成后**只显示一次**，立即复制保存（关掉再也看不到，只能重生成）。
> 3. 若邮箱**未绑定手机号**，短信验证无法完成 → 需先在网页端绑定手机，否则生成不了。
> 4. 页面版本差异：新版在 **「账号与安全」**，旧版在 **「账户」**，找不到就两个地方都翻一下。
> 5. QQ 改密码会使**所有授权码失效**需重生成；163 授权码有效期约 180 天。

### Gmail（本期暂未实现）
Gmail 需 OAuth2 授权且国内需代理，`ProviderPresets` 已留接口，后续版本接入。

## 构建与运行
```bash
cd mailmaster

# 桌面端（JavaFX）
mvn clean javafx:run

# 网页版（手机浏览器可访问）
mvn clean compile exec:java                  # 默认端口 8080
mvn clean compile exec:java -Dexec.args=9090 # 指定端口 9090
```
- 网页版启动后：本机访问 `http://localhost:8080`；**手机与电脑同一 WiFi 时**，
  访问 `http://<电脑局域网IP>:8080` 即可当 App 使用（界面为移动端样式）。
- 网页版与桌面端**共用同一套账户库**（`~/.mailmaster/db`）：任一端添加账户，另一端也能看到。
- 网页版对外暴露的 REST（v1.20 起与桌面端功能拉齐）：
  - 状态/账户：`GET /api/status`、`GET|POST /api/accounts`、`DELETE /api/accounts/{id}`
  - 收件箱：`GET /api/inbox`、`GET /api/messages/{id}`（含分类 category / UID / hasContent）
  - 单封操作：`POST /api/messages/{id}/read`（已读回写）、`POST /api/messages/{id}/trash`、`POST /api/messages/{id}/retry`（重拉正文）
  - 回收站：`GET /api/trash`、`POST /api/trash/restore`、`DELETE /api/trash/{id}`、`POST /api/trash/purge-all`
  - 草稿箱：`GET|POST /api/drafts`、`DELETE /api/drafts/{id}`
  - 历史翻页：`GET|POST /api/older`
  - 发送/演示：`POST /api/send`、`POST /api/demo/toggle`
  - AI 分类：`GET|POST /api/ai`、`POST /api/ai/test`
  - 要做小程序后端或自建前端，直接复用这套接口即可。

```bash
mvn clean package          # 打包（可用 jpackage 进一步生成安装包）
```

## 最简使用：单文件 Jar（本机只需装 JDK）
不想装 Maven、不想敲一长串命令？项目已打包出 **`dist/MailMaster.jar`**（约 5.7MB，已含全部依赖，且不含 JavaFX，Windows / macOS / Linux 通用）：
```bash
java -jar dist/MailMaster.jar          # 默认 8080 端口
java -jar dist/MailMaster.jar 9090    # 指定端口 9090
```
启动后本机浏览器开 `http://localhost:8080`；**手机与电脑同一 WiFi 时**开 `http://<电脑局域网IP>:8080` 即可当 App 使用。
> 重新生成该 jar：`mvn clean package`（产物在 `target/mailmaster-1.21.jar`，已自动复制到 `dist/`）。

## 演示模式（没有授权码也能先看效果）
授权码还没搞定、或只想先看看界面长什么样时，直接启动即可：
```bash
mvn clean javafx:run
```
- 桌面端或网页版在本机**还没录入任何真实账户**时，都会自动进入演示模式：
  收件箱里铺满示例数据 —— **3 个示例邮箱 + 7 封邮件**（覆盖 QQ / 163 / Outlook 三种服务商）。
- 窗口标题会显示「演示模式（示例数据，未连接真实邮箱）」；工具栏的 🎭 **演示模式** 按钮可随时来回切换。
- 演示模式下点「写邮件」只会**模拟发送**（控制台打印一行日志），**不会真的联网发信**，可以放心点。
- 一旦录入真实账户，会自动退出演示模式，并开始真实轮询（默认每 60 秒）。

> 示例内容全部由 `core/demo/DemoData.java` 生成。想改示例邮件，只改这一个文件即可，
> 不会碰到任何真实收发逻辑。

## 安全提示
- 凭证以 AES 加密存于 `~/.mailmaster/db.mv.db`
- 生产环境请设置环境变量 `MAILMASTER_AES_KEY`（≥16 字节），否则回退内置默认密钥（仅开发用）
- 切勿把含真实凭证的数据库文件提交到代码仓库

## 多端扩展路线
- **Web（已实现 ✅）**：`web/` 包用 JDK 自带 `HttpServer` 把 `core` 包成 REST + 静态页面，
  手机浏览器直接访问即可，无需额外框架。逻辑全部复用 `core`，零重写。
- **安卓 App（已实现 ✅，独立 APK）**：`core` 的模型 / AES / 演示数据 / IMAP·SMTP 思路移植为原生 Android（Java），
  用安卓兼容版 JavaMail 实现真实收发。产出 `MailMaster-debug.apk`（演示模式默认，可加真实账户）。
  工程与构建脚本见同仓库 `android/` 目录（手动 aapt2 + d8 + apksigner 构建，无需 Android Studio）。`.github/workflows/build-apk.yml` 已实现 **push 自动出 APK**：推到 GitHub 即构建并生成可下载的 `MailMaster-debug.apk`，打 `v*` tag 自动发 Release。详见 `android/README.md`。
- **小程序**：把 `web` 包部署到服务端（小程序不能直连 IMAP），小程序 UI 调本项目的 `/api/*` 接口即可
  （注意：小程序要求 HTTPS + 备案域名）。
- 三端共享 `core` 的模型、存储格式与加密方案。

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
