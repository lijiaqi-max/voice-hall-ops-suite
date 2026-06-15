import type { ReactNode } from "react";

export function Card({ title, action, children, className = "" }: {
  title: string;
  action?: ReactNode;
  children: ReactNode;
  className?: string;
}) {
  return (
    <section className={`card ${className}`}>
      <header className="card-header"><h3>{title}</h3>{action}</header>
      {children}
    </section>
  );
}

export function Stat({ label, value, detail }: { label: string; value: string; detail: string }) {
  return <div className="stat"><span>{label}</span><strong>{value}</strong><small>{detail}</small></div>;
}

export function Table({ headers, children, empty }: {
  headers: string[];
  children: ReactNode;
  empty?: boolean;
}) {
  return (
    <div className="table-wrap">
      <table><thead><tr>{headers.map((h) => <th key={h}>{h}</th>)}</tr></thead>
        <tbody>{empty ? <tr><td colSpan={headers.length} className="empty">暂无数据</td></tr> : children}</tbody>
      </table>
    </div>
  );
}
