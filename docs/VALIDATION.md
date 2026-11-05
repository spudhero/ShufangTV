# 验证记录

日期：2026-11-05。构建环境：JDK 17、Gradle 8.13、Android SDK / Build Tools 36。

## 已通过

- `:app:assembleDebug`：生成可安装的开发签名 APK。
- `:app:lintDebug`：无错误；存在程序化布局和中文文字等非阻断警告。
- `:core:test`：32 项解析测试通过，覆盖 EPUB spine、中文编码、压缩包与资源边界、损坏文件和解析错误。
- 网络层：25 项受控 HTTP / 路径与重试测试通过，覆盖 Range、200 响应拒绝、ETag 变化、中文 WebDAV、原子下载、取消、认证和重连分类。
- 在线读取：7 项 JVM 测试通过，验证 8 MiB 未读 EPUB 内容不会在元数据 / 第一章打开时被传输、1 MiB 缓存上限、UTF-8 / GB18030 / UTF-16 分段往返与资源释放。
- 进度与存储：3 项 Robolectric 测试通过，验证字体变化后的逻辑位置、在线记录重载不生成离线文件、损坏书库记录禁止覆盖。
- 原生界面：2 项 Android API 31 / TV 配置的 Robolectric 测试通过，覆盖中文页面、返回操作、正文遥控器翻页、书签、退出和继续阅读。
- 模式完整流程：1 项原生 UI 与本机 HTTP 服务集成测试通过。先在线打开生成的长 TXT，确认仅发出 Range 请求且离线目录为空；随后在 UI 主动下载，确认只有一个完整文件；关闭 HTTP 服务后离线打开，确认不发请求且恢复相同文字位置。

合计 **70 项测试通过**：`core` 32 项、`app` 38 项，0 失败、0 跳过。最终联合检查 `:core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` 成功。

EPUB 的受控测试文件含 8 MiB 未读内容，元数据与第一章合计传输断言小于 512 KiB。在线 TXT 的集成测试同时验证 TV 私有书籍目录没有生成整本文件。相关用例分别为 `OnlineReadingTest` 和 `OnlineOfflineFlowTest`。

当前 GitHub 登录缺少工作流写入权限，自动构建配置以 `ci/android.yml` 模板提供，尚未启用；本记录不是 GitHub CI 通过声明。

## 可复核结果

[界面图](SCREENSHOTS.md)来自原生 Android View 与 Robolectric 的图形渲染，使用原创指南和生成的测试书籍，不是电视截屏。API 31 的 JVM 环境可验证页面与输入逻辑，不能替代设备驱动、Binder、系统文件选择器和实际遥控器。

Robolectric 默认从 Maven Central 下载 Android 测试运行时。本机为解决下载连接缓慢，使用已校验的 `android-all-instrumented:12-robolectric-7732740-i7` 本地文件，SHA-256：`ee2dbe28bfe63ddce34a2d631c6168c131f78a4cb58caeb4d832988e936760cb`。Gradle 支持透传 `-Drobolectric.offline=true -Drobolectric.dependency.dir=<运行时目录>`；普通构建无需这两个参数。

## 设备验证边界

本机磁盘空间不足，Android TV 模拟器未能启动。本次临时下载的模拟器与系统镜像已移除以恢复空间。没有把 JVM 结果写成电视实机结果。

真实 SMB NAS、Sony 电视、系统文件选择器、Android Keystore 和 PDF 代理文件描述符仍需要设备验证。当前版本用于首轮测试，尚不等于稳定发布。

所有测试使用生成的书籍、原创指南和本机受控 HTTP 服务，不使用用户书籍或真实网盘账号。
