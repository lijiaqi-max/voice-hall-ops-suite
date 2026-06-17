# 1.0.0 生产候选验收记录

验证日期：2026-06-16

## 已自动验证

- 后台：集成测试通过，覆盖一次性设备注册令牌、bootstrap、重复令牌拒绝、设备校准启用、错误能力拒绝和设备禁用；`buildFatJar` 通过，本地前端代理冒烟验证健康检查、刷新令牌、厅控和财务接口通过。
- 管理台：15 项 Vitest、TypeScript 和 Vite 生产构建通过；前端代理冒烟覆盖登录、刷新令牌轮换、注销、厅控和财务接口。
- 成员端：19 项 JVM 测试、`lintRelease`、debug instrumentation Kotlin 编译和签名 release 构建通过；Room `6 → 7` 迁移源码可编译。
- 厅控端：11 项 JVM 测试、`lintRelease`、debug instrumentation Kotlin 编译和签名 release 构建通过；Room 迁移、排麦、主持插入、10 秒防抖和事件同步测试通过。
- 财务：全程整数分/`BigInteger`，随机属性测试验证固定勾稽公式；演示结算总流水 512,300 分、净利润 225,599 分、勾稽差额 0。
- 厅控投影：绑定、队列、麦时和出勤事件按事件 ID 幂等处理，页面不可读时停止推算。
- 生产设备门禁：Android 厅控端改为 `POST /devices/bootstrap`，不再粘贴管理员访问令牌；新增设备签名 `POST /devices/config` 同步服务端能力，正式微信/映客默认关闭，必须服务端能力和本机能力同时开启后才执行。
- 演示环境：`reset-demo.ps1` 可重建独立 H2 数据库；`start-demo.ps1`、API 代理健康检查和 `stop-demo.ps1` 已通过。
- 管理台体积：首屏主脚本约 241.8 KB；约 429.2 KB 的 XLSX 依赖改为按需加载。
- APK 签名：两个 APK 均通过 APK Signature Scheme v2，证书 SHA-256 为 `9445a3629afe47529cd253c67b3816efd17d287795385b0f38cd1791a79ad0cd`。
- 成员端权限：仅联网权限和 AndroidX 内部动态接收器权限，不含无障碍服务。
- 厅控端权限：联网、通知、前台服务、连接设备前台服务和唤醒锁；是唯一声明 `BIND_ACCESSIBILITY_SERVICE` 的 APK。
- 源码部署 ZIP：241 个条目，不含签名密钥、`local.properties`、`node_modules`、Gradle 缓存、构建目录或运行数据库。
- Git 工作区：本次生产上线门禁改动尚未提交；交付前应按设备注册、正式适配器门禁、文档与发布产物分批提交。

## 当前发布产物

- `voice-hall-ops-api-1.0.0.jar`
- `voice-hall-admin-1.0.0.zip`
- `voice-hall-member-2.0.0.apk`
- `voice-hall-control-1.0.0.apk`
- `voice-hall-ops-suite-1.0.0-source-and-deploy.zip`

最终哈希以 `artifacts/SHA256SUMS.txt` 为准。

## 待真机或 VPS 完成

- 当前 `adb devices -l` 无连接设备，尚未执行 Android instrumentation、覆盖安装和断线重连真机验收。
- 微信 8.0.74 和映客 9.8.60 页面适配器尚未在两台目标真机校准；正式能力必须继续显示“待校准”，不得宣称自动回复或麦位识别已真实验证。
- 本机没有 Docker Engine，只完成 Compose、Caddy、备份脚本和 CI 的静态检查；PostgreSQL、MinIO、HTTPS 和加密备份恢复必须在目标 VPS 实跑。
- 最新管理台无法通过当前 Browser 插件执行视觉自动化；已用生产构建和前后端代理冒烟兜底，比赛前仍需人工检查 12 个页面的布局与中文显示。
- 正式发布前需完成 8 分钟计时彩排、真机失败后 15 秒切换模拟环境，以及本机/VPS/录屏三路兜底。
