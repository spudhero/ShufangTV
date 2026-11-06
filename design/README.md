# 书房 TV 设计原型

本目录保存本轮通过 OpenDesign Cloud、由 Local Codex 生成的可交互设计稿与尺寸规范，延续“暗色书房”。它用于设计对照，不打包进入 APK。

在仓库根目录执行 `python3 -m http.server 4177 --bind 127.0.0.1`，打开 `http://127.0.0.1:4177/design/cloud-library.html`。四个页签分别查看来源、扫码、选择文件夹和浏览来源。原型里的账号、目录、二维码和状态控制均为演示；阅读与本地/家庭书库入口复用同目录的旧原型。

[原始设计规范](CLOUD-UI-DESIGN.md)记录设计工具交付时的状态。[原生落地说明](../docs/UI-DESIGN.md)记录实际 Android 实现与验收；[原生界面图](../docs/SCREENSHOTS.md)用于核对 APK 的界面。

网页原型的工具栏、模拟数据、DOM 焦点算法和 iframe 均未进入 Android 应用。原生版本继续使用真实网盘协议、View、Canvas 和已有阅读器。

## UI、听书准备与启动器细化

新增入口 [ui-review.html](ui-review.html)，与网盘稿共用既有 tokens；可切换 1080p / 720p、语音状态与文本 / PDF / 图片样例。所有书名、正文和状态均为合成内容；网页不连接 TTS，也不渲染用户文件。

[原始细化规范](UI-REFINEMENTS.md)与[最终原生落地差异](NATIVE-ADOPTION.md)分别保留。后者记录真实 PDF 操作、混排图片文本能力、图标资源映射与复核修订。`reading-features.html` 及其 CSS / JS 同步保存，以供本轮入口里的既有书库与书签链接使用。
