import type { Audit } from "../../types";
import { localTime } from "../../utils";
import { Card, Table } from "../ui";

export function AuditPage({ audit }: { audit: Audit[] }) {
  return <Card title="审计日志" action={<span className="pill">最近 {audit.length} 条</span>}>
    <Table headers={["时间", "动作", "资源", "摘要", "账号"]} empty={!audit.length}>
      {audit.map((a) => <tr key={a.id}>
        <td>{localTime(a.createdAtEpochMs)}</td>
        <td>{a.action}</td>
        <td>{a.resourceType}<small className="subline">{a.resourceId || ""}</small></td>
        <td>{a.summary}</td>
        <td>{a.accountId?.slice(0, 8) || "系统"}</td>
      </tr>)}
    </Table>
  </Card>;
}
