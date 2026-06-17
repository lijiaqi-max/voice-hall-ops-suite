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

1. 管理台创建厅房。
2. 管理台进入“厅控数据”，选择厅房和设备角色，生成 10 分钟有效的一次性设备注册令牌。
3. 厅控端先选择角色：微信机器人端或映客采集端。手机角色必须和注册令牌角色一致。
4. 厅控端设置页填写 `https://DOMAIN/api`、设备名和一次性设备注册令牌。不要填写普通管理员访问令牌。
5. 注册成功后令牌立即从输入框清除，设备密钥写入 Android Keystore。
6. 管理台记录真机校准结果并启用对应能力；厅控端启动同步，拉取到“管理台能力：已启用”后，本机再点击“真机校准通过，启用微信/映客”。
7. 开启通知、前台服务和无障碍服务，再启动同步。

正式微信/映客自动化默认关闭。未完成真机校准、管理台能力启用和本机能力启用前，模拟应用可跑通，但正式微信不会自动回复，正式映客不会计时。

## 备份

在 `infra` 目录加载 `.env` 后运行：

```bash
set -a; . ./.env; set +a
./backup.sh
./restore.sh ./backups/voice-hall-YYYYMMDDTHHMMSSZ.tar.gz.enc
```

恢复会覆盖数据库和对象存储，执行前先保留当前备份并停止业务写入。
