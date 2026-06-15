import { useState, type FormEvent } from "react";
import type { Room, Account } from "../../types";
import { Card, Table } from "../ui";
import type { ApiCall } from "./types";

export function MembersPage({ accounts, rooms, call, reload, notify }: { accounts: Account[]; rooms: Room[]; call: ApiCall; reload: () => Promise<void>; notify: (s: string) => void }) {
  const [form, setForm] = useState({ username: "", displayName: "", password: "", role: "member", roomId: "", totpSecret: "" });

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    await call("/accounts", { method: "POST", body: JSON.stringify({ username: form.username, displayName: form.displayName, password: form.password, role: form.role, roomIds: form.roomId ? [form.roomId] : [], totpSecret: form.totpSecret || null }) });
    await reload(); notify("成员账号已创建");
  };

  return <div className="two-col wide-left">
    <Card title="组织成员">
      <Table headers={["成员", "账号", "角色", "组织"]} empty={!accounts.length}>
        {accounts.map((a) => <tr key={a.id}>
          <td><b>{a.displayName}</b></td>
          <td>{a.username}</td>
          <td><span className="state">{a.role}</span></td>
          <td>{a.organizationId.slice(0, 8)}</td>
        </tr>)}
      </Table>
    </Card>
    <Card title="添加成员">
      <form className="form-stack" onSubmit={submit}>
        <label>登录账号<input required value={form.username} onChange={(e) => setForm({ ...form, username: e.target.value })} /></label>
        <label>显示名称<input required value={form.displayName} onChange={(e) => setForm({ ...form, displayName: e.target.value })} /></label>
        <label>初始密码<input required minLength={10} type="password" value={form.password} onChange={(e) => setForm({ ...form, password: e.target.value })} /></label>
        <label>角色<select value={form.role} onChange={(e) => setForm({ ...form, role: e.target.value })}>{["admin", "scheduler", "finance", "member", "auditor"].map((r) => <option key={r}>{r}</option>)}</select></label>
        <label>厅房范围<select value={form.roomId} onChange={(e) => setForm({ ...form, roomId: e.target.value })}><option value="">组织全部厅房</option>{rooms.map((r) => <option value={r.id} key={r.id}>{r.name}</option>)}</select></label>
        <label>TOTP Base32 密钥（可选）<input value={form.totpSecret} onChange={(e) => setForm({ ...form, totpSecret: e.target.value.toUpperCase() })} placeholder="至少 16 位，仅在创建时提交" /></label>
        <button className="primary">创建账号</button>
      </form>
    </Card>
  </div>;
}
