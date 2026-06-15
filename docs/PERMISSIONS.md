# 权限说明

## 成员端

- `INTERNET`：访问私有云 API。
- `ACCESS_NETWORK_STATE`：判断离线并使用本地任务缓存。

成员端不声明无障碍、联系人、短信、录音、相机、存储、安装应用、ROOT、Shizuku 或系统悬浮窗权限。

## 厅控端

- `INTERNET`、`ACCESS_NETWORK_STATE`：同步签名事件。
- `POST_NOTIFICATIONS`：显示前台同步状态。
- `FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_CONNECTED_DEVICE`：持续运行采集和同步。
- `WAKE_LOCK`：目标页面前台采集时保持稳定。
- `BIND_ACCESSIBILITY_SERVICE`：仅由系统授予无障碍服务，用于当前可见微信或映客页面。

厅控端不申请联系人、短信、录音、相机、ROOT、Shizuku 或安装/删除应用权限。

## 账号角色

- 所有者：全部权限。
- 管理员：厅房、成员、作业、财务、设备和审核。
- 排班员：排班、客户和作业。
- 财务：账单、结算、报表、审计和精确金额。
- 成员：客户摘要、领取与执行作业，不默认查看精确流水。
- 审计员：只读报表、审计和精确金额。
- 厅控设备：只允许提交已签名设备事件。

账号密码使用 Argon2id 保存，访问令牌默认 15 分钟有效；账号可选启用 TOTP。成员令牌与厅控设备密钥是两套独立凭据，设备密钥由 Android Keystore 保护。
