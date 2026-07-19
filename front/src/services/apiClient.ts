import axios, { AxiosError, type AxiosRequestConfig, type AxiosResponse } from 'axios';

export const API_AUTH_ERROR_EVENT = 'avento:api-auth-error';

export type ApiAuthErrorKind = 'session-expired' | 'forbidden';

export interface ApiAuthErrorDetail {
  kind: ApiAuthErrorKind;
  status: number;
  error: string;
  message: string;
  path: string;
  method: string;
}

interface RetryableRequestConfig extends AxiosRequestConfig {
  _retry?: boolean;
}

interface ApiErrorPayload {
  message?: string;
  path?: string;
  timestamp?: string;
  traceId?: string;
  errors?: Array<{ field: string; message: string }>;
}

export interface BaseResponse<T> {
  status: number;
  code: string;
  data: T;
}

let refreshSessionPromise: Promise<boolean> | null = null;
let refreshSessionController: AbortController | null = null;
let interceptorsReady = false;
let authLifecycle: 'unknown' | 'authenticated' | 'anonymous' | 'logging-out' = 'unknown';

export const api = axios.create({
  baseURL: '/',
  withCredentials: true,
});

function requestUrl(config?: AxiosRequestConfig): string {
  return config?.url || '';
}

function requestMethod(config?: AxiosRequestConfig): string {
  return (config?.method || 'GET').toUpperCase();
}

function requestPath(config?: AxiosRequestConfig): string {
  const url = requestUrl(config);
  try {
    return new URL(url, window.location.origin).pathname;
  } catch {
    return url;
  }
}

function isAuthRequest(config?: AxiosRequestConfig): boolean {
  const path = requestPath(config);
  return path.includes('/api/auth/login') ||
    path.includes('/api/auth/bootstrap') ||
    path.includes('/api/auth/refresh') ||
    path.includes('/api/auth/logout');
}

function shouldHandleAuthFailure(): boolean {
  return authLifecycle !== 'anonymous' && authLifecycle !== 'logging-out';
}

export function markApiSessionAuthenticated() {
  authLifecycle = 'authenticated';
}

export function markApiSessionAnonymous() {
  authLifecycle = 'anonymous';
}

export async function beginApiLogout() {
  authLifecycle = 'logging-out';
  refreshSessionController?.abort();
  const pendingRefresh = refreshSessionPromise;
  if (pendingRefresh) {
    await pendingRefresh.catch(() => false);
  }
}

function isSilentAuthProbe(config?: AxiosRequestConfig): boolean {
  return requestPath(config).includes('/api/auth/me');
}

type ApiErrorResponse = BaseResponse<ApiErrorPayload> | ApiErrorPayload | string;

function isBaseResponse<T = unknown>(value: unknown): value is BaseResponse<T> {
  if (!value || typeof value !== 'object') return false;
  const candidate = value as Partial<BaseResponse<unknown>>;
  return typeof candidate.status === 'number' &&
    typeof candidate.code === 'string' &&
    Object.prototype.hasOwnProperty.call(candidate, 'data');
}

function unwrapApiResponse(response: AxiosResponse): AxiosResponse {
  if (isBaseResponse(response.data)) {
    response.data = response.data.data;
  }
  return response;
}

function readErrorPayload(error: AxiosError<ApiErrorResponse>): { error: string; message: string; path?: string } {
  const status = error.response?.status || 0;
  const fallback = status === 403 ? 'Acesso negado pelo servidor.' : 'Sessao invalida ou expirada.';
  const responseData = error.response?.data;
  const data = isBaseResponse<ApiErrorPayload>(responseData) ? responseData.data : responseData;
  const code = isBaseResponse(responseData) ? responseData.code : `HTTP ${status}`;

  if (data && typeof data === 'object') {
    const message = typeof data.message === 'string' ? data.message : fallback;
    return {
      error: code,
      message,
      path: typeof data.path === 'string' ? data.path : undefined,
    };
  }

  if (typeof data === 'string' && data.trim()) {
    return { error: `HTTP ${status}`, message: data };
  }

  return { error: `HTTP ${status}`, message: fallback };
}

function dispatchAuthError(error: AxiosError<ApiErrorResponse>, kind: ApiAuthErrorKind) {
  const status = error.response?.status || 0;
  const payload = readErrorPayload(error);
  const detail: ApiAuthErrorDetail = {
    kind,
    status,
    error: payload.error,
    message: payload.message,
    path: payload.path || requestPath(error.config),
    method: requestMethod(error.config),
  };

  window.dispatchEvent(new CustomEvent<ApiAuthErrorDetail>(API_AUTH_ERROR_EVENT, { detail }));
}

async function refreshSessionOnce(): Promise<boolean> {
  if (!refreshSessionPromise) {
    refreshSessionController = new AbortController();
    refreshSessionPromise = axios.post('/api/auth/refresh', undefined, {
      withCredentials: true,
      signal: refreshSessionController.signal,
    })
      .then(response => response.status >= 200 && response.status < 300)
      .catch(() => false)
      .finally(() => {
        refreshSessionPromise = null;
        refreshSessionController = null;
      });
  }

  return refreshSessionPromise;
}

async function handleApiError(error: AxiosError<ApiErrorResponse>): Promise<AxiosResponse> {
  const status = error.response?.status;
  const config = error.config as RetryableRequestConfig | undefined;

  if (status === 403) {
    if (shouldHandleAuthFailure()) {
      dispatchAuthError(error, 'forbidden');
    }
    throw error;
  }

  if (status !== 401 || !config || isAuthRequest(config)) {
    throw error;
  }

  if (!shouldHandleAuthFailure()) {
    throw error;
  }

  if (!config._retry) {
    config._retry = true;
    const refreshed = await refreshSessionOnce();
    if (refreshed && shouldHandleAuthFailure()) {
      return api(config);
    }
  }

  if (shouldHandleAuthFailure() && !isSilentAuthProbe(config)) {
    dispatchAuthError(error, 'session-expired');
  }

  throw error;
}

export function apiErrorMessage(error: unknown): string {
  if (axios.isAxiosError<ApiErrorResponse>(error)) {
    const payload = readErrorPayload(error);
    return payload.message || payload.error;
  }
  if (error instanceof Error) {
    return error.message;
  }
  return 'Erro inesperado.';
}

export function setupApiClient() {
  if (interceptorsReady) {
    return;
  }

  api.interceptors.response.use(
    unwrapApiResponse,
    error => axios.isAxiosError<ApiErrorResponse>(error) ? handleApiError(error) : Promise.reject(error),
  );
  interceptorsReady = true;
}
