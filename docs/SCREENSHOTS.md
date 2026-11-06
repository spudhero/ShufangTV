# 原生界面与 Sony 实机图

## 4K 展示 · 0.5.0-alpha.2

[4K 阅读页](screenshots/display-4k-2026-11-06/reader-native-4k.png)、[4K 首页](screenshots/display-4k-2026-11-06/covers-home-4k.png)、[4K 书库](screenshots/display-4k-2026-11-06/covers-shelf-4k.png)，以及 [4K](screenshots/display-4k-2026-11-06/display-4k.png) / [1080p](screenshots/display-4k-2026-11-06/display-1080p.png) / [720p](screenshots/display-4k-2026-11-06/display-720p.png) 显示面板。以上均为 Robolectric 原生 View 渲染的合成内容，4K 阅读页实际尺寸 3840×2160；不是 Sony 原生 4K 界面的证明。

Sony 独立源的 [3840×2160 PixelCopy 测试图](screenshots/display-4k-2026-11-06/pixelcopy-4k.png)保留逐像素黑白细节；该固件最终仍按 1080p 合成，完整边界见 [4K 验证](DISPLAY-4K-2026-11-06.md)。

## 独立离线中文 · 0.5.0-alpha.1

[语音接入验收](SPEECH-INTEGRATION.md)包含实际 Sony 的合成音频与指标。以下为 Robolectric 原生 View 渲染，背景与书籍均为合成内容：

| 状态 | 图像 |
| --- | --- |
| 默认离线、语音包未下载 | [1080p](screenshots/speech-offline/speech-offline-missing-1080p.png)、[720p](screenshots/speech-offline/speech-offline-missing-720p.png) |
| 网络配置、固定底部操作 | [720p](screenshots/speech-offline/speech-network-empty-720p.png) |
| 可选系统服务、中文待检查 | [1080p](screenshots/speech-offline/speech-system-detected-1080p.png) |

Sony 语音管理：[已就绪面板裁图](screenshots/speech-offline/sony-voice-ready.png)。Sony 实际电视、原创 TXT 指南的离线朗读与高亮：[1920×1080 实机截图](screenshots/speech-offline/sony-guide-speaking.png)。

历史截图中“设备缺少语音引擎”的结论保留为当时状态；当前独立离线中文已在同一 Sony 电视通过 JNI / AudioTrack 实测。

## 真实封面 · 0.4.0-alpha.3

下图为 Robolectric 原生 View 渲染，使用自行绘制的原创测试封面，经过实际 EPUB 提取 / 缓存 / 首页与书库绑定流程；不是 Sony 实机截图。Sony 上的 16 本真实书籍封面、首页与书库完整画面保存在本地忽略目录，验收汇总见 [封面修复](COVER-VALIDATION-2026-11-06.md)。

| 原生合成测试 | 图像 |
| --- | --- |
| 首页 | [1080p](screenshots/covers-2026-11-06/covers-home-1080p.png)、[720p](screenshots/covers-2026-11-06/covers-home-720p.png) |
| 书库 | [1080p](screenshots/covers-2026-11-06/covers-shelf-1080p.png)、[720p](screenshots/covers-2026-11-06/covers-shelf-720p.png) |

## UI、听书准备与启动器 · 0.4.0-alpha.2

本轮结果见 [UI 复核](UI-AUDIT-2026-11-06.md)。实机图仅包含原创指南或应用设置；合成渲染与设备画面分别列出。

| 证据 | 图像 |
| --- | --- |
| Sony 原生设置与指南，1920×1080 | [设置前后](screenshots/ui-audit-2026-11-06/settings-comparison.png)、[指南菜单前后](screenshots/ui-audit-2026-11-06/guide-menu-comparison.png)、[缺少引擎提示](screenshots/ui-audit-2026-11-06/speech-setup-final.png) |
| Robolectric 原生 View，合成极长书名 | [1080p](screenshots/ui-audit-2026-11-06/home-title-1080p.png)、[720p](screenshots/ui-audit-2026-11-06/home-title-720p.png)、[720p 完整详情](screenshots/ui-audit-2026-11-06/home-title-720p-detail.png) |
| Robolectric 合成语音服务状态 | [720p 无服务](screenshots/ui-audit-2026-11-06/speech-settings-missing-720p.png)、[服务已检测、中文待检查](screenshots/ui-audit-2026-11-06/speech-settings-detected-1080p.png) |
| 本轮交付的真实启动资源，静态资产预览 | [图标与电视横幅](screenshots/ui-audit-2026-11-06/launcher-assets.png)；[Sony 应用卡片裁图](screenshots/ui-audit-2026-11-06/sony-launcher-crop.png)为实际设备画面 |

服务状态样例不证明中文可发声；本次 Sony 实测未安装语音引擎。用户实际书名、漫画 / PDF / Kindle 页面与首页前后对照保存在本地忽略目录，未纳入公开截图。

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

Sony 本次实机：[显示设置](screenshots/display-4k-2026-11-06/sony-display-panel.png)、[遥控器滚动后的设备详情](screenshots/display-4k-2026-11-06/sony-display-details.png)、[原创指南正文](screenshots/display-4k-2026-11-06/sony-guide-reading.png)。系统截图仍为 1080p；对话框按实际边界裁切，不能用来证明最终原生 4K 输出。
