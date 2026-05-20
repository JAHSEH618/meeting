/**
 * Workstation runtime config — dev fallback.
 *
 * In production, FastAPI serves this path (registered before the
 * /workstation StaticFiles mount in apps/ai-worker/ai_worker/interfaces/api/main.py)
 * and overrides the body with values from AI_WORKER_AUTH_LOGIN_URL etc.
 *
 * In dev (Vite), this file is copied as-is from public/ and provides
 * a safe empty default so the SPA bundle can read window.__WORKSTATION_CONFIG__
 * without hitting an undefined global. shared/auth/store.ts falls back to
 * VITE_AUTH_LOGIN_URL + /auth/login when the keys are missing.
 */
window.__WORKSTATION_CONFIG__ = {};
