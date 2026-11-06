# 发布签名与 GitHub 自动打包

更新：2026-11-06。应用版本仍为 `0.5.0-alpha.2` / 9；这次补齐分发流程，不代表 alpha 已达到稳定版标准。已有 GitHub Releases、APK、标签和开发密钥保持原样。

## 自动构建入口

工作流：[Android CI and signed APK](https://github.com/spudhero/ShufangTV/actions/workflows/android.yml)，源码位于 [android.yml](../.github/workflows/android.yml)。旧的 `ci/android.yml` 模板已由实际工作流替代。

| 触发方式 | 检查与产物 |
| --- | --- |
| 推送 main | JVM / 原生界面测试、debug lint / APK、release lint / APK，再用固定 release 密钥签名 |
| Pull request 到 main | 测试、debug lint / APK 和报告；不提供发布密钥、不生成发布签名包 |
| 推送 `v*` 标签 | 标签提交必须已包含在 main，标签必须等于 `v` + APK 的 versionName；通过后生成发布签名包 |
| Actions 页面 Run workflow | 选择 main，生成同样的发布签名包；其他开发分支不签名 |

只修改 Markdown、`docs/` 或 `design/` 的分支推送 / PR 会跳过构建；标签和手动触发仍可构建。主干和标签构建不会相互取消；同一 PR 的旧构建可以取消。

Actions 成功后，在该次运行的 **Artifacts** 下载 `ShufangTV-release-<提交 SHA>`，解压后包含：

- `ShufangTV-<versionName>-release.apk`：非 debuggable，包含 ARMv7、ARM64、x86、x86_64。
- `SHA256SUMS`：APK、元数据及公开证书的文件校验值。
- `release-metadata.json`：版本、源码提交、ABI、APK SHA-256 和签名证书 SHA-256。
- `release-certificate.pem`：公开证书，不含私钥。

发布产物保留 90 天；debug APK / 检查报告保留 14 天。下载 Actions 产物通常需要登录 GitHub。此工作流负责打包和保存产物，**不会自动创建或替换 GitHub Release**；长期分发仍需单独发布。历史下载页继续保留原开发签名测试版。

Actions 的 debug APK 使用 runner 的临时开发证书，仅用于诊断，不能作为持续升级通道；需要固定签名的下载请选择 release 产物。

## 固定的发布身份

首个 release 密钥：RSA 3072 / SHA256withRSA，PKCS12，别名 `shufangtv-release`，有效期 10,950 天。公开证书见 [release-certificate.pem](signing/release-certificate.pem)。证书 DER 的 SHA-256：

```text
72ef3a39e3ea77a6abd31f8ebade251c7e935dc1e8f1e0fcafb2ac1a6130f41f
```

`release-certificate.sha256` 保存的是 DER 证书指纹，不是 PEM 文本文件哈希。生成、上传与打包工具都会核对这个身份；仅改别名、密码或文件名不能代替正确私钥。[Android 签名说明](https://developer.android.com/studio/publish/app-signing)。

私钥及随机密码只在本地忽略目录 `local-test-data/signing/release/` 和 GitHub Actions Secrets 中保存。本地目录权限 700、PKCS12 与 `credentials.json` 权限 600。公开证书可提交；私钥、密码、Base64 密钥内容及测试签名不得提交。

**请将 PKCS12 与 credentials.json 一起另行安全备份。** 当前机器上的文件不是异地备份；GitHub Secrets 不支持读回原值，不能作为密钥恢复方案。密钥遗失后不能凭公开证书重新生成同一签名，也不应为后续版本随意换钥。

## 本地构建发布包

与 README 一致，需要 JDK 17、SDK / Build Tools 36、NDK 27.2.12479018、CMake 3.22.1，正确设置 `JAVA_HOME` 和 `ANDROID_HOME`。

```sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:lintRelease :app:assembleRelease
python3 scripts/package-release.py --signing-directory local-test-data/signing/release
```

Gradle 只构建 unsigned release APK，发布密钥不会传给 Gradle 或进入其配置缓存。`package-release.py` 随后校验包名、非 debug 状态、版本和四种 ABI，执行 zipalign，再用 apksigner 签名并核对提交的证书。缺少密钥、错误证书、版本标签不匹配或缺少 ABI 都会失败，不把未签名 APK 当作发布包。

本地输出默认位于忽略目录 `artifacts/release/`；可通过 `--output` 指定另一个目录。初次建立本项目发布身份使用 `scripts/generate-release-key.py`；**当前身份已经建立，不要再次生成**。工具发现证书或私钥目录已存在会拒绝执行，换机器应恢复备份。

## GitHub Secrets 与恢复

仓库 `spudhero/ShufangTV` 已配置以下 Actions Secrets：

| Secret | 用途 |
| --- | --- |
| `ANDROID_RELEASE_KEYSTORE_BASE64` | PKCS12 二进制的 Base64 表示 |
| `ANDROID_RELEASE_STORE_PASSWORD` | PKCS12 密码 |
| `ANDROID_RELEASE_KEY_ALIAS` | `shufangtv-release` |
| `ANDROID_RELEASE_KEY_PASSWORD` | 私钥密码，当前与容器密码相同 |

Base64 是编码；实际保密依赖 GitHub Secrets 的加密存储与受控注入。[GitHub Secrets 文档](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets)。

使用已有 spudhero 登录并恢复同一份私钥后，可以重新安装 Secrets：

```sh
python3 scripts/configure-release-secrets.py --update-existing
```

脚本显式选取 `spudhero` 账号，核对公开证书，再通过标准输入传值，不在参数或日志中打印密码。首次配置可省略 `--update-existing`；仓库已有同名 Secret 时默认拒绝覆盖。

签名步骤只运行于本仓库 main 或已验证的版本标签；普通 PR、fork 和其他开发分支不签名，不使用 `pull_request_target`。Actions 默认只有 `contents: read`，第三方 Actions 固定到官方发布的完整提交 SHA。密钥在 runner 临时目录中以受限权限解码，正常、失败路径都会删除临时文件；上传路径只包含公开产物与报告。

## 安装与升级边界

电视当前 `0.5.0-alpha.2` 是原开发签名，证书 SHA-256 为 `9aeb612cabfb2d39bd0d7b40f212a562c0aee3440a9bd4b84e376f335ab6e0e0`。新 release 签名与其不同，Android 会拒绝直接覆盖安装；同一包名和更大版本号也不能解决签名不一致。

本次没有卸载或更换电视上的开发版本。正式切换签名前应确认书库、书签、来源及语音配置的迁移方式；应用目前没有完整数据导入 / 导出能力，不应通过卸载来进行无备份的迁移。此前的 debug APK 和 GitHub Releases 保留原证书与历史记录。后续 release 构建必须持续使用本次 release 密钥，并在更新应用时递增 versionCode。

CI 检查不能替代电视实测：原生 PDF / Kindle、离线语音的设备结果继续以 [验证记录](VALIDATION.md) 和专项实机报告为准。本次只改构建工具和文档，未改变阅读逻辑。

## 本轮证据

- 本地 224 项测试通过；debug / release lint 各 0 错误、19 警告；两种构建成功。
- 本地发布签名包的四 ABI、非 debug、APK v2/v3、证书与 zipalign 验证通过。
- 防止覆盖已有密钥、无凭据打包、误用 debug 包、错误证书、错误版本标签和单个 ABI 缺失原生库等失败路径已检查。
- GitHub 首次实际运行结果在流程启用后补充；不能把本地成功等同于 GitHub 构建成功。
