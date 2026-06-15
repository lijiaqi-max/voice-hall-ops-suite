# 测试与发布门槛

已自动化覆盖：

- 后台：财务计算、设备加密、密码与可选 TOTP 登录、建厅、排班冲突、账单总额校验、设备事件签名与幂等。
- 管理台：金额元/分转换、中文账单字段映射、TypeScript 生产构建。
- 成员端：原关系策略测试、Room `5 → 6` 迁移测试编译、Room schema 生成、Release Lint 与签名构建。
- 厅控端：指令解析、容量、主持插入、10 秒防抖、XLSX 生成、Room `1 → 2` 迁移测试编译、Release Lint 与签名构建。

仍必须在发布前执行：

- PostgreSQL Docker Compose 端到端部署。
- `1.2.0 -> 2.0.0` 成员端覆盖安装。
- `0.1.0 -> 1.0.0` 厅控端覆盖安装。
- 微信和映客真机校准。
- 纯文字、1/2/3 图的真实人工跟进流程。
- 断网、恢复、设备时钟偏差和 30 天备份恢复演练。

## 比赛环境快速验证

```powershell
.\scripts\smoke-web.ps1
.\scripts\reset-demo.ps1 -SkipBuild
.\scripts\start-demo.ps1
.\scripts\stop-demo.ps1
```

`smoke-web.ps1` 使用独立内存数据库并自动清理进程；`reset-demo.ps1` 只重建 `build/demo` 下经过路径校验的专用演示数据库。
