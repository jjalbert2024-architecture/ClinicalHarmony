import { http, HttpResponse } from 'msw';
import { describe, expect, it, vi } from 'vitest';
import { ApiClient, ApiError, buildQuery, newCorrelationId, type TokenSource } from '../src/api/client';
import { API, calls, fixtures, problem, reply, resetRequests, server } from './gateway';

function tokens(overrides: Partial<TokenSource> = {}): TokenSource & { [k: string]: ReturnType<typeof vi.fn> } {
  return {
    getAccessToken: vi.fn(async () => 'token-1'),
    refresh: vi.fn(async () => 'token-2'),
    onUnauthenticated: vi.fn(),
    ...overrides,
  } as never;
}

describe('ApiClient', () => {
  it('sends the Bearer token and a ui- correlation id on every call', async () => {
    resetRequests();
    const client = new ApiClient({ baseUrl: API, tokens: tokens() });
    const me = await client.get<{ username: string }>('/me');
    expect(me.username).toBe('dana');
    const [req] = calls('/me');
    expect(req!.headers.get('Authorization')).toBe('Bearer token-1');
    expect(req!.headers.get('X-Correlation-Id')).toMatch(/^ui-[0-9a-f]{12}$/);
  });

  it('calls public endpoints without a token', async () => {
    resetRequests();
    const t = tokens();
    const client = new ApiClient({ baseUrl: API, tokens: t });
    await client.request('GET', '/config', { anonymous: true });
    expect(calls('/config')[0]!.headers.get('Authorization')).toBeNull();
    expect(t.getAccessToken).not.toHaveBeenCalled();
  });

  it('on 401 refreshes once and retries with the new token and the SAME correlation id', async () => {
    resetRequests();
    server.use(
      http.get(`${API}/me`, ({ request }) =>
        request.headers.get('Authorization') === 'Bearer token-2'
          ? reply(request, fixtures.meDana)
          : problem(request, 401, 'unauthorized', 'Unauthorized', 'expired'),
      ),
    );
    const t = tokens();
    const client = new ApiClient({ baseUrl: API, tokens: t });
    await expect(client.get('/me')).resolves.toMatchObject({ username: 'dana' });
    expect(t.refresh).toHaveBeenCalledTimes(1);
    expect(t.onUnauthenticated).not.toHaveBeenCalled();
    const [first, second] = calls('/me');
    expect(second!.headers.get('X-Correlation-Id')).toBe(first!.headers.get('X-Correlation-Id'));
  });

  it('when the retry is still 401, signals re-login and throws the problem', async () => {
    server.use(http.get(`${API}/me`, ({ request }) => problem(request, 401, 'unauthorized', 'Unauthorized', 'nope')));
    const t = tokens();
    const client = new ApiClient({ baseUrl: API, tokens: t });
    const err = await client.get('/me').catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).status).toBe(401);
    expect(t.onUnauthenticated).toHaveBeenCalledTimes(1);
  });

  it('when no refresh is possible, signals re-login without a second request', async () => {
    resetRequests();
    server.use(http.get(`${API}/me`, ({ request }) => problem(request, 401, 'unauthorized', 'Unauthorized', 'nope')));
    const t = tokens({ refresh: vi.fn(async () => null) });
    await new ApiClient({ baseUrl: API, tokens: t }).get('/me').catch(() => undefined);
    expect(calls('/me')).toHaveLength(1);
    expect(t.onUnauthenticated).toHaveBeenCalledTimes(1);
  });

  it('turns a gateway problem into an ApiError carrying the problem and the echoed correlation id', async () => {
    server.use(
      http.post(`${API}/pmi/match/run`, ({ request }) =>
        problem(request, 503, 'downstream-unavailable', 'Service Unavailable', 'patient-index-service is unavailable', {
          service: 'patient-index-service',
        }),
      ),
    );
    const client = new ApiClient({ baseUrl: API, tokens: tokens(), correlationIds: () => 'ui-fixed' });
    const err = (await client.post('/pmi/match/run').catch((e: unknown) => e)) as ApiError;
    expect(err.status).toBe(503);
    expect(err.problem.service).toBe('patient-index-service');
    expect(err.correlationId).toBe('ui-fixed');
    expect(err.kind).toBe('problem');
  });

  it('synthesizes a problem for a non-problem error body', async () => {
    server.use(http.get(`${API}/summary`, () => new HttpResponse('<html>bad gateway</html>', { status: 502, statusText: 'Bad Gateway' })));
    const err = (await new ApiClient({ baseUrl: API, tokens: tokens() }).get('/summary').catch((e: unknown) => e)) as ApiError;
    expect(err.status).toBe(502);
    expect(err.problem.title).toBe('Bad Gateway');
    expect(err.problem.detail).toContain('bad gateway');
  });

  it('reports an unreachable gateway as a network ApiError with status 0', async () => {
    server.use(http.get(`${API}/summary`, () => HttpResponse.error()));
    const err = (await new ApiClient({ baseUrl: API, tokens: tokens() }).get('/summary').catch((e: unknown) => e)) as ApiError;
    expect(err.kind).toBe('network');
    expect(err.status).toBe(0);
    expect(err.problem.title).toBe('Gateway unreachable');
    expect(err.correlationId).toMatch(/^ui-/);
  });

  it('sends JSON bodies as application/json and strings with their content type', async () => {
    resetRequests();
    server.use(
      http.post(`${API}/fhir/validate`, ({ request }) => reply(request, fixtures.fhirValidate)),
      http.post(`${API}/pmi/candidates/:id/reject`, ({ request }) => reply(request, {})),
    );
    const client = new ApiClient({ baseUrl: API, tokens: tokens() });
    await client.post('/pmi/candidates/1/reject', { note: 'different' });
    await client.post('/fhir/validate', '{"resourceType":"Patient"}', { contentType: 'application/fhir+json' });
    const reject = calls('/reject')[0]!;
    expect(reject.headers.get('Content-Type')).toBe('application/json');
    expect(JSON.parse(reject.body)).toEqual({ note: 'different' });
    const validate = calls('/fhir/validate')[0]!;
    expect(validate.headers.get('Content-Type')).toBe('application/fhir+json');
    expect(validate.body).toBe('{"resourceType":"Patient"}');
  });
});

describe('buildQuery / newCorrelationId', () => {
  it('drops empty values and joins arrays', () => {
    expect(buildQuery({ q: 'garcia', dob: '', status: undefined, page: 0, types: ['CONDITION', 'MEDICATION'] })).toBe(
      '?q=garcia&page=0&types=CONDITION%2CMEDICATION',
    );
    expect(buildQuery({})).toBe('');
  });

  it('correlation ids match the gateway pattern and differ', () => {
    const a = newCorrelationId();
    expect(a).toMatch(/^[A-Za-z0-9._-]{1,64}$/);
    expect(a).not.toBe(newCorrelationId());
  });
});
