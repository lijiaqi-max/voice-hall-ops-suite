import { useCallback, useEffect, useState } from "react";
import type { RevenueImport, Room } from "../../types";
import { cents, localTime, yuan, parseRevenueFile } from "../../utils";
import { Card, Table } from "../ui";
import type { ApiCall } from "./types";

interface PreviewResult {
  importId: string;
  rowCount: number;
  validRowCount: number;
  duplicateCount: number;
  calculatedTotalCents: number;
  expectedTotalCents: number;
  canCommit: boolean;
  errors: string[];
}

export function RevenuePage({ rooms, call, reload, notify }: { rooms: Room[]; call: ApiCall; reload: () => Promise<void>; notify: (s: string) => void }) {
  const [roomId, setRoomId] = useState("");
  const [file, setFile] = useState<File | null>(null);
  const [expected, setExpected] = useState("");
  const [preview, setPreview] = useState<PreviewResult | null>(null);
  const [history, setHistory] = useState<RevenueImport[]>([]);

  const loadHistory = useCallback(async () => {
    setHistory(await call<RevenueImport[]>("/revenue-imports"));
  }, [call]);

  useEffect(() => { loadHistory().catch(() => undefined); }, [loadHistory]);

  const createPreview = async () => {
    if (!file || !roomId) return;
    const parsed = await parseRevenueFile(file, roomId);
    setPreview(await call("/revenue-imports/preview", { method: "POST", body: JSON.stringify({ ...parsed, expectedTotalCents: cents(expected) }) }));
  };

  const commit = async () => {
    if (!preview) return;
    await call(`/revenue-imports/${preview.importId}/commit`, { method: "POST" });
    await Promise.all([reload(), loadHistory()]);
    notify("官方账单已提交并完成去重");
  };

  return <>
    <div className="two-col">
      <Card title="上传官方账单">
      <div className="upload-zone">
        <b>CSV / XLSX</b>
        <p>先映射和预览，再校验总额。文件 SHA-256、交易号和行指纹共同去重。</p>
        <input type="file" accept=".csv,.xlsx,.xls" onChange={(e) => setFile(e.target.files?.[0] || null)} />
      </div>
      <div className="form-stack">
        <label>归属厅房<select value={roomId} onChange={(e) => setRoomId(e.target.value)}><option value="">选择厅房</option>{rooms.map((r) => <option value={r.id} key={r.id}>{r.name}</option>)}</select></label>
        <label>账单声明总额（元）<input type="number" step=".01" value={expected} onChange={(e) => setExpected(e.target.value)} /></label>
        <button className="primary" disabled={!file || !roomId} onClick={createPreview}>解析并预览</button>
      </div>
      </Card>
      <Card title="导入校验">
      {preview ? <div className="validation">
        <div><span>总行数</span><b>{preview.rowCount}</b></div>
        <div><span>有效行</span><b>{preview.validRowCount}</b></div>
        <div><span>重复行</span><b>{preview.duplicateCount}</b></div>
        <div><span>计算总额</span><b>{yuan(preview.calculatedTotalCents)}</b></div>
        <div><span>声明总额</span><b>{yuan(preview.expectedTotalCents)}</b></div>
        <div className={preview.canCommit ? "check-ok" : "check-bad"}>{preview.canCommit ? "校验通过，可以提交" : "校验未通过，禁止提交"}</div>
        {preview.errors.map((e) => <p className="error-text" key={e}>{e}</p>)}
        <button className="primary" disabled={!preview.canCommit} onClick={commit}>提交账单</button>
      </div> : <div className="empty large">等待账单预览</div>}
      </Card>
    </div>
    <Card title="导入历史">
      <Table headers={["文件", "状态", "总额", "行数/重复", "创建时间", "提交时间"]} empty={!history.length}>
        {history.map((item) => <tr key={item.id}>
          <td>{item.fileName}<small className="subline">{item.fileSha256.slice(0, 16)}…</small></td>
          <td><span className="state">{item.state}</span></td>
          <td>{yuan(item.calculatedTotalCents)}</td>
          <td>{item.rowCount} / {item.duplicateCount}</td>
          <td>{localTime(item.createdAtEpochMs)}</td>
          <td>{localTime(item.committedAtEpochMs)}</td>
        </tr>)}
      </Table>
    </Card>
  </>;
}
