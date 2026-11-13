# api-gateway

**Phase 7.** The single REST entry point for the dashboard (port 8080), sitting in front of
ingestion (8081), ontology (8082), rules-engine (8083), fhir-compliance (8084) and patient-index
(8085). Package `com.clinicalharmony.gateway`.

It's a thin **backend-for-frontend**, not a routing proxy. Most of what the five dashboard views need
had no read endpoint anywhere: validation findings, lineage, Bronze messages, a patient's clinical
timeline, ontology coverage, and Gold FHIR beyond the five CMS-0057 types. So the gateway works in
two halves:

| | How | Why |
|---|---|---|
| **Reads** (`GET`) | SQL projections over a **SELECT-only Postgres role** (`clinicalharmony_gateway_ro`, migration 22, sessions read-only by default) | Dashboard-shaped data in one query, with no change to the five services. patient-index-service already reads Silver and Gold this way. |
| **Actions** | Proxied over HTTP to the service that owns them, using the gateway's **own** Keycloak client | The gateway never writes. GoldWriter stays the only Gold writer, the PMI still decides merges, and rules-engine / fhir-compliance own their rule toggles. |

Because reads come from the database, the views keep working while a service is down. Only that
service's actions fail, with a 503 naming it.

## Auth

```
browser ──Auth Code + PKCE──▶ Keycloak (client clinicalharmony-dashboard, public)
   │  user access token: aud clinicalharmony-gateway, roles [...], preferred_username
   ▼
api-gateway ── validates the USER token, checks its role
   │  client_credentials (client api-gateway, scope clinicalharmony/pipeline.write)
   ▼
fhir-compliance-service · patient-index-service   (ingestion / ontology / rules-engine: no auth yet)
```

- The user's token is **never relayed**. The services only accept `system/` and `pipeline.write`
  service tokens, so the user's role authorizes the action here and the gateway's own client
  authorizes it downstream (the same pattern patient-index-service uses toward GoldWriter).
- A service token (`aud clinicalharmony-fhir`) is **not** accepted by the gateway (wrong
  audience → 401), and a user token isn't accepted by the services.
- PMI decisions record the signed-in user as `reviewer`, taken from the token's
  `preferred_username`. A `reviewer` in the request body is ignored, so the UI can't act as
  someone else.

| Who | What |
|---|---|
| anyone | `/actuator/health`, `GET /api/v1/config` (what the SPA needs to start its PKCE login) |
| `dashboard-viewer` or `data-steward` | every other `GET /api/v1/**`, plus the side-effect-free `POST /api/v1/fhir/validate` and `/fhir/convert/ccda` |
| `data-steward` | `POST /api/v1/pmi/**` (match runs, accept / reject, unmerge), `PUT /api/v1/validation/rules/{code}/toggle` |

The realm (`keycloak/realm-clinicalharmony.json`) has two DEV-ONLY users: **`dana`** (viewer + steward,
password `dana-dev-password`) and **`vic`** (viewer only, `vic-dev-password`). Postman and the smoke
test log in through **`clinicalharmony-dashboard-dev`**, a DEV-ONLY public client with direct grants
that issues the same token shape. Never enable that client anywhere real.

## Endpoints (`/api/v1`)

