import { apiRequest } from "./client";

export interface RegisterRequest {
  email: string;
  password: string;
  name: string;
  birthYear: number;
}

export interface RegisterResponse {
  id: string;
  email: string;
  name: string;
  createdAt: string;
}

export interface LoginRequest {
  email: string;
  password: string;
}

export interface AccessTokenResponse {
  accessToken: string;
}

export interface MeResponse {
  id: string;
  email: string;
  name: string;
  role: string;
  emailVerified: boolean;
  isMinor: boolean;
  guardianConsentRequired: boolean;
}

export function register(payload: RegisterRequest): Promise<RegisterResponse> {
  return apiRequest<RegisterResponse>("/auth/register", { method: "POST", body: payload, skipAuthRetry: true });
}

export function login(payload: LoginRequest): Promise<AccessTokenResponse> {
  return apiRequest<AccessTokenResponse>("/auth/login", { method: "POST", body: payload, skipAuthRetry: true });
}

export function refresh(): Promise<AccessTokenResponse> {
  return apiRequest<AccessTokenResponse>("/auth/refresh", { method: "POST", skipAuthRetry: true });
}

export function logout(): Promise<void> {
  return apiRequest<void>("/auth/logout", { method: "POST", skipAuthRetry: true });
}

export function me(): Promise<MeResponse> {
  return apiRequest<MeResponse>("/me");
}
