# 邮箱大师 · 安卓端 (MailMaster Android)

原生 Android（Java），无 AndroidX、无第三方 UI 框架，仅用系统控件。
核心逻辑（模型 / AES 加密 / 演示数据 / IMAP·SMTP）移植自主项目的 `core` 设计，验证「core 与 UI 分离、多端复用」的预留架构。

## 本项目包含
| 内容 | 说明 |
|---|---|
| `src/` | Java 源码（8 个类：MainActivity / ComposeActivity / MessageActivity / MailService / MailStore / MailAccount / MailMessage / Crypto / DemoData） |
| `res/` | 布局、菜单、配色、字符串、启动图标 |
| `libs/` | 安卓兼容版 JavaMail：`android-mail-1.6.2.jar` + `android-activation-1.6.2.jar`（已随仓库提交，CI 无需联网拉） |
| `build_apk.sh` | 手动/CI 通用构建脚本（aapt2 编译→链接→javac→d8→zipalign→apksigner） |
| `.gitignore` | 排除构建产物与密钥 |

## 能力
- 统一收件箱（演示模式默认 3 示例账户 + 7 封邮件）
- 写邮件任选账户发送（演示仅模拟；录入真实账户后真发）
- 添加 QQ / 163 / Outlook 授权码（真实 IMAP 收 + SMTP 发）
- 菜单一键切换「演示 / 真实」
- 最低 Android 5.0，目标 API 34

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
adb install MailMaster-debug.apk       # 电脑连手机
# 或把 apk 传到手机，文件管理器点击安装（需开启「允许未知来源」）
```

## 四、诚实说明
本工程 SDK 工具链与 APK 已在沙箱用离线镜像验证：`apksigner verify` 通过（v1/v2/v3 签名有效）、`classes.dex` 在根目录且含 `javax.mail.Session` / `MailService` / `MainActivity` 等关键类。
**沙箱无安卓设备/模拟器，无法真机实跑**，请在手机上装一次确认；有异常把现象发我，据此修。