| View | Endpoint | Source |
|---|---|---|
| Shell | `GET /config` (public) · `GET /me` · `GET /summary` (counts per layer) · `GET /services/health` | config · token · DB · each service's `/actuator/health` |
| Patient Timeline | `GET /patients?q=&dob=&status=&includeMerged=&page=&size=` | DB |
| | `GET /patients/{masterId}` (source records with MRNs, merge history, FHIR counts, pending reviews) | DB |
| | `GET /patients/{masterId}/timeline?from=&to=&types=CONDITION,OBSERVATION,MEDICATION` | DB |
| | `GET /pmi/candidates?status=&limit=` · `GET /pmi/candidates/{id}` · `GET /pmi/runs` | patient-index |
| | `POST /pmi/match/run` · `POST /pmi/candidates/{id}/accept\|reject` `{"note"}` · `POST /pmi/merges/{id}/unmerge` `{"reason"}` | patient-index (steward) |
| Ontology Coverage | `GET /ontology/coverage` | DB |
| | `GET /ontology/codes/{icd10\|loinc}/{code}` · `GET /ontology/map?source=&code=&target=` | ontology |
| Validation Report | `GET /validation/summary` · `GET /validation/findings?ruleCode=&severity=&entityType=&category=&patientId=&resolved=&page=&size=` · `GET /validation/rules[/{code}]` | DB |
| | `PUT /validation/rules/{code}/toggle` → rules-engine or fhir-compliance, whichever evaluates the rule (409 for rules no service can toggle) | owner (steward) |
| FHIR Viewer | `GET /fhir/resource-types` · `GET /fhir/resources?type=&masterPatientId=&compliant=&q=&page=&size=` · `GET /fhir/resources/{type}/{fhirId}` (JSON + validator notes + lineage) | DB |
| | `POST /fhir/validate` (body forwarded verbatim) · `POST /fhir/convert/ccda` | fhir-compliance |
| Data Lineage | `GET /lineage?fhirResourceId=` **or** `?masterPatientId=` · `GET /lineage/summary` · `GET /bronze/messages?status=&messageType=&sourceSystem=&page=&size=` · `GET /bronze/messages/{id}` | DB |

What the read models add:

- **Timeline.** One list from every Silver record linked to a master, i.e. every source feed. Each
  event says whether its code is in the reference terminology (`codeKnown`) and which Gold FHIR
  resource it became (through GoldWriter's `FHIR_MAP` lineage rows). Conditions and medications
  are DATEs, reported at UTC midnight with `datePrecision: "day"`.
- **Coverage.** Per terminology: how much of what Silver *uses* resolves in the reference set, the
  top unknown codes, open `CODE-00x` findings, and SNOMED CT ↔ ICD-10 mapping coverage.
- **Rule catalog.** All `ontology.clinical_rules`, each tagged with the service that evaluates it
  and whether the gateway can toggle it.

Lists are paged (`page` is 0-based, `size` 1–200, default 25) and come back as
`{items, page, size, total, totalPages}`. Proxied calls return the service's JSON and 2xx status
unchanged.

## Cross-cutting behaviour

- **Correlation ids.** The gateway honors an incoming `X-Correlation-Id` (`[A-Za-z0-9._-]{1,64}`)
  or generates one. It stamps every log line, echoes the id on every response (including 401/403
  and CORS preflights) and in every error body, and forwards it on every downstream call.
  fhir-compliance-service and patient-index-service log under the same id. ingestion, ontology and
  rules-engine receive the header but don't read it yet; the gateway's own log line for each call
  carries the id.
- **Errors.** Every error is RFC 7807 `application/problem+json`, whatever shape the service used:
  `{type: "urn:clinicalharmony:problem:<slug>", title, status, detail, instance, correlationId}`,
  plus `service` and `downstreamStatus` when a downstream call failed, and `errors[]` for bad
  parameters.

  | Downstream outcome | Gateway answer |
  |---|---|
  | connection refused, unknown host, connect timeout | **503** `downstream-unavailable` |
  | no answer within the read timeout | **504** `downstream-timeout` |
  | 5xx or an unusable body | **502** `bad-gateway` |
  | 401/403 against the gateway's own credentials | **502**: it's a gateway configuration problem, not the user's, so it's never shown as a 401 |
  | its own 4xx (400/404/409/413/415/422) | **same status** `downstream-rejected`, with the service's message |
  | Keycloak down while getting the gateway's token | **503**, `service: "keycloak"` |
  | gateway database down | **503** `database-unavailable` |

- **Timeouts.** Connect 2 s everywhere. Read timeouts: 5 s for ingestion and ontology, 10 s for
  rules-engine (it calls ontology), 30 s for fhir-compliance (HL7 validator, C-CDA) and
  patient-index (matching runs). Health probes use their own 2 s budget.
- **Retries.** One retry after a 401 from a secured service (the cached token is dropped first; this
  covers Keycloak being recreated with new keys). For `GET`s only, one retry after a connection
  failure. `POST` and `PUT` are never replayed, and there's no circuit breaker:
  `/services/health` shows what's down.
- **CORS.** The allowlist comes from `CORS_ALLOWED_ORIGINS` (default `http://localhost:3000`).
  Methods are GET/POST/PUT; allowed headers are `Authorization`, `Content-Type` and
  `X-Correlation-Id`; `X-Correlation-Id` is exposed to the browser. There are no credentials
  (Bearer tokens only), and other origins' preflights get 403.

## Configuration

Same `SPRING_PROFILES_ACTIVE` (`local` / `docker`) + `DB_*` pattern as the other services, but
`DB_USER` / `DB_PASSWORD` default to the read-only role.

| Variable | Default | Purpose |
|---|---|---|
| `DB_USER` / `DB_PASSWORD` | `clinicalharmony_gateway_ro` / `gateway-ro-dev-password` | Migration 22's SELECT-only role (DEV-ONLY password) |
| `OAUTH_ISSUER_URI` | `http://localhost:8180/realms/clinicalharmony` | Required `iss` of user tokens; also served by `/config` |
| `OAUTH_JWK_SET_URI` | `…:8180/…/certs` (compose: `keycloak:8080`) | Signing keys |
| `DASHBOARD_CLIENT_ID` | `clinicalharmony-dashboard` | Served by `/config` |
| `OAUTH_TOKEN_URI` | `…:8180/…/token` (compose: `keycloak:8080`) | The gateway's own client_credentials token |
| `GATEWAY_CLIENT_ID` / `GATEWAY_CLIENT_SECRET` | `api-gateway` / `api-gateway-secret` | Its Keycloak client (DEV-ONLY secret, in the realm JSON) |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000` | Comma-separated |
| `INGESTION_BASE_URL` … `PATIENT_INDEX_BASE_URL` | `http://localhost:8081` … `:8085` | Downstream services (compose: service names) |

## Run and test

```bash
mvn test                                   # 42 tests; the integration test is skipped without Docker
docker compose up -d --build api-gateway   # from the repo root
```

Locally, `mvn package` needs `maven-jar-plugin`'s dependencies, which this machine's `~/.m2`
doesn't have. The Docker build downloads them inside the build container, like every other service.

- **Unit tests** (`DownstreamClientTest`, `DownstreamErrorsTest`, `RuleOwnershipTest`) run real HTTP
  against a JDK `HttpServer` stub. They cover the gateway's token and the correlation id on every
  call, 401 refresh + retry, 4xx pass-through, 5xx → 502, slow → 504, down → 503 (and Keycloak down),
  and never replaying a POST.
- **Web tests** (`GatewayWebTest`, `@WebMvcTest`) use the real security chain with self-signed user
  JWTs through production's validators. They cover the access matrix, wrong audience, reviewer from
  the token rather than the body, toggle routing and 409, problem shapes for every error kind,
  parameter validation and CORS.
- **Integration test** (`ApiGatewayIntegrationTest`) runs Testcontainers Postgres initialized from
  `docs/db-init`, **logged in as the read-only role** like in compose, with stubbed Keycloak and
  services and ontology-service on a dead port. It checks every read model over real SQL (timeline
  joins, coverage, findings, FHIR + lineage back to Bronze), that the role can't write, relayed
  actions, and 503 / 504 / DEGRADED health.
- **Smoke test** section 16 (`scripts/smoke-test.sh`, 56 assertions) runs against the live stack
  with real Keycloak user tokens. It cross-checks every read model against SQL and has dana reject a
  fresh twin pair through the gateway (recorded reviewer = `dana`, even though the body says
  otherwise).
- **Postman**: `postman/phase7-api-gateway.postman_collection.json`, 12 numbered happy-path requests
  through the five views plus one steward action.
