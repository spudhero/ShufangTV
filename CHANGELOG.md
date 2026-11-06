# 变更记录

这里记录主干开发版本；是否已公开发布，以 [GitHub Releases](https://github.com/spudhero/ShufangTV/releases) 为准。自动打包下载见 [Actions](https://github.com/spudhero/ShufangTV/actions/workflows/android.yml)。

## 0.5.0-alpha.2 · 版本码 9

- 独立高清阅读画布、自动 / 兼容模式、实际清晰度与设备详情；原生 4K 布局、高清封面 / SVG，修复高密度裁字。Sony Android 10 仍受最终 1080p 合成限制。
- 固定 release 签名与 GitHub 自动打包；公开证书、APK 校验和、源码提交元数据。新 release 密钥与原 debug 密钥不同，切换限制见 [发布说明](docs/RELEASE-AND-CI.md)。
- 整理文档导航、当前语音状态及分支清理记录；3 个无独有提交的已合并分支删除，历史提交和旧工作区保留。

## 0.5.0-alpha.1 · 版本码 8

- 默认应用内离线中文朗读，不依赖系统 TTS；语音包下载、校验和管理。
- 可选 Azure / 系统适配器；真实 Sony 离线播放、取消、语速及后台暂停验证。

## 0.4.0-alpha.3 · 版本码 7

- 首页与书库显示 EPUB、Kindle、PDF 真实封面；后台按需读取、缓存和失败重试。
- Huawei SMB 的 16 本实际书籍封面抽查通过。

更早阶段的插图 / 漫画、PDF 屏宽滚动、AZW3/MOBI、云盘来源与阅读工具，见 [实施计划](docs/READING-ROADMAP.md) 和 [历史集成报告](docs/INTEGRATION-2026-11-06.md)。保留原有发布物与标签。
