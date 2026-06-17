import type {
  Account,
  Audit,
  Customer,
  Device,
  MicSegment,
  ReportSummary,
  Room,
  Settlement,
  Shift,
  Task,
} from "./types";

export type ReadinessLevel = "pass" | "warn" | "block";

export type ReadinessCheck = {
  id: string;
  title: string;
  detail: string;
  level: ReadinessLevel;
  nextAction: string;
};

export type ReadinessInput = {
  rooms: Room[];
  accounts: Account[];
  shifts: Shift[];
  customers: Customer[];
  tasks: Task[];
  report: ReportSummary;
  devices: Device[];
  micSegments: MicSegment[];
  settlements: Settlement[];
  audit: Audit[];
};

const hasEnabledRole = (devices: Device[], role: string) =>
  devices.some((device) => device.enabled && device.role === role);

const hasCalibratedWechat = (devices: Device[]) =>
  devices.some((device) =>
    device.enabled &&
    device.role === "robot" &&
    device.wechatGroupReplyEnabled &&
    device.wechatCalibrationStatus === "已校准",
  );

const hasCalibratedIngkee = (devices: Device[]) =>
  devices.some((device) =>
    device.enabled &&
    device.role === "collector" &&
    device.ingkeeVoiceRoomCaptureEnabled &&
    device.ingkeeCalibrationStatus === "已校准",
  );

export function buildReadinessChecks(input: ReadinessInput): ReadinessCheck[] {
  const closedMicSegments = input.micSegments.filter((segment) => segment.state === "closed").length;
  const closedSettlements = input.settlements.filter((settlement) => settlement.state === "closed").length;
  const openTasks = input.tasks.filter((task) => !["approved", "cancelled"].includes(task.state)).length;
  const priorityCustomers = input.customers.filter((customer) => customer.valueLevel !== "standard").length;

  return [
    {
      id: "rooms",
      title: "厅房与账号",
      detail: `${input.rooms.length} 个厅房，${input.accounts.length} 个账号`,
      level: input.rooms.length > 0 && input.accounts.length > 1 ? "pass" : "block",
      nextAction: "先创建组织下的厅房、管理员、财务、排班员和成员账号。",
    },
    {
      id: "rules",
      title: "排班与基础业务",
      detail: `${input.shifts.length} 个班次，${input.customers.length} 位客户`,
      level: input.shifts.length > 0 && input.customers.length > 0 ? "pass" : "warn",
      nextAction: "补齐主持排班和首批关系客户；演示环境至少需要一个当天班次。",
    },
    {
      id: "devices",
      title: "厅控设备注册",
      detail: `微信端 ${hasEnabledRole(input.devices, "robot") ? "已注册" : "未注册"}，映客端 ${hasEnabledRole(input.devices, "collector") ? "已注册" : "未注册"}`,
      level: hasEnabledRole(input.devices, "robot") && hasEnabledRole(input.devices, "collector") ? "pass" : "block",
      nextAction: "在厅控数据页生成 10 分钟一次性令牌，分别注册微信机器人端和映客采集端。",
    },
    {
      id: "calibration",
      title: "正式自动化校准",
      detail: `微信 ${hasCalibratedWechat(input.devices) ? "已启用" : "待校准"}，映客 ${hasCalibratedIngkee(input.devices) ? "已启用" : "待校准"}`,
      level: hasCalibratedWechat(input.devices) && hasCalibratedIngkee(input.devices) ? "pass" : "block",
      nextAction: "真机核验包名、版本、页面节点、群名/语音房节点和端到端结果后，再启用正式能力。",
    },
    {
      id: "mic",
      title: "麦时闭环",
      detail: `${closedMicSegments} 条已关闭麦时分段`,
      level: closedMicSegments > 0 ? "pass" : "warn",
      nextAction: "用模拟应用或真机完成一次上麦、下麦、换位和 10 秒防抖采集。",
    },
    {
      id: "revenue",
      title: "流水导入",
      detail: `近 30 天流水 ${input.report.grossCents > 0 ? "已有数据" : "暂无数据"}`,
      level: input.report.grossCents > 0 ? "pass" : "warn",
      nextAction: "导入一份脱敏官方账单，确认交易号去重、总额一致和客户等级更新。",
    },
    {
      id: "settlement",
      title: "财务结算",
      detail: `${closedSettlements} 个已关闭结算期`,
      level: closedSettlements > 0 ? "pass" : "warn",
      nextAction: "创建结算规则，关闭一个结算期，并导出利润表和成员明细。",
    },
    {
      id: "tasks",
      title: "作业运营",
      detail: `${openTasks} 条待处理作业，${priorityCustomers} 位重点客户`,
      level: input.tasks.length > 0 ? "pass" : "warn",
      nextAction: "从客户分层生成作业，成员提交后由管理员审核，禁止联系客户必须阻断。",
    },
    {
      id: "audit",
      title: "审计追踪",
      detail: `${input.audit.length} 条最近审计日志`,
      level: input.audit.length > 0 ? "pass" : "warn",
      nextAction: "上线前至少覆盖登录、设备注册、能力启用、账单提交、结算关闭和作业审核日志。",
    },
  ];
}

export function readinessScore(checks: ReadinessCheck[]) {
  const passed = checks.filter((check) => check.level === "pass").length;
  const blocked = checks.filter((check) => check.level === "block").length;
  const warned = checks.filter((check) => check.level === "warn").length;
  return {
    passed,
    blocked,
    warned,
    total: checks.length,
    percent: Math.round((passed / checks.length) * 100),
    state: blocked > 0 ? "blocked" : warned > 0 ? "partial" : "ready",
  };
}
