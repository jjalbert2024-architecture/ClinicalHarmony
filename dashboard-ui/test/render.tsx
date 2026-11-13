import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { vi } from 'vitest';
import { ApiClient } from '../src/api/client';
import { AppRoutes } from '../src/app/App';
import { AppServicesProvider } from '../src/app/context';
import type { AuthSession } from '../src/auth/oidc';
import { API, GATEWAY, resetRequests } from './gateway';

export interface FakeSession extends AuthSession {
  token: string;
  login: ReturnType<typeof vi.fn<AuthSession['login']>>;
  logout: ReturnType<typeof vi.fn<AuthSession['logout']>>;
  refresh: ReturnType<typeof vi.fn<AuthSession['refresh']>>;
  onUnauthenticated: ReturnType<typeof vi.fn<AuthSession['onUnauthenticated']>>;
}

/** A signed-in session without Keycloak: a fixed token, and spies for refresh/login/logout. */
export function fakeSession(token = 'token-1'): FakeSession {
  const session = {
    token,
    getAccessToken: async () => session.token,
    refresh: vi.fn(async () => null as string | null),
    login: vi.fn(async () => undefined),
    logout: vi.fn(async () => undefined),
    onUnauthenticated: vi.fn(() => undefined),
  };
  return session as unknown as FakeSession;
}

/** Renders the real routes at `path`, against the stub gateway, with a real ApiClient. */
export function renderApp(path: string, session: FakeSession = fakeSession()) {
  resetRequests();
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: Infinity }, mutations: { retry: false } },
  });
  const api = new ApiClient({ baseUrl: API, tokens: session });
  const result = render(
    <AppServicesProvider value={{ api, session, runtime: { gatewayUrl: GATEWAY, apiBase: API } }}>
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={[path]}>
          <AppRoutes />
        </MemoryRouter>
      </QueryClientProvider>
    </AppServicesProvider>,
  );
  return { ...result, session, queryClient };
}
