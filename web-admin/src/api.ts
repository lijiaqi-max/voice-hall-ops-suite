import type { Session } from "./types";

const configuredBase = import.meta.env.VITE_API_BASE_URL?.replace(/\/$/, "");
export const apiBase = configuredBase || "/api";

export class ApiError extends Error {
  constructor(
    message: string,
    public status: number,
  ) {
    super(message);
  }
}

export async function request<T>(
  path: string,
  options: RequestInit = {},
  token?: string,
): Promise<T> {
  const response = await fetch(`${apiBase}${path}`, {
    ...options,
    headers: {
      "Content-Type": "application/json",
      ...options.headers,
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
  });
  const body = await response.json().catch(() => ({}));
  if (!response.ok) {
    throw new ApiError(body.error || `请求失败（${response.status}）`, response.status);
  }
  return body as T;
}

export async function requestBlob(
  path: string,
  token?: string,
): Promise<Blob> {
  const response = await fetch(`${apiBase}${path}`, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  });
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    throw new ApiError(body.error || `下载失败（${response.status}）`, response.status);
  }
  return response.blob();
}

export const login = (username: string, password: string, otp?: string) =>
  request<Session>("/auth/login", {
    method: "POST",
    body: JSON.stringify({ username, password, otp: otp || null }),
  });
