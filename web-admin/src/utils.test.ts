import { describe, expect, it } from "vitest";
import { cents, normalizeRevenueRows, yuan, localTime } from "./utils";

describe("money helpers", () => {
  it("converts yuan to integer cents", () => {
    expect(cents("12.34")).toBe(1234);
    expect(cents("0")).toBe(0);
    expect(cents("100")).toBe(10000);
    expect(cents("0.01")).toBe(1);
    expect(cents("99.99")).toBe(9999);
  });

  it("handles edge cases for cents", () => {
    expect(cents("")).toBe(0);
    expect(cents(undefined as unknown as string)).toBe(0);
    expect(cents(null as unknown as string)).toBe(0);
  });

  it("formats cents as yuan currency", () => {
    expect(yuan(1234)).toContain("12.34");
    expect(yuan(0)).toContain("0.00");
    expect(yuan(10000)).toContain("100.00");
    expect(yuan(null)).toContain("0.00");
    expect(yuan(undefined)).toContain("0.00");
  });
});

describe("localTime", () => {
  it("formats epoch milliseconds to local time string", () => {
    const result = localTime(1718380800000); // 2024-06-14 20:00:00 UTC
    expect(result).toContain("2024");
    expect(result).not.toBe("—");
  });

  it("returns dash for undefined", () => {
    expect(localTime(undefined)).toBe("—");
    expect(localTime(0)).toBe("—");
  });
});

describe("official bill normalization", () => {
  it("maps Chinese headers without floating point storage", () => {
    const rows = normalizeRevenueRows([{
      平台交易号: "T-1",
      用户ID: "U-1",
      用户昵称: "测试客户",
      礼物类别: "公开礼物",
      流水金额: "99.90",
      发生时间: "2026-06-14 20:00:00",
    }], "room-1");
    expect(rows[0].grossCents).toBe(9990);
    expect(rows[0].transactionId).toBe("T-1");
    expect(rows[0].customerExternalId).toBe("U-1");
    expect(rows[0].customerDisplayName).toBe("测试客户");
    expect(rows[0].giftCategory).toBe("公开礼物");
    expect(rows[0].roomId).toBe("room-1");
    expect(rows[0].platform).toBe("ingkee");
  });

  it("maps English headers", () => {
    const rows = normalizeRevenueRows([{
      transaction_id: "T-2",
      customer_id: "U-2",
      nickname: "Customer 2",
      gift_category: "private_gift",
      gross_yuan: "50.00",
      occurred_at: "2026-06-14 21:00:00",
    }], "room-2");
    expect(rows[0].transactionId).toBe("T-2");
    expect(rows[0].grossCents).toBe(5000);
  });

  it("throws on invalid amount", () => {
    expect(() => normalizeRevenueRows([{
      流水金额: "invalid",
      发生时间: "2026-06-14 20:00:00",
    }], "room-1")).toThrow("第 2 行金额无效");
  });

  it("throws on negative amount", () => {
    expect(() => normalizeRevenueRows([{
      流水金额: "-10",
      发生时间: "2026-06-14 20:00:00",
    }], "room-1")).toThrow("第 2 行金额无效");
  });

  it("throws on invalid date", () => {
    expect(() => normalizeRevenueRows([{
      流水金额: "100",
      发生时间: "invalid-date",
    }], "room-1")).toThrow("第 2 行时间无效");
  });

  it("handles multiple rows", () => {
    const rows = normalizeRevenueRows([
      { 流水金额: "100", 发生时间: "2026-06-14 20:00:00" },
      { 流水金额: "200", 发生时间: "2026-06-14 21:00:00" },
    ], "room-1");
    expect(rows).toHaveLength(2);
    expect(rows[0].grossCents).toBe(10000);
    expect(rows[1].grossCents).toBe(20000);
  });

  it("handles missing optional fields", () => {
    const rows = normalizeRevenueRows([{
      流水金额: "100",
      发生时间: "2026-06-14 20:00:00",
    }], "room-1");
    expect(rows[0].transactionId).toBeUndefined();
    expect(rows[0].customerExternalId).toBeUndefined();
    expect(rows[0].customerDisplayName).toBeUndefined();
    expect(rows[0].giftCategory).toBeUndefined();
  });
});
