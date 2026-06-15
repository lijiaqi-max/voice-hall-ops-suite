import { useState, type FormEvent } from "react";
import type { Customer } from "../../types";
import { yuan } from "../../utils";
import { Card, Table } from "../ui";
import type { ApiCall } from "./types";

export function CustomersPage({ customers, call, reload, notify }: { customers: Customer[]; call: ApiCall; reload: () => Promise<void>; notify: (s: string) => void }) {
  const [query, setQuery] = useState("");
  const [form, setForm] = useState({ displayName: "", externalUserId: "", stage: "new_interaction" });
  const filtered = customers.filter((c) => `${c.displayName}${c.externalUserId}`.toLowerCase().includes(query.toLowerCase()));

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    await call("/customers", { method: "POST", body: JSON.stringify({ displayName: form.displayName, platform: "ingkee", externalUserId: form.externalUserId, relationshipStage: form.stage, contactEligibility: "manual_confirmed" }) });
    await reload(); notify("关系客户已保存");
  };

  return <>
    <Card title="关系库" action={<input className="compact-input" placeholder="搜索昵称或 ID" value={query} onChange={(e) => setQuery(e.target.value)} />}>
      <Table headers={["客户", "关系阶段", "价值等级", "7/30/90 天流水", "累计", "联系资格"]} empty={!filtered.length}>
        {filtered.map((c) => <tr key={c.id}>
          <td><b>{c.displayName}</b><small className="subline">{c.externalUserId}</small></td>
          <td>{c.relationshipStage}</td>
          <td><span className={`level ${c.valueLevel}`}>{c.valueLevel}</span></td>
          <td>{yuan(c.revenue7dCents)} / {yuan(c.revenue30dCents)} / {yuan(c.revenue90dCents)}</td>
          <td>{yuan(c.lifetimeRevenueCents)}</td>
          <td>{c.contactEligibility}</td>
        </tr>)}
      </Table>
    </Card>
    <Card title="人工确认关系">
      <form className="form-grid" onSubmit={submit}>
        <label>客户显示名<input required value={form.displayName} onChange={(e) => setForm({ ...form, displayName: e.target.value })} /></label>
        <label>映客用户 ID<input required value={form.externalUserId} onChange={(e) => setForm({ ...form, externalUserId: e.target.value })} /></label>
        <label>关系阶段<select value={form.stage} onChange={(e) => setForm({ ...form, stage: e.target.value })}>{["new_interaction", "followed", "gifted", "follow_up", "active", "priority", "silent", "do_not_contact"].map((s) => <option key={s}>{s}</option>)}</select></label>
        <button className="primary">保存关系</button>
      </form>
    </Card>
  </>;
}
