# 书房 TV · ShufangTV

面向中文家庭书库的原生 Android TV 开源阅读器。深灰背景、暖白文字、琥珀色焦点，为电视和遥控器设计。Android 8.0（API 26）及以上。

**在线阅读与离线阅读是两个独立操作。** 在线打开书籍时按需读取，临时内容留在有上限的内存缓存，不先把书下载到电视。只有主动选择“保存到本地”，才会完整下载离线副本。

这是独立编写的新项目，不是原 TVReader 的汉化补丁。UI 延续通过 Local Codex 完成的“暗色书房”设计，并以原生 Android View 实现。

[下载测试版 APK](https://github.com/spudhero/ShufangTV/releases/tag/v0.1.0-alpha.1) · [验证记录](docs/VALIDATION.md) · [原生界面图](docs/SCREENSHOTS.md)

![首页：原生视图渲染](docs/screenshots/home.png)

## 功能

- 中文首页、书库、阅读页、书签、书籍来源与设置；遥控器焦点、方向键与分层返回。
- EPUB 文本、TXT、PDF；书籍进度、书签、目录跳转、字号、行距与阅读主题。
- SMB 家庭共享、WebDAV；CloudDrive2、网盘通过自行配置的标准协议服务接入。
- 在线打开、保存离线、离线打开、单独删除离线副本，分别提供明确入口。
- 电视本地 / USB 文件使用系统文件选择器授权，直接读取原文件，不再复制一份。
- 来源凭据使用 Android Keystore 加密保存，无云端账号、广告和统计 SDK。

## 在线与离线

| 场景 | 行为 | 电视存储 |
| --- | --- | --- |
| 在线打开 | SMB 按偏移读取；WebDAV 使用严格校验的 HTTP Range | 保存少量进度与来源记录；书籍内容只在内存 |
| 保存到本地 | 显示大小、剩余空间，用户确认后下载；支持取消和失败清理 | 完整成功后才登记离线副本 |
| 离线打开 | 仅打开已保存副本或已授权的本地 / USB 原文件 | 不连接远程来源 |
| 删除离线副本 | 只删除应用保存的文件，保留在线入口、进度与书签 | 释放离线文件占用 |

服务器不支持随机读取、忽略 Range、文件变化或读取失败时会报错，**不会自动退回整本下载**。小书的全部字节可能自然落入一次读取窗口；在线模式仍不会将其保存为离线文件。PDF 的实际请求范围由系统渲染器决定。

EPUB 先读取 ZIP 中央目录，再按 spine 加载当前章节。ZIP 原始数据缓存最多 1 MiB，正文保留最近两章，每项解压上限 8 MiB。TXT 每段最多读取 48 KiB，支持 UTF-8、带 BOM 的 UTF-16、GB18030。离线保存首版上限 128 MiB。

在线与离线副本共享章节和文字位置。文本偏移采用 Java UTF-16 单元；TXT 额外保存分段起始字节位置，PDF 保存页索引。

## 使用

1. 在“书籍来源”添加 SMB 或 WebDAV 连接，选择书库目录。
2. 选中 EPUB、TXT 或 PDF，选择“在线打开”。需要离线时另选“保存到本地”。
3. 正文左右键翻页，确定键打开菜单，下键进入底部操作区，上键返回正文，返回键逐层退出。

CD2 连接使用其提供的 WebDAV 服务地址、目录和凭据。本项目不实现云厂商账号直连或 CD2 私有接口。局域网 HTTP 可用，公网连接宜使用服务端配置好的 HTTPS。

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
- 无跨设备进度同步、自动网盘登录、全文 TXT 目录扫描。
- 电视需有可用的系统文件选择器；远程文件提供方若不能 seek，会明确失败。
- 真实 NAS、Sony 电视、在线 PDF 代理文件描述符仍需设备验证。自动化检查不能替代这些环境测试。

见 [架构与存储规则](docs/ARCHITECTURE.md)、[验证记录](docs/VALIDATION.md)、[参考行为分析](docs/REFERENCE-BEHAVIOR.md)、[第三方依赖](THIRD-PARTY-NOTICES.md)。参考 APK、反编译源码、凭据、用户书籍和签名密钥均不在仓库中。

## License

[Apache-2.0](LICENSE)。TVReader 和 CloudDrive2 仅用于本地研究功能行为，本仓库不包含其源码、二进制、图标或商标素材。
