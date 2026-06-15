import { useCallback, useEffect, useState } from "react";
import type { FinancialReport, Room, Settlement } from "../../types";
import { cents, localTime, yuan } from "../../utils";
import { Card, Table } from "../ui";
import type { ApiCall, ApiDownload } from "./types";

export function FinancePage({
  rooms,
  call,
  download,
  notify,
}: {
  rooms: Room[];
  call: ApiCall;
  download: ApiDownload;
  notify: (message: string) => void;
}) {
  const [form, setForm] = useState({ roomId: "", start: "", end: "", overrideReason: "" });
  const [view, setView] = useState<Settlement | null>(null);
  const [history, setHistory] = useState<Settlement[]>([]);
  const [selectedId, setSelectedId] = useState("");
  const [report, setReport] = useState<FinancialReport | null>(null);
  const [adjustment, setAdjustment] = useState({ effect: "payable", amount: "", reason: "" });

  const loadHistory = useCallback(async () => {
    const rows = await call<Settlement[]>("/settlements");
    setHistory(rows);
    if (!selectedId && rows[0]?.id) setSelectedId(rows[0].id);
  }, [call, selectedId]);

  useEffect(() => { loadHistory().catch(() => undefined); }, [loadHistory]);
  useEffect(() => {
    if (!selectedId) {
      setReport(null);
      return;
    }
    call<FinancialReport>(`/reports/finance/${selectedId}`)
      .then(setReport)
      .catch(() => undefined);
  }, [call, selectedId]);

  const payload = () => ({
    roomId: form.roomId || null,
    periodStartEpochMs: new Date(form.start).getTime(),
    periodEndEpochMs: new Date(form.end).getTime(),
    hostCostOverrideReason: form.overrideReason || null,
  });
  const preview = async () => setView(await call<Settlement>("/settlements/preview", {
    method: "POST",
    body: JSON.stringify(payload()),
  }));
  const close = async () => {
    const closed = await call<Settlement>("/settlements/close", {
      method: "POST",
      body: JSON.stringify(payload()),
    });
    setView(closed);
    await loadHistory();
    if (closed.id) setSelectedId(closed.id);
    notify("结算期已关闭，后续只能通过调整单修正");
  };
  const addAdjustment = async () => {
    if (!selectedId) return;
    await call(`/settlements/${selectedId}/adjustments`, {
      method: "POST",
      body: JSON.stringify({
        effect: adjustment.effect,
        amountCents: cents(adjustment.amount),
        reason: adjustment.reason,
      }),
    });
    setAdjustment({ effect: "payable", amount: "", reason: "" });
    await loadHistory();
    setReport(await call<FinancialReport>(`/reports/finance/${selectedId}`));
    notify("调整单已计入应收、应付和净利润");
  };
  const exportXlsx = async () => {
    if (!selectedId) return;
    const blob = await download(`/reports/export.xlsx?settlementId=${selectedId}`);
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = `财务结算-${selectedId}.xlsx`;
    anchor.click();
    URL.revokeObjectURL(url);
  };

  const lines = [
    ["总流水", "grossCents"],
    ["平台扣除", "platformDeductionCents"],
    ["公会/厅分成", "organizationShareCents"],
    ["成员佣金", "memberCommissionCents"],
    ["主持费用", "hostCostCents"],
    ["其他支出", "expenseCents"],
    ["调整合计", "adjustmentCents"],
    ["应收", "accountsReceivableCents"],
    ["应付", "accountsPayableCents"],
    ["净利润", "netProfitCents"],
  ] as const;

  return <>
    <Card title="结算期间">
      <div className="form-grid">
        <label>厅房<select value={form.roomId} onChange={(event) => setForm({ ...form, roomId: event.target.value })}>
          <option value="">组织全部厅房</option>
          {rooms.map((room) => <option value={room.id} key={room.id}>{room.name}</option>)}
        </select></label>
        <label>开始<input type="date" value={form.start} onChange={(event) => setForm({ ...form, start: event.target.value })} /></label>
        <label>结束<input type="date" value={form.end} onChange={(event) => setForm({ ...form, end: event.target.value })} /></label>
        <label>主持费用人工确认理由<input value={form.overrideReason} onChange={(event) => setForm({ ...form, overrideReason: event.target.value })} placeholder="仅麦时缺失时必填" /></label>
        <button onClick={preview} disabled={!form.start || !form.end}>试算</button>
        <button className="primary" onClick={close} disabled={!form.start || !form.end}>关闭结算期</button>
      </div>
    </Card>
    <Card title="经营结算表">
      {view ? <>
        <div className="finance-grid">{lines.map(([label, key]) => (
          <div className={key === "netProfitCents" ? "profit" : ""} key={key}>
            <span>{label}</span><b>{yuan(view[key])}</b>
          </div>
        ))}</div>
        {(view.hostCostEstimated || view.unallocatedRevenueCents > 0 || view.reconciliationDifferenceCents !== 0) &&
          <div className="warning-panel">
            {view.hostCostEstimated && <p>主持费用含预估值，正式关账必须填写人工确认理由。</p>}
            {view.unallocatedRevenueCents > 0 && <p>待分配流水：{yuan(view.unallocatedRevenueCents)}，当前禁止关账。</p>}
            {view.reconciliationDifferenceCents !== 0 && <p>勾稽差额：{yuan(view.reconciliationDifferenceCents)}，必须为 0。</p>}
          </div>}
      </> : <div className="empty large">选择期间后试算</div>}
    </Card>
    <Card title="结算历史" action={<button onClick={exportXlsx} disabled={!selectedId}>导出 XLSX</button>}>
      <Table headers={["选择", "期间", "厅房", "流水", "调整", "净利润", "核验"]} empty={!history.length}>
        {history.map((item) => <tr key={item.id}>
          <td><input type="radio" checked={selectedId === item.id} onChange={() => setSelectedId(item.id || "")} /></td>
          <td>{localTime(item.periodStartEpochMs)}<small className="subline">至 {localTime(item.periodEndEpochMs)}</small></td>
          <td>{rooms.find((room) => room.id === item.roomId)?.name || "组织全部"}</td>
          <td>{yuan(item.grossCents)}</td>
          <td>{yuan(item.adjustmentCents)}</td>
          <td>{yuan(item.netProfitCents)}</td>
          <td><span className="state">{item.hostCostEstimated ? "人工确认预估" : "已核验"}</span></td>
        </tr>)}
      </Table>
    </Card>
    {selectedId && <div className="two-col wide-left">
      <Card title="结算明细">
        <Table headers={["类型", "项目", "归属流水", "金额", "核验/说明"]} empty={!report}>
          {report && [
            ...report.memberCommissions,
            ...report.hostCosts,
            ...report.expenses,
          ].map((line) => <tr key={line.id}>
            <td>{line.lineType}</td>
            <td>{line.label}</td>
            <td>{yuan(line.grossCents)}</td>
            <td>{yuan(line.amountCents)}</td>
            <td>{line.verified ? "已核验" : "预估"}<small className="subline">{line.note || "—"}</small></td>
          </tr>)}
        </Table>
      </Card>
      <Card title="新增调整单">
        <div className="form-stack">
          <label>影响类型<select value={adjustment.effect} onChange={(event) => setAdjustment({ ...adjustment, effect: event.target.value })}>
            <option value="receivable">应收调整</option>
            <option value="payable">应付调整</option>
            <option value="expense">支出调整</option>
          </select></label>
          <label>金额（元，可为负数）<input type="number" step=".01" value={adjustment.amount} onChange={(event) => setAdjustment({ ...adjustment, amount: event.target.value })} /></label>
          <label>原因<textarea value={adjustment.reason} onChange={(event) => setAdjustment({ ...adjustment, reason: event.target.value })} /></label>
          <button className="primary" disabled={!adjustment.amount || adjustment.reason.trim().length < 2} onClick={addAdjustment}>提交调整单</button>
        </div>
        {report?.adjustments.map((item) => <div className="history-note" key={item.id}>
          <b>{item.effect} {yuan(item.amountCents)}</b>
          <span>{item.reason}</span>
          <small>{localTime(item.createdAtEpochMs)}</small>
        </div>)}
      </Card>
    </div>}
  </>;
}
