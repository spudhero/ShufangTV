# 原生界面图

这些图片由应用的原生 Android View 在 Android API 31 / 1920×1080 TV 配置下，通过 Robolectric 图形引擎渲染；不是浏览器原型，也不是电视实机截屏。书籍为原创指南和自动生成的测试文本。

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
