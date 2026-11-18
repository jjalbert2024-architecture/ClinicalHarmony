import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http } from 'msw';
import { describe, expect, it, vi } from 'vitest';
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

  it('lists the last 10 decisions with who, when and note', async () => {
    renderApp('/pmi');
    const list = await screen.findByRole('list', { name: 'Recently decided' });
    const items = within(list).getAllByRole('listitem');
    expect(items).toHaveLength(fixtures.pmiDecided.length);
    const newest = fixtures.pmiDecided[0]!;
    expect(items[0]).toHaveTextContent(`#${newest.id}`);
    expect(items[0]).toHaveTextContent(newest.reviewedBy!);
    expect(items[0]).toHaveTextContent(newest.reviewNote!);
    const decided = calls('/pmi/candidates').find((r) => r.query.get('status') === 'DECIDED');
    expect(decided?.query.get('limit')).toBe('10');
  });

  it("opens a run's review candidates from its count and clears the run filter", async () => {
    const run = fixtures.pmiRuns.find((r) => r.queuedForReview > 0)!;
    renderApp('/pmi');
    await userEvent.click(await screen.findByRole('link', { name: `Review candidates of run #${run.id}` }));
    expect(await screen.findByRole('button', { name: `run #${run.id} ×` })).toBeInTheDocument();
    await waitFor(() =>
      expect(calls('/pmi/candidates').some((r) => r.query.get('status') === 'QUEUED' && r.query.get('runId') === String(run.id))).toBe(true),
    );
    expect(screen.getByText('Newest decision first.')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: `run #${run.id} ×` }));
    await waitFor(() => expect(screen.queryByRole('button', { name: `run #${run.id} ×` })).not.toBeInTheDocument());
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

  it('downloads the resource as a .json file', async () => {
    const blobs: Blob[] = [];
    const create = vi.fn((b: Blob) => {
      blobs.push(b);
      return 'blob:test';
    });
    const revoke = vi.fn();
    Object.assign(URL, { createObjectURL: create, revokeObjectURL: revoke });
    let downloaded = '';
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
      downloaded = this.download;
    });
    renderApp('/fhir/Condition/silver-condition-5');
    await userEvent.click(await screen.findByRole('button', { name: 'Download .json' }));
    expect(downloaded).toBe('Condition-silver-condition-5.json');
    expect(blobs[0]!.type).toBe('application/fhir+json');
    expect(JSON.parse(await blobs[0]!.text())).toEqual(fixtures.fhirResourceCondition.resource);
    expect(revoke).toHaveBeenCalledWith('blob:test');
    click.mockRestore();
  });

  it('lists newest first by default and sorts by clicking column headers', async () => {
    const before = calls('/fhir/resources').length;
    renderApp('/fhir');
    const updated = await screen.findByRole('button', { name: /^Updated/ });
    expect(updated.closest('th')).toHaveAttribute('aria-sort', 'descending');
    expect(calls('/fhir/resources')[before]!.query.get('sort')).toBe('updated');
    expect(calls('/fhir/resources')[before]!.query.get('dir')).toBe('desc');
    await userEvent.click(screen.getByRole('button', { name: /^Resource/ }));
    await waitFor(() => expect(calls('/fhir/resources').at(-1)!.query.get('sort')).toBe('resource'));
    expect(calls('/fhir/resources').at(-1)!.query.get('dir')).toBe('asc');
    await userEvent.click(screen.getByRole('button', { name: /^Resource/ }));
    await waitFor(() => expect(calls('/fhir/resources').at(-1)!.query.get('dir')).toBe('desc'));
    // Back to the default (served from the query cache, so no new request): newest first again.
    await userEvent.click(screen.getByRole('button', { name: /^Updated/ }));
    await waitFor(() =>
      expect(screen.getByRole('button', { name: /^Updated/ }).closest('th')).toHaveAttribute('aria-sort', 'descending'),
    );
  });

  it('shows each row\'s patient and origin, and filters by patient, source, origin and code', async () => {
    renderApp('/fhir');
    const first = fixtures.fhirResources.items[0]!;
    expect((await screen.findAllByText(`${first.sourceSystem} · ${first.sourcePatientId}`)).length).toBe(
      fixtures.fhirResources.items.length,
    );
    expect(screen.getAllByText('HL7 pipeline').length).toBeGreaterThan(0);

    const source = fixtures.fhirSourceSystems[0]!.sourceSystem;
    await screen.findByRole('option', { name: new RegExp(`^${source} \\(`) });
    await userEvent.selectOptions(screen.getByLabelText('Source system'), source);
    await waitFor(() => expect(calls('/fhir/resources').at(-1)!.query.get('sourceSystem')).toBe(source));
    await userEvent.selectOptions(screen.getByLabelText('Origin'), 'pipeline');
    await waitFor(() => expect(calls('/fhir/resources').at(-1)!.query.get('origin')).toBe('pipeline'));

    await userEvent.type(screen.getByLabelText('Patient'), ' nc700343 ');
    await userEvent.type(screen.getByLabelText('Code or name'), 'Amoxicillin');
    await userEvent.type(screen.getByLabelText('Resource id contains'), 'silver-allergy');
    await userEvent.click(screen.getByRole('button', { name: 'Search' }));
    await waitFor(() => expect(calls('/fhir/resources').at(-1)!.query.get('patient')).toBe('nc700343'));
    const last = calls('/fhir/resources').at(-1)!.query;
    expect([last.get('code'), last.get('q'), last.get('sourceSystem'), last.get('origin')])
      .toEqual(['Amoxicillin', 'silver-allergy', source, 'pipeline']);

    // Clear filters resets every box and dropdown (the unfiltered list comes back from the query cache).
    await userEvent.click(screen.getByRole('button', { name: 'Clear filters' }));
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Clear filters' })).not.toBeInTheDocument());
    expect(screen.getByLabelText('Patient')).toHaveValue('');
    expect(screen.getByLabelText('Code or name')).toHaveValue('');
    expect(screen.getByLabelText('Resource id contains')).toHaveValue('');
    expect(screen.getByLabelText('Source system')).toHaveValue('');
    expect(screen.getByLabelText('Origin')).toHaveValue('');
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
  it('lists recorded transformations in pipeline order, as the gateway returns them', async () => {
    renderApp('/lineage');
    const heading = await screen.findByRole('heading', { name: 'Transformations recorded' });
    const card = heading.closest('section')!;
    await waitFor(() => expect(within(card).getAllByRole('row').length).toBeGreaterThan(1));
    const steps = within(card)
      .getAllByRole('row')
      .slice(1)
      .map((row) => row.querySelector('td')!.textContent);
    expect(steps).toEqual(fixtures.lineageSummary.map((r) => r.step));
    expect(steps[0]).toBe('FHIR_MAP');
  });

  it('traces a FHIR resource from the query string', async () => {
    renderApp('/lineage?fhirResourceId=46');
    expect(await screen.findByText('gold.fhir_resources')).toBeInTheDocument();
    expect(calls('/lineage').some((r) => r.query.get('fhirResourceId') === '46')).toBe(true);
    expect(await screen.findByText('Transformations recorded')).toBeInTheDocument();
    expect(await screen.findByText('Bronze messages')).toBeInTheDocument();
  });

  it('lists Bronze messages with their payload format and HL7 message type', async () => {
    renderApp('/lineage');
    const card = (await screen.findByRole('heading', { name: 'Bronze messages' })).closest('section')!;
    await waitFor(() => expect(within(card).getAllByRole('row').length).toBeGreaterThan(1));
    const headers = within(card).getAllByRole('columnheader').map((h) => h.textContent);
    expect(headers).toEqual(['Message', 'Patient', 'Source', 'Format', 'HL7 type', 'Status', 'Bytes', 'Received']);
    const cells = (id: number) =>
      within(within(card).getByRole('link', { name: `#${id}` }).closest('tr')!)
        .getAllByRole('cell')
        .map((c) => c.textContent);
    expect(cells(1279).slice(3, 5)).toEqual(['HL7 v2', 'ADT^A08']);
    expect(cells(1275).slice(3, 5)).toEqual(['Claims CSV', '—']);
    expect(cells(1270).slice(3, 5)).toEqual(['HL7 v2', '—']); // quarantined: no usable MSH-9
    expect(cells(1270)[1]).toBe('—'); // …and no readable PID: no patient
    expect(within(card).getByLabelText('Payload format')).toHaveDisplayValue('Any payload format');
    expect(within(card).getByLabelText('HL7 message type')).toHaveDisplayValue('Any HL7 message type');
  });

  it('an HL7 message type implies HL7 v2, and is cleared and disabled for other formats', async () => {
    renderApp('/lineage');
    const format = await screen.findByLabelText('Payload format');
    const hl7 = screen.getByLabelText('HL7 message type');
    const lastQuery = () => calls('/bronze/messages').at(-1)!.query;

    await userEvent.selectOptions(hl7, 'ORU');
    await waitFor(() => expect(lastQuery().get('hl7Type')).toBe('ORU'));
    expect(lastQuery().get('messageType')).toBe('HL7V2');
    expect(format).toHaveDisplayValue('HL7 v2');
    expect(hl7).toHaveDisplayValue('ORU (lab results)');

    await userEvent.selectOptions(format, 'FHIR_JSON');
    await waitFor(() => expect(lastQuery().get('messageType')).toBe('FHIR_JSON'));
    expect(lastQuery().has('hl7Type')).toBe(false);
    expect(hl7).toBeDisabled();
    expect(hl7).toHaveDisplayValue('Any HL7 message type');

    await userEvent.selectOptions(format, '');
    expect(hl7).toBeEnabled();
  });

  it('reads the Bronze filters from the URL, ignoring an HL7 type next to a non-HL7 format', async () => {
    renderApp('/lineage?bronzeStatus=PROCESSED&messageType=CLAIMS_CSV&hl7Type=ORU');
    expect(await screen.findByLabelText('Payload format')).toHaveDisplayValue('Claims CSV');
    await waitFor(() => expect(calls('/bronze/messages').length).toBeGreaterThan(0));
    const query = calls('/bronze/messages').at(-1)!.query;
    expect([query.get('status'), query.get('messageType'), query.has('hl7Type')]).toEqual(['PROCESSED', 'CLAIMS_CSV', false]);
    expect(screen.getByLabelText('HL7 message type')).toBeDisabled();
  });

  it('links each Silver row a Bronze message produced to its Silver patient', async () => {
    server.use(
      http.get(`${API}/bronze/messages/:id`, ({ request }) =>
        reply(request, {
          ...fixtures.bronzeMessage,
          silverRows: [
            { table: 'silver.patients', id: 40, silverPatientId: 40 },
            { table: 'silver.allergies', id: 7, silverPatientId: 40 },
          ],
        }),
      ),
    );
    renderApp(`/lineage/bronze/${fixtures.bronzeMessage.summary.id}`);
    expect(await screen.findByRole('link', { name: 'silver.patients #40' })).toHaveAttribute('href', '/lineage/silver/40');
    expect(screen.getByRole('link', { name: 'silver.allergies #7' })).toHaveAttribute('href', '/lineage/silver/40#allergies-7');
    expect(screen.getByRole('link', { name: 'Trace this message into Gold' })).toHaveAttribute(
      'href',
      `/lineage?bronzeMessageId=${fixtures.bronzeMessage.summary.id}`,
    );
    expect(screen.queryByText(/silver\.silver\./)).not.toBeInTheDocument();
  });

  it('shows a Bronze message with HL7 segments on separate lines', async () => {
    renderApp('/lineage/bronze/1');
    expect(await screen.findByRole('heading', { name: 'Bronze message #1' })).toBeInTheDocument();
    expect(screen.getByText(/HL7V2 ADT\^A01 from CITY_HOSPITAL/)).toBeInTheDocument();
    expect(displayPayload('HL7V2', 'MSH|a\rPID|b\r')).toBe('MSH|a\nPID|b\n');
    expect(displayPayload('FHIR_JSON', '{"a":1}')).toBe('{\n  "a": 1\n}');
  });
});

