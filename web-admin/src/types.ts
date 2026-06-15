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
