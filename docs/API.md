# API 摘要

管理端与成员端使用 `Authorization: Bearer <JWT>`。访问令牌有效期 15 分钟，
刷新令牌采用单次轮换，修改密码会撤销该账号的全部现有令牌。

## 认证

- `POST /auth/login`
- `POST /auth/refresh`
- `POST /auth/logout`
- `GET /auth/me`
- `POST /auth/change-password`

连续登录失败达到 5 次后，账号名会被限制 15 分钟。启用 TOTP 的账号必须在
登录请求中提供六位 `otp`。

## 经营接口

- `GET/POST /rooms`
- `GET/POST /shifts`
- `GET/POST /customers`
- `GET/POST /tasks`
- `POST /tasks/{id}/claim|start|submit|approve|reject|cancel`
- `POST /revenue-imports/preview`
- `POST /revenue-imports/{id}/commit`
- `POST /settlements/rules|expenses|preview|close`
- `POST /settlements/{id}/adjustments`
- `GET /reports/summary`
- `GET/POST /devices`
- `POST /devices/{id}/disable`
- `GET /audit`
- `POST /migration/legacy`

所有查询同时校验组织和厅房范围。房间范围账号无法读取未授权厅房的排班、
客户、作业和报表。作业读取要求 `tasks.read` 权限。

作业响应包含递增的 `version`。成员端可在领取或开始作业时传
`expectedVersion` 查询参数，并使用 `X-Client-Operation-Id` 提供客户端操作
ID；重复操作会按该 ID 幂等处理。

## 厅控设备

厅控设备事件不使用成员 JWT。设备注册后使用：

- `X-Device-Id`
- `X-Timestamp`：Unix 毫秒，允许误差 5 分钟。
- `X-Signature`：`HMAC-SHA256(deviceSecret, timestamp + "\n" + rawBody)` 的十六进制值。
- `eventId`：全局幂等 ID。

设备禁用后，新的事件签名即使正确也会被拒绝。

API 入口由 Caddy 暴露为 `https://DOMAIN/api`。
