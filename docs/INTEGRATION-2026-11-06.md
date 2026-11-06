# 主干集成与 Sony 验证

日期：2026-11-06；开发版 0.3.0-alpha.1，版本码 4。

## 分支集成

原生云盘来源分支 `codex/native-cloud-sources` 与旧 main 同在 `81a3c0e`。云盘 UI 分支 `codex/cloud-ui-delivery` 的 `d40dfab` 已在 origin/main。先独立提交阅读功能 `5c92582`，再将本地 main 快进到 origin/main，使用 `880db22` 合并阅读分支 `codex/cloud-ui-polish`。没有文本冲突；仍检查共享 MainActivity 的来源、书库、收藏、导出、阅读和返回行为。所有现有本地分支均已包含在主干，保留分支与其他工作区。

## UI 补漏

全局设置页复用阅读面板的暗色选项和当前值标记，补齐栏宽。字号统一为 16–40；字体、行距、纸色和栏宽都保留选中状态。修改后按稳定标签恢复焦点，滚动立即到位；不会改动本书独立排版。关于页面读取实际安装版本，帮助和原创指南补充搜索、收藏、朗读和自动翻页。普通消息统一使用暗色阅读弹窗。

对比及边界图见 [UI 检查](../design-qa.md)。原生测试图不能代替设备结果。

## 自动化和安装包

运行仓库要求的 `:core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`；JDK 17、Gradle 8.13、SDK/Build Tools 36。离线 Robolectric 运行时及来源校验值见 [历史验证](VALIDATION.md)。

- core 38、app 132，合计 **170 项**；失败、错误、跳过均为 0。
- lint 0 错误、20 个警告，涉及程序化 View、字符串、绘制分配与存储 API 提示；未添加基线掩盖问题。
- 四项新增设置/表单/系统组件检查覆盖全局/本书隔离、40 号边界、焦点恢复、动态版本以及 640×360dp 下选项与底部操作的完整可见性。
- 安装包 `artifacts/ShufangTV-0.3.0-alpha.1-sony-final-debug.apk`：7,065,595 字节；SHA-256 `96e0c9626ee5733f54ecea4f35bfde67514a785035fd0a1d85a3f06a2d5a7538`。
- 原项目本地测试证书 SHA-256 `9aeb612cabfb2d39bd0d7b40f212a562c0aee3440a9bd4b84e376f335ab6e0e0`，APK v2/v3 签名验证成功。保留旧版本 APK、标签和校验记录；密钥及安装包不进入源码 Git。

## Sony 实机

已连接 BRAVIA 4K UR2，Android 10/API 29，1920×1080，320dpi；初始未安装书房 TV。没有发现平台 TTS 引擎。安装和逐项操作结果将在本节补充，不把 JVM 检查计为实机通过。

首轮 Sony 截图发现长排版面板触及上下边缘；已将自定义内容与原生列表都限制在高度安全区，并统一 SMB/WebDAV 表单、下载、进度和删除确认弹窗。表单显示后再绑定校验，避免覆盖通用样式回调。重新运行全部 170 项检查成功，安装包重新签名。

Sony 的文件请求解析到 `com.google.android.tv.frameworkpackagestubs` 后直接返回。新增已知系统占位组件检测：本地/USB 与导出明确说明缺少系统界面，导出不会生成临时快照或丢失收藏；正常处理器和查找受可见性过滤时继续走原流程。下载进度同时更新自定义标题。
