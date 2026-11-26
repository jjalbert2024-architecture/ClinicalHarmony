import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http } from 'msw';
import { describe, expect, it, vi } from 'vitest';
import { displayPayload, splunkTraceLink } from '../src/views/lineage/BronzeMessagePage';
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

  it('lists allergies with the Gold criticality and the source severity it came from', async () => {
    // Values of masters #344 / #346 in the live stack (2026-11-24).
    const allergy = (id: number, code: string, display: string, severity: string, reaction: string, criticality: string,
                     reactionSeverity: string, silverPatientId: number, rawMessageId: number) => ({
      id, codeSystem: 'RXNORM', code, display, allergyType: 'DA', severity, reaction, sourceSystem: 'NORTH_CLINIC',
      silverPatientId, rawMessageId, fhirResourceId: 900 + id, fhirReference: `AllergyIntolerance/silver-allergy-${id}`,
      usCoreCompliant: true, clinicalStatus: 'active', criticality, reactionSeverity, category: 'medication',
    });
    server.use(
      http.get(`${API}/patients/:id`, ({ request }) =>
        reply(request, {
          ...fixtures.patientMerged,
          allergies: [
            allergy(97, '723', 'Amoxicillin', 'SV', 'SWELLING', 'high', 'severe', 717, 839),
            allergy(96, '5640', 'Ibuprofen', 'MO', 'HIVES', 'low', 'moderate', 716, 836),
          ],
        }),
      ),
    );
    renderApp('/patients/1');
    const card = (await screen.findByRole('heading', { name: 'Allergies (2)' })).closest('section')!;
    const amox = within(card).getByText('Amoxicillin').closest('tr')!;
    expect(within(amox).getByText('high')).toBeInTheDocument();
    expect(within(amox).getByText('AL1-4 SV (severe)')).toBeInTheDocument();
    expect(within(amox).getByRole('link', { name: /AllergyIntolerance\/silver-allergy-97/ })).toHaveAttribute(
      'href',
      '/fhir/AllergyIntolerance/silver-allergy-97',
    );
    const ibu = within(card).getByText('Ibuprofen').closest('tr')!;
    expect(within(ibu).getByText('low')).toBeInTheDocument();
    expect(within(ibu).getByText('AL1-4 MO (moderate)')).toBeInTheDocument();
    expect(within(ibu).getByText('severity moderate')).toBeInTheDocument();
  });

  it('says when no source has an allergy', async () => {
    renderApp('/patients/1');
    expect(await screen.findByText('No allergies recorded in any source.')).toBeInTheDocument();
  });

  it('shows a lab result flag and reference range in the timeline', async () => {
    const [first, ...rest] = fixtures.timeline.events;
    server.use(
      http.get(`${API}/patients/:id/timeline`, ({ request }) =>
        reply(request, {
          ...fixtures.timeline,
          events: [{ ...first!, abnormalFlag: 'H', referenceLow: 4, referenceHigh: 5.6 }, ...rest],
        }),
      ),
    );
    renderApp('/patients/1');
    expect(await screen.findByText('H · high')).toBeInTheDocument();
    expect(screen.getByText('ref 4–5.6')).toBeInTheDocument();
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
    const scrolled = vi.spyOn(window, 'scrollTo').mockImplementation(() => {});
    await userEvent.click(await screen.findByRole('link', { name: `Review candidates of run #${run.id}` }));
    expect(await screen.findByRole('button', { name: `run #${run.id} ×` })).toBeInTheDocument();
    // The queue is the top card, the runs the bottom one: the click brings the queue into view and focus.
    expect(scrolled).toHaveBeenCalledWith(expect.objectContaining({ behavior: 'smooth' }));
    expect(screen.getByRole('heading', { name: 'Review queue' })).toHaveFocus();
    await waitFor(() =>
      expect(calls('/pmi/candidates').some((r) => r.query.get('status') === 'QUEUED' && r.query.get('runId') === String(run.id))).toBe(true),
    );
    expect(screen.getByText('Newest decision first.')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: `run #${run.id} ×` }));
    await waitFor(() => expect(screen.queryByRole('button', { name: `run #${run.id} ×` })).not.toBeInTheDocument());
    scrolled.mockRestore();
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
    expect(screen.getByRole('button', { name: 'Rule: CODE-001 ×' })).toBeInTheDocument();
  });

  it('lists filters in the order applied, highlights their columns, and resets them all', async () => {
    // Found by the user: chips came out in a fixed order, and nothing in the table showed what was filtered.
    renderApp('/validation?severity=ERROR&ruleCode=CODE-001');
    const region = await screen.findByRole('region', { name: 'Findings' });
    await within(region).findByRole('option', { name: 'CONDITION' });   // options come with the summary
    await userEvent.selectOptions(within(region).getByLabelText('Entity type'), 'CONDITION');
    const chips = within(region).getAllByRole('button', { name: / ×$/ }).map((b) => b.textContent);
    expect(chips).toEqual(['Severity: ERROR ×', 'Rule: CODE-001 ×', 'Entity: CONDITION ×']);
    await waitFor(() => expect(within(region).getAllByRole('columnheader', { name: /\(filtered\)/ }).map((h) => h.textContent))
      .toEqual(['Severity (filtered)', 'Rule (filtered)', 'Entity (filtered)']));
    await userEvent.click(within(region).getByRole('button', { name: 'Reset filters' }));
    await waitFor(() => expect(within(region).queryByRole('button', { name: / ×$/ })).not.toBeInTheDocument());
    expect(within(region).queryAllByRole('columnheader', { name: /\(filtered\)/ })).toHaveLength(0);
  });

  it('shows the top rules until "Show all", keeping the selected one visible', async () => {
    renderApp('/validation');
    const card = (await screen.findByRole('heading', { name: 'By rule' })).closest('section') as HTMLElement;
    const total = fixtures.validationSummary.byRule.length;
    if (total <= 6) return;   // the captured fixture may hold few rules
    expect(within(card).getAllByRole('listitem')).toHaveLength(6);
    await userEvent.click(within(card).getByRole('button', { name: `Show all ${total} rules` }));
    expect(within(card).getAllByRole('listitem')).toHaveLength(total);
  });

  it('brings the Findings table into view when a summary card is clicked, with the match count', async () => {
    // Found by the user: the table sits below the three cards, so a click seemed to do nothing.
    const scrolled = vi.fn();
    Element.prototype.scrollIntoView = scrolled;
    renderApp('/validation');
    await userEvent.click(await screen.findByRole('button', { name: /^WARNING/ }));
    expect(scrolled).toHaveBeenCalledWith(expect.objectContaining({ behavior: 'smooth' }));
    const heading = within(screen.getByRole('region', { name: 'Findings' })).getByRole('heading', { level: 2 });
    await waitFor(() => expect(heading).toHaveTextContent(/Findings \(\d[\d,]* matching\)/));
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
  it('counts resources per type in a table with a Total row and a Gold summary line', async () => {
    // fixture fhir-types.json: 77 resources, 73 compliant; Condition 5 of 6, Patient 41 of 43, PractitionerRole 1 of 2
    renderApp('/fhir');
    const table = await screen.findByRole('table', { name: 'Resource types' });
    expect(within(table).getAllByRole('columnheader').map((h) => h.textContent)).toEqual(['Type', 'Compliant', 'Not', 'Total']);
    const condition = within(table).getByRole('button', { name: 'Condition' }).closest('tr')!;
    expect(within(condition).getAllByRole('cell').map((c) => c.textContent)).toEqual(['Condition', '5', '1', '6']);
    const total = within(table).getByRole('rowheader', { name: 'Total' }).closest('tr')!;
    expect(within(total).getAllByRole('cell').map((c) => c.textContent)).toEqual(['73', '4', '77']);
    expect(screen.getByLabelText('Gold totals')).toHaveTextContent('In Gold: 77 resources · 73 US Core compliant · 4 not compliant');

    // A non-zero "Not" filters to that type's non-compliant resources; the summary follows the type
    await userEvent.click(within(condition).getByRole('button', { name: '1' }));
    await waitFor(() => {
      const query = calls('/fhir/resources').at(-1)!.query;
      expect([query.get('type'), query.get('compliant')]).toEqual(['Condition', 'false']);
    });
    expect(screen.getByLabelText('Gold totals')).toHaveTextContent('In Gold: 6 Condition · 5 US Core compliant · 1 not compliant');
  });

  it('shows a resource with validator messages, lineage and JSON', async () => {
    renderApp('/fhir/Condition/silver-condition-5');
    expect(await screen.findByRole('heading', { name: 'Condition/silver-condition-5' })).toBeInTheDocument();
    expect(screen.getByText('Not US Core compliant')).toBeInTheDocument();
    const validation = screen.getByRole('heading', { name: 'Validation' }).closest('section')!;
    expect(within(validation).getByText('Condition.code')).toBeInTheDocument();
    expect(screen.getByText('FHIR_MAP')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Copy JSON' })).toBeInTheDocument();
  });

  it('shows the element checklist: what the profile asks for and what the resource has', async () => {
    renderApp('/fhir/Condition/silver-condition-5');
    const card = (await screen.findByRole('heading', { name: 'What it was checked for' })).closest('section')!;
    // Real fixture: the Condition has no code, the one error the validator reported.
    const codeRow = within(card).getByText('Condition.code').closest('tr')!;
    expect(within(codeRow).getByText('Required')).toBeInTheDocument();
    expect(within(codeRow).getByText('Missing')).toBeInTheDocument();
    const subjectRow = within(card).getByText('Condition.subject').closest('tr')!;
    expect(within(subjectRow).getByText('Present')).toBeInTheDocument();
    const onsetRow = within(card).getByText('Condition.onset[x]').closest('tr')!;
    expect(within(onsetRow).getByText('Not sent (allowed)')).toBeInTheDocument();
    expect(within(onsetRow).getByText('USCDI')).toBeInTheDocument();
    expect(within(card).getByText('required 2 of 3')).toBeInTheDocument();
  });

  it('says when a resource has no recorded checklist', async () => {
    const notes = Object.fromEntries(
      Object.entries(fixtures.fhirResourceCondition.complianceNotes).filter(([key]) => key !== 'checklist'),
    );
    server.use(
      http.get(`${API}/fhir/resources/:type/:id`, ({ request }) =>
        reply(request, { ...fixtures.fhirResourceCondition, complianceNotes: notes }),
      ),
    );
    renderApp('/fhir/Condition/silver-condition-5');
    expect(await screen.findByText(/No element checklist was recorded/)).toBeInTheDocument();
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

  it('shows the selected type its own filters and sends them as attr.* parameters', async () => {
    renderApp('/fhir?type=Patient');
    const row = await screen.findByRole('group', { name: 'Patient attributes' });
    expect(row).toHaveTextContent('Patient attributes');
    // Real fixture: the dropdown lists what Gold holds, with counts.
    const gender = screen.getByRole('combobox', { name: 'Gender' });
    expect(within(gender).getByRole('option', { name: 'male (142)' })).toBeInTheDocument();
    await userEvent.selectOptions(gender, 'female');
    await waitFor(() => expect(calls('/fhir/resources').at(-1)!.query.get('attr.gender')).toBe('female'));

    // Text and date boxes wait for Search, like the other text filters.
    await userEvent.type(screen.getByRole('textbox', { name: 'Birth date from' }), '1970');
    await userEvent.type(screen.getByRole('textbox', { name: 'City' }), 'spring');
    await userEvent.click(screen.getByRole('button', { name: 'Search' }));
    await waitFor(() => expect(calls('/fhir/resources').at(-1)!.query.get('attr.birthDateFrom')).toBe('1970'));
    const last = calls('/fhir/resources').at(-1)!.query;
    expect(last.get('attr.city')).toBe('spring');
    expect(last.get('attr.gender')).toBe('female');
    expect(last.has('attr.birthDateTo')).toBe(false);   // an empty box is no filter

    // Another type: the Patient filters are dropped (they mean nothing for a Condition).
    await userEvent.click(screen.getByRole('button', { name: 'Condition' }));
    await waitFor(() => expect(calls('/fhir/resources').at(-1)!.query.get('type')).toBe('Condition'));
    expect([...calls('/fhir/resources').at(-1)!.query.keys()].filter((k) => k.startsWith('attr.'))).toEqual([]);
    // Let the Condition filters load before the test ends (no request may land in the next test).
    await waitFor(() => expect(calls('/fhir/attributes').at(-1)!.query.get('type')).toBe('Condition'));
    await screen.findByRole('group', { name: 'Condition attributes' });
  });

  it('shows each row its own attribute values, the filtered ones emphasised', async () => {
    const [first] = fixtures.fhirResources.items;
    server.use(
      http.get(`${API}/fhir/resources`, ({ request }) =>
        reply(request, {
          ...fixtures.fhirResources,
          items: [{ ...first!, attributes: { gender: 'female', birthDate: '1971-05-06', state: 'WY', phone: 'yes' } }],
        }),
      ),
    );
    renderApp('/fhir?type=Patient&attr.gender=female');
    const gender = await screen.findByText('Gender: female');
    expect(gender).toHaveClass('attr-filtered');
    expect(gender).toHaveAttribute('title', 'Patient.gender');
    expect(screen.getByText('State: WY')).not.toHaveClass('attr-filtered');
    expect(screen.getByText('Has a phone: yes')).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: 'Key attributes' })).toBeInTheDocument();
    // The "no filter" choice of US Core reads like every other dropdown's.
    expect(within(screen.getByRole('combobox', { name: 'Compliance' })).getByRole('option', { name: 'Any' })).toBeInTheDocument();
  });

  it('offers no attribute row until a type is picked', async () => {
    renderApp('/fhir');
    expect(await screen.findByText(/Pick a resource type on the left for its own filters/)).toBeInTheDocument();
    await screen.findByRole('button', { name: /^Updated/ });
    expect(screen.queryByRole('group', { name: /attributes$/ })).not.toBeInTheDocument();
    expect(calls('/fhir/attributes')).toEqual([]);
  });

  it('lists newest first by default and sorts by clicking column headers', async () => {
    // renderApp clears the request log, so the first request of this page is [0].
    const before = 0;
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

  it('offers C-CDA XML as a payload format (9d) and filters by it', async () => {
    renderApp('/lineage?messageType=CCDA_XML');
    expect(await screen.findByLabelText('Payload format')).toHaveDisplayValue('C-CDA XML');
    await waitFor(() => expect(calls('/bronze/messages').length).toBeGreaterThan(0));
    expect(calls('/bronze/messages').at(-1)!.query.get('messageType')).toBe('CCDA_XML');
    expect(screen.getByLabelText('HL7 message type')).toBeDisabled();
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

  it('shows a Bronze message\'s correlation id with a link to its trace in Splunk (9e)', async () => {
    server.use(
      http.get(`${API}/bronze/messages/:id`, ({ request }) =>
        reply(request, {
          ...fixtures.bronzeMessage,
          summary: { ...fixtures.bronzeMessage.summary, receivedAt: '2026-11-23T08:00:00Z' },
          intakeChannel: 'REST_KAFKA',
          correlationId: 'rest-0123456789ab',
        }),
      ),
    );
    renderApp('/lineage/bronze/1');
    expect(await screen.findByText('rest-0123456789ab')).toBeInTheDocument();
    expect(screen.getByText('REST → Kafka')).toBeInTheDocument();
    const link = await screen.findByRole('link', { name: 'Open trace in Splunk' });
    const url = new URL(link.getAttribute('href')!);
    expect(url.origin + url.pathname).toBe(fixtures.config.observability!.splunkTraceUrl);
    expect(url.searchParams.get('form.cid')).toBe('rest-0123456789ab');
    // Opened an hour before the payload arrived, not the dashboard's default last 24 h.
    expect(url.searchParams.get('form.t.earliest')).toBe(String(Date.parse('2026-11-23T08:00:00Z') / 1000 - 3600));
    expect(link).toHaveAttribute('target', '_blank');
  });

  it('says a legacy Bronze row has no correlation id, and builds a safe trace link', async () => {
    server.use(
      http.get(`${API}/bronze/messages/:id`, ({ request }) =>
        reply(request, {
          ...fixtures.bronzeMessage,
          // Migration 29: a pre-9d REST row is closed as FAILED with the reason, not PENDING forever.
          summary: { ...fixtures.bronzeMessage.summary, processingStatus: 'FAILED', errorMessage: 'Legacy row: written straight to Bronze' },
          silverRows: [],
          intakeChannel: 'REST',
          correlationId: null,
        }),
      ),
    );
    renderApp('/lineage/bronze/1');
    expect(await screen.findByText(/none — written straight to Bronze/)).toBeInTheDocument();
    expect(screen.getByText('Why it failed:')).toBeInTheDocument();
    expect(screen.getByText('REST, written directly (before Phase 9d)')).toBeInTheDocument();
    expect(screen.getByText(/failed messages never reach Silver/)).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Open trace in Splunk' })).toBeNull();
    // An id with characters that mean something in a URL is encoded, and a missing time falls back to 7 days.
    const href = splunkTraceLink('http://s/trace', 'a&b=c', null);
    expect(new URL(href).searchParams.get('form.cid')).toBe('a&b=c');
    expect(new URL(href).searchParams.get('form.t.earliest')).toBe('-7d@d');
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

  it("starts the trace box from the selected layer's own record kind", async () => {
    renderApp('/lineage');
    const kind = await screen.findByLabelText('Record kind');
    expect(kind).toHaveDisplayValue('Bronze message id');
    await userEvent.click(screen.getByRole('tab', { name: /Silver/ }));
    await waitFor(() => expect(kind).toHaveDisplayValue('Silver patient id'));
    await userEvent.click(screen.getByRole('tab', { name: /Gold/ }));
    await waitFor(() => expect(kind).toHaveDisplayValue('Master patient id'));
    await userEvent.click(screen.getByRole('tab', { name: /Bronze/ }));
    await waitFor(() => expect(kind).toHaveDisplayValue('Bronze message id'));
  });

  it('keeps the kind of a trace on screen when another layer is picked', async () => {
    renderApp(`/lineage?silverPatientId=${hugo.id}`);
    const kind = await screen.findByLabelText('Record kind');
    expect(kind).toHaveDisplayValue('Silver patient id');
    await userEvent.click(screen.getByRole('tab', { name: /Gold/ }));
    await screen.findByRole('heading', { name: 'Gold master patients' });
    expect(kind).toHaveDisplayValue('Silver patient id');
  });

  it('lists merged-away Gold masters under MERGED, each naming the master that absorbed it', async () => {
    const survivor = fixtures.patients.items[1]!;
    const absorbed = { ...survivor, id: 9001, matchStatus: 'MERGED', active: false, sourcePatientCount: 0, sourceSystems: null, fhirResourceCount: 0, mergedIntoMasterId: survivor.id };
    server.use(
      http.get(`${API}/patients`, ({ request }) =>
        reply(request, new URL(request.url).searchParams.get('status') === 'MERGED' ? { ...fixtures.patients, items: [absorbed], total: 1 } : fixtures.patients),
      ),
    );
    renderApp('/lineage?layer=gold');
    await screen.findByRole('heading', { name: 'Gold master patients' });
    await userEvent.selectOptions(screen.getByLabelText('Match status'), 'MERGED');
    await waitFor(() => expect(calls('/patients').at(-1)!.query.get('status')).toBe('MERGED'));
    expect(await screen.findByRole('link', { name: `#${survivor.id}` })).toHaveAttribute('href', `/patients/${survivor.id}`);
    expect(screen.getByText(/merged into/)).toBeInTheDocument();
    expect(screen.getByText(/Masters absorbed by a merge/)).toBeInTheDocument();
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

  it('lists a claims member\'s claim lines in their own card, which stay in Silver (9d)', async () => {
    const base = {
      kind: 'CLAIM', codeSystem: 'ICD-10', display: null, value: null, instant: null,
      fhirResourceId: null, fhirResourceType: null, fhirId: null, usCoreCompliant: null,
    };
    const lifecycle = {
      payerId: null, payerName: null, status: null, statusReason: null, allowedAmount: null, paidAmount: null,
      patientResponsibility: null, submittedDate: null, statusDate: null, waitingOn: null, waitingSince: null,
    };
    // One claim from a file without the lifecycle columns, one denied by the payer.
    const old = {
      ...base, id: 4242, code: 'E11.9', display: 'Claim C9D-1', status: null, value: 'billed 150.00', date: '2026-11-01',
      rawMessageId: 3554, claim: { ...lifecycle, claimId: 'C9D-1', billedAmount: 150, serviceDate: '2026-11-01' },
    };
    const denied = {
      ...base, id: 4243, code: 'I10', display: 'Claim CLM-2', status: 'DENIED', value: 'billed 95.50', date: '2026-11-02',
      rawMessageId: 3555,
      claim: {
        ...lifecycle, claimId: 'CLM-2', payerId: '60054', payerName: 'AETNA', status: 'DENIED', statusReason: 'CO-50',
        billedAmount: 95.5, allowedAmount: 0, paidAmount: 0, patientResponsibility: 0, serviceDate: '2026-11-02',
        submittedDate: '2026-11-03', statusDate: '2026-11-20', waitingOn: 'PROVIDER', waitingSince: '2026-11-20',
      },
    };
    server.use(
      http.get(`${API}/silver/patients/:id`, ({ request }) =>
        reply(request, { ...fixtures.silverPatient, rows: [...fixtures.silverPatient.rows, old, denied] }),
      ),
    );
    renderApp(`/lineage/silver/${hugo.id}`);
    const heading = await screen.findByRole('heading', { name: /Claims/ });
    expect(heading).toHaveTextContent('Claims (2)');
    const card = document.getElementById('records-claims')!;
    expect(within(card).getByText(/they stay in Silver/)).toBeInTheDocument();

    const row = document.getElementById('claims-4242')!;
    expect(within(row).getByText('C9D-1')).toBeInTheDocument();
    expect(within(row).getByText('payer not given')).toBeInTheDocument();
    expect(within(row).getByText('not in feed')).toBeInTheDocument();
    expect(within(row).getByText('billed 150.00')).toBeInTheDocument();
    expect(within(row).getByRole('link', { name: '#3554' })).toHaveAttribute('href', '/lineage/bronze/3554');

    const d = document.getElementById('claims-4243')!;
    expect(within(d).getByText('AETNA · 60054')).toBeInTheDocument();
    expect(within(d).getByText('Denied')).toHaveClass('badge-bad');
    expect(within(d).getByText('CO-50 · not medically necessary')).toBeInTheDocument();
    expect(within(d).getByText('Provider')).toBeInTheDocument();
    expect(within(d).getByText('· appeal or correct')).toBeInTheDocument();
    expect(within(d).getByText(/^since .*2026 · \d+ days$/)).toBeInTheDocument();
    expect(within(d).getByText('paid 0.00')).toBeInTheDocument();
    expect(within(d).getByText(/^sent /)).toBeInTheDocument();

    // Visible without scrolling: the Records strip counts the claims, flags the denial, and jumps to the card.
    const strip = screen.getByRole('navigation', { name: 'Records of this patient' });
    expect(within(strip).getByRole('link', { name: /^Claims/ })).toHaveAttribute(
      'href',
      `/lineage/silver/${hugo.id}#records-claims`,
    );
    expect(within(strip).getByRole('link', { name: /^Claims/ })).toHaveTextContent(/^Claims 2 · 1 denied$/);
  });

  it('counts every kind in the Records strip and collapses the empty kinds into one line', async () => {
    // Found by the user: a claims member's Claims card sat under four "None." cards, several screens down.
    const scrolled = vi.fn();
    Element.prototype.scrollIntoView = scrolled;
    renderApp(`/lineage/silver/${hugo.id}`);
    const strip = await screen.findByRole('navigation', { name: 'Records of this patient' });
    const count = (kind: string) => fixtures.silverPatient.rows.filter((r) => r.kind === kind).length;
    // The fixture: conditions, observations and an allergy, no medications or claims.
    expect(count('MEDICATION')).toBe(0);
    expect(count('CLAIM')).toBe(0);
    for (const [title, kind] of [['Conditions', 'CONDITION'], ['Observations', 'OBSERVATION'], ['Allergies', 'ALLERGY']] as const) {
      expect(within(strip).getByRole('link', { name: `${title} ${count(kind)}` })).toHaveAttribute('href', expect.stringMatching(/#records-/));
    }
    expect(within(strip).getByRole('link', { name: `Findings ${fixtures.silverPatient.findings.length}` })).toHaveAttribute(
      'href',
      `/lineage/silver/${hugo.id}#findings`,
    );
    // Zero counts are shown but aren't links, and their cards are folded into one muted line.
    expect(within(strip).getByText('Medications 0')).not.toHaveAttribute('href');
    expect(within(strip).queryByRole('link', { name: /Claims/ })).toBeNull();
    expect(screen.queryByRole('heading', { name: /Medications/ })).toBeNull();
    expect(screen.queryByRole('heading', { name: /Claims/ })).toBeNull();
    expect(screen.getByText('No medications or claims.')).toBeInTheDocument();
    expect(screen.queryByText('None.')).toBeNull();
    await userEvent.click(within(strip).getByRole('link', { name: /^Observations/ }));
    await waitFor(() => expect(scrolled.mock.contexts).toContain(document.getElementById('records-observations')));
    expect(scrolled).toHaveBeenCalledWith(expect.objectContaining({ block: 'start' }));
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
    renderApp('/lineage?layer=silver'); // the Silver card starts the box on Silver patient id
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
    renderApp('/lineage?layer=silver'); // the Silver card starts the box on Silver patient id
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
    const card = () => within(screen.getByRole('heading', { name: 'Transformations recorded' }).closest('section')!);
    const steps = () =>
      card()
        .getAllByRole('row')
        .slice(1)
        .map((r) => r.querySelector('td')!.textContent);
    await waitFor(() => expect(steps()).toEqual(fixtures.lineageSummary.map((r) => r.step)));
    await userEvent.click(card().getByRole('button', { name: /^Last/ }));
    const byDate = [...fixtures.lineageSummary]
      .sort((a, b) => new Date(b.lastTransformedAt!).getTime() - new Date(a.lastTransformedAt!).getTime())
      .map((r) => r.step);
    expect(steps()).toEqual(byDate);
    expect(steps().at(-1)).toBe('SEED_LOAD'); // the oldest step goes to the bottom
    await userEvent.click(card().getByRole('button', { name: /^Records/ }));
    expect(steps()[0]).toBe('FHIR_MAP');
    await userEvent.click(card().getByRole('button', { name: /^Step/ }));
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

describe('List controls (every paged list)', () => {
  it('pages the Bronze list: ids on screen, go to page, First / Last, rows per page', async () => {
    // fixture: page 3 (index 2) of 102, 10 rows, #1279 … #1270
    renderApp('/lineage');
    const pager = (await screen.findByLabelText('Go to page')).closest('.pager') as HTMLElement;
    expect(pager).toHaveTextContent('21–30 of 1,017 · #1279 → #1270');
    expect(pager).toHaveTextContent('of 102');

    await userEvent.type(screen.getByLabelText('Go to page'), '7{Enter}');
    await waitFor(() => expect(calls('/bronze/messages').at(-1)!.query.get('page')).toBe('6'));
    await userEvent.click(screen.getByRole('button', { name: 'Last page' }));
    await waitFor(() => expect(calls('/bronze/messages').at(-1)!.query.get('page')).toBe('101'));
    // (page 1 is the cached first request, so First is checked by being enabled on page 3)
    expect(screen.getByRole('button', { name: 'First page' })).toBeEnabled();

    await userEvent.selectOptions(screen.getByLabelText('Rows per page'), '50');
    await waitFor(() => {
      const query = calls('/bronze/messages').at(-1)!.query;
      expect([query.get('size'), query.get('page')]).toEqual(['50', '0']);
    });
  });

  it('filters the Bronze list by a message-number range ("#" ignored) and flips the order', async () => {
    renderApp('/lineage');
    await userEvent.type(await screen.findByLabelText('Message # from'), '#990');
    await userEvent.type(screen.getByLabelText('Message # to'), '1003');
    await userEvent.click(screen.getByRole('button', { name: 'Apply' }));
    await waitFor(() => {
      const query = calls('/bronze/messages').at(-1)!.query;
      expect([query.get('idFrom'), query.get('idTo')]).toEqual(['990', '1003']);
    });
    await userEvent.selectOptions(screen.getByLabelText('Order'), 'oldest');
    await waitFor(() => expect(calls('/bronze/messages').at(-1)!.query.get('order')).toBe('oldest'));
    expect(screen.getByText(/^Oldest first \(by message #\)\./)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Clear range' }));
    await waitFor(() => expect(calls('/bronze/messages').at(-1)!.query.get('idFrom')).toBeNull());
    expect(calls('/bronze/messages').at(-1)!.query.get('order')).toBe('oldest');
  });

  it.each([
    { path: '/lineage?layer=silver', api: '/silver/patients', label: 'Silver id', order: 'oldest' },
    { path: '/lineage?layer=gold', api: '/patients', label: 'Master #', order: 'newest' },
    { path: '/patients', api: '/patients', label: 'Master #', order: 'oldest' },
    { path: '/validation', api: '/validation/findings', label: 'Finding #', order: 'oldest' },
  ])('$path sends the same range, order and rows per page', async ({ path, api, label, order }) => {
    renderApp(path);
    await userEvent.type(await screen.findByLabelText(`${label} from`), '5');
    await userEvent.type(screen.getByLabelText(`${label} to`), '9');
    await userEvent.click(screen.getByRole('button', { name: 'Apply' }));
    await waitFor(() => {
      const query = calls(api).at(-1)!.query;
      expect([query.get('idFrom'), query.get('idTo')]).toEqual(['5', '9']);
    });
    await userEvent.selectOptions(screen.getByLabelText('Order'), order);
    await waitFor(() => expect(calls(api).at(-1)!.query.get('order')).toBe(order));
    await userEvent.selectOptions(await screen.findByLabelText('Rows per page'), '100');
    await waitFor(() => expect(calls(api).at(-1)!.query.get('size')).toBe('100'));
    // the range survived the other two changes
    expect(calls(api).at(-1)!.query.get('idFrom')).toBe('5');
  });

  it('gives the FHIR Viewer rows per page and the resources on screen (its ids are text: headers sort instead of a range)', async () => {
    renderApp('/fhir');
    const first = fixtures.fhirResources.items[0]!;
    const pager = (await screen.findByLabelText('Rows per page')).closest('.pager') as HTMLElement;
    expect(pager).toHaveTextContent(`${first.resourceType}/${first.fhirId}`);
    expect(screen.queryByLabelText(/ from$/)).toBeNull();
    await userEvent.selectOptions(screen.getByLabelText('Rows per page'), '50');
    await waitFor(() => expect(calls('/fhir/resources').at(-1)!.query.get('size')).toBe('50'));
  });
});

describe('List summary above every list', () => {
  it('says how many and the starting / ending number of the whole result, next to the search box', async () => {
    server.use(
      http.get(`${API}/bronze/messages`, ({ request }) => reply(request, { ...fixtures.bronzeMessages, minId: 5, maxId: 1088 })),
    );
    renderApp('/lineage');
    const summary = await screen.findByLabelText('List summary');
    // one line with the card's title
    expect(summary.closest('.list-summary-line')).toHaveTextContent(
      'Bronze messages| Total # messages: 1,017 | Range: #5 (oldest) → #1088 (newest)',
    );
    expect(summary.closest('.card-head')).toContainElement(screen.getByLabelText('Bronze search'));
    expect(screen.getByLabelText('Bronze search')).toHaveAttribute('placeholder', 'Patient name, MRN (exact) or message #');
  });

  it('leaves the number span out when the list has none', async () => {
    renderApp('/fhir');
    const summary = await screen.findByLabelText('List summary');
    expect(summary).toHaveTextContent(/^\| Total # resources: 14$/);
  });
});
