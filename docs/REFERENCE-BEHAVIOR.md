# 参考 APK 的行为分析与独立重写规范

分析日期：2026-11-05。本记录用于书房 TV 的独立实现，只描述静态观察得到的接口和行为，以及本项目另行作出的设计决定。没有执行参考 APK、连接真实账号或采集用户网盘数据；静态可达关系不能证明某条路径在特定设备上实际执行。

## 样本与方法

| 应用 | 包名、版本 | APK SHA-256 |
| --- | --- | --- |
| TVReader | `com.tvreader.app`，1.0.1（1000001） | `c2715a47329bbc91cbfe06030fae3b60870d00f88f89052b6dcff261a0a0caca` |
| CloudDrive2 | `com.clouddrive2.CloudDrive2`，1.1.3（346） | `03cd7e685eab50a2c9db8958fabfe8f23a9af2577145c111afbab0b2fa8fb362` |

使用 REA CLI 6.8.0 调用 [jadx-headless-mcp 0.7.1 官方发布包](https://github.com/1013503897/jadx-headless-mcp/releases/tag/v0.7.1)，JDK 17，单 worker。分析器 JAR 的官方发布摘要与本地文件核对一致：`6e5eacf500b64292bfb73c49797c1958f6ee44646e43e868039ae7feb573ff75`。仅在单次分析进程中指定 provider，没有修改共享 MCP 配置。Java 反编译失败的方法使用 REA 返回的完整 smali；没有把反编译实现复制进产品代码。

原始 APK、分析器及 REA 证据 JSON 保留在本地忽略目录 `private-analysis/`，不随开源仓库发布。下文的 `ev_…` 是该次本地 REA 证据标识，便于核对，不是公开下载地址。

## 静态观察

### TVReader：来源导入与阅读位置

已追踪的来源导入链为 `SourceBrowserViewModel.e` → `com.tvreader.app.v.g` → `com.tvreader.app.s.x` → `com.tvreader.app.v.b`。来源适配器先交付 `InputStream`，导入实现随后循环读取直到 EOF，写入本地文件，同时更新摘要并报告进度。因此这条链是整本导入路径，不能据此声称 TVReader 对远程书籍进行了按需随机读取。协程及写入循环都可见 150 MiB 导入限制；该数值仅是参考应用的观察结果。

`ReadingProgressEntity` 独立保存 `bookId`、`chapterIndex`、`chapterTitle`、`charOffset`、`pageIndex`、`percentage`、`updatedAt`。这些字段支持把逻辑位置与显示页码分开存储的设计判断，但只看实体定义不能证明恢复算法、字符偏移单位或分页精度。

### CloudDrive2：可定位读取与 PDF 回退

`HttpRangeRandomAccessRead` 暴露 `length`、`seek`、`read`、`readFully`、`rewind`、`peek`、`cancel`、`close` 等接口。`blockFor` 将位置按 64 KiB 块对齐，优先查缓存，再用 HTTP `Range: bytes=start-end` 读取对应块。已检查的方法体中未见每次读取均严格核对响应状态和 `Content-Range` 的过程，不能将其作为本项目的完整协议校验实现。

`RemoteSeekableFd.probeRangeSize` 请求 `bytes=0-0`，要求 206 响应，并从 `Content-Range` 取得正的总长度。`open` 在 Android API ≥ 28 且探测成功时尝试 `openStreaming`；否则或流式打开失败后，调用 `openDownloaded`。这是参考应用的一项整本下载回退策略，与书房 TV“在线阅读、离线下载分开”的需求不同。

REA 的类引用结果把 `HttpRangeRandomAccessRead` 关联到 `StreamingPdfDoc`、`BookSession` 和 PDF 页面；`RemoteSeekableFd` 的引用节点为 `StreamingPdfDoc`。因此目前可以确认 PDF 相关的可定位读取工具及回退分支，不能把该结论扩大为所有格式都采用相同通路。

`BookClient.open` 将 URL、名称、长度放入请求，交给 `BookNative`。`BookClient` 另有目录、章节块、文本片段、资源、PDF 页和定位接口；`BookPlace` 保存章节编号及长整型 byte 位置。`BookNative` 的核心是 native 调用，本次未分析其本地库实现，尚不能确定它怎样读取 EPUB ZIP 目录、压缩数据或 TXT 索引。

`BookReaderPrefs` 可见字体、缩放、间距、边距、主题、连续阅读、双页、封面单页、纵向等设置键，以及部分按书保存的接口。这是接口元数据观察，不代表这些模式已在电视遥控器环境验证。

### 核心证据索引

| 目标 | 观察方式 | REA 证据 ID |
| --- | --- | --- |
| TVReader 包身份 | 包检查 | `ev_a56ba7fd2c73f6bd5e4c2ce59a66bd2e887eb502f086896f73c9bd73e242ac80` |
| CD2 包身份 | 包检查 | `ev_83b1415626670b754f89a52d33cbcd695ff1fbee0a88aab2d37b888a37e56973` |
| TVReader `SourceBrowserViewModel.e` | Java 方法体 | `ev_a3ade873b4bf8bc11007f16ceb3003f4a8bf916e500cb4b2d4c5aeda168b2abb` |
| TVReader `com.tvreader.app.s.x` | Java 方法体，含反编译警告 | `ev_ae9b8a0e63a5f10845e7b9c06bbcbaba3a43f1e891418107449949a0fce62420` |
| TVReader `com.tvreader.app.v.b` | 完整 smali 方法体 | `ev_010209294e15da0473a82fe2aa62a22d75f1e5f4eb8c17f1f2eaef90c19835ed` |
| TVReader `ReadingProgressEntity` | 类与字段元数据 | `ev_16810af3a36201f70d9ae1f7ae2b6fe641cdada80c9a3119262a76199c489eb8` |
| CD2 `HttpRangeRandomAccessRead.blockFor` | Java 方法体，含反编译警告 | `ev_7b849036d31fc5517e925cdfd6fee060b570b32f29aa7c0b8bbd909e5ed39acc` |
| CD2 `RemoteSeekableFd.probeRangeSize` | Java 方法体 | `ev_0b4326e46a117b573ad5f48792c994227bc49743e38040232b337bbfbc6c0f7f` |
| CD2 `RemoteSeekableFd.open` | Java 方法体 | `ev_1c4a9b42e1afaa78af1feac5289796c332a9e66818fbaedaa65e10d9baae6896` |
| CD2 两个读取工具的引用节点 | 静态类引用，不含运行覆盖率 | `ev_3345ff81d620faea0a197fe890ba62a315a33a3d798e956d16f5d5ee0cb196db`；`ev_88b2ce7944f97658c2126b1cb3b3effae02e959f6a38b16f85b7447f79eb77a4` |
| CD2 `BookClient.open` / `BookNative` | Java 方法体 / native 方法元数据 | `ev_9764b7d72377107d2bc5bbb325c04aba6359739428817609c12eb280be262c32`；`ev_2d30fb24b16b9fbcd7adcf0d016df7c3132d41199a7480a7b92580f6d3689873` |
| CD2 `BookPlace` / `BookReaderPrefs` | 类与字段元数据 | `ev_77439b75441b35cb0232c01faf952e3186a17255cb2ce30275a812100d88c4ba`；`ev_da35ad995bf726a7244132b19deed968b218c83e59624c0c6d303da70d59167c` |

## 书房 TV 的独立实现决定

以下是本项目的产品与技术规范，不是对参考 APK 未观察到部分的推断，也不表示当前版本已经完成所有能力。

### 在线阅读和离线下载

默认“在线阅读”使用远程可定位读源和有上限的临时缓存。不得为了打开远程书籍先完整复制文件，不得因服务不支持 Range 而自动下载整本书。

“下载离线”是独立的用户操作，需要展示文件大小、下载进度、取消入口和完成状态。只有完整成功后才把文件标为可离线；中断或失败的文件不得冒充完整书籍。在线暂存与离线文件分别计量和管理。

在线请求遇到无法随机读取、对象已变化或资源不足时，给出明确原因和“下载离线”选择，保留当前阅读位置。在线读取不应在失败后无提示地改变为下载任务。

### 来源与随机访问

来源适配器提供长度、对象身份、按偏移读取和关闭能力，目录浏览不应触发书籍内容下载。SMB 使用带偏移的文件读取；网盘原文件直链与 WebDAV 使用 Range。根据用户后续明确要求，四家网盘由书房 TV 自行扫码、列目录和取得直链，不使用 CD2 服务或代理；见 [网盘参考边界](CLOUD-REFERENCE.md)。

HTTP 每次块读取都校验 206 状态、起止位置、总长度与实际返回长度。服务可能忽略 Range，不能把 200 响应正文当作某个非零偏移的数据块；对象校验信息变化时必须清理旧缓存并重新打开。[RFC 9110 的范围请求与 Content-Range 定义](https://www.rfc-editor.org/rfc/rfc9110.html#name-range)是协议依据。

缓存设置明确的容量上限，关闭书籍释放句柄，取消操作传递到网络读取。连接、列目录和读文件均有超时、错误状态与重试入口；UI 不等待无限重连。具体缓存大小与重试策略由书房 TV 自行选择，不沿用参考应用常量。

### 格式读取

| 格式 | 独立实现要求 |
| --- | --- |
| EPUB | 用可定位读源读取 ZIP 尾部索引和中央目录，再读取容器、OPF、目录及所需内容项。以 OPF spine 决定正文顺序，惰性解压当前章节和必要资源，不在打开时批量解压全书。对压缩项和输出大小设置上限；暂不支持的 ZIP 特性应明确报错。[EPUB 规范的 spine 定义](https://www.w3.org/TR/epub-33/#sec-spine-elem)是阅读顺序依据。 |
| PDF | 为渲染器提供真正可 seek 的文件描述符或等价读接口。Android 提供将底层 I/O 回调到应用的可定位代理文件描述符；设备或源不支持时说明原因，由用户决定下载。[Android `openProxyFileDescriptor` 文档](https://developer.android.com/reference/android/os/storage/StorageManager#openProxyFileDescriptor(int,%20android.os.ProxyFileDescriptorCallback,%20android.os.Handler))是平台依据。 |
| TXT | 逐段读取和解码，保留编码状态及可恢复的字节检查点；不能从任意字节中间直接解码多字节字符。初次读取不等待整本内容加载，目录扫描可独立进行并显示状态。 |

### 分页、位置和遥控器

文本书籍保存“书籍身份、章节、逻辑文字偏移”，页码和百分比是辅助信息。实现必须明确偏移使用字节、Unicode 码点或 UTF-16 单元中的哪一种，并在解析、存储、分页之间保持一致。字号、行距、显示区域改变后，以逻辑位置重新分页，不直接套用旧页码。PDF 保存页索引及适当的页内位置。

遥控器左右翻页、确认键打开阅读菜单、返回键分层退出；焦点状态可见并能恢复。目录跳转、阅读设置和下载操作应各自可用遥控器完成。上述按键规则由本项目制定，本次静态分析未验证参考应用的全部键位和焦点流程。

## 已知空白与验证边界

- 没有运行参考 APK，因此没有首屏耗时、内存、真实缓存命中率、设备兼容性或遥控器使用结果。
- CD2 native 书籍引擎内部未分析；其 EPUB 中央目录访问、TXT 编码检测和分页算法仍未知。
- TVReader 已确认的是来源导入链，不代表穷尽了所有可能的打开入口；不能据此断言整个应用绝无在线模式。
- 没有分析或接入参考应用的私有网盘登录流程，不声称已经复现厂商直连、账号同步、付费能力或 DRM。
- 本记录不证明书房 TV 已实现以上规范。需要通过独立测试验证：受控 Range/SMB 读源、中文编码、字号恢复、损坏压缩包、断线重试及在线打开不整本下载。
