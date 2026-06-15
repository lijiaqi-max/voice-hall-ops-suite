import { useState } from "react";
import { cents } from "../../utils";
import { Card, Table } from "../ui";
import type { ApiCall } from "./types";

export function SettingsPage({ call, notify }: { call: ApiCall; notify: (s: string) => void }) {
  const [levels, setLevels] = useState([
    { code: "priority", label: "重点", minimum30dCents: 100000, minimumLifetimeCents: 300000, sortOrder: 10 },
    { code: "core", label: "核心", minimum30dCents: 500000, minimumLifetimeCents: 1000000, sortOrder: 20 },
  ]);

  const save = async () => {
    await call("/settings/value-levels", { method: "POST", body: JSON.stringify(levels) });
    notify("流水等级规则已生效，仅代表已确认流水层级");
  };

  return <>
    <Card title="流水等级定制">
      <p className="muted">等级只依据官方账单中的已确认流水，不推断用户真实财富或消费能力。</p>
      <Table headers={["代码", "显示名", "近 30 天门槛", "累计门槛"]}>
        {levels.map((l, index) => <tr key={l.code}>
          <td><input value={l.code} onChange={(e) => setLevels(levels.map((x, i) => i === index ? { ...x, code: e.target.value } : x))} /></td>
          <td><input value={l.label} onChange={(e) => setLevels(levels.map((x, i) => i === index ? { ...x, label: e.target.value } : x))} /></td>
          <td><input type="number" value={l.minimum30dCents / 100} onChange={(e) => setLevels(levels.map((x, i) => i === index ? { ...x, minimum30dCents: cents(e.target.value) } : x))} /></td>
          <td><input type="number" value={l.minimumLifetimeCents / 100} onChange={(e) => setLevels(levels.map((x, i) => i === index ? { ...x, minimumLifetimeCents: cents(e.target.value) } : x))} /></td>
        </tr>)}
      </Table>
      <button className="primary" onClick={save}>保存等级规则</button>
    </Card>
    <Card title="安全边界">
      <div className="policy-grid">
        <div><b>官方账单唯一入账</b><p>可见礼物事件只记录关系，不直接进入财务。</p></div>
        <div><b>成员人工交流</b><p>成员端不申请无障碍，不自动私信或群发。</p></div>
        <div><b>精确金额按角色隐藏</b><p>接口层强制执行，不依赖前端遮挡。</p></div>
        <div><b>结算关闭后锁定</b><p>只能使用可审计调整单修正。</p></div>
      </div>
    </Card>
  </>;
}
