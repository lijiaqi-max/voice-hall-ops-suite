import type { ReactNode } from "react";
import { Link, useLocation } from "react-router-dom";
import { hasAnyPermission } from "../permissions";
import type { Session } from "../types";

export const navigation = [
  { id: "/", label: "经营看板", icon: "⌁", permissions: [] },
  { id: "/rooms", label: "厅房管理", icon: "⌂", permissions: ["rooms.read"] },
  { id: "/shifts", label: "主持排班", icon: "◫", permissions: ["shifts.read"] },
  { id: "/control", label: "厅控数据", icon: "⌁", permissions: ["mic.read"] },
  { id: "/members", label: "成员管理", icon: "◎", permissions: ["rooms.write"] },
  { id: "/customers", label: "关系库", icon: "◇", permissions: ["customers.read"] },
  { id: "/tasks", label: "作业中心", icon: "✓", permissions: ["tasks.read"] },
  { id: "/revenue", label: "流水导入", icon: "↥", permissions: ["revenue.write"] },
  { id: "/finance", label: "财务结算", icon: "¥", permissions: ["finance.read"] },
  { id: "/reports", label: "报表", icon: "▥", permissions: ["reports.read"] },
  { id: "/audit", label: "审计", icon: "◉", permissions: ["audit.read"] },
  { id: "/settings", label: "定制设置", icon: "⚙", permissions: ["rooms.write"] },
] as const;

export function AppShell({
  session,
  error,
  notice,
  onLogout,
  onRefresh,
  children,
}: {
  session: Session;
  error: string;
  notice: string;
  onLogout: () => void;
  onRefresh: () => void;
  children: ReactNode;
}) {
  const location = useLocation();
  const visibleNavigation = navigation.filter((item) =>
    hasAnyPermission(session.permissions, item.permissions),
  );

  return (
    <div className="app-shell">
      <aside>
        <div className="brand">
          <div className="brand-mark">声</div>
          <div><b>语音厅运营中台</b><small>PRIVATE OPS SUITE</small></div>
        </div>
        <nav>{visibleNavigation.map((item) => (
          <Link key={item.id} to={item.id} className={location.pathname === item.id ? "active" : ""}>
            <span>{item.icon}</span>{item.label}
          </Link>
        ))}</nav>
        <div className="operator">
          <span className="avatar">{session.account.displayName.slice(0, 1)}</span>
          <div><b>{session.account.displayName}</b><small>{session.account.role}</small></div>
          <button title="退出" onClick={onLogout}>↪</button>
        </div>
      </aside>
      <main className="workspace">
        <header className="topbar">
          <div>
            <span className="eyebrow">运营工作台</span>
            <h2>{navigation.find((item) => item.id === location.pathname)?.label}</h2>
          </div>
          <div className="top-actions">
            <span className="status-dot">服务已连接</span>
            <button onClick={onRefresh}>刷新</button>
          </div>
        </header>
        {error && <div className="error-banner page-banner">{error}</div>}
        {notice && <div className="toast">{notice}</div>}
        <div className="page">{children}</div>
      </main>
    </div>
  );
}
