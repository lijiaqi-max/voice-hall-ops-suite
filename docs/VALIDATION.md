# 1.0.0 验收记录

验证日期：2026-06-14

## 已通过

- 后台 `test buildFatJar`：通过。覆盖 Argon2id 登录、可选 TOTP、厅房创建、排班冲突、账单总额校验、设备 HMAC 与事件幂等。
- 发布 JAR 独立启动：使用 H2 发布验证库启动 `voice-hall-ops-api-1.0.0.jar`，`GET /health` 返回 `status=ok`、`version=1.0.0`。
- 管理台：Vitest 2 项通过，TypeScript 与 Vite 生产构建通过；本地浏览器已验证登录、看板和厅房页面切换。
- 成员端：JVM 单元测试、Release Lint、签名 release 构建通过；Room `5 → 6` 迁移 instrumentation 测试源码编译通过。
- 厅控端：JVM 单元测试、Release Lint、签名 release 构建通过；Room `1 → 2` 迁移 instrumentation 测试源码编译通过。
- APK 签名：两个 APK 均通过 APK Signature Scheme v2 验证，证书 SHA-256 为 `9445a3629afe47529cd253c67b3816efd17d287795385b0f38cd1791a79ad0cd`。
- 覆盖签名：新成员端与旧关系助手、新厅控端与旧麦序机器人证书 SHA-256 一致。
- 成员端权限：仅 `INTERNET`、`ACCESS_NETWORK_STATE` 及 AndroidX 自动生成的非导出动态接收器权限。
- 厅控端权限：联网、通知、前台服务、连接设备前台服务、唤醒锁；无障碍服务由系统通过 `BIND_ACCESSIBILITY_SERVICE` 授予。
- 财务样表：XLSX 公式与中文内容检查通过，并完成全部工作表渲染预览。
- 源码部署 ZIP：内容白名单检查通过，不含 JKS、`keystore.properties`、`local.properties`、`node_modules`、Gradle 缓存或运行数据库。
- Docker Compose：PostgreSQL、MinIO、API、Caddy、健康检查、生产密钥变量和 HTTPS 端口结构检查通过。

## 待真机或部署环境完成

- 当前 ADB 无连接设备，未执行两套 Android instrumentation 测试、覆盖安装和离线重连实机验收。
- 未完成微信与映客 `9.8.60` 真机页面校准，因此正式适配器保持不可用状态，不宣称真实环境自动回复或麦位识别已验证。
- 本机没有 Docker Engine，未执行 PostgreSQL/MinIO/Caddy 容器启动、备份恢复和 HTTPS 域名端到端验收。
- 正式上线前仍需在目标 VPS 执行一次账单导入、结算关闭、调整单、日周月报表和加密备份恢复演练。

最终文件哈希以 `artifacts/SHA256SUMS.txt` 为准。
