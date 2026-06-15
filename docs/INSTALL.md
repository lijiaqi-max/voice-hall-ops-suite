# 安装手册

## VPS

要求：Linux、Docker Engine、Docker Compose v2、可用域名。

```bash
cd voice-hall-ops-suite/infra
cp ../.env.example .env
# 编辑 .env 后执行
docker compose --env-file .env up -d --build
docker compose --env-file .env ps
```

生产环境必须设置：

- `JWT_SECRET`：至少 32 字符随机值。
- `DEVICE_MASTER_KEY`：32 字节随机值的 Base64。
- `BOOTSTRAP_ADMIN_PASSWORD`：首次启动强密码。
- PostgreSQL、MinIO 与备份独立强密码。

## 管理台

访问 `https://DOMAIN`。管理台和 API 同域，API 路径为 `/api`。

所有者或管理员创建账号时可选填 TOTP Base32 密钥。启用后，该账号登录时必须同时填写认证器生成的 6 位动态验证码。密钥只在创建时提交，不会通过账号列表接口返回。

## 成员端

安装 `voice-hall-member-2.0.0.apk`。可覆盖原关系助手，包名和签名不变。

1. 输入 `https://DOMAIN/api`。
2. 使用成员账号登录。
3. 在任务池领取，人工交流后填写渠道、结果、备注和下次提醒。
4. 旧数据迁移仅上传到暂存区，管理员确认后再合并。

## 厅控端

安装 `voice-hall-control-1.0.0.apk`。可覆盖原麦序机器人，包名和签名不变。

1. 管理台创建厅房并由管理员登录取得短期访问令牌。
2. 厅控端设置页填写 `https://DOMAIN/api`、厅房 ID、设备名和一次性管理员令牌。
3. 注册成功后令牌立即从输入框清除，设备密钥写入 Android Keystore。
4. 开启通知、前台服务和无障碍服务，再启动同步。

## 备份

在 `infra` 目录加载 `.env` 后运行：

```bash
set -a; . ./.env; set +a
./backup.sh
./restore.sh ./backups/voice-hall-YYYYMMDDTHHMMSSZ.tar.gz.enc
```

恢复会覆盖数据库和对象存储，执行前先保留当前备份并停止业务写入。
