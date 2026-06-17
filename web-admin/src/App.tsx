import { lazy, Suspense, useCallback, useEffect, useState } from "react";
import { BrowserRouter, Routes, Route, Navigate } from "react-router-dom";
import { ApiError, request, requestBlob } from "./api";
import type {
  Account,
  Attendance,
  Audit,
  Customer,
  MicSegment,
  ReportSummary,
  Room,
  Settlement,
  Shift,
  Task,
} from "./types";
import { useSession } from "./hooks/useSession";
import { Login } from "./components/Login";
import { AppShell } from "./layouts/AppShell";
import { hasPermission } from "./permissions";

const Dashboard = lazy(() => import("./components/pages/Dashboard").then((m) => ({ default: m.Dashboard })));
const LaunchPage = lazy(() => import("./components/pages/LaunchPage").then((m) => ({ default: m.LaunchPage })));
const RoomsPage = lazy(() => import("./components/pages/RoomsPage").then((m) => ({ default: m.RoomsPage })));
const ShiftsPage = lazy(() => import("./components/pages/ShiftsPage").then((m) => ({ default: m.ShiftsPage })));
const ControlPage = lazy(() => import("./components/pages/ControlPage").then((m) => ({ default: m.ControlPage })));
const MembersPage = lazy(() => import("./components/pages/MembersPage").then((m) => ({ default: m.MembersPage })));
const CustomersPage = lazy(() => import("./components/pages/CustomersPage").then((m) => ({ default: m.CustomersPage })));
const TasksPage = lazy(() => import("./components/pages/TasksPage").then((m) => ({ default: m.TasksPage })));
const RevenuePage = lazy(() => import("./components/pages/RevenuePage").then((m) => ({ default: m.RevenuePage })));
const FinancePage = lazy(() => import("./components/pages/FinancePage").then((m) => ({ default: m.FinancePage })));
const ReportsPage = lazy(() => import("./components/pages/ReportsPage").then((m) => ({ default: m.ReportsPage })));
const AuditPage = lazy(() => import("./components/pages/AuditPage").then((m) => ({ default: m.AuditPage })));
const SettingsPage = lazy(() => import("./components/pages/SettingsPage").then((m) => ({ default: m.SettingsPage })));

const emptyReport: ReportSummary = {
  periodStartEpochMs: Date.now() - 30 * 86_400_000,
  periodEndEpochMs: Date.now(),
  grossCents: 0,
  taskTotal: 0,
  taskApproved: 0,
  customerTotal: 0,
  roomTotals: [],
  dailyTotals: [],
};

