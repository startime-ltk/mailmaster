# 邮箱大师 · 安卓端 (MailMaster Android)

原生 Android（Java），无 AndroidX、无第三方 UI 框架，仅用系统控件。
核心逻辑（模型 / AES 加密 / 演示数据 / IMAP·SMTP）移植自主项目的 `core` 设计，验证「core 与 UI 分离、多端复用」的预留架构。

## 本项目包含
| 内容 | 说明 |
|---|---|
| `src/` | Java 源码（13 个类：MainActivity / ComposeActivity / MessageActivity / MailService / MailStore / MailAccount / MailMessage / Attachment / MailParser / Classifier / AttachmentProvider / Crypto / DemoData） |
| `res/` | 布局、菜单、配色、字符串、启动图标 |
| `libs/` | 安卓兼容版 JavaMail：`android-mail-1.6.2.jar` + `android-activation-1.6.2.jar`（已随仓库提交，CI 无需联网拉） |
| `build_apk.sh` | 手动/CI 通用构建脚本（aapt2 编译→链接→javac→d8→zipalign→apksigner） |
| `.gitignore` | 排除构建产物与密钥 |

## 能力
- 统一收件箱（演示模式默认 3 示例账户 + 9 封示例邮件）
- **中文编码治理**：GBK / GB2312 / UTF-8 全兼容，multipart 递归解析，缺 charset 自动探测兜底
- **本地智能分类**：重要 / 推广 / 社交 / 其他，纯离线规则，邮件内容不出手机
- **跨账户统一搜索**：一次搜索所有邮箱（主题 + 发件人 + 正文 + 所属邮箱）
- **回复 / 转发**：自动带 `Re:` `Fwd:` 与引用原文，收件人自动填原发件人（会从「名称 &lt;地址&gt;」里抽出纯地址）
- **附件收取**：自动落盘，点一下交给系统里能打开它的 App（WPS / 相册 / 浏览器）
- **附件发送**：从系统文件选择器添加，中文附件名自动 encoded-word 编码
- **已读标记**：列表左侧蓝点 + 主题加粗，打开即已读，真实账户会回写服务器 IMAP SEEN 标记
- **HTML 富文本**：有 HTML 的邮件用 WebView 保留原排版（表格 / 加粗都还在），纯文本邮件回退 TextView
- **隐私细节**：WebView 一律禁止加载远程资源——邮件里的追踪像素正是靠它统计你何时开信的
- 写邮件任选账户发送（演示仅模拟；录入真实账户后真发）
- 添加 QQ / 163 / Outlook 授权码（真实 IMAP 收 + SMTP 发）
- 菜单一键切换「演示 / 真实」、一键刷新
- 最低 Android 5.0，目标 API 34

### v1.3 新增：自己写的 FileProvider
Android 7.0 起把 `file://` 甩给别的 App 会抛 `FileUriExposedException`，常规做法是引入 AndroidX 的 FileProvider。
本项目坚持零第三方依赖，所以直接用原生 `ContentProvider` 写了一个 30 行的极简版（`AttachmentProvider`），
并做了目录白名单校验：只允许读本应用私有目录，拿到临时授权的第三方 App 借不到沙箱外的任何东西。

---

## 竞品对比与差异化

> 说明：以下基于对各产品的公开认知整理。沙箱无外网，**未能核实各项目最新版本与 GitHub star 数**，
> 因此不做精确数据断言，只比较设计取向与能力差异。

| 产品 | 优点 | 缺点 | 我们的应对 |
|---|---|---|---|
| Foxmail | 轻量、国内邮箱适配好、稳定 | UI 陈旧、更新慢、几乎无智能化 | 学「轻量 + 国内适配」，在智能化与现代体验上超越 |
| 网易邮件大师 | 界面现代、推送及时、国内邮箱开箱即用 | 闭源、有推广、邮件数据经其服务器 | 本地优先：凭证与邮件只存本机，不上传 |
| Outlook | 功能全（规则 / 日历 / 协作）、生态强 | 臃肿、国内同步不稳、隐私争议 | 只做核心收发，保持轻量 |
| Gmail | 智能三分（重要/推广/社交）是行业标杆、搜索极强 | 国内不可用、不开放第三方聚合 | 本地规则复刻三分法，离线可用 |
| K-9 Mail (Thunderbird Android) | 开源、隐私、无广告 | UI 老旧、智能化弱、国内邮箱需手填 | 中文优先 + 智能分类 |
| FairEmail | 隐私极致、功能极丰富 | 设置复杂、新手劝退、中文支持一般 | 做「轻量版」：开箱即用、中文友好 |

### 它们的共性弱点 = 我们的机会
1. **中文乱码**：国际客户端对 GBK、缺失 charset 的老邮件处理普遍不佳 → 我们专门治理（已实现并实测）
2. **智能化两难**：要么没有（Foxmail / K-9），要么要联网、要 API 成本 → 我们本地规则实现，零成本、零上传
3. **隐私 vs 便利二选一**：便利的（网易 / Outlook）数据上云，隐私的（FairEmail）太复杂 → 我们默认本地 + 开箱即用
4. **臃肿或复杂**：巨头臃肿、专业工具劝退 → 我们保持轻量

### 已实测验证（不是「应该能跑」）
**v1.1～v1.2（中文治理 + 分类，9 项）**
- GBK 主题 + GBK 正文 → 正确显示中文
- multipart 邮件 → 正确提取正文（旧写法会输出 `MimeMultipart@xxx` 对象地址，已修复）
- 无 charset 声明的 GBK 老邮件 → 自动探测并正确解码
- 纯 HTML 邮件 → 正确转为纯文本
- 智能分类对 9 封演示邮件 → 重要 5 / 推广 1 / 社交 1 / 其他 2，与预期一致

