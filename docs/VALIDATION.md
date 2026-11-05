# 验证记录

2026-11-06 的阅读体验与 UI 工作区增量已通过 **164 项测试**及完整构建，详见 [阅读体验验证记录](READING-VALIDATION.md) 和 [界面对比](READING-UI-REVIEW.md)。这是未发布的工作区安装包。

以下保留 `0.2.0-alpha.1` 已发布版本的原验证记录与校验值。

日期：2026-11-05。构建环境：JDK 17、Gradle 8.13、Android SDK / Build Tools 36。

本记录对应 `0.2.0-alpha.1`，增加四家原生网盘扫码来源。`0.1.0-alpha.1` 的 70 项检查与已发布 APK 保留在原标签和发布页。

## 已通过

- `:app:assembleDebug`：生成可安装的开发签名 APK。
- `:app:lintDebug`：无错误；存在程序化布局和中文文字等非阻断警告。
- `:core:test`：32 项解析测试通过，覆盖 EPUB spine、中文编码、压缩包与资源边界、损坏文件和解析错误。
- 网络层：25 项受控 HTTP / 路径与重试测试通过，覆盖 Range、200 响应拒绝、ETag 变化、中文 WebDAV、原子下载、取消、认证和重连分类。
- 在线读取：7 项 JVM 测试通过，验证 8 MiB 未读 EPUB 内容不会在元数据 / 第一章打开时被传输、1 MiB 缓存上限、UTF-8 / GB18030 / UTF-16 分段往返与资源释放。
- 进度与存储：3 项 Robolectric 测试通过，验证字体变化后的逻辑位置、在线记录重载不生成离线文件、损坏书库记录禁止覆盖。
- 原生界面：2 项 Android API 31 / TV 配置的 Robolectric 测试通过，覆盖中文页面、返回操作、正文遥控器翻页、书签、退出和继续阅读。
- 模式完整流程：1 项原生 UI 与本机 HTTP 服务集成测试通过。先在线打开生成的长 TXT，确认仅发出 Range 请求且离线目录为空；随后在 UI 主动下载，确认只有一个完整文件；关闭 HTTP 服务后离线打开，确认不发请求且恢复相同文字位置。
- 四家网盘协议：41 项受控测试（阿里/百度 24、夸克/UC 17），覆盖二维码、POST 轮询、票据交换、个人盘验证、签名、续期、401 单次重试、目录分页、请求头、Cookie 隔离与更新。
- 网盘按需读取：12 项 HTTP 测试，覆盖 256 KiB 上限、严格 Range、200 响应拒绝、长度/ETag 变化、地址更新、跨源认证头隔离，以及关闭时取消活跃读取。
- 网盘模式与账号：8 项集成测试，覆盖不透明 ID、多页目录、仅显式下载写入离线副本、取消保留旧副本、退出阻止新读取和旧请求不能恢复/覆盖账号。
- 加密会话与来源：4 项存储测试，使用注入的测试 AES 密钥验证密文往返、篡改拒绝、Cookie 域/路径/Secure 保留、跨厂商账号 ID 和既有 SMB 来源兼容。设备 AndroidKeyStore 仍待实机验证。
- 网盘原生界面：3 项 API 31 / TV 配置测试，覆盖模拟扫码→选目录→返回→保存来源、目录加载失败不改变父栈、取消后停止轮询，以及二维码显示内容可解码。扫码图与目录图使用合成会话。

合计 **138 项测试通过**：`core` 32 项、`app` 106 项，0 失败、0 错误、0 跳过。最终联合检查 `:core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` 成功。

EPUB 的受控测试文件含 8 MiB 未读内容，元数据与第一章合计传输断言小于 512 KiB。在线 TXT 的集成测试同时验证 TV 私有书籍目录没有生成整本文件。相关用例分别为 `OnlineReadingTest` 和 `OnlineOfflineFlowTest`。

当前 GitHub 登录缺少工作流写入权限，自动构建配置以 `ci/android.yml` 模板提供，尚未启用；本记录不是 GitHub CI 通过声明。

## 可复核结果

发布 APK：`ShufangTV-0.2.0-alpha.1-debug.apk`，6,894,385 字节；SHA-256：`bc418d56b2f122bd6cfa72bd103272b9ff69aeab51702328230b4e8d58b54cff`。版本码 2、Android API 26–36，APK Signature Scheme v2 验证通过。签名证书与已发布 `0.1.0-alpha.1` 相同，可以覆盖安装测试；原发布 APK 的 SHA-256 也已重新核对。

[界面图](SCREENSHOTS.md)来自原生 Android View 与 Robolectric 的图形渲染，使用原创指南和生成的测试书籍，不是电视截屏。API 31 的 JVM 环境可验证页面与输入逻辑，不能替代设备驱动、Binder、系统文件选择器和实际遥控器。

Robolectric 默认从 Maven Central 下载 Android 测试运行时。本机为解决下载连接缓慢，使用已校验的 `android-all-instrumented:12-robolectric-7732740-i7` 本地文件，SHA-256：`ee2dbe28bfe63ddce34a2d631c6168c131f78a4cb58caeb4d832988e936760cb`。Gradle 支持透传 `-Drobolectric.offline=true -Drobolectric.dependency.dir=<运行时目录>`；普通构建无需这两个参数。

## 设备验证边界

本机磁盘空间不足，Android TV 模拟器未能启动。本次临时下载的模拟器与系统镜像已移除以恢复空间。没有把 JVM 结果写成电视实机结果。

真实 SMB NAS、Sony 电视、系统文件选择器、Android Keystore 和 PDF 代理文件描述符仍需要设备验证。当前版本用于首轮测试，尚不等于稳定发布。

匿名首方在线请求已核对四家二维码初始化、阿里 POST 未扫码轮询、百度交换响应格式、夸克/UC 等待状态与 config 鉴权路由。真实账号扫码确认后的目录、会话刷新、三种格式按需读取仍待验收，详见 [网盘连接与验收](CLOUD-CONNECTIONS.md)。参考本地 NetVplayer 源码不构成真实账号或设备验证。

所有测试使用生成的书籍、原创指南和本机受控 HTTP 服务，不使用用户书籍或真实网盘账号。
