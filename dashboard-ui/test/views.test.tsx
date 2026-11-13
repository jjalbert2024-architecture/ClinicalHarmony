import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http } from 'msw';
import { describe, expect, it } from 'vitest';
import { displayPayload } from '../src/views/lineage/BronzeMessagePage';
import { API, calls, fixtures, meVic, problem, reply, server } from './gateway';
import { renderApp } from './render';

const asVic = () => server.use(http.get(`${API}/me`, ({ request }) => reply(request, meVic)));
const degraded = (service: string) =>
  server.use(
    http.get(`${API}/services/health`, ({ request }) =>
      reply(request, {
        ...fixtures.health,
        status: 'DEGRADED',
        degraded: [service],
        services: fixtures.health.services.map((s) => (s.service === service ? { ...s, status: 'DOWN', httpStatus: null, detail: 'Connection refused' } : s)),
      }),
    ),
  );

describe('shell', () => {
  it('shows the layer summary, who is signed in and service health', async () => {
    renderApp('/');
    expect(await screen.findByText('Medallion layers')).toBeInTheDocument();
    expect(await screen.findByText('Signed in as dana · data steward')).toBeInTheDocument();
    expect(screen.getByText('Data steward')).toBeInTheDocument();
    expect(await screen.findByText('All services up')).toBeInTheDocument();
    const services = await screen.findByRole('table');
    expect(within(services).getByText('patient-index-service')).toBeInTheDocument();
  });

  it('labels a viewer as such', async () => {
    asVic();
    renderApp('/');
    expect(await screen.findByText('Signed in as vic · viewer')).toBeInTheDocument();
  });

  it('shows a DEGRADED banner naming the service that is down', async () => {
    degraded('patient-index-service');
    renderApp('/');
    expect(await screen.findByText('Degraded (1)')).toBeInTheDocument();
    expect(await screen.findByText(/patient-index-service is not responding/)).toBeInTheDocument();
  });

  it('signs out through the session', async () => {
    const { session } = renderApp('/');
    await userEvent.click(await screen.findByRole('button', { name: 'Sign out' }));
    expect(session.logout).toHaveBeenCalled();
  });

  it('shows the gateway problem with its correlation id when a view fails', async () => {
    server.use(http.get(`${API}/summary`, ({ request }) => problem(request, 503, 'database-unavailable', 'Service Unavailable', 'gateway database is down')));
    renderApp('/');
    const alert = await screen.findByText(/gateway database is down/);
    const box = alert.closest('[role="alert"]') as HTMLElement;
    expect(within(box).getByTestId('correlation-id').textContent).toMatch(/^ui-[0-9a-f]{12}$/);
  });

  it('re-logs in when the gateway keeps answering 401', async () => {
    server.use(http.get(`${API}/summary`, ({ request }) => problem(request, 401, 'unauthorized', 'Unauthorized', 'expired')));
    const { session } = renderApp('/');
    await waitFor(() => expect(session.onUnauthenticated).toHaveBeenCalled());
    expect(session.refresh).toHaveBeenCalled();
  });
});

describe('Patient Timeline', () => {
  it('searches with the form values as query parameters', async () => {
    renderApp('/patients');
    expect(await screen.findByRole('link', { name: '#1' })).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText('Name or MRN'), 'garcia');
    await userEvent.selectOptions(screen.getByLabelText('Match status'), 'MATCHED');
    await userEvent.click(screen.getByRole('button', { name: 'Search' }));
    await waitFor(() => expect(calls('/patients').some((r) => r.query.get('q') === 'garcia' && r.query.get('status') === 'MATCHED')).toBe(true));
  });

  it('shows source records, merge history and the timeline with code-known badges', async () => {
    renderApp('/patients/1');
    expect(await screen.findByText('Source records (2)')).toBeInTheDocument();
    expect(screen.getByText('Merge history')).toBeInTheDocument();
    expect(await screen.findByText('Clinical timeline')).toBeInTheDocument();
    const events = await screen.findAllByRole('listitem');
    expect(events.length).toBeGreaterThan(0);
    expect(screen.getAllByText(/known code|not in reference set/).length).toBeGreaterThan(0);
  });

  it('filters the timeline by event type', async () => {
    renderApp('/patients/1');
    await userEvent.click(await screen.findByLabelText('Medications'));
    await waitFor(() =>
      expect(calls('/patients/1/timeline').some((r) => r.query.get('types') === 'CONDITION,OBSERVATION')).toBe(true),
    );
  });

  it('lets a steward unmerge only with a reason, and sends no reviewer', async () => {
    server.use(http.post(`${API}/pmi/merges/:id/unmerge`, ({ request }) => reply(request, { unmerge: {}, exclusionsAdded: 1 })));
    renderApp('/patients/1');
    await userEvent.click(await screen.findByRole('button', { name: 'Unmerge…' }));
    const dialog = screen.getByRole('dialog');
    const confirm = within(dialog).getByRole('button', { name: 'Unmerge' });
    expect(confirm).toBeDisabled();
    await userEvent.type(within(dialog).getByLabelText(/Reason/), 'different people');
    await userEvent.click(confirm);
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    const [post] = calls('/unmerge', 'POST');
    expect(post!.path).toBe('/api/v1/pmi/merges/1/unmerge');
    expect(JSON.parse(post!.body)).toEqual({ reason: 'different people' });
  });

  it('gives a viewer a disabled unmerge button that says why', async () => {
    asVic();
    renderApp('/patients/1');
    const button = await screen.findByRole('button', { name: 'Unmerge…' });
    await waitFor(() => expect(button).toBeDisabled());
    expect(button).toHaveAttribute('title', 'Requires the data-steward role');
  });
});

