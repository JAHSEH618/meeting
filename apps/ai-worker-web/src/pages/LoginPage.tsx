import { FormEvent, useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { authStore } from "@/shared/auth/store";
import type { ErrorInfo } from "@/shared/api/client";

interface LoginData {
  accessToken: string;
  expiresAt: string;
  user: {
    userId: string;
    tenantId: string;
    roles: string[];
  };
}

interface LoginEnvelope {
  success: boolean;
  data?: LoginData | null;
  error?: ErrorInfo | null;
}

function normalizeRedirect(raw: string | null): string {
  if (!raw) return "/meetings";
  try {
    const parsed = new URL(raw, window.location.origin);
    if (parsed.origin !== window.location.origin) return "/meetings";
    if (parsed.pathname.startsWith("/workstation/")) {
      return parsed.pathname.replace(/^\/workstation/, "") + parsed.search + parsed.hash;
    }
    if (parsed.pathname === "/workstation") return "/meetings";
    return parsed.pathname + parsed.search + parsed.hash;
  } catch {
    return raw.startsWith("/") ? raw : "/meetings";
  }
}

export function LoginPage() {
  const location = useLocation();
  const navigate = useNavigate();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent<HTMLFormElement>): Promise<void> {
    event.preventDefault();
    setError(null);
    setBusy(true);
    try {
      const response = await fetch("/api/auth/login", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Accept: "application/json",
        },
        credentials: "include",
        body: JSON.stringify({ username, password }),
      });
      const envelope = (await response.json()) as LoginEnvelope;
      if (!response.ok || envelope.success === false || envelope.error || !envelope.data?.accessToken) {
        setError(envelope.error?.message ?? `登录失败：HTTP ${response.status}`);
        return;
      }
      authStore.set(envelope.data.accessToken);
      const params = new URLSearchParams(location.search);
      navigate(normalizeRedirect(params.get("redirect")), { replace: true });
    } catch (e) {
      setError(e instanceof Error ? e.message : "登录失败");
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="login-page" aria-labelledby="login-title">
      <form className="card login-panel" onSubmit={submit}>
        <div className="stack">
          <h1 id="login-title" className="page-title">运营工作站登录</h1>
          <p className="page-subtitle">使用 Java 服务签发的管理员令牌进入 Python 工作站。</p>
        </div>

        <div className="field">
          <label className="field__label" htmlFor="workstation-username">用户名</label>
          <input
            id="workstation-username"
            className="input"
            autoComplete="username"
            value={username}
            onChange={(event) => setUsername(event.currentTarget.value)}
            disabled={busy}
          />
        </div>

        <div className="field">
          <label className="field__label" htmlFor="workstation-password">密码</label>
          <input
            id="workstation-password"
            className="input"
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(event) => setPassword(event.currentTarget.value)}
            disabled={busy}
          />
        </div>

        {error ? (
          <div className="banner banner--danger" role="alert">
            <strong className="banner__title">{error}</strong>
          </div>
        ) : null}

        <button className="button button--primary login-panel__submit" type="submit" disabled={busy}>
          {busy ? "登录中…" : "登录"}
        </button>
      </form>
    </section>
  );
}
