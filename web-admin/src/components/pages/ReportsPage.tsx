import { useState } from "react";
import type { ReportSummary } from "../../types";
import { yuan, localTime } from "../../utils";
import { Card, Table } from "../ui";
import type { ApiCall } from "./types";

export function ReportsPage({ report, call, onReport }: { report: ReportSummary; call: ApiCall; onReport: (r: ReportSummary) => void }) {
  const [range, setRange] = useState("30");

  const load = async () => {
    const end = Date.now(); const start = end - Number(range) * 86_400_000;
    onReport(await call(`/reports/summary?start=${start}&end=${end}`));
  };

  return <Card title="经营报表" action={<div className="inline">
    <select value={range} onChange={(e) => setRange(e.target.value)}>
      <option value="1">今日</option><option value="7">近 7 天</option><option value="30">近 30 天</option><option value="90">近 90 天</option>
    </select>
    <button onClick={load}>生成</button>
    <button onClick={() => window.print()}>打印 / PDF</button>
  </div>}>
    <div className="report-hero">
      <span>期间总流水</span>
      <strong>{yuan(report.grossCents)}</strong>
      <small>{localTime(report.periodStartEpochMs)} 至 {localTime(report.periodEndEpochMs)}</small>
    </div>
    <Table headers={["厅房", "流水", "占比"]} empty={!report.roomTotals.length}>
      {report.roomTotals.map((r) => <tr key={r.roomId}>
        <td>{r.roomName}</td>
        <td>{yuan(r.grossCents)}</td>
        <td>{report.grossCents ? `${(r.grossCents / report.grossCents * 100).toFixed(1)}%` : "0%"}</td>
      </tr>)}
    </Table>
  </Card>;
}
