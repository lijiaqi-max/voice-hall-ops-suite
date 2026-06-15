import { useCallback, useEffect, useState } from "react";
import { BrowserRouter, Routes, Route, Navigate } from "react-router-dom";
import { ApiError, request } from "./api";
import type { Account, Audit, Customer, ReportSummary, Room, Shift, Task } from "./types";
import { useSession } from "./hooks/useSession";
import { Login } from "./components/Login";
import { AppShell } from "./layouts/AppShell";
import {
  Dashboard,
  RoomsPage,
  ShiftsPage,
  MembersPage,
  CustomersPage,
  TasksPage,
  RevenuePage,
  FinancePage,
  ReportsPage,
  AuditPage,
  SettingsPage,
} from "./components/pages";

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
  const [report, setReport] = useState<ReportSummary>(emptyReport);
  const [notice, setNotice] = useState("");
  const [error, setError] = useState("");
  const token = session?.accessToken;

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

  const loadAll = useCallback(async () => {
    if (!token) return;
    const [roomData, shiftData, customerData, taskData, reportData] = await Promise.all([
      call<Room[]>("/rooms"),
      call<Shift[]>("/shifts"),
      call<Customer[]>("/customers"),
      call<Task[]>("/tasks"),
      call<ReportSummary>(`/reports/summary?start=${Date.now() - 30 * 86_400_000}&end=${Date.now()}`),
    ]);
    setRooms(roomData);
    setShifts(shiftData);
    setCustomers(customerData);
    setTasks(taskData);
    setReport(reportData);
    if (session?.permissions.includes("*") || session?.permissions.includes("rooms.write")) {
      setAccounts(await call<Account[]>("/accounts"));
    }
    if (session?.permissions.includes("*") || session?.permissions.includes("audit.read")) {
      setAudit(await call<Audit[]>("/audit?limit=100"));
    }
  }, [token, call, session?.permissions]);

  useEffect(() => { loadAll().catch(() => undefined); }, [loadAll]);

  const notify = (message: string) => {
    setNotice(message);
    window.setTimeout(() => setNotice(""), 2600);
  };

  if (!session) return <Login onLogin={setSession} />;

  return (
    <AppShell
      session={session}
      error={error}
      notice={notice}
      onLogout={() => setSession(null)}
      onRefresh={() => loadAll()}
    >
      <Routes>
        <Route path="/" element={<Dashboard report={report} shifts={shifts} tasks={tasks} customers={customers} />} />
        <Route path="/rooms" element={<RoomsPage rooms={rooms} call={call} reload={loadAll} notify={notify} />} />
        <Route path="/shifts" element={<ShiftsPage rooms={rooms} accounts={accounts} shifts={shifts} call={call} reload={loadAll} notify={notify} />} />
        <Route path="/members" element={<MembersPage accounts={accounts} rooms={rooms} call={call} reload={loadAll} notify={notify} />} />
        <Route path="/customers" element={<CustomersPage customers={customers} call={call} reload={loadAll} notify={notify} />} />
        <Route path="/tasks" element={<TasksPage tasks={tasks} rooms={rooms} customers={customers} accounts={accounts} call={call} reload={loadAll} notify={notify} />} />
        <Route path="/revenue" element={<RevenuePage rooms={rooms} call={call} reload={loadAll} notify={notify} />} />
        <Route path="/finance" element={<FinancePage rooms={rooms} call={call} notify={notify} />} />
        <Route path="/reports" element={<ReportsPage report={report} call={call} onReport={setReport} />} />
        <Route path="/audit" element={<AuditPage audit={audit} />} />
        <Route path="/settings" element={<SettingsPage call={call} notify={notify} />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
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
