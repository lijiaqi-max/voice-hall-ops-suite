import { describe, expect, it } from "vitest";
import { buildReadinessChecks, readinessScore, type ReadinessInput } from "./readiness";

const baseInput = (): ReadinessInput => ({
  rooms: [],
  accounts: [],
  shifts: [],
  customers: [],
  tasks: [],
  report: {
    periodStartEpochMs: 1,
    periodEndEpochMs: 2,
    grossCents: 0,
    taskTotal: 0,
    taskApproved: 0,
    customerTotal: 0,
    roomTotals: [],
    dailyTotals: [],
  },
  devices: [],
  micSegments: [],
  settlements: [],
  audit: [],
});

describe("production readiness", () => {
  it("blocks launch when rooms, accounts, devices, and calibration are missing", () => {
    const checks = buildReadinessChecks(baseInput());
    const score = readinessScore(checks);

    expect(score.state).toBe("blocked");
    expect(checks.find((check) => check.id === "rooms")?.level).toBe("block");
    expect(checks.find((check) => check.id === "devices")?.level).toBe("block");
    expect(checks.find((check) => check.id === "calibration")?.level).toBe("block");
  });

  it("marks device and calibration checks as passed only when both official capabilities are enabled", () => {
    const input = baseInput();
    input.rooms = [{ id: "room-1", name: "A厅", platform: "ingkee", enabled: true }];
    input.accounts = [
      { id: "owner", organizationId: "org", username: "owner", displayName: "老板", role: "owner" },
      { id: "member", organizationId: "org", username: "member", displayName: "成员", role: "member" },
    ];
    input.devices = [
      {
        id: "robot",
        roomId: "room-1",
        name: "微信手机",
        role: "robot",
        enabled: true,
        wechatGroupReplyEnabled: true,
        ingkeeVoiceRoomCaptureEnabled: false,
        wechatCalibrationStatus: "已校准",
        ingkeeCalibrationStatus: "待校准",
      },
      {
        id: "collector",
        roomId: "room-1",
        name: "映客手机",
        role: "collector",
        enabled: true,
        wechatGroupReplyEnabled: false,
        ingkeeVoiceRoomCaptureEnabled: true,
        wechatCalibrationStatus: "待校准",
        ingkeeCalibrationStatus: "已校准",
      },
    ];

    const checks = buildReadinessChecks(input);

    expect(checks.find((check) => check.id === "devices")?.level).toBe("pass");
    expect(checks.find((check) => check.id === "calibration")?.level).toBe("pass");
  });

  it("returns ready only when every check passes", () => {
    const input = baseInput();
    input.rooms = [{ id: "room-1", name: "A厅", platform: "ingkee", enabled: true }];
    input.accounts = [
      { id: "owner", organizationId: "org", username: "owner", displayName: "老板", role: "owner" },
      { id: "member", organizationId: "org", username: "member", displayName: "成员", role: "member" },
    ];
    input.shifts = [{
      id: "shift",
      roomId: "room-1",
      title: "晚班",
      startAtEpochMs: 1,
      endAtEpochMs: 2,
      status: "open",
      hostFixedCents: 0,
      hostHourlyCents: 0,
    }];
    input.customers = [{
      id: "customer",
      displayName: "客户",
      platform: "ingkee",
      externalUserId: "u1",
      relationshipStage: "active",
      contactEligibility: "eligible",
      revenue7dCents: 100,
      revenue30dCents: 100,
      revenue90dCents: 100,
      lifetimeRevenueCents: 100,
      valueLevel: "high",
    }];
    input.tasks = [{
      id: "task",
      customerId: "customer",
      customerName: "客户",
      title: "回访",
      brief: "人工回访",
      state: "published",
      priority: 80,
      valueLevel: "high",
    }];
    input.report.grossCents = 10000;
    input.devices = [
      {
        id: "robot",
        roomId: "room-1",
        name: "微信手机",
        role: "robot",
        enabled: true,
        wechatGroupReplyEnabled: true,
        ingkeeVoiceRoomCaptureEnabled: false,
        wechatCalibrationStatus: "已校准",
        ingkeeCalibrationStatus: "待校准",
      },
      {
        id: "collector",
        roomId: "room-1",
        name: "映客手机",
        role: "collector",
        enabled: true,
        wechatGroupReplyEnabled: false,
        ingkeeVoiceRoomCaptureEnabled: true,
        wechatCalibrationStatus: "待校准",
        ingkeeCalibrationStatus: "已校准",
      },
    ];
    input.micSegments = [{
      id: "segment",
      roomId: "room-1",
      ingkeeName: "主持",
      role: "host",
      startedAtEpochMs: 1,
      endedAtEpochMs: 2,
      durationSeconds: 60,
      state: "closed",
    }];
    input.settlements = [{
      periodStartEpochMs: 1,
      periodEndEpochMs: 2,
      ruleId: "rule",
      grossCents: 10000,
      platformDeductionCents: 0,
      organizationShareCents: 10000,
      memberCommissionCents: 0,
      hostCostCents: 0,
      expenseCents: 0,
      accountsReceivableCents: 10000,
      accountsPayableCents: 0,
      netProfitCents: 10000,
      baseAccountsReceivableCents: 10000,
      baseAccountsPayableCents: 0,
      baseNetProfitCents: 10000,
      adjustmentCents: 0,
      hostCostEstimated: false,
      unallocatedRevenueCents: 0,
      reconciliationDifferenceCents: 0,
      state: "closed",
    }];
    input.audit = [{
      id: "audit",
      action: "device.calibration.update",
      resourceType: "device",
      summary: "更新设备能力",
      createdAtEpochMs: 1,
    }];

    const score = readinessScore(buildReadinessChecks(input));

    expect(score.state).toBe("ready");
    expect(score.percent).toBe(100);
  });
});
