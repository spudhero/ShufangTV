# 阿里与百度首方网页协议记录

核对日期：2026-11-05。实现为独立 Java 代码；使用首方网页会话，没有应用私钥、第三方登录中转，也未复制参考项目源码。会话和本机随机生成的设备私钥由 Android Keystore 加密落盘。

## 阿里云盘

- 从首方登录页面取得网页表单参数，`passport.aliyundrive.com/newlogin/qrcode/generate.do` 生成二维码内容。状态查询 `qrcode/query.do` 使用 **POST 表单**；匿名现网验证发现对同一张新二维码改用 GET 会立即出现 EXPIRED，而 POST 返回 NEW。
- 仅 CONFIRMED 中合法的 `bizExt` / `pds_login_result` 能建立会话；扫描和等待不写账号。客户端标识为首方网页的公开标识，不使用 AppSecret。
- 用本机独立随机生成的 secp256k1 密钥创建带恢复位的标准 ECDSA 设备签名；不从用户 ID 推导私钥。
- `auth.alipan.com/v2/account/token` 续期；响应缺少 `refresh_token` 时保留旧值。401 最多续期并重建设备一次，持续鉴权失败停止，权限错误不续期循环。
- 资源库/备份盘分别绑定实际 drive ID；外层保存编码后的不透明目录/文件标识。原文件地址使用 `api.alipan.com/v2/file/get_download_url`，保留必要请求头及有效期，不调用视频转码接口。

依据：[阿里登录页面](https://www.alipan.com/sign/in)、[现网网页 bundle](https://g.alicdn.com/aliyun-drive-fe/aliyun-drive/6.8.12-web/web/bundle.js)。用户授权的本地 NetVplayer `DriveEngine/AliDriveClient.swift` 用于核对续期域名、原文件直链、缺省刷新令牌和失效重试规则；本项目没有收录其实现。

[阿里官方 SDK](https://alibaba.github.io/aliyunpan-ios-sdk/)要求开发者自己的 AppId；其 PKCE/二维码方式不能笼统描述成必须使用 AppSecret。这里实现的是首方网页会话，未借用其他开发者的应用私钥。

## 百度网盘

- 首方 `passport.baidu.com/v2/api/getqrcode` 返回二维码图片地址；UI 加载受限大小的官方图片，不把图片 URL 错当扫码内容再次编码。
- `/channel/unicast` 使用 POST；手机确认后交换官方票据。为交换响应指定固定 JSONP 回调，只解析 JSON，不执行返回的 JavaScript。
- 保留合法 Set-Cookie，并处理首方交换响应中的 BDUSS/PTOKEN/STOKEN 会话字段。缺少真实 BDUSS 不报登录成功，再用个人盘模板信息核对账号。
- `/api/list` 分页；文件 ID 采用原路径，包含 `+` 的路径按查询参数正确编码。优先读取 `/api/filemetas` 原文件链接，必要时使用首方网页签名生成下载链接；签名算法由自有实现完成，不求值服务端脚本。
- 仅把按 CookieJar 域/路径匹配的 Cookie 附到可信百度 PCS 首方文件域名，外域不附。文件重定向由统一读取层剥除跨源认证信息。

依据：[百度首方登录 JS](https://passport.baidu.com/passApi/js/loginv4_64dd56e.js)、[现网文件页面 bundle](https://nd-static.bdstatic.com/m-static/v20-main/home/js/home.6c242846.js)。用户授权的本地 NetVplayer `DriveEngine/BaiduQRLoginClient.swift` / `BaiduDriveClient.swift` 用于核对 POST 轮询、交换响应和文件会话行为；本项目没有收录其实现或账户配置。

百度标准 OpenAPI 的授权码模式需要开发者自己的 AppKey/SecretKey，见 [官方授权文档](https://pan.baidu.com/union/doc/使用入门/接入授权/授权码模式/)。本项目不借用该密钥。[官方下载文档](https://pan.baidu.com/union/doc/基础网盘服务/下载/)也说明了 Range 和 User-Agent 要求。

## 验证限制

24 项受控协议测试覆盖了二维码状态、CamelCase/包装响应、签名、双盘目录、分页、令牌轮转、401 单次重试、权限错误、JSONP、Cookie 域隔离和拒绝无效会话。匿名现网仅核对二维码生成、阿里未扫码轮询和百度交换响应格式。

真实扫码确认、目录、令牌续期和原文件在线阅读仍未用真实账号验收；不能把本地夹具测试称为真实网盘已通过。具体范围见 [网盘验收](../CLOUD-CONNECTIONS.md)。
