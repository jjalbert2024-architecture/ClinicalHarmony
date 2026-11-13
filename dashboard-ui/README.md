# dashboard-ui

**Phase 8.** The ClinicalHarmony dashboard: a React + TypeScript single-page app on port 3000. It
talks **only** to the Phase 7 [api-gateway](../api-gateway) (`http://localhost:8080/api/v1`), never to
the services directly, and signs users in with Keycloak.

| View | Route | What it shows | Gateway endpoints |
|---|---|---|---|
| Overview (shell) | `/` | Layer summary Bronze → Silver → Gold → PMI, open findings, service health | `GET /summary`, `/services/health`, `/me` |
| Patient Timeline | `/patients`, `/patients/:id` | Master patient search; one master's source records (MRNs), Gold FHIR counts, merge history with **Unmerge**, and a clinical timeline across every source feed (SVG strip + list), each event flagged known/unknown code and linked to its Gold resource and Bronze message | `GET /patients`, `/patients/{id}`, `/patients/{id}/timeline`; `POST /pmi/merges/{id}/unmerge` |
| PMI Review | `/pmi`, `/pmi/candidates/:id` | Review queue, recent runs, **Run matching**; a candidate side by side with the per-field score breakdown, **Same person — merge** / **Not the same person** | `GET /pmi/candidates[/{id}]`, `/pmi/runs`; `POST /pmi/match/run`, `/pmi/candidates/{id}/accept\|reject` |
| Ontology Coverage | `/ontology` | Per terminology: % of used codes in the reference set, top unknown codes, open `CODE-00x` findings; SNOMED ↔ ICD-10 mapping coverage; code lookup and a mapper | `GET /ontology/coverage`, `/ontology/codes/{system}/{code}`, `/ontology/map` |
| Validation Report | `/validation`, `/validation/rules` | Findings by severity / rule / entity type (click to filter), a paged, filterable findings table; the rule catalog with **toggles** | `GET /validation/summary`, `/validation/findings`, `/validation/rules`; `PUT /validation/rules/{code}/toggle` |
| FHIR Viewer | `/fhir`, `/fhir/:type/:id`, `/fhir/tools` | Gold resources by type and compliance; one resource's HL7 validator messages (FHIRPath location, suggested fix), lineage and JSON; **validate** pasted JSON and **convert** a C-CDA (nothing stored) | `GET /fhir/resource-types`, `/fhir/resources[/{type}/{id}]`; `POST /fhir/validate`, `/fhir/convert/ccda` |
| Data Lineage | `/lineage`, `/lineage/bronze/:id` | Trace a Gold resource or master through every step (BRONZE → SILVER → GOLD with transformer and content hashes), transformation counts, the Bronze message browser with raw payload and quarantine reason | `GET /lineage`, `/lineage/summary`, `/bronze/messages[/{id}]` |

No gateway change was needed: every view maps onto an existing `/api/v1` endpoint.

## Stack

Lean on purpose. Versions are pinned exactly (`.npmrc` `save-exact`), each one at least two weeks old
when chosen.

| | Package | Version |
|---|---|---|
| UI | react / react-dom | 19.3.1 |
| Routing | react-router | 8.5.1 |
| Server state | @tanstack/react-query | 5.105.0 |
| OIDC | oidc-client-ts | 3.5.0 |
| Build | vite + @vitejs/plugin-react, typescript (strict) | 8.3.5, 6.1.2, 7.0.2 |
| Tests | vitest, jsdom, @testing-library/{react,dom,user-event,jest-dom}, msw | 5.0.5, 30.1.3, …, 3.0.3 |

No component or chart library: plain CSS with design tokens (light and dark), and the few charts
(proportion bars, the timeline strip) are hand-drawn CSS/SVG. About 128 KB of gzipped JS.

## Auth

```
browser ──GET /api/v1/config (public)──▶ api-gateway      issuer, client id, endpoints
   │
   ├──Auth Code + PKCE S256──▶ Keycloak (client clinicalharmony-dashboard, public)
   │        redirect http://localhost:3000/callback
   │
   └──Bearer <user access token> + X-Correlation-Id──▶ api-gateway /api/v1/**
```

- **Library:** `oidc-client-ts` (state, nonce, PKCE, code exchange, refresh, RP-initiated logout).
  Settings come from `GET /api/v1/config`, passed as explicit metadata, so there is no OIDC discovery
  call and nothing about Keycloak is baked into the build (`src/auth/oidc.ts`).
- **Token storage:** in memory only (`InMemoryWebStorage`). sessionStorage holds just the short-lived
  state + PKCE verifier between leaving for Keycloak and returning to `/callback`. Nothing goes in
  localStorage.
