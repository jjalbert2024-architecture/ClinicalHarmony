import type { Problem } from './types';

/** Where the client gets Bearer tokens; implemented by the OIDC session (and by fakes in tests). */
export interface TokenSource {
  /** Current access token, or null when signed out. */
  getAccessToken(): Promise<string | null>;
  /** Try to get a fresh token (refresh-token grant). Null when that isn't possible. */
  refresh(): Promise<string | null>;
  /** Called when the gateway still says 401 after a refresh: the UI must sign in again. */
  onUnauthenticated(): void;
}

export type ApiErrorKind = 'problem' | 'network';

/**
 * Every failed call surfaces as an ApiError carrying an RFC 7807 problem — the gateway's own, or one
 * synthesized here (gateway unreachable, non-problem body) so the UI renders all errors the same way.
 */
export class ApiError extends Error {
  readonly status: number;
  readonly problem: Problem;
  readonly correlationId: string;
  readonly kind: ApiErrorKind;

  constructor(kind: ApiErrorKind, problem: Problem, correlationId: string) {
    super(problem.detail ?? problem.title);
    this.name = 'ApiError';
    this.kind = kind;
    this.status = problem.status;
    this.problem = problem;
    this.correlationId = problem.correlationId ?? correlationId;
  }
}

export type QueryValue = string | number | boolean | null | undefined | string[];

export interface RequestOptions {
  query?: Record<string, QueryValue>;
  /** Object → JSON; string → sent as-is with `contentType`. */
  body?: unknown;
  contentType?: string;
  signal?: AbortSignal;
  /** Public endpoints (GET /config) are called without a token. */
  anonymous?: boolean;
}

/** `ui-` + 12 hex chars: matches the gateway's accepted pattern [A-Za-z0-9._-]{1,64}. */
export function newCorrelationId(): string {
  const bytes = new Uint8Array(6);
  crypto.getRandomValues(bytes);
  return 'ui-' + Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
}

export function buildQuery(query: Record<string, QueryValue> | undefined): string {
  if (!query) return '';
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined || value === null || value === '') continue;
    params.set(key, Array.isArray(value) ? value.join(',') : String(value));
  }
  const s = params.toString();
  return s ? `?${s}` : '';
}

export interface ApiClientOptions {
  baseUrl: string;
  tokens: TokenSource;
  fetchImpl?: typeof fetch;
  correlationIds?: () => string;
}

export class ApiClient {
  private readonly baseUrl: string;
  private readonly tokens: TokenSource;
  private readonly fetchImpl: typeof fetch;
  private readonly correlationIds: () => string;

  constructor(options: ApiClientOptions) {
    this.baseUrl = options.baseUrl.replace(/\/+$/, '');
    this.tokens = options.tokens;
    this.fetchImpl = options.fetchImpl ?? ((input, init) => fetch(input, init));
    this.correlationIds = options.correlationIds ?? newCorrelationId;
  }

  get<T>(path: string, query?: Record<string, QueryValue>, signal?: AbortSignal): Promise<T> {
    return this.request<T>('GET', path, { query, signal });
  }

  post<T>(path: string, body?: unknown, options: Omit<RequestOptions, 'body'> = {}): Promise<T> {
    return this.request<T>('POST', path, { ...options, body });
  }

  put<T>(path: string, body?: unknown): Promise<T> {
    return this.request<T>('PUT', path, { body });
  }

  async request<T>(method: string, path: string, options: RequestOptions = {}): Promise<T> {
    // One correlation id per logical call: the 401-refresh retry reuses it, so the gateway's logs
    // show both attempts under the id the user sees.
    const correlationId = this.correlationIds();
    const url = `${this.baseUrl}${path}${buildQuery(options.query)}`;

    let token = options.anonymous ? null : await this.tokens.getAccessToken();
    let response = await this.send(method, url, options, correlationId, token);

    if (response.status === 401 && !options.anonymous) {
      token = await this.tokens.refresh();
      if (token) {
        response = await this.send(method, url, options, correlationId, token);
      }
      if (response.status === 401) {
        const error = await toApiError(response, correlationId);
        this.tokens.onUnauthenticated();
        throw error;
      }
    }

    if (!response.ok) {
      throw await toApiError(response, correlationId);
    }
    if (response.status === 204) return undefined as T;
    const text = await response.text();
    if (!text) return undefined as T;
    try {
      return JSON.parse(text) as T;
    } catch {
      throw new ApiError(
        'problem',
        {
          type: 'urn:clinicalharmony:problem:ui-unreadable-response',
          title: 'Unreadable response',
          status: response.status,
          detail: `The gateway answered ${method} ${path} with a body that isn't JSON.`,
        },
        response.headers.get('X-Correlation-Id') ?? correlationId,
      );
    }
  }

  private async send(
    method: string,
    url: string,
    options: RequestOptions,
    correlationId: string,
    token: string | null,
  ): Promise<Response> {
    const headers: Record<string, string> = {
      Accept: 'application/json, application/problem+json',
      'X-Correlation-Id': correlationId,
    };
    if (token) headers.Authorization = `Bearer ${token}`;
    let body: BodyInit | undefined;
    if (options.body !== undefined) {
      if (typeof options.body === 'string') {
        body = options.body;
        headers['Content-Type'] = options.contentType ?? 'text/plain';
      } else {
        body = JSON.stringify(options.body);
        headers['Content-Type'] = options.contentType ?? 'application/json';
      }
    }
    try {
      return await this.fetchImpl(url, { method, headers, body, signal: options.signal });
    } catch (e) {
      if (e instanceof DOMException && e.name === 'AbortError') throw e;
      // fetch rejects (TypeError) on DNS failure, refused connection, or a CORS rejection.
      throw new ApiError(
        'network',
        {
          type: 'urn:clinicalharmony:problem:ui-gateway-unreachable',
          title: 'Gateway unreachable',
          status: 0,
          detail: `The dashboard couldn't reach the api-gateway at ${new URL(url).origin}. It may be down, or blocking this origin (CORS).`,
        },
        correlationId,
      );
    }
  }
}

/** Reads an error response into a problem; anything that isn't one gets a synthesized problem. */
export async function toApiError(response: Response, sentCorrelationId: string): Promise<ApiError> {
  const correlationId = response.headers.get('X-Correlation-Id') ?? sentCorrelationId;
  const text = await response.text().catch(() => '');
  let parsed: unknown = null;
  try {
    parsed = text ? JSON.parse(text) : null;
  } catch {
    parsed = null;
  }
  if (isProblem(parsed)) {
    return new ApiError('problem', { ...parsed, status: parsed.status ?? response.status }, correlationId);
  }
  const p = (parsed ?? {}) as Record<string, unknown>;
  const detail =
    (typeof p.detail === 'string' && p.detail) ||
    (typeof p.message === 'string' && p.message) ||
    (text && text.length < 500 ? text : undefined) ||
    `HTTP ${response.status}`;
  return new ApiError(
    'problem',
    {
      type: 'about:blank',
      title: response.statusText || `HTTP ${response.status}`,
      status: response.status,
      detail,
      correlationId,
    },
    correlationId,
  );
}

function isProblem(value: unknown): value is Problem {
  return (
    typeof value === 'object' &&
    value !== null &&
    typeof (value as Problem).title === 'string' &&
    typeof (value as Problem).type === 'string'
  );
}
