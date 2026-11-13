import { useQuery } from '@tanstack/react-query';
import { createContext, useContext, type ReactNode } from 'react';
import type { ApiClient } from '../api/client';
import type { HealthReport, Me } from '../api/types';
import type { AuthSession } from '../auth/oidc';
import type { RuntimeConfig } from '../runtime';

export interface AppServices {
  api: ApiClient;
  session: AuthSession;
  runtime: RuntimeConfig;
}

const AppContext = createContext<AppServices | null>(null);

export function AppServicesProvider({ value, children }: { value: AppServices; children: ReactNode }) {
  return <AppContext.Provider value={value}>{children}</AppContext.Provider>;
}

export function useServices(): AppServices {
  const ctx = useContext(AppContext);
  if (!ctx) throw new Error('useServices outside AppServicesProvider');
  return ctx;
}

export function useApi(): ApiClient {
  return useServices().api;
}

/** Who-am-I from the gateway (GET /me) — roles as the gateway sees them, not decoded client-side. */
export function useMe() {
  const api = useApi();
  return useQuery({ queryKey: ['me'], queryFn: ({ signal }) => api.get<Me>('/me', undefined, signal), staleTime: 60_000 });
}

/** True only for data-steward. Hides nothing security-relevant: the gateway enforces every role. */
export function useCanAct(): boolean {
  return useMe().data?.canAct ?? false;
}

export const HEALTH_POLL_MS = 30_000;

export function useHealth() {
  const api = useApi();
  return useQuery({
    queryKey: ['services', 'health'],
    queryFn: ({ signal }) => api.get<HealthReport>('/services/health', undefined, signal),
    refetchInterval: HEALTH_POLL_MS,
  });
}

/** Whether the service that owns an action is down per the last health probe (unknown = not down). */
export function useServiceDown(service: string | null | undefined): boolean {
  const { data } = useHealth();
  if (!service || !data) return false;
  return data.degraded.includes(service);
}
