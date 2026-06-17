import { useCallback, useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import type {
  Account,
  Audit,
  Customer,
  Device,
  MicSegment,
  ReportSummary,
  Room,
  Settlement,
  Shift,
  Task,
} from "../../types";
import { buildReadinessChecks, readinessScore } from "../../readiness";
import { localTime } from "../../utils";
import { Card, Stat, Table } from "../ui";
import type { ApiCall } from "./types";

const levelLabel = {
  pass: "通过",
  warn: "待补强",
  block: "阻塞",
} as const;

const roleLabel = (role: string) => role === "robot" ? "微信机器人端" : "映客采集端";

const deviceCapability = (device: Device) => device.role === "robot"
  ? {
      name: "微信自动回复",
      enabled: device.wechatGroupReplyEnabled,
      status: device.wechatCalibrationStatus,
    }
  : {
      name: "映客麦位采集",
      enabled: device.ingkeeVoiceRoomCaptureEnabled,
      status: device.ingkeeCalibrationStatus,
    };

export function LaunchPage({
  rooms,
  accounts,
  shifts,
  customers,
  tasks,
  report,
  micSegments,
  settlements,
  audit,
  call,
  canManageDevices,
}: {
  rooms: Room[];
  accounts: Account[];
  shifts: Shift[];
  customers: Customer[];
  tasks: Task[];
  report: ReportSummary;
  micSegments: MicSegment[];
  settlements: Settlement[];
  audit: Audit[];
  call: ApiCall;
  canManageDevices: boolean;
}) {
  const [devices, setDevices] = useState<Device[]>([]);

  const loadDevices = useCallback(async () => {
    if (!canManageDevices) {
      setDevices([]);
      return;
    }
    setDevices(await call<Device[]>("/devices"));
  }, [call, canManageDevices]);

  useEffect(() => { loadDevices().catch(() => undefined); }, [loadDevices]);

  const checks = useMemo(() => buildReadinessChecks({
    rooms,
    accounts,
    shifts,
    customers,
    tasks,
    report,
    devices,
    micSegments,
    settlements,
    audit,
  }), [rooms, accounts, shifts, customers, tasks, report, devices, micSegments, settlements, audit]);
  const score = readinessScore(checks);
  const nextBlocks = checks.filter((check) => check.level !== "pass").slice(0, 4);

  return <>
    <div className={`launch-hero ${score.state}`}>
      <div>
        <span className="eyebrow">PRODUCTION READINESS</span>
        <h2>{score.state === "ready" ? "可以进入生产验收" : score.state === "partial" ? "主链路具备，仍需补强" : "上线仍有阻塞项"}</h2>
        <p>上线向导只读取当前中台数据，不代替真机校准和 VPS 备份恢复。正式开放业务前，必须完成 HTTPS、设备注册、微信/映客真机校准、账单到结算闭环和审计记录。</p>
      </div>
      <strong>{score.percent}%</strong>
    </div>
    <div className="stats-grid">
      <Stat label="通过项" value={`${score.passed}/${score.total}`} detail="可直接用于演示的准备项" />
      <Stat label="阻塞项" value={String(score.blocked)} detail="上线前必须完成" />
      <Stat label="待补强" value={String(score.warned)} detail="不一定阻断演示，但影响生产稳定" />
      <Stat label="设备数" value={String(devices.length)} detail={canManageDevices ? "来自设备管理接口" : "当前角色无设备管理权限"} />
    </div>
    <div className="two-col wide-left">
      <Card title="上线检查清单" action={<button onClick={() => loadDevices()}>刷新设备状态</button>}>
        <div className="readiness-list">
          {checks.map((check) => <div className={`readiness-item ${check.level}`} key={check.id}>
            <span>{levelLabel[check.level]}</span>
            <div>
              <b>{check.title}</b>
              <small>{check.detail}</small>
              {check.level !== "pass" && <p>{check.nextAction}</p>}
            </div>
          </div>)}
        </div>
      </Card>
      <Card title="下一步动作">
        {nextBlocks.length ? <ol className="next-steps">
          {nextBlocks.map((check) => <li key={check.id}>
            <b>{check.title}</b>
            <span>{check.nextAction}</span>
          </li>)}
        </ol> : <div className="empty">当前检查项已通过，可以进入 VPS 和真机 24 小时观察。</div>}
        <div className="launch-links">
          <Link to="/control">打开校准工作台</Link>
          <Link to="/revenue">导入官方账单</Link>
          <Link to="/finance">生成结算报表</Link>
          <Link to="/audit">查看审计记录</Link>
        </div>
      </Card>
    </div>
    <Card title="校准工作台">
      <Table headers={["设备", "厅房", "角色", "正式能力", "校准状态", "最近在线", "证据摘要"]} empty={!devices.length}>
        {devices.map((device) => {
          const capability = deviceCapability(device);
          const room = rooms.find((item) => item.id === device.roomId);
          return <tr key={device.id}>
            <td>{device.name}<small className="subline">{device.id.slice(0, 8)}</small></td>
            <td>{room?.name || device.roomId.slice(0, 8)}</td>
            <td>{roleLabel(device.role)}</td>
            <td><span className="state">{capability.name}：{capability.enabled ? "已启用" : "关闭"}</span></td>
            <td><span className={capability.status === "已校准" ? "state check-ok" : "state check-bad"}>{capability.status}</span></td>
            <td>{localTime(device.lastSeenAtEpochMs)}</td>
            <td><small className="subline">{device.calibrationSummary || "未记录真机验收证据"}</small></td>
          </tr>;
        })}
      </Table>
      <div className="calibration-guide">
        <div><b>微信验收</b><p>包名、版本、目标群名、输入框、发送按钮、白名单指令、重复消息保护和自循环保护必须逐项记录。</p></div>
        <div><b>映客验收</b><p>包名、9.8.60 版本、语音房页面、麦位节点、进入/离开/换位、10 秒防抖和断线恢复必须逐项记录。</p></div>
        <div><b>上线底线</b><p>未知页面、版本不符、节点不足、设备禁用或服务端能力关闭时，正式自动化必须停止。</p></div>
      </div>
    </Card>
  </>;
}
