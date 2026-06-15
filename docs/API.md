# API 摘要

管理与成员接口使用 `Authorization: Bearer <JWT>`：

- `POST /auth/login`
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
- `POST /devices/register`
- `GET /audit`
- `POST /migration/legacy`

厅控设备事件不使用成员 JWT。设备注册后使用：

- `X-Device-Id`
- `X-Timestamp`：Unix 毫秒，允许误差 5 分钟。
- `X-Signature`：`HMAC-SHA256(deviceSecret, timestamp + "\n" + rawBody)` 的十六进制。
- `eventId`：全局幂等 ID。

接口入口由 Caddy 暴露为 `https://DOMAIN/api`。

