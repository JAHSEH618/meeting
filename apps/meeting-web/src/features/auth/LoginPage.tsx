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
    <main className="auth-page">
      <section className="auth-hero" aria-labelledby="auth-title">
        <span className="auth-hero__label">PRIVATE MEETING AI</span>
        <h1 id="auth-title" className="auth-hero__title">本地会议智能系统</h1>
        <p className="auth-hero__subtitle">
          转录、纪要、知识问答与合规留痕集中在一个本地工作台内完成。
        </p>
      </section>

      <section className="auth-card glass-panel" aria-label="登录">
        <div>
          <h2 className="auth-card__title">登录</h2>
          <p className="auth-card__subtitle">使用内置 MVP 账号进入会议处理工作台。</p>
        </div>
        <form className="auth-form form" onSubmit={onSubmit}>
          <div className="field">
            <label htmlFor="username">账号</label>
            <input id="username" value={username} onChange={(event) => setUsername(event.target.value)} autoComplete="username" />
          </div>
          <div className="field">
            <label htmlFor="password">密码</label>
            <input id="password" type="password" value={password} onChange={(event) => setPassword(event.target.value)} autoComplete="current-password" />
          </div>
          {error ? <div className="error" role="alert">{error}</div> : null}
          <button className="button button--primary" type="submit" disabled={submitting || !username.trim() || !password}>
            {submitting ? "登录中" : "登录"}
          </button>
        </form>
      </section>
    </main>
  );
}
