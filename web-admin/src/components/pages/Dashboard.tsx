import type {
  Attendance,
  Customer,
  MicSegment,
  ReportSummary,
  Settlement,
  Shift,
  Task,
} from "../../types";
import { yuan, localTime } from "../../utils";
import { Card, Stat, Table } from "../ui";

export function Dashboard({ report, shifts, tasks, customers, micSegments, attendance, settlements }: {
  report: ReportSummary;
  shifts: Shift[];
  tasks: Task[];
  customers: Customer[];
  micSegments: MicSegment[];
  attendance: Attendance[];
  settlements: Settlement[];
}) {
  const today = new Date().toDateString();
  const todayShifts = shifts.filter((s) => new Date(s.startAtEpochMs).toDateString() === today);
  const openTasks = tasks.filter((t) => !["approved", "cancelled"].includes(t.state));
  const recentStart = Date.now() - 30 * 86_400_000;
  const recentProfit = settlements
    .filter((item) => item.periodEndEpochMs >= recentStart)
    .reduce((total, item) => total + item.netProfitCents, 0);
  const verifiedMicSeconds = micSegments
    .filter((item) => item.state === "closed")
    .reduce((total, item) => total + item.durationSeconds, 0);
  const micHours = `${(verifiedMicSeconds / 3600).toFixed(1)} 小时`;
  const priorityCustomers = customers.filter((customer) => customer.valueLevel !== "standard");
  const priorityWithTask = new Set(
    openTasks
      .filter((task) => priorityCustomers.some((customer) => customer.id === task.customerId))
      .map((task) => task.customerId),
  ).size;
  const max = Math.max(...report.dailyTotals.map((d) => d.grossCents), 1);

  return <>
    <div className="stats-grid">
      <Stat label="近 30 天总流水" value={yuan(report.grossCents)} detail="仅来自已提交官方账单" />
      <Stat label="近 30 天净利润" value={yuan(recentProfit)} detail="来自已关闭结算期" />
      <Stat label="已核验麦时" value={micHours} detail={`${attendance.length} 条成员出勤汇总`} />
      <Stat label="今日班次" value={String(todayShifts.length)} detail={`${shifts.length} 个已登记班次`} />
      <Stat label="待处理作业" value={String(openTasks.length)} detail={`${report.taskApproved} 条已审核通过`} />
      <Stat label="关系客户" value={String(customers.length)} detail={`${priorityWithTask}/${priorityCustomers.length} 位重点层级已有作业`} />
    </div>
    <div className="two-col">
      <Card title="流水趋势" action={<span className="pill">近 30 天</span>}>
        <div className="bars">{report.dailyTotals.length ? report.dailyTotals.map((item) => (
          <div className="bar-item" key={item.date} title={`${item.date} ${yuan(item.grossCents)}`}>
            <div style={{ height: `${Math.max(6, item.grossCents / max * 100)}%` }} /><span>{item.date.slice(5)}</span>
          </div>
        )) : <div className="empty">导入账单后显示趋势</div>}</div>
      </Card>
      <Card title="厅房贡献">
        <div className="rank-list">{report.roomTotals.length ? report.roomTotals.map((room, index) => (
          <div key={room.roomId}><span className="rank">{index + 1}</span><b>{room.roomName}</b><strong>{yuan(room.grossCents)}</strong></div>
        )) : <div className="empty">暂无厅房流水</div>}</div>
      </Card>
    </div>
    <Card title="今日排班">
      <Table headers={["班次", "开始", "结束", "状态", "主持成本"]} empty={!todayShifts.length}>
        {todayShifts.map((shift) => <tr key={shift.id}><td>{shift.title}</td><td>{localTime(shift.startAtEpochMs)}</td><td>{localTime(shift.endAtEpochMs)}</td><td><span className="state">{shift.status}</span></td><td>{yuan(shift.hostFixedCents)}</td></tr>)}
      </Table>
    </Card>
  </>;
}
