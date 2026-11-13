import { InMemoryWebStorage } from 'oidc-client-ts';
import { describe, expect, it } from 'vitest';
import type { GatewayConfig } from '../src/api/types';
import { buildUserManagerSettings, safeReturnTo } from '../src/auth/oidc';
import { readRuntimeConfig } from '../src/runtime';
import config from './fixtures/config.json';

describe('buildUserManagerSettings', () => {
  const settings = buildUserManagerSettings(config as GatewayConfig, 'http://localhost:3000');

  it('uses Auth Code (PKCE is on by default) against the public client from /config', () => {
    expect(settings.response_type).toBe('code');
    expect(settings.disablePKCE).toBeUndefined();
    expect(settings.client_id).toBe('clinicalharmony-dashboard');
    expect(settings.authority).toBe('http://localhost:8180/realms/clinicalharmony');
    expect(settings.scope).toBe('openid');
  });

  it('takes endpoints from /config instead of OIDC discovery', () => {
    expect(settings.metadata).toEqual({
      issuer: config.auth.issuer,
      authorization_endpoint: config.auth.authorizationEndpoint,
      token_endpoint: config.auth.tokenEndpoint,
      end_session_endpoint: config.auth.logoutEndpoint,
    });
  });

  it('redirects within the registered localhost:3000/* URIs', () => {
    expect(settings.redirect_uri).toBe('http://localhost:3000/callback');
    expect(settings.post_logout_redirect_uri).toBe('http://localhost:3000/signed-out');
  });

  it('keeps tokens in memory only and never uses an iframe', () => {
    const userStore = settings.userStore as unknown as { _store: unknown };
    expect(userStore._store).toBeInstanceOf(InMemoryWebStorage);
    expect(settings.monitorSession).toBe(false);
    expect(settings.loadUserInfo).toBe(false);
    expect(settings.automaticSilentRenew).toBe(true);
  });
});

describe('safeReturnTo', () => {
  it.each([
    ['/patients/3?types=CONDITION', '/patients/3?types=CONDITION'],
    ['/', '/'],
    [undefined, '/'],
    ['https://evil.example/', '/'],
    ['//evil.example/', '/'],
    ['/\\evil.example', '/'],
    ['/callback?code=x', '/'],
    ['/signed-out', '/'],
  ])('%s → %s', (input, expected) => {
    expect(safeReturnTo(input)).toBe(expected);
  });
});

describe('readRuntimeConfig', () => {
  it('reads the gateway URL written by the container and strips trailing slashes', () => {
    const win = { __CLINICALHARMONY__: { gatewayUrl: 'http://gw.example:8080//' } } as unknown as Window;
    expect(readRuntimeConfig(win)).toEqual({ gatewayUrl: 'http://gw.example:8080', apiBase: 'http://gw.example:8080/api/v1' });
  });

  it('defaults to localhost:8080', () => {
    expect(readRuntimeConfig({} as Window).apiBase).toBe('http://localhost:8080/api/v1');
  });
});