- **Reload:** with no token in memory, the app redirects to Keycloak. While the Keycloak SSO session is
  alive, Keycloak redirects straight back without a prompt, to the same route (the route travels in
  the OIDC `state`; only same-origin paths are accepted).
- **Refresh:** the refresh-token grant, about 30 s before the 300 s access token expires, and on any
  401. Never a hidden iframe: the CSP forbids frames.
- **401:** one refresh and a retry with the **same** correlation id. If it's still 401, the user goes
  back through login to the same route.
- **Logout:** Keycloak's end-session endpoint with `id_token_hint`, then `/signed-out` with a
  "Sign in again" button. It really ends the SSO session.
- **Who-am-I:** `GET /me` (roles as the gateway sees them), never decoded client-side.

## Roles

| | `vic` (dashboard-viewer) | `dana` (dashboard-viewer + data-steward) |
|---|---|---|
| All five views, filters, drill-downs | ✅ | ✅ |
| FHIR validate / C-CDA convert (no side effects) | ✅ | ✅ |
| Run matching, accept / reject a candidate, unmerge, toggle a rule | Button shown **disabled**, with the reason "Requires the data-steward role" | ✅ behind a confirmation |

`/me.canAct` drives the UI only. The gateway enforces every role (403 otherwise), and records the
**token's** user as the PMI reviewer; the UI never sends a `reviewer`.

Every steward action opens a `ConfirmDialog` that says exactly what will happen and runs the call:

- **Unmerge:** a reason is required, and the dialog says which records move back.
- **Reject:** a note is required.
- **Accept:** the note is optional.
- **Rule toggle:** names the owning service.

On failure the dialog stays open with the gateway's problem (correlation id included). Rules that
can't be toggled show "not toggleable" instead of a switch, so the 409 is never reachable from the UI.

## Errors and degraded services

- **One error component** (`ProblemView`) for every RFC 7807 problem. It shows title, HTTP status,
  detail, `service` / `downstreamStatus`, parameter `errors[]`, and the **correlation id** with a copy
  button. A 403 is shown as "Not allowed" plus the required role.
- **Synthesized problems** for failures that never reach the gateway, so they render the same way:
  - unreachable gateway or CORS failure: status 0, "Gateway unreachable"
  - a non-JSON error body
- **Correlation ids:** every request sends `X-Correlation-Id: ui-<12 hex>`. The gateway echoes it,
  logs under it and forwards it, so the id on screen is the one to grep for in the gateway's,
  fhir-compliance-service's and patient-index-service's logs.
- **Retries:** queries are retried once only for an unreachable gateway or 502/503/504, never for
  4xx. Mutations are never retried.
- **Health:** `GET /services/health` is polled every 30 s and shown as a pill in the header. When it
  reports DEGRADED:
  - a banner names the services that are down;
  - read views keep working, since the gateway serves them from the database;
  - buttons for actions owned by a down service are disabled, with "<service> is down".

## Running

### In Docker (the normal way)

```bash
docker compose up -d --build dashboard-ui     # from the repo root; then open http://localhost:3000
```

The image is multi-stage:

1. **Build stage** (`node:24-alpine`): runs `npm ci`, the **test suite**, the typecheck and
   `vite build`. If any test fails, there is no image.
2. **Serve stage** (`nginx:1.30.6-alpine`): serves the static bundle. Both alpine images have
   arm64 builds.

