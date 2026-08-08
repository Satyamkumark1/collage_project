// Hand-written client for the handful of endpoints in play this phase — see
// specs/11-frontend.md. Worth generating from OpenAPI once the surface grows.

const API_BASE = import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080/api/v1";

// Access token lives in memory only, per specs/04-identity-and-security.md — never persisted,
// so a full page reload always goes through the refresh-cookie flow in AuthContext.
let accessToken: string | null = null;

export function setAccessToken(token: string | null): void {
  accessToken = token;
}

export function getAccessToken(): string | null {
  return accessToken;
}

// AuthProvider registers itself here so a failed silent-refresh (the session cookie is gone
// or expired) can reset in-memory auth state immediately, instead of leaving `status:
// "authenticated"` stale until the next unrelated 401 — protected routes rely on `status`
// flipping to "unauthenticated" to unmount.
let onSessionExpired: (() => void) | null = null;

export function setOnSessionExpired(callback: (() => void) | null): void {
  onSessionExpired = callback;
}

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;

  constructor(status: number, code: string, detail: string) {
    super(detail);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
  }
}

interface RequestOptions {
  method?: string;
  body?: unknown;
  isFormData?: boolean;
  idempotencyKey?: string;
  /** Set on the refresh/login/register calls themselves to avoid an infinite retry loop. */
  skipAuthRetry?: boolean;
}

async function rawRequest(path: string, options: RequestOptions): Promise<Response> {
  // Also doubles as the interim CSRF mitigation on the cookie-authenticated refresh/logout
  // endpoints — see docs/DECISIONS.md.
  const headers: Record<string, string> = { "X-Request-Id": crypto.randomUUID() };
  if (accessToken) {
    headers.Authorization = `Bearer ${accessToken}`;
  }
  if (options.idempotencyKey) {
    headers["Idempotency-Key"] = options.idempotencyKey;
  }

  let body: BodyInit | undefined;
  if (options.body !== undefined) {
    if (options.isFormData) {
      body = options.body as FormData;
    } else {
      headers["Content-Type"] = "application/json";
      body = JSON.stringify(options.body);
    }
  }

  return fetch(`${API_BASE}${path}`, {
    method: options.method ?? "GET",
    headers,
    body,
    credentials: "include",
  });
}

let refreshPromise: Promise<string | null> | null = null;

async function refreshAccessToken(): Promise<string | null> {
  refreshPromise ??= (async () => {
    try {
      const response = await fetch(`${API_BASE}/auth/refresh`, {
        method: "POST",
        credentials: "include",
        headers: { "X-Request-Id": crypto.randomUUID() },
      });
      if (!response.ok) {
        return null;
      }
      const data = (await response.json()) as { accessToken: string };
      setAccessToken(data.accessToken);
      return data.accessToken;
    } catch {
      return null;
    } finally {
      refreshPromise = null;
    }
  })();
  return refreshPromise;
}

export async function apiRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
  let response = await rawRequest(path, options);

  if (response.status === 401 && !options.skipAuthRetry) {
    const newToken = await refreshAccessToken();
    if (newToken) {
      response = await rawRequest(path, options);
    } else {
      onSessionExpired?.();
    }
  }

  if (!response.ok) {
    let code = "UNKNOWN";
    let detail = response.statusText;
    try {
      const problem = (await response.json()) as { code?: string; detail?: string };
      code = problem.code ?? code;
      detail = problem.detail ?? detail;
    } catch {
      // Non-JSON error body — fall back to statusText above.
    }
    throw new ApiError(response.status, code, detail);
  }

  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

export function newIdempotencyKey(): string {
  return crypto.randomUUID();
}
