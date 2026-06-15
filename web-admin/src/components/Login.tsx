import { useState, type FormEvent } from "react";
import { login } from "../api";
import type { Session } from "../types";

export function Login({ onLogin }: { onLogin: (session: Session) => void }) {
  const [username, setUsername] = useState("admin");
  const [password, setPassword] = useState("ChangeMe-12345");
  const [otp, setOtp] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setError("");
    try {
      onLogin(await login(username, password, otp));
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "登录失败");
    } finally {
      setBusy(false);
    }
  };

  return (
    <main className="login-shell">
      <section className="login-copy">
        <span className="eyebrow">VOICE HALL OPERATIONS</span>
        <h1>把排班、关系、流水和结算放进一条可审计的工作流。</h1>
        <p>
          私有部署，按组织和厅房隔离。官方账单是金额唯一来源，成员交流保持人工确认。
        </p>
        <div className="login-points">
          <span>多厅排班</span><span>整数分账</span><span>角色权限</span>
        </div>
      </section>
      <form className="login-card" onSubmit={submit}>
        <div className="brand-mark">声</div>
        <div>
          <h2>语音厅运营中台</h2>
          <p className="muted">管理端 1.0.0</p>
        </div>
        <label>
          账号
          <input value={username} onChange={(e) => setUsername(e.target.value)} />
        </label>
        <label>
          密码
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
        </label>
        <label>
          动态验证码（已启用 TOTP 的账号填写）
          <input
            inputMode="numeric"
            maxLength={6}
            value={otp}
            onChange={(e) => setOtp(e.target.value.replace(/\D/g, ""))}
          />
        </label>
        {error && <div className="error-banner">{error}</div>}
        <button className="primary" disabled={busy}>{busy ? "登录中…" : "进入中台"}</button>
        <small>首次部署后请立即修改默认密码，并通过 HTTPS 访问。</small>
      </form>
    </main>
  );
}
