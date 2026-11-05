# 书房 TV · ShufangTV

面向中文家庭书库的原生 Android TV 开源阅读器。深灰背景、暖白文字、琥珀色焦点，为电视和遥控器设计。Android 8.0（API 26）及以上。

**在线阅读与离线阅读是两个独立操作。** 在线打开书籍时按需读取，临时内容留在有上限的内存缓存，不先把书下载到电视。只有主动选择“保存到本地”，才会完整下载离线副本。

这是独立编写的新项目，不是原 TVReader 的汉化补丁。UI 延续通过 OpenDesign Cloud / Local Codex 完成的“暗色书房”设计，并以原生 Android View 实现。新增网盘来源、扫码、目录选择与阅读方式面板已按设计稿优化；[设计原型](design/README.md)与[落地说明](docs/UI-DESIGN.md)随源码保存。

[下载测试版 APK](https://github.com/spudhero/ShufangTV/releases/tag/v0.2.0-alpha.2) · [网盘连接说明](docs/CLOUD-CONNECTIONS.md) · [验证记录](docs/VALIDATION.md) · [原生界面图](docs/SCREENSHOTS.md)

![首页：原生视图渲染](docs/screenshots/home.png)

## 功能

- 中文首页、书库、阅读页、书签、书籍来源与设置；遥控器焦点、方向键与分层返回。
- EPUB 文本、TXT、PDF；书籍进度、书签、目录跳转、字号、行距与阅读主题。
- 窄 / 中 / 宽居中阅读栏，排版可选全局默认或仅本书；调整后保留文字位置。
- EPUB 本章 / TXT 当前片段正文搜索、命中高亮、上下条与返回搜索前位置；每书搜索历史。
- 逻辑书单、置顶、待读 / 在读 / 已读筛选；来源文件保持原位置。
- EPUB / TXT 前台中文朗读、段落高亮、语速、媒体键和定时停止；使用电视系统 TTS 引擎。
- 遥控器选段、摘录备注、回到原文，以及通过系统文件保存界面导出文本 / Markdown。
- 可调自动翻页；关菜单后恢复，主动暂停或手动翻页后需主动继续。
- 阿里云盘、百度网盘、夸克与 UC 网盘原生扫码直连；选择文件夹作为书籍来源。同一账号可添加多个文件夹。
- SMB 家庭共享、WebDAV、本地与 USB 文件。
- 在线打开、保存离线、离线打开、单独删除离线副本，分别提供明确入口。
- 电视本地 / USB 文件使用系统文件选择器授权，直接读取原文件，不再复制一份。
- 网盘会话与来源凭据使用 Android Keystore 加密保存，无书房 TV 云端账号、广告和统计 SDK。

## 在线与离线

| 场景 | 行为 | 电视存储 |
| --- | --- | --- |
| 在线打开 | SMB 按偏移读取；网盘直链与 WebDAV 使用严格校验的 HTTP Range | 保存少量进度与来源记录；书籍内容只在内存 |
| 保存到本地 | 显示大小、剩余空间，用户确认后下载；支持取消和失败清理 | 完整成功后才登记离线副本 |
| 离线打开 | 仅打开已保存副本或已授权的本地 / USB 原文件 | 不连接远程来源 |
| 删除离线副本 | 只删除应用保存的文件，保留在线入口、进度与书签 | 释放离线文件占用 |

服务器不支持随机读取、忽略 Range、文件变化或读取失败时会报错，**不会自动退回整本下载**。小书的全部字节可能自然落入一次读取窗口；在线模式仍不会将其保存为离线文件。PDF 的实际请求范围由系统渲染器决定。

EPUB 先读取 ZIP 中央目录，再按 spine 加载当前章节。ZIP 原始数据缓存最多 1 MiB，正文保留最近两章，每项解压上限 8 MiB。TXT 每段最多读取 48 KiB，支持 UTF-8、带 BOM 的 UTF-16、GB18030。离线保存首版上限 128 MiB。

在线与离线副本共享章节和文字位置。文本偏移采用 Java UTF-16 单元；TXT 额外保存分段起始字节位置，PDF 保存页索引。

## 使用

1. 在“书籍来源”选择网盘，使用对应手机应用扫码并确认，然后选择书籍文件夹。UC 使用“UC 浏览器”扫码。也可添加 SMB / WebDAV 家庭书库。
2. 选中 EPUB、TXT 或 PDF，选择“在线打开”。需要离线时另选“保存到本地”。
3. 正文左右键翻页，确定键打开菜单，下键进入底部操作区，上键返回正文，返回键逐层退出。
4. “字体与排版”直接调整字号、行距、字体和栏宽；顶端选择全局默认或仅本书。“亮度与纸色”的纸色使用同一范围，显示亮度始终全局。
5. “收藏正文段落”用上下键选择，确定键保存并写备注；在“我的书签”选择卡片管理和回到原文，“导出收藏”选择格式后保存。
6. “朗读正文”的语速和停止时间位于“朗读设置”；应用进入后台会暂停，返回后需主动继续。自动翻页与朗读、搜索、选段互斥。

阅读体验增量与 OpenDesign UI 优化见 [任务计划](docs/READING-ROADMAP.md)、[界面验收与对比](docs/READING-UI-REVIEW.md)、[工作区验证](docs/READING-VALIDATION.md)。主干当前开发版为 `0.3.0-alpha.1`；上面的下载页保留已发布的 `0.2.0-alpha.2`。合并、整体 UI 复查与实机进度见 [集成记录](docs/INTEGRATION-2026-11-06.md) 和 [项目进度](docs/PROGRESS.md)。

网盘由本应用直接授权和读取，不需要 CloudDrive2 服务或第三方登录中转。会话失效时重新扫码；阿里令牌支持自动续期。原文件能否按需读取取决于网盘权限和 Range 支持，拒绝时会提示，不自动下载整本书。SMB / WebDAV 的局域网 HTTP 仍可用，网盘连接使用 HTTPS。

## 构建

需要 JDK 17、Android SDK Platform 36 和 Build Tools 36.0.0。

```sh
git clone https://github.com/spudhero/ShufangTV.git
cd ShufangTV
# 设置 ANDROID_HOME，或在未跟踪的 local.properties 中填写 sdk.dir。
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

安装包：`app/build/outputs/apk/debug/app-debug.apk`。这是使用开发签名的测试版本，正式分发需自行配置发布签名。仓库提供 [GitHub Actions 模板](ci/android.yml)，有工作流管理权限时将它放入 `.github/workflows/`，可自动构建并上传 APK、测试报告和原生界面渲染结果。

## 当前边界

- EPUB 以文本阅读为主，保留图片说明；暂不支持纯图片 EPUB、DRM、MOBI / AZW3、在线 ZIP64。
- 无跨设备进度同步、全文 TXT 目录扫描；不绕过网盘的文件权限、会员限制或额外验证。
- 正文搜索限当前已加载章节 / 片段；PDF 不提供文字搜索、重排、朗读和段落选取。
- 朗读需要支持中文的系统 TTS 引擎；首版仅前台持续播放。书单为单层、每书一个归属。
- 电视需有可用的系统文件选择器；远程文件提供方若不能 seek，会明确失败。
- 四家网盘匿名二维码初始化已核对；真实账号扫码后的目录、会话续期和文件读取仍待验收。协议夹具通过不等于真实账号已通过。
- 真实 NAS、Sony 电视、在线 PDF 代理文件描述符仍需设备验证。

见 [架构与存储规则](docs/ARCHITECTURE.md)、[验证记录](docs/VALIDATION.md)、[阅读行为分析](docs/REFERENCE-BEHAVIOR.md)、[CD2 网盘参考边界](docs/CLOUD-REFERENCE.md)、[第三方依赖](THIRD-PARTY-NOTICES.md)。网盘流程也依据用户授权的本地 NetVplayer 源码核对，公开仓库只记录行为差异和自有实现；不包含该项目源码、参考 APK、反编译源码、凭据、用户书籍或签名密钥。

## License

[Apache-2.0](LICENSE)。TVReader 和 CloudDrive2 仅用于本地研究功能行为，本仓库不包含其源码、二进制、图标或商标素材。
