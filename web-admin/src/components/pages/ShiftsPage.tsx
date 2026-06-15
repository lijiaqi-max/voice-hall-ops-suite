import { useState, type FormEvent } from "react";
import type { Room, Account, Shift } from "../../types";
import { cents, localTime, yuan } from "../../utils";
import { Card, Table } from "../ui";
import type { ApiCall } from "./types";

export function ShiftsPage({ rooms, accounts, shifts, call, reload, notify }: { rooms: Room[]; accounts: Account[]; shifts: Shift[]; call: ApiCall; reload: () => Promise<void>; notify: (s: string) => void }) {
  const [form, setForm] = useState({ roomId: "", hostAccountId: "", title: "", start: "", end: "", fixed: "0", hourly: "0" });

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    await call("/shifts", { method: "POST", body: JSON.stringify({ roomId: form.roomId, hostAccountId: form.hostAccountId || null, title: form.title, startAtEpochMs: new Date(form.start).getTime(), endAtEpochMs: new Date(form.end).getTime(), hostFixedCents: cents(form.fixed), hostHourlyCents: cents(form.hourly) }) });
    await reload(); notify("班次已排入，冲突检查通过");
  };

  return <>
    <Card title="排班日历" action={<span className="pill">{shifts.length} 个班次</span>}>
      <Table headers={["厅房", "班次", "主持", "起止", "固定班费", "时薪", "状态"]} empty={!shifts.length}>
        {shifts.map((s) => <tr key={s.id}>
          <td>{rooms.find((r) => r.id === s.roomId)?.name || s.roomId}</td>
          <td>{s.title}</td>
          <td>{accounts.find((a) => a.id === s.hostAccountId)?.displayName || "待定"}</td>
          <td>{localTime(s.startAtEpochMs)}<br />{localTime(s.endAtEpochMs)}</td>
          <td>{yuan(s.hostFixedCents)}</td>
          <td>{yuan(s.hostHourlyCents)}</td>
          <td><span className="state">{s.status}</span></td>
        </tr>)}
      </Table>
    </Card>
    <Card title="新增排班">
      <form className="form-grid" onSubmit={submit}>
        <label>厅房<select required value={form.roomId} onChange={(e) => setForm({ ...form, roomId: e.target.value })}><option value="">选择厅房</option>{rooms.map((r) => <option value={r.id} key={r.id}>{r.name}</option>)}</select></label>
        <label>主持<select value={form.hostAccountId} onChange={(e) => setForm({ ...form, hostAccountId: e.target.value })}><option value="">待定</option>{accounts.map((a) => <option value={a.id} key={a.id}>{a.displayName}</option>)}</select></label>
        <label>班次名称<input required value={form.title} onChange={(e) => setForm({ ...form, title: e.target.value })} /></label>
        <label>开始<input required type="datetime-local" value={form.start} onChange={(e) => setForm({ ...form, start: e.target.value })} /></label>
        <label>结束<input required type="datetime-local" value={form.end} onChange={(e) => setForm({ ...form, end: e.target.value })} /></label>
        <label>固定班费（元）<input type="number" min="0" step=".01" value={form.fixed} onChange={(e) => setForm({ ...form, fixed: e.target.value })} /></label>
        <label>按时长计费（元/小时）<input type="number" min="0" step=".01" value={form.hourly} onChange={(e) => setForm({ ...form, hourly: e.target.value })} /></label>
        <button className="primary">检查冲突并排班</button>
      </form>
    </Card>
  </>;
}
