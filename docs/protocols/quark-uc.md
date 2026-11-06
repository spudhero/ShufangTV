# 夸克与 UC 原生网盘协议观察

核对日期：2026-11-06。实现为独立 Java 代码，仅观察首方网页公开发布的请求与响应格式；未复制 CD2、OpenList 或其他网盘驱动代码，未使用第三方应用密钥、Cookie 中转或登录授权服务。

## 首方来源

- [夸克网盘首页](https://pan.quark.cn/)
- [夸克当前登录页脚本 0.1.78](https://g.alicdn.com/quark-cloud-drive/quark-cloud-drive-static-page/0.1.78/js/index.js)
- [夸克桌面前端脚本 4.6.7](https://g.alicdn.com/uc-cloud-drive-web-system/cloud-drive-web/4.6.7/share.js)，由首方桌面分享页引用，包含个人文件列表、下载链接和账户请求定义。
- [UC 网盘首页](https://drive.uc.cn/)
- [UC 当前登录页配置脚本 1.1.11](https://g.alicdn.com/code/npm/@ali/uc-pegasus-source-project-uc-cloud-drive-login-static-page/1.1.11/pages/index/index.12e257.js)
- [UC 当前登录页行为脚本 1.1.11](https://g.alicdn.com/code/npm/@ali/uc-pegasus-source-project-uc-cloud-drive-login-static-page/1.1.11/pages/index/index_bootstrap.24cedd.js)
- [UC 官方二维码页面](https://broccoli.uc.cn/apps/ZY5qIZiK/routes/1hbcQwLhH)
- [UC 官方二维码页面脚本](https://image.uc.cn/s/uae/g/1y/broccoli/ZY5qIZiK/1hbcQwLhH/bundle.ef6859ca8f85a18eaba9.js)
- [UC 桌面前端脚本 2.21.5](https://g.alicdn.com/uc-cloud-drive-web-system/cloud-drive-web/2.21.5/share.js)，由首方桌面分享页引用，包含个人文件和会话请求定义。

上述地址仅用于协议来源说明。应用运行时直接调用首方服务，不运行或加载这些前端脚本。

## 扫码与会话

| 步骤 | 夸克 | UC |
| --- | --- | --- |
| CAS 服务 | `https://uop.quark.cn` | `https://api.open.uc.cn` |
| 公开网页客户端编号 | `532` | `381` |
| 初始化 | GET `/cas/ajax/getTokenForQrcodeLogin` | POST 同一路径，表单 |
| 轮询 | GET `/cas/ajax/getServiceTicketByQrcodeToken` | POST 同一路径，表单 |
| 二维码确认页 | `https://su.quark.cn/4_eMHBJ` | `https://su.uc.cn/1_n0ZCv` |
| 确认后换取会话 | GET `https://pan.quark.cn/account/info?st=…` | GET `https://drive.uc.cn/account/info?st=…` |
| 个人盘会话校验 | GET `https://drive.quark.cn/1/clouddrive/config` | GET `https://pc-api.uc.cn/1/clouddrive/config` |

CAS 请求带 `client_id`、`v=1.2`、新的 `request_id`；轮询另带 `token`。UC CAS 的 `Origin`、`Referer` 均使用官方二维码页面的 `https://broccoli.uc.cn/`。初始化响应的令牌位于 `data.members.token`。二维码的内容是上述官方确认页加 `token`、`client_id` 及官方页面使用的展示参数。

公开响应状态中，`2000000` 表示成功，`50004001` 表示等待确认，`50004002` 表示二维码过期。轮询成功后，从 `data.members.service_ticket` 取一次性票据，向本家网盘站点交换会话。应用只显示实际得到的等待、成功或过期状态，不从等待状态推断“已经扫码”。未知状态及网络错误保留为失败。

账户响应须 `success=true`。优先使用有效的 `data.uid`；2026-11-06 真实夸克响应仅包含昵称、头像、配置等字段，未提供 `uid`，因此兼容读取与本站匹配且未过期的 `__uid` Cookie。该行为也见上述首方桌面脚本；不使用 `__kps` 等会话凭据作为账户主键。缺少或非法账号编号仍拒绝授权。Cookie jar 还必须存在能匹配本家文件 API 的未过期 Cookie。随后请求个人盘 `/1/clouddrive/config`，要求 `code=0` 且响应中的 `status`（如有）为 `200`，接收其更新 Cookie，并确认会话未被清除，才返回授权完成。站点专用的 host-only Cookie 不能冒充文件 API 会话；仅有账户 JSON 或 Cookie 均不足以授权。config 拒绝会话、响应错误或网络失败时保留真实失败，不写入已登录账户。

账户主键采用 `quark:<uid>` 或 `uc:<uid>`，原始编号保存在加密会话的 `data.uid` 中，避免两家同编号互相覆盖。二维码使用本地五分钟生命周期上限防止无限轮询，服务器明确返回过期时立即结束；该上限不代表上游保证的二维码有效期。

观察到的网页流程使用 Cookie 会话，没有公开的 `refresh_token` 换取流程。HTTP 响应的 `Set-Cookie` 由共享 `CloudHttp` Cookie jar 按 Domain、Path、Secure、过期时间、host-only 等属性更新，再由账户存储加密持久化。不改变 Cookie 的作用域，也不将全部账号 Cookie 附到任意下载域。服务返回未登录错误码 `31001` 或 HTTP 401 时提示重新扫码。

## 目录与读取链接

| 项目 | 夸克 | UC |
| --- | --- | --- |
| 目录 API | `https://drive-pc.quark.cn` | `https://pc-api.uc.cn` |
| config 与读取链接 API | `https://drive.quark.cn` | `https://pc-api.uc.cn` |
| 公共参数 | `pr=ucpro&fr=pc` | `pr=UCBrowser&fr=pc` |
| 根目录编号 | `0` | `0` |
| 目录分页 | GET `/1/clouddrive/file/sort` | 同左 |
| 书籍读取链接 | POST `/1/clouddrive/file/download` | 同左 |

域名核对：当前首方桌面脚本 4.6.7 仍将 config、目录与下载链接请求定义在 `cloudDriveHost=https://drive-pc.quark.cn`，因此不能断言该域名已停用。本实现按用户授权核对的本地 Netplayer 个人盘请求行为，将 config 与下载链接查询选择为首方 `drive.quark.cn`，目录保留 `drive-pc.quark.cn`。匿名探测两个夸克域名的 config 路由均返回一致的未登录错误，只证明两者公开路由存在，尚未证明真实登录、会员或风控场景下两域名完全等价。未复制本地参考项目源码。

列表请求使用 `pdir_fid`、`_page`、`_size=100`、`_fetch_total=1`、`_fetch_sub_dirs=1`、`_sort=file_type:asc,file_name:asc`。条目来自 `data.list`；字段包括 `fid`、`file_name`、`file_type` 和 `size`，其中 `file_type=0` 为目录、`1` 为文件。总量来自 `metadata._total`；仅显示目录时仍以服务器未筛选的总量计算下一页，避免漏掉后续目录。

读取链接查询发送 JSON `{"fids":["<fid>"]}`，读取 `data` 数组中对应文件的 `download_url` 和大小。该请求仅查询链接，不下载书籍。在线阅读由独立 Range 读取层按需获取字节，离线下载仍是用户单独触发的动作。

链接必须为没有 URL 用户名、密码或片段的 HTTPS 地址。实现目前允许以下域族：`quark.cn`、`uc.cn`、`ucweb.com`、`alicdn.com`、`aliyuncs.com`、`alicloudcs.com`。这是保守的实现允许范围，尚未通过真实账号确认所有下载场景。只把 Cookie jar 与具体读取 URL 匹配的 Cookie 放进读取请求，跨域重定向由读取层剥除账号凭据。

链接在本地五分钟后重新解析，以减少持有失效签名的时间；这是客户端刷新策略，不声称上游签名一定在五分钟后过期。

## 已验证范围

匿名在线探测确认：两家二维码初始化均返回 HTTP 200、状态 `2000000` 和 32 字符令牌；未扫码时轮询均返回 HTTP 200、状态 `50004001`。另对 `drive.quark.cn`、`drive-pc.quark.cn`、`pc-api.uc.cn` 的 `/1/clouddrive/config` 使用相应公共参数、不带 Cookie 请求，三者均返回 HTTP 401、`code=31001`。探测没有展示或保存令牌，没有扫码或取得账号会话，没有读取任何用户账户、目录或书籍数据。

受控测试覆盖两家初始化方法与参数、UC CAS 来源头、等待和过期、票据交换成功与失败、个人盘 config 校验拒绝、config 更新或清除 Cookie、更新后的账户序列化、各操作 API 域名、API Cookie 作用域、目录分页与筛选、失效登录、读取链接查询、下载域和 Cookie 隔离。测试响应为本机合成数据；脚本传输测试将合成 Set-Cookie 按 OkHttp 标准解析交付真实账户 Cookie jar，不代表真实账号或真实服务端 Cookie 更新的端到端验证。

真实手机扫码确认、服务端 Cookie 更新、真实网盘目录、会员/风控限制、真实签名链接的 Range 兼容性仍需在用户主动授权账号后验证。协议改变或官方要求额外验证时，应用显示真实失败，不通过第三方授权服务绕过。

## 2026-11-06 真实电视增量

夸克真实扫码确认和票据交换成功；旧实现随后因假定 `data.uid` 必有而失败。兼容身份 Cookie 后，使用同一次已确认会话完成 config 验证、读取 6 个根目录条目并加密保存账号，电视目录选择也可见。UC 已保存真实账号根目录读取通过。本节更新前文匿名观察的范围，不代表链接、Range 或过期续期均已验收。详见 [本轮修复验证](../CLOUD-FIX-2026-11-06.md)。