describe('Data Lineage layer browser', () => {
  const hugo = fixtures.silverPatient.summary;

  it('shows the three layers as tabs and switches the list below', async () => {
    renderApp('/lineage');
    const tabs = await screen.findAllByRole('tab');
    expect(tabs.map((t) => t.getAttribute('aria-selected'))).toEqual(['true', 'false', 'false']);
    expect(await screen.findByRole('heading', { name: 'Bronze messages' })).toBeInTheDocument();

    await userEvent.click(screen.getByRole('tab', { name: /Silver/ }));
    expect(await screen.findByRole('heading', { name: 'Silver patients' })).toBeInTheDocument();
    const first = fixtures.silverPatients.items[0]!;
    expect(await screen.findByRole('link', { name: `#${first.id}` })).toHaveAttribute('href', `/lineage/silver/${first.id}`);
    expect(screen.getByText(`${first.sourceSystem} · ${first.sourcePatientId}`)).toBeInTheDocument();

    await userEvent.click(screen.getByRole('tab', { name: /Gold/ }));
    expect(await screen.findByRole('heading', { name: 'Gold master patients' })).toBeInTheDocument();
    await waitFor(() => expect(calls('/patients').length).toBeGreaterThan(0));
    expect(screen.getByRole('tab', { name: /Gold/ })).toHaveAttribute('aria-selected', 'true');
  });

  it('filters Silver patients by MRN, source and validation status', async () => {
    renderApp('/lineage?layer=silver');
    await screen.findByRole('heading', { name: 'Silver patients' });
    await userEvent.type(screen.getByLabelText('Silver patient search'), 'NC700343');
    await userEvent.click(screen.getByRole('button', { name: 'Search' }));
    await waitFor(() => expect(calls('/silver/patients').at(-1)!.query.get('q')).toBe('NC700343'));
    const source = fixtures.silverSourceSystems[0]!.sourceSystem;
    await screen.findByRole('option', { name: new RegExp(`^${source} \\(`) });
    await userEvent.selectOptions(screen.getByLabelText('Silver source system'), source);
    await waitFor(() => expect(calls('/silver/patients').at(-1)!.query.get('sourceSystem')).toBe(source));
    await userEvent.selectOptions(screen.getByLabelText('Validation status'), 'NOT_TRACKED');
    await waitFor(() => expect(calls('/silver/patients').at(-1)!.query.get('validationStatus')).toBe('NOT_TRACKED'));
  });

  it('traces from a Silver patient id, with every step ref a link', async () => {
    renderApp(`/lineage?layer=silver&silverPatientId=${hugo.id}`);
    expect(await screen.findByRole('link', { name: 'open Silver patient' })).toHaveAttribute('href', `/lineage/silver/${hugo.id}`);
    expect(calls('/lineage').at(-1)!.query.get('silverPatientId')).toBe(String(hugo.id));
    expect(screen.getByLabelText('Record kind')).toHaveDisplayValue('Silver patient id');
    const allergy = fixtures.lineageSilver.records.find((r) => r.sourceTable === 'allergies')!;
    expect(screen.getAllByRole('link', { name: `silver.allergies/${allergy.sourceId}` })[0]).toHaveAttribute(
      'href',
      `/lineage/silver/allergies/${allergy.sourceId}`,
    );
    const fhirTarget = allergy.details.target as string;
    expect(screen.getAllByRole('link', { name: `gold.fhir_resources/${allergy.targetId}` })[0]).toHaveAttribute(
      'href',
      `/fhir/${fhirTarget.split('/')[0]}/${fhirTarget.split('/')[1]}`,
    );
  });

  it('shows a Silver patient between its Bronze messages and its Gold master', async () => {
    renderApp(`/lineage/silver/${hugo.id}`);
    expect(await screen.findByRole('heading', { name: `Silver patient #${hugo.id} · ${hugo.firstName} ${hugo.lastName}` })).toBeInTheDocument();
    const path = screen.getByRole('navigation', { name: 'Where this record sits' });
    const bronzeId = fixtures.silverPatient.bronzeMessages[0]!.id;
    expect(within(path).getByRole('link', { name: `#${bronzeId}` })).toHaveAttribute('href', `/lineage/bronze/${bronzeId}`);
    const master = fixtures.silverPatient.gold.masterPatientId!;
    expect(within(path).getByRole('link', { name: `#${master}` })).toHaveAttribute('href', `/patients/${master}`);
    const allergy = fixtures.silverPatient.rows.find((r) => r.kind === 'ALLERGY')!;
    expect(screen.getByRole('link', { name: `AllergyIntolerance/${allergy.fhirId}` })).toHaveAttribute(
      'href',
      `/fhir/AllergyIntolerance/${allergy.fhirId}`,
    );
    expect(screen.getByRole('heading', { name: /Allergies/ })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Validation findings' })).toBeInTheDocument();
    const finding = fixtures.silverPatient.findings.find((f) => f.entityType === 'OBSERVATION' && f.entityId !== null)!;
    expect(screen.getAllByRole('link', { name: `OBSERVATION #${finding.entityId}` })[0]).toHaveAttribute(
      'href',
      `/lineage/silver/${hugo.id}#observations-${finding.entityId}`,
    );
  });

  it('opens a lineage ref like silver.allergies/165 on its Silver patient', async () => {
    renderApp('/lineage/silver/allergies/165');
    expect(await screen.findByRole('heading', { name: new RegExp(`^Silver patient #${hugo.id}`) })).toBeInTheDocument();
    expect(calls('/silver/rows/allergies/165')).toHaveLength(1);
  });

  it('traces a patient by MRN from the trace box (exact match, one per source)', async () => {
    server.use(
      http.get(`${API}/silver/patients`, ({ request }) =>
        reply(request, { items: [hugo], page: 0, size: 50, total: 1, totalPages: 1 }),
      ),
    );
    renderApp('/lineage');
    await userEvent.type(await screen.findByLabelText('Record id'), 'nc700343');
    await userEvent.click(screen.getByRole('button', { name: 'Trace' }));
    await waitFor(() => expect(calls('/lineage').at(-1)?.query.get('silverPatientId')).toBe(String(hugo.id)));
    expect(calls('/silver/patients').at(-1)!.query.get('q')).toBe('nc700343');
    expect(screen.getByRole('status')).toHaveTextContent(`${hugo.sourceSystem} · ${hugo.sourcePatientId}`);
    expect(screen.getByLabelText('Record id')).toHaveValue(String(hugo.id));

    // as a master patient: the same MRN traces that patient's master
    await userEvent.selectOptions(screen.getByLabelText('Record kind'), 'masterPatientId');
    await userEvent.clear(screen.getByLabelText('Record id'));
    await userEvent.type(screen.getByLabelText('Record id'), 'NC700343');
    await userEvent.click(screen.getByRole('button', { name: 'Trace' }));
    await waitFor(() => expect(calls('/lineage').at(-1)?.query.get('masterPatientId')).toBe(String(hugo.masterPatientId)));
  });

  it('asks which one when an MRN exists at two sources, and says so when none matches', async () => {
    const twin = { ...hugo, id: hugo.id + 1, sourceSystem: 'CITY_HOSPITAL' };
    server.use(
      http.get(`${API}/silver/patients`, ({ request }) => {
        const q = new URL(request.url).searchParams.get('q');
        const items = q === 'NC700343' ? [hugo, twin] : [{ ...hugo, sourcePatientId: 'OTHER', mrn: 'OTHER' }];
        return reply(request, { items, page: 0, size: 50, total: items.length, totalPages: 1 });
      }),
    );
    renderApp('/lineage');
    await userEvent.type(await screen.findByLabelText('Record id'), 'NC700343');
    await userEvent.click(screen.getByRole('button', { name: 'Trace' }));
    expect(await screen.findByText(/is the MRN of 2 Silver patients/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: new RegExp(`^CITY_HOSPITAL · ${hugo.sourcePatientId}`) }));
    await waitFor(() => expect(calls('/lineage').at(-1)?.query.get('silverPatientId')).toBe(String(twin.id)));

    await userEvent.clear(screen.getByLabelText('Record id'));
    await userEvent.type(screen.getByLabelText('Record id'), 'Patel');   // a name matches, but only an exact MRN counts
    await userEvent.click(screen.getByRole('button', { name: 'Trace' }));
    expect(await screen.findByText(/No Silver patient has MRN or source id "Patel"/)).toBeInTheDocument();
  });

  it('does not strip letters from a Bronze or FHIR id: it explains instead', async () => {
    renderApp('/lineage');
    await userEvent.selectOptions(await screen.findByLabelText('Record kind'), 'bronzeMessageId');
    await userEvent.type(screen.getByLabelText('Record id'), 'NC700343');
    await userEvent.click(screen.getByRole('button', { name: 'Trace' }));
    expect(await screen.findByText(/A bronze message id is a number/)).toBeInTheDocument();
    expect(calls('/lineage').filter((c) => c.query.has('bronzeMessageId'))).toHaveLength(0);
  });

  it('keeps Transformations in pipeline order, saying so, and sorts by date or records on click', async () => {
    renderApp('/lineage');
    expect(await screen.findByText(/In pipeline order, not by date/)).toBeInTheDocument();
    const steps = () =>
      within(screen.getByRole('heading', { name: 'Transformations recorded' }).closest('section')!)
        .getAllByRole('row')
        .slice(1)
        .map((r) => r.querySelector('td')!.textContent);
    await waitFor(() => expect(steps()).toEqual(fixtures.lineageSummary.map((r) => r.step)));
    await userEvent.click(screen.getByRole('button', { name: /^Last/ }));
    const byDate = [...fixtures.lineageSummary]
      .sort((a, b) => new Date(b.lastTransformedAt!).getTime() - new Date(a.lastTransformedAt!).getTime())
      .map((r) => r.step);
    expect(steps()).toEqual(byDate);
    expect(steps().at(-1)).toBe('SEED_LOAD'); // the oldest step goes to the bottom
    await userEvent.click(screen.getByRole('button', { name: /^Records/ }));
    expect(steps()[0]).toBe('FHIR_MAP');
    await userEvent.click(screen.getByRole('button', { name: /^Step/ }));
    expect(steps()).toEqual(fixtures.lineageSummary.map((r) => r.step));
  });

  it('shows who each Bronze message is about, linked to the Silver patient, and searches by it', async () => {
    renderApp('/lineage');
    const withPatient = fixtures.bronzeMessages.items.find((m) => m.silverPatientId !== null)!;
    const link = (await screen.findAllByRole('link', { name: withPatient.patientName! }))[0]!;
    expect(link).toHaveAttribute('href', `/lineage/silver/${withPatient.silverPatientId}`);
    expect(within(link.closest('td')!).getByText(withPatient.patientMrn!)).toBeInTheDocument();

    await userEvent.type(screen.getByLabelText('Bronze search'), 'NGUYEN');
    await userEvent.click(within(screen.getByRole('heading', { name: 'Bronze messages' }).closest('section')!).getByRole('button', { name: 'Search' }));
    await waitFor(() => expect(calls('/bronze/messages').at(-1)!.query.get('q')).toBe('NGUYEN'));
  });

  it('marks a Bronze patient who never reached Silver', async () => {
    const m = { ...fixtures.bronzeMessages.items[0]!, id: 9001, patientName: 'ZED NOBODY', patientMrn: 'BRZ-404', silverPatientId: null };
    server.use(
      http.get(`${API}/bronze/messages`, ({ request }) => reply(request, { items: [m], page: 0, size: 15, total: 1, totalPages: 1 })),
    );
    renderApp('/lineage');
    const cell = (await screen.findByText('ZED NOBODY')).closest('td')!;
    expect(within(cell).getByText('not in Silver')).toBeInTheDocument();
    expect(within(cell).queryByRole('link')).not.toBeInTheDocument();
  });

  it('names the patient at the top of a Bronze message page', async () => {
    const detail = { ...fixtures.bronzeMessage, summary: { ...fixtures.bronzeMessage.summary, patientName: 'LIAM NGUYEN', patientMrn: 'CH799449', silverPatientId: 1849 } };
    server.use(http.get(`${API}/bronze/messages/:id`, ({ request }) => reply(request, detail)));
    renderApp(`/lineage/bronze/${detail.summary.id}`);
    expect(await screen.findByText('LIAM NGUYEN')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Silver patient #1849' })).toHaveAttribute('href', '/lineage/silver/1849');
  });
});
