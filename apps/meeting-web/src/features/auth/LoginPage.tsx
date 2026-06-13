import { FormEvent, useState } from "react";
import { Navigate, useLocation, useNavigate } from "react-router-dom";
import { useAuth } from "@services/auth";
import { getUserMessage } from "@shared/utils/error-mapper";
import type { ApiClientError } from "@shared/api/client";

export function LoginPage() {
  const { isAuthenticated, isLoading, login } = useAuth();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const navigate = useNavigate();
  const location = useLocation();
  const from = (location.state as { from?: { pathname?: string } } | null)?.from?.pathname ?? "/meetings";

  if (!isLoading && isAuthenticated) {
    return <Navigate to={from} replace />;
  }

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      await login(username.trim(), password);
      navigate(from, { replace: true });
    } catch (cause) {
      const apiError = cause as ApiClientError;
      setError(apiError.code ? getUserMessage(apiError.code) : "登录服务暂不可用");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className="page">
      <section className="card" style={{ maxWidth: 460, margin: "64px auto" }}>
        <h1 className="page-title">本地会议智能系统</h1>
        <p className="muted">使用内置 MVP 账号进入会议处理工作台。</p>
        <form className="form" onSubmit={onSubmit}>
          <div className="field">
            <label htmlFor="username">账号</label>
            <input id="username" value={username} onChange={(event) => setUsername(event.target.value)} autoComplete="username" />
          </div>
          <div className="field">
            <label htmlFor="password">密码</label>
            <input id="password" type="password" value={password} onChange={(event) => setPassword(event.target.value)} autoComplete="current-password" />
          </div>
          {error ? <div className="error" role="alert">{error}</div> : null}
          <button className="primary" type="submit" disabled={submitting || !username.trim() || !password}>
            {submitting ? "登录中" : "登录"}
          </button>
        </form>
      </section>
    </main>
  );
}
