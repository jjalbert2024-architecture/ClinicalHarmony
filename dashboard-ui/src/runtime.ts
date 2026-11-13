// The one runtime setting the SPA needs: where the gateway is. Everything else (Keycloak issuer,
// client id, endpoints) comes from the gateway's public GET /api/v1/config.

declare global {
  interface Window {
    __CLINICALHARMONY__?: { gatewayUrl?: string };
  }
}

export interface RuntimeConfig {
  /** Gateway origin, no trailing slash, e.g. http://localhost:8080 */
  gatewayUrl: string;
  /** Base of every API call: `${gatewayUrl}/api/v1` */
  apiBase: string;
}

export function readRuntimeConfig(win: Window = window): RuntimeConfig {
  const raw = win.__CLINICALHARMONY__?.gatewayUrl ?? 'http://localhost:8080';
  const gatewayUrl = raw.replace(/\/+$/, '');
  return { gatewayUrl, apiBase: `${gatewayUrl}/api/v1` };
}