nginx proxies nothing. The browser calls the gateway and Keycloak directly, and CORS is the
gateway's (`CORS_ALLOWED_ORIGINS`) and Keycloak's (the client's `webOrigins`).

| Variable | Default | Purpose |
|---|---|---|
| `GATEWAY_URL` | `http://localhost:8080` | Written into `/config.js` at container start; also in the CSP `connect-src` |
| `KEYCLOAK_URL` | `http://localhost:8180` | Only used in the CSP `connect-src` (the issuer itself comes from `/api/v1/config`) |

Both are URLs **as the browser sees them**, not compose service names.
`docker/40-runtime-config.sh` renders them at startup, so one image works in any environment.

What nginx serves (`docker/default.conf.template`, `docker/security-headers.conf`):

- **Routes:** SPA fallback for deep links, and a real 404 for missing `/assets/*`.
- **Caching:** `no-cache` for `index.html`, `no-store` for `config.js`, `immutable` for hashed bundles.
- **Health:** `/healthz` for the container healthcheck.
- **Security headers:**
  - CSP: `script-src 'self'`, `connect-src` = self + gateway + Keycloak, no frames, no objects
  - `X-Content-Type-Options: nosniff`
  - `X-Frame-Options: DENY`
  - `Referrer-Policy: no-referrer`
  - `Permissions-Policy`
  - COOP
- **Version:** `server_tokens off`, so the `Server` header doesn't reveal the nginx version.

### Development without installing Node

The host doesn't need Node: npm runs in the same `node:24-alpine` image, with this folder
bind-mounted. `node_modules/` gets Linux arm64 binaries (esbuild-free Vite 8 / rolldown, the
native TypeScript 7 compiler). Your IDE can still resolve the types; only run the tools through
Docker.

```bash
docker run --rm -v "$PWD":/app -w /app node:24-alpine npm ci          # install (from dashboard-ui/)
```

```bash
docker run --rm -v "$PWD":/app -w /app node:24-alpine npm test        # Vitest, ~5 s
```

```bash
docker run --rm -v "$PWD":/app -w /app node:24-alpine npm run build   # typecheck + production build
```

```bash
docker run --rm -it -p 3000:3000 -v "$PWD":/app -w /app node:24-alpine npm run dev   # Vite dev server
```

Stop the compose `dashboard-ui` container first: the dev server needs port 3000, which is the only
redirect URI registered in Keycloak. It uses `public/config.js` (gateway on `localhost:8080`).

## Tests

**56 Vitest tests** (`test/`) in jsdom, against an MSW stub of the gateway. The stub serves **real
responses captured from the live gateway** (`test/fixtures/*.json`), and any request without a handler
fails the test (`onUnhandledFrame: 'error'`).

| File | Covers |
|---|---|
| `client.test.ts` | Bearer + `ui-` correlation id on every call; anonymous `/config`; 401 → refresh → retry with the same id; still 401 → re-login; no refresh → no second request; problem → `ApiError` with service and correlation id; non-JSON error; unreachable gateway (status 0); JSON vs raw bodies and content types; query building |
| `oidc.test.ts` | Auth Code against the `/config` client, endpoints from `/config` (no discovery), `/callback` and `/signed-out` redirects, in-memory token store, no iframe; open-redirect-safe return paths; runtime config |
| `components.test.tsx` | `ProblemView` (service, correlation id, 403 role, parameter errors, retry); `ConfirmDialog` (required reason, trimmed text, stays open with the problem on failure, Escape / Cancel) |
| `views.test.tsx` | Every view through the real routes: steward vs viewer (disabled buttons with reasons), DEGRADED banner and disabled actions, 401 → re-login, gateway problems with correlation ids, search and filter query strings, unmerge / reject bodies (no `reviewer`), run-matching result, rule toggle + 409 in the dialog, coverage meters, FHIR validator messages + lineage, JSON validation, lineage trace, Bronze payload formatting |

There is no end-to-end browser suite: Playwright's image is about 2 GB and a second toolchain. Instead:

- `scripts/smoke-test.sh` **section 17** checks the deployed container: SPA fallback, caching, CSP and
  headers, `config.js`, `/healthz`, Keycloak accepting the dashboard's PKCE request (and refusing it
  without PKCE or with a foreign redirect), and CORS for the token endpoint and the gateway.
- [`CHECKLIST.md`](CHECKLIST.md) is a 12-step manual click-through as dana and vic.

## Layout

```
dashboard-ui/
├── Dockerfile · docker/            # node build (tests gate it) → nginx; runtime config + CSP at start
├── index.html · public/            # loads /config.js before the bundle
├── src/
│   ├── main.tsx                    # bootstrap: /config → OIDC session → /callback or Keycloak → app
│   ├── runtime.ts                  # window.__CLINICALHARMONY__.gatewayUrl
│   ├── auth/oidc.ts                # UserManager settings, OidcSession (login/refresh/logout)
│   ├── api/client.ts · types.ts    # fetch wrapper: token, correlation id, 401 retry, problems
│   ├── app/                        # routes, layout (nav, health pill, DEGRADED banner, user), context hooks
│   ├── components/                 # ProblemView, ConfirmDialog, StewardButton, tables/badges/bars
│   └── views/                      # overview · patients (timeline + PMI) · ontology · validation · fhir · lineage
└── test/                           # Vitest + MSW gateway stub + real fixtures
```

## Gotchas found while building

- **MSW 3 renamed `onUnhandledRequest` to `onUnhandledFrame`.** The old option is silently ignored,
  so unhandled requests stop failing tests. `test/setup.ts` uses the new name; a probe test confirmed
  it.
- **React StrictMode runs effects twice in development**, and a `/callback` code can be exchanged only
  once. The bootstrap is memoized per attempt (`startBoot` in `main.tsx`).
- **No silent-renew iframe:** the CSP's `frame-src 'none'` rules it out. `OidcSession.refresh()` only
  uses the refresh token, and falls back to a login redirect.
