export const cents = (yuan: string | number) =>
  Math.round(Number(yuan || 0) * 100);

export const yuan = (value?: number | null) =>
  new Intl.NumberFormat("zh-CN", {
    style: "currency",
    currency: "CNY",
  }).format((value || 0) / 100);

export const localTime = (epoch?: number) =>
  epoch ? new Date(epoch).toLocaleString("zh-CN", { hour12: false }) : "—";

export type ImportedRevenueRow = {
  roomId: string;
  platform: string;
  transactionId?: string;
  customerExternalId?: string;
  customerDisplayName?: string;
  giftCategory?: string;
  grossCents: number;
  occurredAtEpochMs: number;
};

const headerAliases: Record<string, string[]> = {
  transactionId: ["平台交易号", "交易号", "transaction_id"],
  customerExternalId: ["用户ID", "客户ID", "customer_id"],
  customerDisplayName: ["用户昵称", "客户昵称", "nickname"],
  giftCategory: ["礼物类别", "礼物", "gift_category"],
  grossYuan: ["流水金额", "金额", "gross_yuan"],
  occurredAt: ["发生时间", "时间", "occurred_at"],
};

const field = (row: Record<string, unknown>, key: keyof typeof headerAliases) => {
  const header = headerAliases[key].find((candidate) => row[candidate] !== undefined);
  return header ? String(row[header] ?? "").trim() : "";
};

export function normalizeRevenueRows(
  raw: Record<string, unknown>[],
  roomId: string,
): ImportedRevenueRow[] {
  return raw.map((row, index) => {
    const amount = Number(field(row, "grossYuan"));
    const date = new Date(field(row, "occurredAt"));
    if (!Number.isFinite(amount) || amount < 0) {
      throw new Error(`第 ${index + 2} 行金额无效`);
    }
    if (Number.isNaN(date.getTime())) {
      throw new Error(`第 ${index + 2} 行时间无效`);
    }
    return {
      roomId,
      platform: "ingkee",
      transactionId: field(row, "transactionId") || undefined,
      customerExternalId: field(row, "customerExternalId") || undefined,
      customerDisplayName: field(row, "customerDisplayName") || undefined,
      giftCategory: field(row, "giftCategory") || undefined,
      grossCents: cents(amount),
      occurredAtEpochMs: date.getTime(),
    };
  });
}

export async function parseRevenueFile(file: File, roomId: string) {
  const XLSX = await import("xlsx");
  const buffer = await file.arrayBuffer();
  const workbook = XLSX.read(buffer, { type: "array", cellDates: true });
  const sheet = workbook.Sheets[workbook.SheetNames[0]];
  const raw = XLSX.utils.sheet_to_json<Record<string, unknown>>(sheet, { defval: "" });
  const hash = await crypto.subtle.digest("SHA-256", buffer);
  const fileSha256 = Array.from(new Uint8Array(hash))
    .map((byte) => byte.toString(16).padStart(2, "0"))
    .join("");
  return {
    fileName: file.name,
    fileSha256,
    rows: normalizeRevenueRows(raw, roomId),
  };
}
