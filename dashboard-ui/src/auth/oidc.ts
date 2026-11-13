import { InMemoryWebStorage, UserManager, WebStorageStateStore, type User, type UserManagerSettings } from 'oidc-client-ts';
import type { TokenSource } from '../api/client';
import type { GatewayConfig } from '../api/types';

export const CALLBACK_PATH = '/callback';
export const SIGNED_OUT_PATH = '/signed-out';

/**
 * Settings for Keycloak Authorization Code + PKCE (S256) against the public client from
 * GET /api/v1/config. Endpoints come from that config rather than OIDC discovery, so the SPA needs
 * exactly one bootstrap call and nothing about Keycloak is baked into the build.
 *
 * Tokens live in memory only (InMemoryWebStorage). sessionStorage holds just the short-lived
 * state + PKCE verifier between leaving for Keycloak and coming back to /callback.
 */
export function buildUserManagerSettings(config: GatewayConfig, origin: string): UserManagerSettings {
  const { auth } = config;
  return {
    authority: auth.issuer,
    client_id: auth.clientId,
    redirect_uri: `${origin}${CALLBACK_PATH}`,
    post_logout_redirect_uri: `${origin}${SIGNED_OUT_PATH}`,
    response_type: 'code',
    scope: auth.scope || 'openid',
    metadata: {
      issuer: auth.issuer,
      authorization_endpoint: auth.authorizationEndpoint,
      token_endpoint: auth.tokenEndpoint,
      end_session_endpoint: auth.logoutEndpoint,
    },
    userStore: new WebStorageStateStore({ store: new InMemoryWebStorage() }),
    stateStore: new WebStorageStateStore({ store: window.sessionStorage }),
    // Renewal uses the refresh token (never a hidden iframe: the CSP forbids frames).
    automaticSilentRenew: true,
    accessTokenExpiringNotificationTimeInSeconds: 30,
    loadUserInfo: false,
    monitorSession: false,
    // No revocation endpoint in /config; Keycloak's logout ends the session server-side anyway.
    revokeTokensOnSignout: false,
  };
}

export type InitResult = { status: 'ready' } | { status: 'redirecting' } | { status: 'signed-out' };

/** What the rest of the UI needs from authentication. Tests substitute a fake. */
export interface AuthSession extends TokenSource {
  login(returnTo?: string): Promise<void>;
  logout(): Promise<void>;
}

interface LoginState {
  returnTo?: string;
}

function currentPath(): string {
  return window.location.pathname + window.location.search + window.location.hash;
}

export class OidcSession implements AuthSession {
  private readonly manager: UserManager;
  private user: User | null = null;
  private refreshing: Promise<string | null> | null = null;
  private redirecting = false;

  constructor(settings: UserManagerSettings) {
    this.manager = new UserManager(settings);
    this.manager.events.addUserLoaded((u) => {
      this.user = u;
    });
    this.manager.events.addUserUnloaded(() => {
      this.user = null;
    });
  }

  /**
   * Run once at startup:
   *  - on /callback: finish the code exchange and restore the route the user came from;
   *  - on /signed-out: stay put (the page offers "Sign in again");
   *  - otherwise: with no token in memory (every page load), go to Keycloak. With a live SSO
   *    session there, Keycloak redirects straight back without a password prompt.
   */
  async init(): Promise<InitResult> {
    const path = window.location.pathname;
    if (path === CALLBACK_PATH) {
      const user = await this.manager.signinRedirectCallback();
      this.user = user;
      const returnTo = safeReturnTo((user.state as LoginState | undefined)?.returnTo);
      window.history.replaceState(null, '', returnTo);
      await this.manager.clearStaleState();
      return { status: 'ready' };
    }
    if (path === SIGNED_OUT_PATH) {
      return { status: 'signed-out' };
    }
    const existing = await this.manager.getUser();
    if (existing && !existing.expired) {
      this.user = existing;
      return { status: 'ready' };
    }
    await this.login(currentPath());
    return { status: 'redirecting' };
  }

  async login(returnTo: string = currentPath()): Promise<void> {
    if (this.redirecting) return;
    this.redirecting = true;
    const state: LoginState = { returnTo: safeReturnTo(returnTo) };
    await this.manager.signinRedirect({ state });
  }

  async logout(): Promise<void> {
    const idToken = this.user?.id_token;
    this.user = null;
    await this.manager.removeUser();
    await this.manager.signoutRedirect(idToken ? { id_token_hint: idToken } : {});
  }

  async getAccessToken(): Promise<string | null> {
    if (this.user && !this.user.expired) return this.user.access_token;
    if (this.user?.refresh_token) return this.refresh();
    return null;
  }

  /** Refresh-token grant; concurrent callers share one in-flight refresh. */
  refresh(): Promise<string | null> {
    if (!this.refreshing) {
      const pending = this.doRefresh();
      this.refreshing = pending;
      void pending.finally(() => {
        if (this.refreshing === pending) this.refreshing = null;
      });
    }
    return this.refreshing;
  }

  private async doRefresh(): Promise<string | null> {
    if (!this.user?.refresh_token) return null;
    try {
      // With a refresh token in the stored user, signinSilent uses the refresh_token grant.
      const renewed = await this.manager.signinSilent();
      this.user = renewed;
      return renewed?.access_token ?? null;
    } catch {
      return null; // refresh token expired (SSO idle timeout) or Keycloak down
    }
  }

  onUnauthenticated(): void {
    void this.login(currentPath());
  }
}

/** Only same-origin paths survive the round trip through Keycloak (no open redirect via state). */
export function safeReturnTo(value: string | undefined): string {
  if (!value || !value.startsWith('/') || value.startsWith('//') || value.startsWith('/\\')) return '/';
  if (value.startsWith(CALLBACK_PATH) || value.startsWith(SIGNED_OUT_PATH)) return '/';
  return value;
}
