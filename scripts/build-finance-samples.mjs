import fs from "node:fs/promises";
import path from "node:path";
import { SpreadsheetFile, Workbook } from "@oai/artifact-tool";

const root = path.resolve(import.meta.dirname, "..");
const outputDir = path.join(root, "artifacts");
const previewDir = path.join(outputDir, "previews");
await fs.mkdir(previewDir, { recursive: true });

const palette = {
  green: "#173F33",
  green2: "#2C6A55",
  mint: "#E4EFE8",
  cream: "#F7F2E6",
  gold: "#D2A24D",
  line: "#D9E1DC",
  red: "#A33B38",
  white: "#FFFFFF",
  text: "#1D2A24",
  muted: "#65736D",
};
const money = '¥#,##0.00;[Red](¥#,##0.00);-';
const percent = '0.0%;[Red](0.0%);-';

function title(sheet, range, text) {
  sheet.mergeCells(range);
  const cell = sheet.getRange(range);
  cell.values = [[text]];
  cell.format = {
    fill: palette.green,
    font: { bold: true, color: palette.white },
    horizontalAlignment: "left",
    verticalAlignment: "center",
  };
  cell.format.rowHeight = 34;
}

function header(range) {
  range.format = {
    fill: palette.green2,
    font: { bold: true, color: palette.white },
    borders: { preset: "all", style: "thin", color: palette.line },
    verticalAlignment: "center",
  };
  range.format.rowHeight = 25;
}

function body(range) {
  range.format = {
    borders: { preset: "all", style: "thin", color: palette.line },
    verticalAlignment: "center",
  };
}

async function buildImportTemplate() {
  const workbook = Workbook.create();
  const sheet = workbook.worksheets.add("官方账单导入");
  sheet.showGridLines = false;
  title(sheet, "A1:F1", "语音厅运营中台 · 官方账单导入模板");
  sheet.getRange("A2:F2").values = [[
    "仅填写平台官方账单字段。流水金额单位为元；系统导入时转换为整数分。",
    null, null, null, null, null,
  ]];
  sheet.mergeCells("A2:F2");
  sheet.getRange("A2:F2").format = { fill: palette.cream, font: { color: palette.muted } };
  sheet.getRange("A4:F4").values = [[
    "平台交易号", "用户ID", "用户昵称", "礼物类别", "流水金额", "发生时间",
  ]];
  header(sheet.getRange("A4:F4"));
  sheet.getRange("A5:F7").values = [
    ["T-20260614-001", "U-10001", "示例用户甲", "公开礼物", 99.9, new Date("2026-06-14T20:00:00+08:00")],
    ["T-20260614-002", "U-10002", "示例用户乙", "公开礼物", 188, new Date("2026-06-14T20:15:00+08:00")],
    [null, null, null, null, null, null],
  ];
  body(sheet.getRange("A5:F104"));
  sheet.getRange("E5:E104").format.numberFormat = "0.00";
  sheet.getRange("F5:F104").format.numberFormat = "yyyy-mm-dd hh:mm:ss";
  sheet.getRange("D5:D104").dataValidation = {
    rule: { type: "list", values: ["公开礼物", "活动奖励", "其他官方流水"] },
  };
  sheet.tables.add("A4:F104", true, "OfficialRevenueImportTable");
  sheet.freezePanes.freezeRows(4);
  const widths = [22, 16, 18, 18, 14, 22];
  widths.forEach((width, index) => {
    sheet.getRangeByIndexes(0, index, 104, 1).format.columnWidth = width;
  });
  const preview = await workbook.render({
    sheetName: "官方账单导入",
    range: "A1:F14",
    scale: 1.4,
    format: "png",
  });
  await fs.writeFile(
    path.join(previewDir, "official-revenue-import-template.png"),
    new Uint8Array(await preview.arrayBuffer()),
  );
  const output = await SpreadsheetFile.exportXlsx(workbook);
  await output.save(path.join(outputDir, "official-revenue-import-template.xlsx"));
}

