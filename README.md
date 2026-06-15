# 语音厅运营中台 1.0.0

面向单组织、多语音厅的私有部署运营系统。项目由管理台、成员端、厅控端和私有云后台组成。

## 组件

- `web-admin`：React/TypeScript 管理台，覆盖经营、厅房、排班、成员、关系、作业、流水、结算、报表和审计。
- `services/api`：Kotlin/Ktor API，使用 PostgreSQL、Argon2id、JWT、设备 HMAC 签名和 Flyway。
- `apps/member-android`：成员人工跟进端，沿用 `com.local.interactionassistant.executor` 包名与原签名。
- `apps/control-android`：微信/映客厅控采集端，沿用 `com.local.micqueueassistant` 包名与原签名。
- `infra`：Docker Compose、Caddy HTTPS、MinIO、加密备份与恢复脚本。

## 安全边界

- 财务金额只采用官方 CSV/XLSX 账单，数据库使用人民币“分”的整数。
- 高价值等级只表示已确认流水层级，不推断真实财富能力。
- 成员端不申请无障碍权限，不自动私信、群发或诱导消费。
- 厅控端是唯一申请无障碍权限的 APK；正式微信和映客适配器在真机校准前保持关闭。
- 不使用平台私有接口，不处理登录、验证码或安全验证。

## 本地构建

```powershell
cd D:\int\voice-hall-ops-suite
.\scripts\build-all.ps1
```

产物输出到 `artifacts`。本机未安装 Docker 时，仍可完成 API、网页和 Android 构建，但容器运行验收必须在装有 Docker Compose 的 VPS 或开发机执行。

运行 `.\scripts\package-source.ps1` 会生成不含签名密钥、数据库和构建缓存的源码部署包 `voice-hall-ops-suite-1.0.0-source-and-deploy.zip`。

## 首次部署

1. 复制 `.env.example` 为 `infra/.env`，替换全部密码和密钥。
2. 将域名解析到 VPS，并开放 TCP `80/443` 与 UDP `443`。
3. 在 `infra` 目录执行 `docker compose --env-file .env up -d --build`。
4. 打开 `https://你的域名`，使用启动账号登录后立即创建日常管理员、财务和成员账号。
5. 在管理台创建厅房，再为厅控端生成设备注册信息。

详见 [安装手册](docs/INSTALL.md)、[架构说明](docs/ARCHITECTURE.md)、[权限说明](docs/PERMISSIONS.md) 和 [验收记录](docs/VALIDATION.md)。
