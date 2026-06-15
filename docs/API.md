# API 摘要

管理端与成员端使用 `Authorization: Bearer <JWT>`。访问令牌有效期为 15 分钟，
刷新令牌采用单次轮换；注销、修改密码或禁用账号会撤销对应令牌。

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
- `GET /audit`
- `POST /migration/legacy`

所有查询同时校验组织和厅房范围。房间范围账号无法读取未授权厅房的排班、
客户、作业和报表。作业读取要求 `tasks.read`，厅控数据读取要求 `mic.read`，
财务数据读取要求 `finance.read`。

作业响应包含递增的 `version`。成员端可在领取或开始作业时传 `expectedVersion`
查询参数，并使用 `X-Client-Operation-Id` 提供客户端操作 ID；重复操作会按该
ID 幂等处理。

## 厅控设备

设备管理接口：

- `GET /devices`
- `POST /devices/register`
- `POST /devices/{id}/disable`
- `POST /devices/events`
- `GET /mic-segments`
- `GET /queue-entries`
- `GET /bindings`
- `GET /attendance`

厅控设备事件不使用成员 JWT。设备注册后使用：

- `X-Device-Id`
- `X-Timestamp`：Unix 毫秒，允许误差 5 分钟
- `X-Signature`：`HMAC-SHA256(deviceSecret, timestamp + "\n" + rawBody)` 的十六进制值
- `eventId`：设备事件全局幂等 ID

当前支持的投影事件：

- `shift_upsert`：创建或更新云端班次
- `binding_upsert`：同步微信昵称与映客昵称绑定
- `queue_entry_upsert`：同步普通成员或主持的排麦状态
- `seat_snapshot`：投影麦时分段和出勤统计

事件先保存原始记录，再在同一事务中投影。依赖尚未到达时响应
`accepted=false`，设备保留事件并在重连后使用同一 `eventId` 重放；投影成功后
再次重放只返回已有结果，不重复生成麦时或队列。设备被禁用后，新的签名事件会
被拒绝。

`seat_snapshot.pageStatus` 只有 `voice_room` 才参与计时。昵称短暂消失不足
10 秒不会拆段；页面不可读时立即在最后一次可信时间关闭为 `uncertain`，不推算
不可见期间时长。

API 由 Caddy 暴露为 `https://DOMAIN/api`。
