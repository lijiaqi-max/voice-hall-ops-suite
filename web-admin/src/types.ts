export type Account = {
  id: string;
  organizationId: string;
  username: string;
  displayName: string;
  role: string;
};

export type Session = {
  accessToken: string;
  expiresInSeconds: number;
  account: Account;
  permissions: string[];
};

export type Room = {
  id: string;
  name: string;
  platform: string;
  externalRoomId?: string;
  enabled: boolean;
};

export type Shift = {
  id: string;
  roomId: string;
  hostAccountId?: string;
  title: string;
  startAtEpochMs: number;
  endAtEpochMs: number;
  status: string;
  hostFixedCents: number;
  hostHourlyCents: number;
};

export type Customer = {
  id: string;
  displayName: string;
  platform: string;
  externalUserId: string;
  relationshipStage: string;
  contactEligibility: string;
  lastInteractionAtEpochMs?: number;
  revenue7dCents: number;
  revenue30dCents: number;
  revenue90dCents: number;
  lifetimeRevenueCents: number;
  valueLevel: string;
};

export type Task = {
  id: string;
  roomId?: string;
  customerId: string;
  customerName: string;
  title: string;
  brief: string;
  state: string;
  priority: number;
  assignedAccountId?: string;
  resultChannel?: string;
  resultNote?: string;
  valueLevel: string;
  visibleRevenueCents?: number;
};

export type ReportSummary = {
  periodStartEpochMs: number;
  periodEndEpochMs: number;
  grossCents: number;
  taskTotal: number;
  taskApproved: number;
  customerTotal: number;
  roomTotals: { roomId: string; roomName: string; grossCents: number }[];
  dailyTotals: { date: string; grossCents: number }[];
};

export type Audit = {
  id: string;
  accountId?: string;
  action: string;
  resourceType: string;
  resourceId?: string;
  summary: string;
  createdAtEpochMs: number;
};

export type Device = {
  id: string;
  roomId: string;
  name: string;
  role: string;
  enabled: boolean;
  wechatGroupReplyEnabled: boolean;
  ingkeeVoiceRoomCaptureEnabled: boolean;
  wechatCalibrationStatus: string;
  ingkeeCalibrationStatus: string;
  calibratedAtEpochMs?: number;
  calibrationSummary?: string;
  lastSeenAtEpochMs?: number;
};

export type DeviceRegistrationToken = {
  id: string;
  roomId: string;
  role: string;
  token: string;
  expiresAtEpochMs: number;
};

export type MicSegment = {
  id: string;
  roomId: string;
  shiftId?: string;
  wechatName?: string;
  ingkeeName: string;
  role: string;
  queuePosition?: number;
  startedAtEpochMs: number;
  endedAtEpochMs?: number;
  durationSeconds: number;
  state: string;
  correctionReason?: string;
};

export type Binding = {
  id: string;
  roomId: string;
  wechatName: string;
  ingkeeName: string;
  state: string;
  updatedAtEpochMs: number;
};

export type Attendance = {
  id: string;
  roomId: string;
  shiftId: string;
  wechatName?: string;
  ingkeeName: string;
  ordinarySeconds: number;
  hostSeconds: number;
  segmentCount: number;
  lastSeenAtEpochMs: number;
};

export type QueueEntry = {
  id: string;
  roomId: string;
  shiftId: string;
  wechatName: string;
  role: string;
  position: number;
  state: string;
  updatedAtEpochMs: number;
};

export type RevenueImport = {
  id: string;
  fileName: string;
  fileSha256: string;
  expectedTotalCents: number;
  calculatedTotalCents: number;
  rowCount: number;
  duplicateCount: number;
  state: string;
  createdAtEpochMs: number;
  committedAtEpochMs?: number;
};

export type Settlement = {
  id?: string;
  roomId?: string;
  periodStartEpochMs: number;
  periodEndEpochMs: number;
  ruleId: string;
  grossCents: number;
  platformDeductionCents: number;
  organizationShareCents: number;
  memberCommissionCents: number;
  hostCostCents: number;
  expenseCents: number;
  accountsReceivableCents: number;
  accountsPayableCents: number;
  netProfitCents: number;
  baseAccountsReceivableCents: number;
  baseAccountsPayableCents: number;
  baseNetProfitCents: number;
  adjustmentCents: number;
  hostCostEstimated: boolean;
  hostCostOverrideReason?: string;
  unallocatedRevenueCents: number;
  reconciliationDifferenceCents: number;
  state: string;
};

export type SettlementLine = {
  id: string;
  lineType: string;
  referenceId?: string;
  accountId?: string;
  label: string;
  grossCents: number;
  amountCents: number;
  verified: boolean;
  note?: string;
};

export type Adjustment = {
  id: string;
  settlementId: string;
  amountCents: number;
  effect: string;
  reason: string;
  beforeReceivableCents: number;
  beforePayableCents: number;
  beforeNetProfitCents: number;
  afterReceivableCents: number;
  afterPayableCents: number;
  afterNetProfitCents: number;
  createdAtEpochMs: number;
};

export type FinancialReport = {
  settlement: Settlement;
  memberCommissions: SettlementLine[];
  hostCosts: SettlementLine[];
  expenses: SettlementLine[];
  adjustments: Adjustment[];
};
