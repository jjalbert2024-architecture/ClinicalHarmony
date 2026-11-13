import { StrictMode, useEffect, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { ApiClient, type TokenSource } from './api/client';
import type { GatewayConfig } from './api/types';
import { App, createQueryClient } from './app/App';
import { buildUserManagerSettings, OidcSession } from './auth/oidc';
import { ProblemView } from './components/ProblemView';
import { readRuntimeConfig } from './runtime';
import './styles.css';

const runtime = readRuntimeConfig();

/** Tokens before the session exists: none (only GET /config is called, anonymously). */
const noTokens: TokenSource = {
  getAccessToken: async () => null,
  refresh: async () => null,
  onUnauthenticated: () => undefined,
};

type Boot =
  | { state: 'loading' }
  | { state: 'ready'; session: OidcSession; api: ApiClient }
  | { state: 'signed-out'; session: OidcSession }
  | { state: 'error'; error: unknown; retry: () => void };

type BootResult = Extract<Boot, { state: 'ready' } | { state: 'signed-out' }> | 'redirecting';

// Memoized per attempt: StrictMode runs effects twice in development, and a /callback code can only
// be exchanged once.
let inflight: { attempt: number; promise: Promise<BootResult> } | null = null;

function startBoot(attempt: number): Promise<BootResult> {
  if (inflight?.attempt !== attempt) {
    inflight = { attempt, promise: boot() };
  }
  return inflight.promise;
}

async function boot(): Promise<BootResult> {
  const anon = new ApiClient({ baseUrl: runtime.apiBase, tokens: noTokens });
  const config = await anon.request<GatewayConfig>('GET', '/config', { anonymous: true });
  const session = new OidcSession(buildUserManagerSettings(config, window.location.origin));
  const result = await session.init();
  if (result.status === 'ready') {
    return { state: 'ready', session, api: new ApiClient({ baseUrl: runtime.apiBase, tokens: session }) };
  }
  if (result.status === 'signed-out') return { state: 'signed-out', session };
  return 'redirecting';
}

/**
 * Startup: GET /api/v1/config (public) → OIDC session → finish /callback or go to Keycloak → app.
 * Nothing renders behind the login: until there's a token, the user only sees this bootstrap.
 */
function Bootstrap() {
  const [boot, setBoot] = useState<Boot>({ state: 'loading' });
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let cancelled = false;
    startBoot(attempt).then(
      (result) => {
        if (cancelled || result === 'redirecting') return; // leaving for Keycloak: keep "Signing in…"
        setBoot(result);
      },
      (error: unknown) => {
        if (!cancelled) setBoot({ state: 'error', error, retry: () => setAttempt((a) => a + 1) });
      },
    );
    return () => {
      cancelled = true;
    };
  }, [attempt]);

  const [queryClient] = useState(createQueryClient);

  switch (boot.state) {
    case 'loading':
      return <Splash>Signing in…</Splash>;
    case 'signed-out':
      return (
        <Splash>
          <p>You're signed out.</p>
          <button
            className="btn btn-primary"
            onClick={() => {
              window.history.replaceState(null, '', '/');
              void boot.session.login('/');
            }}
          >
            Sign in again
          </button>
        </Splash>
      );
    case 'error':
      return (
        <Splash>
          <ProblemView
            error={boot.error}
            context="Starting the dashboard"
            onRetry={() => {
              if (window.location.pathname === '/callback') window.history.replaceState(null, '', '/');
              boot.retry();
            }}
          />
        </Splash>
      );
    case 'ready':
      return <App services={{ api: boot.api, session: boot.session, runtime }} queryClient={queryClient} />;
  }
}

function Splash({ children }: { children: React.ReactNode }) {
  return (
    <div className="splash">
      <div className="brand splash-brand">
        <span className="brand-mark" aria-hidden="true">+</span>
        ClinicalHarmony
      </div>
      <div className="splash-body">{children}</div>
    </div>
  );
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <Bootstrap />
  </StrictMode>,
);
