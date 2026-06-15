import { useState } from "react";
import type { Room } from "../../types";
import { yuan } from "../../utils";
import { Card } from "../ui";
import type { ApiCall } from "./types";

export function FinancePage({ rooms, call, notify }: { rooms: Room[]; call: ApiCall; notify: (s: string) => void }) {
  const [form, setForm] = useState({ roomId: "", start: "", end: "" });
  const [view, setView] = useState<Record<string, number | string | null> | null>(null);

  const payload = () => ({ roomId: form.roomId || null, periodStartEpochMs: new Date(form.start).getTime(), periodEndEpochMs: new Date(form.end).getTime() });
  const preview = async () => setView(await call("/settlements/preview", { method: "POST", body: JSON.stringify(payload()) }));
  const close = async () => { setView(await call("/settlements/close", { method: "POST", body: JSON.stringify(payload()) })); notify("结算期已关闭，后续只能通过调整单修正"); };

  const lines = [["总流水", "grossCents"], ["平台扣除", "platformDeductionCents"], ["公会/厅分成", "organizationShareCents"], ["成员佣金", "memberCommissionCents"], ["主持费用", "hostCostCents"], ["其他支出", "expenseCents"], ["应收", "accountsReceivableCents"], ["应付", "accountsPayableCents"], ["净利润", "netProfitCents"]] as const;

  return <>
    <Card title="结算期间">
      <div className="form-grid">
        <label>厅房<select value={form.roomId} onChange={(e) => setForm({ ...form, roomId: e.target.value })}><option value="">组织全部厅房</option>{rooms.map((r) => <option value={r.id} key={r.id}>{r.name}</option>)}</select></label>
        <label>开始<input type="date" value={form.start} onChange={(e) => setForm({ ...form, start: e.target.value })} /></label>
        <label>结束<input type="date" value={form.end} onChange={(e) => setForm({ ...form, end: e.target.value })} /></label>
        <button onClick={preview}>试算</button>
        <button className="primary" onClick={close}>关闭结算期</button>
      </div>
    </Card>
    <Card title="经营结算表">
      {view ? <div className="finance-grid">{lines.map(([label, key]) => (
        <div className={key === "netProfitCents" ? "profit" : ""} key={key}><span>{label}</span><b>{yuan(Number(view[key] || 0))}</b></div>
      ))}</div> : <div className="empty large">选择期间后试算</div>}
    </Card>
  </>;
}
