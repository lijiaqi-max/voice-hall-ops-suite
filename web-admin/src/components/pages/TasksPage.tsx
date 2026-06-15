import { useState, type FormEvent } from "react";
import type { Room, Customer, Account, Task } from "../../types";
import { Card, Table } from "../ui";
import type { ApiCall } from "./types";

export function TasksPage({ tasks, rooms, customers, accounts, call, reload, notify }: { tasks: Task[]; rooms: Room[]; customers: Customer[]; accounts: Account[]; call: ApiCall; reload: () => Promise<void>; notify: (s: string) => void }) {
  const [state, setState] = useState("");
  const [form, setForm] = useState({ roomId: "", customerId: "", title: "", brief: "", priority: "50", assignedAccountId: "" });
  const visible = state ? tasks.filter((t) => t.state === state) : tasks;

  const action = async (task: Task, verb: string) => {
    const body = verb === "reject" ? JSON.stringify({ channel: "review", note: "退回修改" }) : undefined;
    await call(`/tasks/${task.id}/${verb}`, { method: "POST", body }); await reload(); notify(`作业已${verb}`);
  };

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    await call("/tasks", { method: "POST", body: JSON.stringify({ ...form, priority: Number(form.priority), roomId: form.roomId || null, assignedAccountId: form.assignedAccountId || null, publish: true }) });
    await reload(); notify("作业已发布");
  };

  return <>
    <Card title="作业池" action={<select className="compact-input" value={state} onChange={(e) => setState(e.target.value)}><option value="">全部状态</option>{["published", "assigned", "claimed", "in_progress", "submitted", "approved", "rejected"].map((s) => <option key={s}>{s}</option>)}</select>}>
      <Table headers={["优先级", "客户", "作业", "负责人", "状态", "操作"]} empty={!visible.length}>
        {visible.map((t) => <tr key={t.id}>
          <td><b>{t.priority}</b></td>
          <td>{t.customerName}<small className="subline">{t.valueLevel}</small></td>
          <td><b>{t.title}</b><small className="subline">{t.brief}</small></td>
          <td>{accounts.find((a) => a.id === t.assignedAccountId)?.displayName || "任务池"}</td>
          <td><span className="state">{t.state}</span></td>
          <td className="actions">
            {t.state === "submitted" && <><button onClick={() => action(t, "approve")}>通过</button><button onClick={() => action(t, "reject")}>驳回</button></>}
            {!["approved", "cancelled"].includes(t.state) && <button onClick={() => action(t, "cancel")}>取消</button>}
          </td>
        </tr>)}
      </Table>
    </Card>
    <Card title="发布跟进作业">
      <form className="form-grid" onSubmit={submit}>
        <label>客户<select required value={form.customerId} onChange={(e) => setForm({ ...form, customerId: e.target.value })}><option value="">选择客户</option>{customers.filter((c) => c.relationshipStage !== "do_not_contact").map((c) => <option value={c.id} key={c.id}>{c.displayName} · {c.valueLevel}</option>)}</select></label>
        <label>厅房<select value={form.roomId} onChange={(e) => setForm({ ...form, roomId: e.target.value })}><option value="">不限厅房</option>{rooms.map((r) => <option value={r.id} key={r.id}>{r.name}</option>)}</select></label>
        <label>指定成员<select value={form.assignedAccountId} onChange={(e) => setForm({ ...form, assignedAccountId: e.target.value })}><option value="">发布到任务池</option>{accounts.filter((a) => a.role === "member").map((a) => <option value={a.id} key={a.id}>{a.displayName}</option>)}</select></label>
        <label>优先级<input type="number" min="0" max="100" value={form.priority} onChange={(e) => setForm({ ...form, priority: e.target.value })} /></label>
        <label>标题<input required value={form.title} onChange={(e) => setForm({ ...form, title: e.target.value })} /></label>
        <label className="span-2">交流建议<textarea required value={form.brief} onChange={(e) => setForm({ ...form, brief: e.target.value })} placeholder="只写关系背景与服务建议，不做消费施压。" /></label>
        <button className="primary">发布作业</button>
      </form>
    </Card>
  </>;
}