describe('PMI review', () => {
  it('runs matching after confirmation and reports the result', async () => {
    server.use(
      http.post(`${API}/pmi/match/run`, ({ request }) =>
        reply(request, {
          run: { ...fixtures.pmiRuns[0], id: 99, pairsCompared: 3, autoMerges: 1, queuedForReview: 1, confirmedSingletons: 2 },
          autoMerges: [],
          queuedCandidateIds: [],
          confirmedMasterIds: [],
          skipped: [],
          silverPatientsWithoutMaster: 0,
        }),
      ),
    );
    renderApp('/pmi');
    const run = await screen.findByRole('button', { name: 'Run matching…' });
    await waitFor(() => expect(run).toBeEnabled());
    await userEvent.click(run);
    expect(calls('/pmi/match/run', 'POST')).toHaveLength(0); // nothing happens before confirming
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Run matching' }));
    expect(await screen.findByText(/Run #99/)).toHaveTextContent('3 pair(s) compared, 1 auto-merged, 1 queued for review');
    expect(calls('/pmi/match/run', 'POST')).toHaveLength(1);
  });

  it('disables actions while patient-index-service is down', async () => {
    degraded('patient-index-service');
    renderApp('/pmi');
    const run = await screen.findByRole('button', { name: 'Run matching…' });
    await waitFor(() => expect(run).toHaveAttribute('title', 'patient-index-service is down'));
    expect(run).toBeDisabled();
  });

  it('shows a candidate side by side and requires a note to reject', async () => {
    const pending = { ...fixtures.pmiCandidate, candidate: { ...fixtures.pmiCandidate.candidate, status: 'PENDING', reviewedBy: null } };
    server.use(
      http.get(`${API}/pmi/candidates/:id`, ({ request }) => reply(request, pending)),
      http.post(`${API}/pmi/candidates/:id/reject`, ({ request }) => reply(request, { candidate: pending.candidate, confirmedMasterIds: [] })),
    );
    renderApp(`/pmi/candidates/${pending.candidate.id}`);
    expect(await screen.findByText('Side by side')).toBeInTheDocument();
    expect(screen.getByText('How the score was reached')).toBeInTheDocument();
    await userEvent.click(await screen.findByRole('button', { name: 'Not the same person…' }));
    const dialog = screen.getByRole('dialog');
    const confirm = within(dialog).getByRole('button', { name: 'Not the same person' });
    expect(confirm).toBeDisabled();
    await userEvent.type(within(dialog).getByLabelText(/Note/), 'twins');
    await userEvent.click(confirm);
    await waitFor(() => expect(calls('/reject', 'POST')).toHaveLength(1));
    const body = JSON.parse(calls('/reject', 'POST')[0]!.body);
    expect(body).toEqual({ note: 'twins' });
    expect(body).not.toHaveProperty('reviewer');
  });
});

describe('Validation Report', () => {
  it('filters findings by clicking a rule bucket', async () => {
    renderApp('/validation');
    const bucket = await screen.findByRole('button', { name: /^CODE-001/ });
    await userEvent.click(bucket);
    await waitFor(() => expect(calls('/validation/findings').some((r) => r.query.get('ruleCode') === 'CODE-001')).toBe(true));
    expect(screen.getByRole('button', { name: 'ruleCode: CODE-001 ×' })).toBeInTheDocument();
  });

  it('toggles a rule after confirmation and shows non-toggleable rules as such', async () => {
    server.use(http.put(`${API}/validation/rules/:code/toggle`, ({ request }) => reply(request, { ...fixtures.validationRules[0], active: false })));
    renderApp('/validation/rules');
    expect((await screen.findAllByText('not toggleable')).length).toBeGreaterThan(0);
    const row = (await screen.findByText('GENDER_PREGNANCY_CONFLICT')).closest('tr') as HTMLElement;
    const button = within(row).getByRole('button', { name: 'Turn off…' });
    await waitFor(() => expect(button).toBeEnabled());
    await userEvent.click(button);
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Turn off' }));
    await waitFor(() => expect(calls('/validation/rules/GENDER_PREGNANCY_CONFLICT/toggle', 'PUT')).toHaveLength(1));
  });

  it('shows a 409 from the gateway inside the dialog', async () => {
    server.use(
      http.put(`${API}/validation/rules/:code/toggle`, ({ request }) =>
        problem(request, 409, 'rule-not-toggleable', 'Conflict', "Rule can't be toggled through the gateway"),
      ),
    );
    renderApp('/validation/rules');
    const row = (await screen.findByText('GENDER_PREGNANCY_CONFLICT')).closest('tr') as HTMLElement;
    const button = within(row).getByRole('button', { name: 'Turn off…' });
    await waitFor(() => expect(button).toBeEnabled());
    await userEvent.click(button);
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Turn off' }));
    expect(await within(screen.getByRole('dialog')).findByText(/can't be toggled/)).toBeInTheDocument();
  });
});

describe('Ontology Coverage', () => {
  it('shows coverage per terminology with links to its open findings', async () => {
    renderApp('/ontology');
    expect(await screen.findByRole('heading', { name: 'ICD-10' })).toBeInTheDocument();
    expect(screen.getByText('75%')).toBeInTheDocument();
    expect(screen.getByRole('meter', { name: 'ICD-10 coverage' })).toHaveAttribute('aria-valuenow', '3');
  });

  it('looks up a code and shows a 404 as the gateway problem', async () => {
    server.use(
      http.get(`${API}/ontology/codes/:system/:code`, ({ request }) =>
        problem(request, 404, 'downstream-rejected', 'Not Found', 'Code ZZZ not found', { service: 'ontology-service', downstreamStatus: 404 }),
      ),
    );
    renderApp('/ontology');
    await userEvent.type(await screen.findByLabelText('Code'), 'ZZZ');
    await userEvent.click(screen.getByRole('button', { name: 'Look up' }));
    expect(await screen.findByText('Code ZZZ not found')).toBeInTheDocument();
  });
});

describe('FHIR Viewer', () => {
  it('shows a resource with validator messages, lineage and JSON', async () => {
    renderApp('/fhir/Condition/silver-condition-5');
    expect(await screen.findByRole('heading', { name: 'Condition/silver-condition-5' })).toBeInTheDocument();
    expect(screen.getByText('Not US Core compliant')).toBeInTheDocument();
    expect(screen.getByText('Condition.code')).toBeInTheDocument();
    expect(screen.getByText('FHIR_MAP')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Copy JSON' })).toBeInTheDocument();
  });

  it('validates pasted JSON (allowed for viewers)', async () => {
    asVic();
    server.use(http.post(`${API}/fhir/validate`, ({ request }) => reply(request, fixtures.fhirValidate)));
    renderApp('/fhir/tools');
    await userEvent.click(await screen.findByRole('button', { name: 'Validate' }));
    expect(await screen.findByText('Not compliant')).toBeInTheDocument();
    expect(calls('/fhir/validate', 'POST')[0]!.headers.get('Content-Type')).toBe('application/fhir+json');
  });

  it('refuses to send invalid JSON', async () => {
    renderApp('/fhir/tools');
    const box = await screen.findByLabelText('FHIR resource (JSON)');
    await userEvent.clear(box);
    await userEvent.type(box, 'not json');
    await userEvent.click(screen.getByRole('button', { name: 'Validate' }));
    expect(await screen.findByText(/Not valid JSON/)).toBeInTheDocument();
    expect(calls('/fhir/validate')).toHaveLength(0);
  });
});

describe('Data Lineage', () => {
  it('traces a FHIR resource from the query string', async () => {
    renderApp('/lineage?fhirResourceId=46');
    expect(await screen.findByText('gold.fhir_resources')).toBeInTheDocument();
    expect(calls('/lineage').some((r) => r.query.get('fhirResourceId') === '46')).toBe(true);
    expect(await screen.findByText('Transformations recorded')).toBeInTheDocument();
    expect(await screen.findByText('Bronze messages')).toBeInTheDocument();
  });

  it('shows a Bronze message with HL7 segments on separate lines', async () => {
    renderApp('/lineage/bronze/1');
    expect(await screen.findByRole('heading', { name: 'Bronze message #1' })).toBeInTheDocument();
    expect(displayPayload('HL7V2', 'MSH|a\rPID|b\r')).toBe('MSH|a\nPID|b\n');
    expect(displayPayload('FHIR_JSON', '{"a":1}')).toBe('{\n  "a": 1\n}');
  });
});