async function buildFinanceSample() {
  const workbook = Workbook.create();
  const summary = workbook.worksheets.add("管理摘要");
  const details = workbook.worksheets.add("账单明细");
  const rules = workbook.worksheets.add("结算规则");
  const settlement = workbook.worksheets.add("经营结算");
  const checks = workbook.worksheets.add("Checks");
  [summary, details, rules, settlement, checks].forEach((sheet) => {
    sheet.showGridLines = false;
  });

  title(details, "A1:G1", "官方账单明细（示例）");
  details.getRange("A3:G3").values = [[
    "日期", "厅房", "平台交易号", "客户ID", "客户昵称", "礼物类别", "总流水（元）",
  ]];
  header(details.getRange("A3:G3"));
  details.getRange("A4:G11").values = [
    [new Date("2026-06-08"), "星河一厅", "T-001", "U-101", "示例甲", "公开礼物", 1280],
    [new Date("2026-06-09"), "星河一厅", "T-002", "U-102", "示例乙", "公开礼物", 860],
    [new Date("2026-06-10"), "月光二厅", "T-003", "U-103", "示例丙", "活动奖励", 1560],
    [new Date("2026-06-11"), "星河一厅", "T-004", "U-104", "示例丁", "公开礼物", 2200],
    [new Date("2026-06-12"), "月光二厅", "T-005", "U-101", "示例甲", "公开礼物", 980],
    [new Date("2026-06-13"), "星河一厅", "T-006", "U-105", "示例戊", "公开礼物", 1750],
    [new Date("2026-06-14"), "月光二厅", "T-007", "U-106", "示例己", "公开礼物", 1320],
    [new Date("2026-06-14"), "星河一厅", "T-008", "U-103", "示例丙", "其他官方流水", 640],
  ];
  body(details.getRange("A4:G11"));
  details.getRange("A4:A11").format.numberFormat = "yyyy-mm-dd";
  details.getRange("G4:G11").setNumberFormat(money);
  details.getRange("G4:G11").format.font = { color: "#0000FF" };
  details.tables.add("A3:G11", true, "RevenueDetailTable");
  details.freezePanes.freezeRows(3);
  [13, 16, 17, 14, 17, 18, 16].forEach((width, index) => {
    details.getRangeByIndexes(0, index, 11, 1).format.columnWidth = width;
  });

  title(rules, "A1:D1", "结算规则与经营假设");
  rules.getRange("A3:D3").values = [["项目", "数值", "单位", "说明"]];
  header(rules.getRange("A3:D3"));
  rules.getRange("A4:D9").values = [
    ["平台扣除比例", 0.1, "%", "按总流水计算"],
    ["公会/厅分成比例", 0.2, "%", "按扣除平台费用后的流水计算"],
    ["成员佣金比例", 0.35, "%", "按扣除平台费用后的流水计算"],
    ["星河一厅主持费用", 850, "元", "示例期间固定班费与时长费用"],
    ["月光二厅主持费用", 720, "元", "示例期间固定班费与时长费用"],
    ["其他支出", 430, "元", "场地、活动与其他经审批支出"],
  ];
  body(rules.getRange("A4:D9"));
  rules.getRange("B4:B6").setNumberFormat(percent);
  rules.getRange("B7:B9").setNumberFormat(money);
  rules.getRange("B4:B9").format.font = { color: "#0000FF" };
  rules.getRange("A11:D13").values = [
    ["模型约定", null, null, null],
    ["金额单位", "人民币元", null, "系统数据库实际使用整数分保存"],
    ["权威来源", "官方账单", null, "可见礼物事件不直接入账"],
  ];
  rules.getRange("A11:D11").format = { fill: palette.cream, font: { bold: true, color: palette.text } };
  rules.getRange("A3:D13").format.wrapText = true;
  [24, 16, 12, 36].forEach((width, index) => {
    rules.getRangeByIndexes(0, index, 13, 1).format.columnWidth = width;
  });

  title(settlement, "A1:J1", "经营结算表（示例期间：2026-06-08 至 2026-06-14）");
  settlement.getRange("A3:J3").values = [[
    "厅房", "总流水", "平台扣除", "分成基数", "公会/厅分成", "成员佣金",
    "主持费用", "其他支出", "应收/应付净额", "净利润",
  ]];
  header(settlement.getRange("A3:J3"));
  settlement.getRange("A4:A6").values = [["星河一厅"], ["月光二厅"], ["合计"]];
  settlement.getRange("B4").formulas = [['=SUMIFS(\'账单明细\'!$G$4:$G$11,\'账单明细\'!$B$4:$B$11,A4)']];
  settlement.getRange("B4:B5").fillDown();
  settlement.getRange("C4").formulas = [["=ROUND(B4*'结算规则'!$B$4,2)"]];
  settlement.getRange("C4:C5").fillDown();
  settlement.getRange("D4").formulas = [["=B4-C4"]];
  settlement.getRange("D4:D5").fillDown();
  settlement.getRange("E4").formulas = [["=ROUND(D4*'结算规则'!$B$5,2)"]];
  settlement.getRange("E4:E5").fillDown();
  settlement.getRange("F4").formulas = [["=ROUND(D4*'结算规则'!$B$6,2)"]];
  settlement.getRange("F4:F5").fillDown();
  settlement.getRange("G4:G5").formulas = [["='结算规则'!$B$7"], ["='结算规则'!$B$8"]];
  settlement.getRange("H4:H5").formulas = [["='结算规则'!$B$9/2"], ["='结算规则'!$B$9/2"]];
  settlement.getRange("I4").formulas = [["=ROUND(D4-E4-F4,2)"]];
  settlement.getRange("I4:I5").fillDown();
  settlement.getRange("J4").formulas = [["=ROUND(B4-C4-E4-F4-G4-H4,2)"]];
  settlement.getRange("J4:J5").fillDown();
  settlement.getRange("B6:J6").formulas = [[
    "=SUM(B4:B5)", "=SUM(C4:C5)", "=SUM(D4:D5)", "=SUM(E4:E5)", "=SUM(F4:F5)",
    "=SUM(G4:G5)", "=SUM(H4:H5)", "=SUM(I4:I5)", "=SUM(J4:J5)",
  ]];
  body(settlement.getRange("A4:J6"));
  settlement.getRange("A6:J6").format = {
    fill: palette.mint,
    font: { bold: true, color: palette.text },
    borders: { preset: "doubleBottom", style: "medium", color: palette.green },
  };
  settlement.getRange("B4:J6").setNumberFormat(money);
  settlement.getRange("B4:J6").format.font = { color: "#000000" };
  settlement.getRange("A4:A6").format.font = { color: "#008000" };
  [16, 15, 15, 15, 17, 15, 15, 15, 18, 16].forEach((width, index) => {
    settlement.getRangeByIndexes(0, index, 6, 1).format.columnWidth = width;
  });

  title(checks, "A1:F1", "财务勾稽检查");
  checks.getRange("A3:F3").values = [["检查项", "实际", "预期", "差异", "状态", "说明"]];
  header(checks.getRange("A3:F3"));
  checks.getRange("A4:A6").values = [["流水总额一致"], ["结算组件勾稽"], ["模型总状态"]];
  checks.getRange("B4").formulas = [["='经营结算'!B6"]];
  checks.getRange("C4").formulas = [["=SUM('账单明细'!G4:G11)"]];
  checks.getRange("D4").formulas = [["=B4-C4"]];
  checks.getRange("E4").formulas = [['=IF(ABS(D4)<0.01,"OK","FAIL")']];
  checks.getRange("B5").formulas = [["='经营结算'!J6"]];
  checks.getRange("C5").formulas = [["='经营结算'!B6-'经营结算'!C6-'经营结算'!E6-'经营结算'!F6-'经营结算'!G6-'经营结算'!H6"]];
  checks.getRange("D5").formulas = [["=B5-C5"]];
  checks.getRange("E5").formulas = [['=IF(ABS(D5)<0.01,"OK","FAIL")']];
  checks.getRange("E6").formulas = [['=IF(COUNTIF(E4:E5,"FAIL")=0,"OK","FAIL")']];
  checks.getRange("F4:F6").values = [["官方账单明细应等于结算总流水"], ["净利润应与各扣除组件一致"], ["所有检查均通过才可关闭结算期"]];
  body(checks.getRange("A4:F6"));
  checks.getRange("B4:D5").setNumberFormat(money);
  checks.getRange("E4:E6").conditionalFormats.add("containsText", {
    text: "OK",
    format: { fill: "#DFF0E4", font: { color: "#21633C", bold: true } },
  });
  checks.getRange("E4:E6").conditionalFormats.add("containsText", {
    text: "FAIL",
    format: { fill: "#F6DEDC", font: { color: palette.red, bold: true } },
  });
  [24, 16, 16, 16, 12, 40].forEach((width, index) => {
    checks.getRangeByIndexes(0, index, 6, 1).format.columnWidth = width;
  });

  title(summary, "A1:H1", "语音厅经营管理摘要");
  summary.getRange("A3:B3").values = [["指标", "结果"]];
  header(summary.getRange("A3:B3"));
  summary.getRange("A4:A8").values = [["总流水"], ["平台扣除"], ["成员佣金"], ["主持与其他成本"], ["净利润"]];
  summary.getRange("B4:B8").formulas = [
    ["='经营结算'!B6"],
    ["='经营结算'!C6"],
    ["='经营结算'!F6"],
    ["='经营结算'!G6+'经营结算'!H6"],
    ["='经营结算'!J6"],
  ];
  body(summary.getRange("A4:B8"));
  summary.getRange("B4:B8").setNumberFormat(money);
  summary.getRange("A10:B10").values = [["模型检查", null]];
  summary.getRange("A10:B10").format = { fill: palette.cream, font: { bold: true, color: palette.text } };
  summary.getRange("B10").formulas = [["='Checks'!E6"]];
  summary.getRange("A12:B14").values = [
    ["使用说明", "修改蓝色输入单元格后，所有结算与摘要自动更新。"],
    ["金额精度", "示例表以元显示；正式系统以人民币分的整数保存。"],
    ["合规边界", "价值等级只反映已确认流水，不代表用户真实财富能力。"],
  ];
  summary.getRange("A12:B14").format.wrapText = true;
  summary.getRange("D3:E3").values = [["厅房", "总流水"]];
  summary.getRange("D4:E5").formulas = [
    ["='经营结算'!A4", "='经营结算'!B4"],
    ["='经营结算'!A5", "='经营结算'!B5"],
  ];
  const chart = summary.charts.add("bar", summary.getRange("D3:E5"));
  chart.title = "厅房流水对比（元）";
  chart.hasLegend = false;
  chart.yAxis = { numberFormatCode: "¥#,##0" };
  chart.setPosition("D7", "H19");
  summary.getRange("A1:H19").format.verticalAlignment = "center";
  summary.getRange("A1:H19").format.wrapText = true;
  [22, 26, 4, 18, 16, 14, 14, 14].forEach((width, index) => {
    summary.getRangeByIndexes(0, index, 19, 1).format.columnWidth = width;
  });

  const inspect = await workbook.inspect({
    kind: "table",
    range: "经营结算!A1:J6",
    include: "values,formulas",
    tableMaxRows: 8,
    tableMaxCols: 12,
  });
  console.log(inspect.ndjson);
  const errors = await workbook.inspect({
    kind: "match",
    searchTerm: "#REF!|#DIV/0!|#VALUE!|#NAME\\?|#N/A",
    options: { useRegex: true, maxResults: 100 },
    summary: "finance sample formula error scan",
  });
  console.log(errors.ndjson);

  for (const sheetName of ["管理摘要", "账单明细", "结算规则", "经营结算", "Checks"]) {
    const preview = await workbook.render({ sheetName, autoCrop: "all", scale: 1.2, format: "png" });
    await fs.writeFile(
      path.join(previewDir, `${sheetName}.png`),
      new Uint8Array(await preview.arrayBuffer()),
    );
  }
  const output = await SpreadsheetFile.exportXlsx(workbook);
  await output.save(path.join(outputDir, "voice-hall-finance-sample.xlsx"));
}

await buildImportTemplate();
await buildFinanceSample();
console.log("spreadsheet artifacts created");