function AppLayout() {
  const [session, setSession] = useSession();
  const [rooms, setRooms] = useState<Room[]>([]);
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [shifts, setShifts] = useState<Shift[]>([]);
  const [customers, setCustomers] = useState<Customer[]>([]);
  const [tasks, setTasks] = useState<Task[]>([]);
  const [audit, setAudit] = useState<Audit[]>([]);
  const [micSegments, setMicSegments] = useState<MicSegment[]>([]);
  const [attendance, setAttendance] = useState<Attendance[]>([]);
  const [settlements, setSettlements] = useState<Settlement[]>([]);
  const [report, setReport] = useState<ReportSummary>(emptyReport);
  const [notice, setNotice] = useState("");
  const [error, setError] = useState("");
  const token = session?.accessToken;
  const can = useCallback(
    (permission: string) => hasPermission(session?.permissions ?? [], permission),
    [session?.permissions],
  );

  const call = useCallback(async <T,>(path: string, options?: RequestInit) => {
    try {
      setError("");
      return await request<T>(path, options, token);
    } catch (reason) {
      if (reason instanceof ApiError && reason.status === 401) setSession(null);
      setError(reason instanceof Error ? reason.message : "请求失败");
      throw reason;
    }
  }, [token, setSession]);

  const download = useCallback(async (path: string) => {
    try {
      setError("");
      return await requestBlob(path, token);
    } catch (reason) {
      if (reason instanceof ApiError && reason.status === 401) setSession(null);
      setError(reason instanceof Error ? reason.message : "下载失败");
      throw reason;
    }
  }, [token, setSession]);

  const loadAll = useCallback(async () => {
    if (!token) return;
    const [
      roomData,
      shiftData,
      customerData,
      taskData,
      reportData,
      accountData,
      auditData,
      micData,
      attendanceData,
      settlementData,
    ] = await Promise.all([
      can("rooms.read") ? call<Room[]>("/rooms") : Promise.resolve([]),
      can("shifts.read") ? call<Shift[]>("/shifts") : Promise.resolve([]),
      can("customers.read") ? call<Customer[]>("/customers") : Promise.resolve([]),
      can("tasks.read") ? call<Task[]>("/tasks") : Promise.resolve([]),
      can("reports.read")
        ? call<ReportSummary>(`/reports/summary?start=${Date.now() - 30 * 86_400_000}&end=${Date.now()}`)
        : Promise.resolve(emptyReport),
      can("rooms.write") ? call<Account[]>("/accounts") : Promise.resolve([]),
      can("audit.read") ? call<Audit[]>("/audit?limit=100") : Promise.resolve([]),
      can("mic.read") ? call<MicSegment[]>("/mic-segments") : Promise.resolve([]),
      can("mic.read") ? call<Attendance[]>("/attendance") : Promise.resolve([]),
      can("finance.read") ? call<Settlement[]>("/settlements") : Promise.resolve([]),
    ]);
    setRooms(roomData);
    setShifts(shiftData);
    setCustomers(customerData);
    setTasks(taskData);
    setReport(reportData);
    setAccounts(accountData);
    setAudit(auditData);
    setMicSegments(micData);
    setAttendance(attendanceData);
    setSettlements(settlementData);
  }, [token, call, can]);

  useEffect(() => { loadAll().catch(() => undefined); }, [loadAll]);

  const notify = (message: string) => {
    setNotice(message);
    window.setTimeout(() => setNotice(""), 2600);
  };

  if (!session) return <Login onLogin={setSession} />;

  const guarded = (permission: string, page: React.ReactNode) =>
    can(permission) ? page : <Navigate to="/" replace />;

  return (
    <AppShell
      session={session}
      error={error}
      notice={notice}
      onLogout={() => setSession(null)}
      onRefresh={() => loadAll()}
    >
      <Suspense fallback={<div className="card empty large">正在加载页面…</div>}>
        <Routes>
          <Route path="/" element={<Dashboard
            report={report}
            shifts={shifts}
            tasks={tasks}
            customers={customers}
            micSegments={micSegments}
            attendance={attendance}
            settlements={settlements}
          />} />
          <Route path="/launch" element={guarded("rooms.read", <LaunchPage
            rooms={rooms}
            accounts={accounts}
            shifts={shifts}
            customers={customers}
            tasks={tasks}
            report={report}
            micSegments={micSegments}
            settlements={settlements}
            audit={audit}
            call={call}
            canManageDevices={can("devices.write")}
          />)} />
          <Route path="/rooms" element={guarded("rooms.read", <RoomsPage rooms={rooms} call={call} reload={loadAll} notify={notify} />)} />
          <Route path="/shifts" element={guarded("shifts.read", <ShiftsPage rooms={rooms} accounts={accounts} shifts={shifts} call={call} reload={loadAll} notify={notify} />)} />
          <Route path="/control" element={guarded("mic.read", <ControlPage rooms={rooms} call={call} canManageDevices={can("devices.write")} />)} />
          <Route path="/members" element={guarded("rooms.write", <MembersPage accounts={accounts} rooms={rooms} call={call} reload={loadAll} notify={notify} />)} />
          <Route path="/customers" element={guarded("customers.read", <CustomersPage customers={customers} call={call} reload={loadAll} notify={notify} />)} />
          <Route path="/tasks" element={guarded("tasks.read", <TasksPage tasks={tasks} rooms={rooms} customers={customers} accounts={accounts} call={call} reload={loadAll} notify={notify} />)} />
          <Route path="/revenue" element={guarded("revenue.write", <RevenuePage rooms={rooms} call={call} reload={loadAll} notify={notify} />)} />
          <Route path="/finance" element={guarded("finance.read", <FinancePage rooms={rooms} call={call} download={download} notify={notify} />)} />
          <Route path="/reports" element={guarded("reports.read", <ReportsPage report={report} call={call} onReport={setReport} />)} />
          <Route path="/audit" element={guarded("audit.read", <AuditPage audit={audit} />)} />
          <Route path="/settings" element={guarded("rooms.write", <SettingsPage call={call} notify={notify} />)} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </Suspense>
    </AppShell>
  );
}

export default function App() {
  return (
    <BrowserRouter>
      <AppLayout />
    </BrowserRouter>
  );
}