**v1.3（P0 基本盘，25 项全过）**
构造一封"典型国内邮件"（GBK 主题 + alternative 正文 + 中文名 PDF 附件 + 恶意文件名附件）后：
- GBK 主题 / 发件人解码 ✅、multipart 递归取正文 ✅
- HTML 原文保留 ✅、抹掉原 `gbk` charset 声明并注入 UTF-8 ✅、表格结构保留 ✅
- 识别出 2 个附件并落盘 ✅、中文附件名正确还原 ✅
- **路径穿越被清洗**：附件名 `../../evil.txt` 被清洗成 `evil.txt`，没写出沙箱 ✅
- 纯文本邮件不会误报附件 / HTML ✅
- 回复主题不叠加 `Re:` ✅、转发 ✅、从「名称 &lt;地址&gt;」抽纯地址 ✅、引用原文带 `>` 前缀 ✅
- 分类未回归（交易类仍优先于推广）✅

### 下一步可加（P0 已做完，剩下按推荐顺序）
1. **P1**：深色模式 + 滑动手势（低成本高感知）、未读计数、归档 / 删除
2. **P1**：演示模式的「回复/转发/附件」目前只落本地提示，可补一个「发件箱」把演示邮件真的存下来，演示更闭环
3. **P2**：增量同步（按 UID 增量拉取，突破当前"最近 30 封"限制）、本地全文索引、文件夹体系（已发送 / 草稿 / 垃圾箱）
4. **P2**：把 `MailParser` + `Classifier` 下沉到 `core`，让网页版也具备分类与中文治理
5. **P3**：指纹锁 + 桌面小部件 + 新邮件通知；用 Android Keystore 替换当前硬编码的 AES 密钥（隐私卖点坐实）

---

## 一、本地手动构建 / 验证
需先装好 Android SDK（含 `build-tools;34.0.0` 与 `platforms;android-34`）与 JDK 17。

```bash
export ANDROID_HOME=/你的/android-sdk
cd android
chmod +x build_apk.sh
ANDROID_HOME=$ANDROID_HOME bash build_apk.sh
# 产出: android/MailMaster-v1.1-debug.apk（文件名自动带版本号）
```

沙箱里我用腾讯内网镜像拉了离线 SDK 跑通；本机把 `ANDROID_HOME` 指向你的 SDK 即可。

### 版本号规则（所有 apk 都带版本号）
- **唯一数据源**：`AndroidManifest.xml` 的 `android:versionName` / `android:versionCode`。
- **文件名自动跟随**：debug 包 → `MailMaster-v<版本>-debug.apk`；release 包 → `MailMaster-v<版本>.apk`。
- **升版本只改一处**：改 Manifest 里这两个值，文件名与包内版本同步更新，脚本不用动。
- **CI / 临时覆盖**：`APP_VERSION=2.0 APP_VERSION_CODE=10 bash build_apk.sh`，
  不改源文件即可产出 `MailMaster-v2.0-debug.apk`（CI 用 `github.run_number` 让 versionCode 自动递增）。

## 二、GitHub Actions 自动出 APK（关键）
仓库根已写好 `.github/workflows/build-apk.yml`，**push 到 GitHub 即自动构建**，无需手动。

### 你只需做 3 步
1. **本机加 remote 并 push 主分支**（身份现在还是占位的 `mailmaster`，先改成你自己的）：
   ```bash
   cd /workspace/mailmaster
   git config user.name   "你的GitHub用户名"
   git config user.email "你的GitHub邮箱"
   git remote add origin https://github.com/你的名/你的仓库.git
   git push -u origin main
   ```
2. 打开 `GitHub 仓库 → Actions`，会看到 **Build Android APK** 在跑；
   跑完点 **Artifacts → MailMaster-APK** 下载 `MailMaster-debug.apk`。
3. 想发正式版：打 tag 推上去即可自动建 Release 并附 apk：
   ```bash
   git tag v1.0.0
   git push origin v1.0.0
   ```

### 可选：用你自己的签名（release 包）
在 GitHub 仓库 `Settings → Secrets and variables → Actions` 增加：
- `RELEASE_KEYSTORE_BASE64`：你 keystore 的 Base64（`base64 -w0 你的.keystore`）
- `KEY_ALIAS` / `KEYSTORE_PASS` / `KEY_PASS`：别名与密码

配置了就出 `MailMaster.apk`（release 签名）；没配则自动出 `MailMaster-debug.apk`。

> 未配置这些 Secrets 也完全能自动出 apk（debug 签名），不影响体验。

## 三、安装到手机
```bash
adb install MailMaster-v1.3-debug.apk  # 电脑连手机
# 或把 apk 传到手机，文件管理器点击安装（需开启「允许未知来源」）
```

## 四、诚实说明
- **已在标准 JVM 上实测通过**：中文编码治理（4 类邮件场景）与本地智能分类（9 封演示邮件）。
- **APK 结构校验**：`apksigner verify` 通过（v1/v2/v3 签名有效），`classes.dex` 位于根目录。
- **仍未验证**：沙箱无安卓设备 / 模拟器，**真机 UI 未实跑**。请在手机上装一次确认，
  重点看三件事：分类标签是否显示、搜索是否生效、中文是否正常。有异常把现象发我，据此修。
