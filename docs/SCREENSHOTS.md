# 原生界面与 Sony 实机图

## Sony 实机 · 2026-11-06

BRAVIA 4K UR2，Android 10 / 1920×1080 / 320dpi；通过 ADB 捕获物理电视屏幕。内容为原创指南和自行生成的测试书，不含真实账号或授权票据。概览只缩小/拼接实际画面；单图保留原始分辨率。测试操作及限制见 [集成记录](INTEGRATION-2026-11-06.md)。

![实机九步概览](screenshots/sony-2026-11-06/overview.png)

| 场景 | 实机图 |
| --- | --- |
| 首页与阅读工具 | [首页](screenshots/sony-2026-11-06/home.png)、[工具](screenshots/sony-2026-11-06/tools.png) |
| 排版安全区 | [最终面板](screenshots/sony-2026-11-06/typography.png)、[同尺寸前后对比](screenshots/sony-2026-11-06/typography-comparison.png) |
| 搜索与自动翻页 | [结果高亮](screenshots/sony-2026-11-06/search.png)、[手动暂停保持](screenshots/sony-2026-11-06/auto-paused.png) |
| 网络与离线 | [WebDAV 表单](screenshots/sony-2026-11-06/webdav-form.png)、[在线 PDF](screenshots/sony-2026-11-06/online-pdf.png)、[实时下载进度](screenshots/sony-2026-11-06/download-progress.png)、[服务关闭后离线冷启动](screenshots/sony-2026-11-06/offline-cold.png) |
| 收藏与系统限制 | [持久化备注](screenshots/sony-2026-11-06/notes.png)、[本地选择器缺失](screenshots/sony-2026-11-06/local-unavailable.png)、[外部保存界面缺失](screenshots/sony-2026-11-06/export-unavailable.png) |
| 设置与版本 | [全局默认值](screenshots/sony-2026-11-06/global-settings.png)、[页底完整焦点](screenshots/sony-2026-11-06/settings-bottom.png)、[实际版本号](screenshots/sony-2026-11-06/about.png) |

## Robolectric 原生渲染

以下图片由应用的原生 Android View 在 Android API 31 / TV 配置下，通过 Robolectric 图形引擎渲染；不是浏览器原型，也不是电视实机截屏。书籍为原创指南和自动生成的测试文本。保留历史发布与设计检查证据。

## 首页

![首页](screenshots/home.png)

## 在线阅读

测试使用本机 HTTP Range 服务；此时离线目录为空。

![在线阅读](screenshots/online.png)

## 离线阅读

由用户主动保存同一本测试书；关闭测试服务后，仍可恢复进度。

![离线阅读](screenshots/offline.png)

## 书籍来源

新版使用整卡网盘入口与紧凑的本地/家庭书库入口。

![书籍来源](screenshots/sources.png)

已连接账号、已保存文件夹与独立管理入口（合成测试会话）：

![已连接的来源](screenshots/cloud-sources.png)

旧版 `0.2.0-alpha.1`，用于对照：

![优化前的来源页](screenshots/sources-before.png)

## 扫码登录与选择文件夹

以下使用合成的测试会话和不可用的示例二维码，展示原生交互，不包含用户账号或真实授权票据。

![原生扫码弹窗](screenshots/cloud-login.png)

![原生文件夹选择](screenshots/cloud-folder.png)

## 浏览书籍与选择阅读方式

类型和大小来自测试夹具，动作面板明确区分按需在线读取与占用存储的离线保存。

![浏览来源](screenshots/cloud-browser.png)

![在线与离线动作](screenshots/cloud-actions.png)

## 较小屏幕

以下使用 640×360dp / 1280×720 渲染配置。扫码与账号弹窗按自身窗口大小显示，目录页固定保留确认区。

![小屏目录](screenshots/cloud-folder-720p.png)

![小屏扫码](screenshots/cloud-login-720p.png)

![小屏账号管理](screenshots/cloud-account-720p.png)

## 设置

![设置](screenshots/settings.png)

## 阅读指南

![阅读指南](screenshots/reader.png)
