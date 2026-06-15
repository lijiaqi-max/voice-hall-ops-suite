import { describe, expect, it } from "vitest";
import { hasAnyPermission, hasPermission } from "./permissions";

describe("permission helpers", () => {
  it("allows wildcard and exact permissions", () => {
    expect(hasPermission(["*"], "finance.read")).toBe(true);
    expect(hasPermission(["mic.read"], "mic.read")).toBe(true);
  });

  it("treats write permission as read access for the same domain", () => {
    expect(hasPermission(["rooms.write"], "rooms.read")).toBe(true);
    expect(hasPermission(["rooms.read"], "rooms.write")).toBe(false);
  });

  it("requires at least one permission for guarded navigation", () => {
    expect(hasAnyPermission(["tasks.read"], [])).toBe(true);
    expect(hasAnyPermission(["tasks.read"], ["tasks.read", "tasks.review"])).toBe(true);
    expect(hasAnyPermission(["tasks.read"], ["finance.read"])).toBe(false);
  });
});
