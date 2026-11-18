// A stub api-gateway for component tests, serving REAL responses captured from the live gateway
// (test/fixtures/*.json). Handlers record every request so tests can assert on method, body,
// headers (Authorization, X-Correlation-Id) and query strings.
import { http, HttpResponse, type JsonBodyType } from 'msw';
import { setupServer } from 'msw/node';
import bronzeMessage from './fixtures/bronze-message.json';
import bronzeMessages from './fixtures/bronze-messages.json';
import config from './fixtures/config.json';
import fhirResourceCondition from './fixtures/fhir-resource-condition.json';
import fhirResources from './fixtures/fhir-resources.json';
import fhirSourceSystems from './fixtures/fhir-source-systems.json';
import fhirTypes from './fixtures/fhir-types.json';
import fhirValidate from './fixtures/fhir-validate.json';
import health from './fixtures/health.json';
import lineageFhir from './fixtures/lineage-fhir.json';
import lineageSilver from './fixtures/lineage-silver.json';
import lineageSummary from './fixtures/lineage-summary.json';
import meDana from './fixtures/me-dana.json';
import ontologyCoverage from './fixtures/ontology-coverage.json';
import patientMerged from './fixtures/patient-merged.json';
import patients from './fixtures/patients.json';
import pmiCandidate from './fixtures/pmi-candidate.json';
import pmiCandidates from './fixtures/pmi-candidates.json';
import pmiDecided from './fixtures/pmi-decided.json';
import pmiRuns from './fixtures/pmi-runs.json';
import silverPatient from './fixtures/silver-patient.json';
import silverPatients from './fixtures/silver-patients.json';
import silverSourceSystems from './fixtures/silver-source-systems.json';
import summary from './fixtures/summary.json';
import timeline from './fixtures/timeline.json';
import validationFindings from './fixtures/validation-findings.json';
import validationRules from './fixtures/validation-rules.json';
import validationSummary from './fixtures/validation-summary.json';

export const GATEWAY = 'http://gateway.test';
export const API = `${GATEWAY}/api/v1`;

export const fixtures = {
  bronzeMessage, bronzeMessages, config, fhirResourceCondition, fhirResources, fhirSourceSystems, fhirTypes, fhirValidate, health,
  lineageFhir, lineageSilver, lineageSummary, meDana, ontologyCoverage, patientMerged, patients, pmiCandidate, pmiCandidates,
  pmiDecided, pmiRuns, silverPatient, silverPatients, silverSourceSystems, summary, timeline, validationFindings, validationRules, validationSummary,
};

export const meVic = {
  ...meDana,
  username: 'vic',
  name: 'Vic Viewer',
  roles: ['dashboard-viewer'],
  canAct: false,
};

export interface Recorded {
  method: string;
  path: string;
  query: URLSearchParams;
  headers: Headers;
  body: string;
}

export const requests: Recorded[] = [];

/** Requests seen so far whose path matches, e.g. calls('/pmi/match/run'). */
export function calls(pathSuffix: string, method?: string): Recorded[] {
  return requests.filter((r) => r.path.endsWith(pathSuffix) && (!method || r.method === method));
}

let correlationCounter = 0;

/** JSON reply that echoes the correlation id like the gateway does. */
export function reply(request: Request, body: JsonBodyType, init: { status?: number; contentType?: string } = {}) {
  const id = request.headers.get('X-Correlation-Id') ?? `gw-${++correlationCounter}`;
  return HttpResponse.json(body, {
    status: init.status ?? 200,
    headers: { 'X-Correlation-Id': id, ...(init.contentType ? { 'Content-Type': init.contentType } : {}) },
  });
}

/** An RFC 7807 problem exactly as the gateway shapes it. */
export function problem(request: Request, status: number, slug: string, title: string, detail: string, extra: Record<string, unknown> = {}) {
  const url = new URL(request.url);
  return reply(
    request,
    {
      type: `urn:clinicalharmony:problem:${slug}`,
      title,
      status,
      detail,
      instance: url.pathname,
      correlationId: request.headers.get('X-Correlation-Id') ?? 'gw',
      ...extra,
    },
    { status, contentType: 'application/problem+json' },
  );
}

const get = (path: string, body: JsonBodyType) => http.get(`${API}${path}`, ({ request }) => reply(request, body));

export const defaultHandlers = [
  get('/config', fixtures.config),
  get('/me', fixtures.meDana),
  get('/summary', fixtures.summary),
  get('/services/health', fixtures.health),
  get('/patients', fixtures.patients),
  get('/patients/:id', fixtures.patientMerged),
  get('/patients/:id/timeline', fixtures.timeline),
  // status=DECIDED feeds the "Recently decided" card; every other status gets the queue fixture
  http.get(`${API}/pmi/candidates`, ({ request }) =>
    reply(request, new URL(request.url).searchParams.get('status') === 'DECIDED' ? fixtures.pmiDecided : fixtures.pmiCandidates),
  ),
  get('/pmi/candidates/:id', fixtures.pmiCandidate),
  get('/pmi/runs', fixtures.pmiRuns),
  get('/ontology/coverage', fixtures.ontologyCoverage),
  get('/validation/summary', fixtures.validationSummary),
  get('/validation/findings', fixtures.validationFindings),
  get('/validation/rules', fixtures.validationRules),
  get('/fhir/resource-types', fixtures.fhirTypes),
  get('/fhir/resources', fixtures.fhirResources),
  get('/fhir/source-systems', fixtures.fhirSourceSystems),
  get('/fhir/resources/:type/:id', fixtures.fhirResourceCondition),
  // a Silver-patient trace gets the Silver lineage fixture; every other trace the FHIR-resource one
  http.get(`${API}/lineage`, ({ request }) =>
    reply(request, new URL(request.url).searchParams.has('silverPatientId') ? fixtures.lineageSilver : fixtures.lineageFhir),
  ),
  get('/silver/patients', fixtures.silverPatients),
  get('/silver/patients/:id', fixtures.silverPatient),
  get('/silver/source-systems', fixtures.silverSourceSystems),
  http.get(`${API}/silver/rows/:table/:id`, ({ request, params }) =>
    reply(request, { table: params.table, id: Number(params.id), silverPatientId: fixtures.silverPatient.summary.id }),
  ),
  get('/lineage/summary', fixtures.lineageSummary),
  get('/bronze/messages', fixtures.bronzeMessages),
  get('/bronze/messages/:id', fixtures.bronzeMessage),
];

export const server = setupServer(...defaultHandlers);

server.events.on('request:start', async ({ request }) => {
  const url = new URL(request.url);
  requests.push({
    method: request.method,
    path: url.pathname,
    query: url.searchParams,
    headers: request.headers,
    body: request.method === 'GET' ? '' : await request.clone().text(),
  });
});

export function resetRequests() {
  requests.length = 0;
}
