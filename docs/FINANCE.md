# 财务与账单

## 导入流程

1. 选择厅房并上传官方 CSV/XLSX。
2. 映射平台交易号、用户 ID、昵称、礼物类别、金额和发生时间。
3. 浏览器计算文件 SHA-256，预览有效、重复和错误行。
4. 输入官方账单声明总额。
5. 计算总额不一致时禁止提交；提交后才进入正式流水。

金额输入以元展示，API 传输和数据库均使用整数分。

## 结算

结算规则包含平台扣除比例、公会/厅分成比例和成员佣金比例，并按生效日期版本化。主持费用来自排班的固定班费和按时长费用，其他支出单独登记。

结算期关闭后不可修改原记录，只能创建带原因和审计记录的调整单。

样表：

- `artifacts/official-revenue-import-template.xlsx`
- `artifacts/official-revenue-import-template.csv`
- `artifacts/voice-hall-finance-sample.xlsx`

