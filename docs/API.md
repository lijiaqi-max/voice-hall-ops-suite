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
- `GET /tasks-stats/me`
- `POST /advice/generate`
- `GET /revenue-imports`
- `POST /revenue-imports/preview`
- `POST /revenue-imports/{id}/commit`
- `GET /settlements`
- `GET/POST /settlements/rules`
- `GET/POST /settlements/expenses`
- `POST /settlements/preview|close`
- `POST /settlements/{id}/adjustments`
- `GET /reports/summary`
- `GET /reports/finance/{settlementId}`
- `GET /reports/export.xlsx?settlementId=...`
- `GET /audit`
- `POST /migration/legacy`

所有查询同时校验组织和厅房范围。房间范围账号无法读取未授权厅房的排班、
客户、作业和报表。作业读取要求 `tasks.read`，厅控数据读取要求 `mic.read`，
财务数据读取要求 `finance.read`。

作业响应包含递增的 `version`。成员端可在领取或开始作业时传 `expectedVersion`
查询参数，并使用 `X-Client-Operation-Id` 提供客户端操作 ID；重复操作会按该
ID 幂等处理。

财务计算只使用整数分、整数基点和 `BigInteger` 中间值。主持费用优先采用状态为
`closed` 的已核验主持麦时；没有可信麦时时，试算标记 `hostCostEstimated=true`，
正式关账必须提交 `hostCostOverrideReason`。账期内仍有未归属成员的流水时禁止
关账。

调整单支持 `receivable`、`payable`、`expense` 三类效果（兼容输入 `net`，按
应收调整处理），记录调整前后应收、应付和净利润。结算表始终满足
`应收 - 应付 - 净利润 = 0`。XLSX 导出包含经营汇总、成员佣金、主持成本、
调整与支出四张工作表。

`POST /advice/generate` 只接收关系阶段、互动时间区间、价值等级、任务目的和
语气。服务端不会接收用户 ID、联系方式或精确流水；未配置 AI 服务或输出命中
索礼、消费施压、虚假亲密、频繁催促风险时返回规则模板，并始终要求人工确认。

## 厅控设备

设备管理接口：

- `GET /devices`
- `POST /devices/registration-tokens`
- `POST /devices/bootstrap`
- `POST /devices/config`
- `POST /devices/register`（兼容旧测试接口，生产不要给 Android 使用管理员 JWT）
- `POST /devices/{id}/calibration`
- `POST /devices/{id}/disable`
- `POST /devices/events`
- `GET /mic-segments`
- `GET /queue-entries`
- `GET /bindings`
- `GET /attendance`

生产设备注册流程：

1. 管理员在管理台选择厅房和设备角色，调用 `POST /devices/registration-tokens`。
2. 服务端返回 10 分钟有效、一次性显示的注册令牌，只保存令牌 SHA-256。
3. Android 厅控端提交 `HTTPS API 地址 + 设备名 + 注册令牌` 到 `POST /devices/bootstrap`。
4. bootstrap 成功后返回 `deviceId`、`deviceSecret`、`roomId` 和设备角色；令牌立即标记已用。
5. 管理台完成真机校准记录后，调用 `POST /devices/{id}/calibration` 启用 `wechat_group_reply` 或 `ingkee_voice_room_capture`。
6. Android 通过设备密钥签名调用 `POST /devices/config` 拉取服务端能力；本机也必须在设置页手动启用对应正式能力，否则正式微信/映客不会发送或计时。

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

正式微信/映客自动化必须同时满足：设备未禁用、角色和能力匹配、管理台能力已启用、Android 本机能力已启用、目标 App 包名和版本匹配、当前页面特征可验证。未知页面、版本不符或节点不足时停止，不允许固定坐标兜底。

`seat_snapshot.pageStatus` 只有 `voice_room` 才参与计时。昵称短暂消失不足
10 秒不会拆段；页面不可读时立即在最后一次可信时间关闭为 `uncertain`，不推算
不可见期间时长。

API 由 Caddy 暴露为 `https://DOMAIN/api`。
