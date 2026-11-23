import { useQuery } from '@tanstack/react-query';
import { useEffect } from 'react';
import { Link, useLocation, useNavigate, useParams } from 'react-router';
import type { ClaimDetail, ClaimStatus, ClaimWaitingOn, SilverPatientDetail, SilverRow, SilverRowKind, SilverRowOwner } from '../../api/types';
import { useApi } from '../../app/context';
import { Badge, Empty, Loading, PageHeader, QueryState } from '../../components/common';
import { fmtDate, fmtDateTime, fullName } from '../../format';
import { BronzeTable, VALIDATION_LABELS } from './LineagePage';

const KINDS: { kind: SilverRowKind; title: string; table: string }[] = [
  { kind: 'CONDITION', title: 'Conditions', table: 'conditions' },
  { kind: 'OBSERVATION', title: 'Observations', table: 'observations' },
  { kind: 'MEDICATION', title: 'Medications', table: 'medications' },
  { kind: 'ALLERGY', title: 'Allergies', table: 'allergies' },
  { kind: 'CLAIM', title: 'Claims', table: 'claims' },
];

/** Validation report entity types that are rows on this page. */
const ENTITY_TABLES: Record<string, string> = {
  CONDITION: 'conditions',
  OBSERVATION: 'observations',
  MEDICATION: 'medications',
  ALLERGY: 'allergies',
};

/** HTML id of a Silver row on this page — lineage refs like silver.allergies/165 land on it. */
export const rowAnchor = (table: string, id: number) => `${table}-${id}`;

/**
 * One Silver patient, in the middle of the medallion: the Bronze messages that fed it (left), its parsed clinical
 * rows and validation findings, and the Gold master + FHIR resources it became (right). Every id is a link.
 */
export function SilverPatientPage() {
  const { silverPatientId = '' } = useParams();
  const api = useApi();
  const { hash } = useLocation();
  const detail = useQuery({
    queryKey: ['silver', 'patient', silverPatientId],
    queryFn: ({ signal }) => api.get<SilverPatientDetail>(`/silver/patients/${encodeURIComponent(silverPatientId)}`, undefined, signal),
  });
  // Arriving from a lineage ref (#allergies-165) or the Records strip (#records-claims): bring that row / card into
  // view once it's rendered — a row centered, a card from its top.
  useEffect(() => {
    if (detail.data && hash) scrollToAnchor(hash.slice(1), false);
  }, [detail.data, hash]);

  return (
    <QueryState query={detail} context={`Loading Silver patient #${silverPatientId}`}>
      {(d) => {
        const p = d.summary;
        const status = p.validationStatus ?? 'NOT_TRACKED';
        return (
          <>
            <PageHeader
              title={`Silver patient #${p.id} · ${fullName(p.firstName, p.lastName)}`}
              subtitle={
                <>
                  {p.sourceSystem} · {p.sourcePatientId} · DOB {fmtDate(p.dateOfBirth)} · <Badge value={status}>{VALIDATION_LABELS[status]}</Badge>
                </>
              }
              actions={
                <>
                  <Link className="btn" to={`/lineage?layer=silver&silverPatientId=${p.id}`}>
                    Full lineage
                  </Link>{' '}
                  <Link className="btn" to="/lineage?layer=silver">
                    Back to Silver
                  </Link>
                </>
              }
            />

            <nav className="medallion-path" aria-label="Where this record sits">
              <span className="path-step path-bronze">
                <strong>Bronze</strong>{' '}
                {d.bronzeMessages.length === 0 ? (
                  <span className="muted">no raw message (seed data)</span>
                ) : (
                  d.bronzeMessages.map((b, i) => (
                    <span key={b.id}>
                      {i > 0 && ', '}
                      <Link to={`/lineage/bronze/${b.id}`}>#{b.id}</Link>
                    </span>
                  ))
                )}
              </span>
              <span aria-hidden="true"> → </span>
              <span className="path-step path-silver">
                <strong>Silver</strong> patient #{p.id}
              </span>
              <span aria-hidden="true"> → </span>
              <span className="path-step path-gold">
                <strong>Gold</strong>{' '}
                {d.gold.masterPatientId ? (
                  <>
                    master <Link to={`/patients/${d.gold.masterPatientId}`}>#{d.gold.masterPatientId}</Link>{' '}
                    <Badge value={d.gold.masterMatchStatus} />
                    {d.gold.patientFhirId && (
                      <>
                        {' '}
                        · <Link to={`/fhir/Patient/${encodeURIComponent(d.gold.patientFhirId)}`}>Patient/{d.gold.patientFhirId}</Link>
                      </>
                    )}
                  </>
                ) : (
                  <span className="muted">not built into Gold yet</span>
                )}
              </span>
            </nav>

            <RecordsStrip rows={d.rows} findings={d.findings.length} />

            <div className="grid-2">
              <section className="card">
                <h2>Demographics</h2>
                <dl className="kv">
                  <dt>Source system</dt>
                  <dd>{p.sourceSystem}</dd>
                  <dt>Source id / MRN</dt>
                  <dd>
                    {p.sourcePatientId}
                    {p.mrn && p.mrn !== p.sourcePatientId && <span className="muted"> · MRN {p.mrn}</span>}
                  </dd>
                  <dt>Name</dt>
                  <dd>{fullName(p.firstName, p.lastName)}</dd>
                  <dt>Birth date · sex</dt>
                  <dd>
                    {fmtDate(p.dateOfBirth)} · {p.gender ?? '—'}
                  </dd>
                  <dt>Address</dt>
                  <dd>{[d.addressLine1, d.addressLine2, d.city, d.state, d.postalCode].filter(Boolean).join(', ') || '—'}</dd>
                  <dt>Phone</dt>
                  <dd>{d.phone ?? '—'}</dd>
                  <dt>In Silver since</dt>
                  <dd>
                    {fmtDateTime(d.createdAt)} · updated {fmtDateTime(p.updatedAt)}
                  </dd>
                </dl>
              </section>
              <section className="card">
                <h2>Validation</h2>
                <dl className="kv">
                  <dt>Status</dt>
                  <dd>
                    <Badge value={status}>{VALIDATION_LABELS[status]}</Badge>
                    {p.validatedAt && <span className="muted"> · {fmtDateTime(p.validatedAt)}</span>}
                  </dd>
                  <dt>Attempts</dt>
                  <dd>{d.validationAttempts}</dd>
                  {d.validationError && (
                    <>
                      <dt>Last error</dt>
                      <dd className="wrap">{d.validationError}</dd>
                    </>
                  )}
                  <dt>Open findings</dt>
                  <dd>
                    {p.openFindings > 0 ? <Link to={`/validation?patientId=${p.id}&resolved=false`}>{p.openFindings} in the Validation Report</Link> : 'none'}
                  </dd>
                </dl>
              </section>
            </div>

            <section className="card">
              <h2>Bronze messages behind this patient</h2>
              {d.bronzeMessages.length === 0 ? (
                <Empty>None — this patient was seeded straight into Silver, so its lineage starts here.</Empty>
              ) : (
                <BronzeTable items={d.bronzeMessages} />
              )}
            </section>

            {KINDS.filter(({ kind }) => d.rows.some((r) => r.kind === kind)).map(({ kind, title, table }) =>
              kind === 'CLAIM' ? (
                <ClaimsCard key={kind} rows={d.rows.filter((r) => r.kind === kind)} highlight={hash.slice(1)} />
              ) : (
                <RowsCard key={kind} title={title} table={table} rows={d.rows.filter((r) => r.kind === kind)} highlight={hash.slice(1)} />
              ),
            )}
            <EmptyKinds rows={d.rows} />

            <section className="card" id={FINDINGS_ANCHOR}>
              <h2>Validation findings</h2>
              {d.findings.length === 0 ? (
                <Empty>No findings recorded for this patient.</Empty>
              ) : (
                <table className="table">
                  <thead>
                    <tr>
                      <th>Rule</th>
                      <th>Severity</th>
                      <th>On</th>
                      <th>Message</th>
                      <th>State</th>
                      <th>Validated</th>
                    </tr>
                  </thead>
                  <tbody>
                    {d.findings.map((f) => (
                      <tr key={f.id}>
                        <td>
                          <code>{f.ruleCode}</code>
                        </td>
                        <td>
                          <Badge value={f.severity} />
                        </td>
                        <td>
                          {f.entityId !== null && ENTITY_TABLES[f.entityType] ? (
                            <Link to={{ hash: rowAnchor(ENTITY_TABLES[f.entityType]!, f.entityId) }}>
                              {f.entityType} #{f.entityId}
                            </Link>
                          ) : (
                            <>
                              {f.entityType}
                              {f.entityId !== null && ` #${f.entityId}`}
                            </>
                          )}
                        </td>
                        <td className="wrap">
                          {f.message}
                          {f.badValue && <div className="muted small">bad value: {f.badValue}</div>}
                        </td>
                        <td>{f.resolved ? <Badge tone="muted">resolved</Badge> : <Badge tone="warn">open</Badge>}</td>
                        <td>{fmtDateTime(f.validatedAt)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </section>
          </>
        );
      }}
    </QueryState>
  );
}

/** HTML id of a kind's card (the Records strip links to it). */
export const cardAnchor = (table: string) => `records-${table}`;
const FINDINGS_ANCHOR = 'findings';

/** Smooth on a click; instant on page load, where a smooth scroll gets cut short while the page is still laying out. */
function scrollToAnchor(id: string, smooth = true) {
  const el = document.getElementById(id);
  el?.scrollIntoView?.({ behavior: smooth ? 'smooth' : 'auto', block: el.tagName === 'SECTION' ? 'start' : 'center' });
}

/**
 * One line under the medallion path with the patient's row counts per kind, so what the patient has (e.g. claims) is
 * visible without scrolling; each non-zero count jumps to its card.
 */
function RecordsStrip({ rows, findings }: { rows: SilverRow[]; findings: number }) {
  const items = [
    ...KINDS.map(({ kind, title, table }) => ({
      key: kind,
      title,
      anchor: cardAnchor(table),
      count: rows.filter((r) => r.kind === kind).length,
      note: kind === 'CLAIM' ? claimsNote(rows) : undefined,
    })),
    { key: 'FINDINGS', title: 'Findings', anchor: FINDINGS_ANCHOR, count: findings, note: undefined },
  ];
  return (
    <nav className="records-strip" aria-label="Records of this patient">
      <span className="muted small">Records</span>
      {items.map(({ key, title, anchor, count, note }) =>
        count > 0 ? (
          // Clicking the count of the card already in the URL doesn't change the hash, so scroll here too.
          <Link key={key} className="record-count" to={{ hash: anchor }} onClick={() => scrollToAnchor(anchor)}>
            {title} <strong>{count}</strong>
            {note && <span className="record-note"> · {note}</span>}
          </Link>
        ) : (
          <span key={key} className="record-count record-count-empty">
            {title} 0
          </span>
        ),
      )}
    </nav>
  );
}

/** What needs attention among the patient's claims, for the Records strip: "1 denied, 1 patient owes", … */
function claimsNote(rows: SilverRow[]): string | undefined {
  const claims = rows.flatMap((r) => (r.kind === 'CLAIM' && r.claim ? [r.claim] : []));
  const denied = claims.filter((c) => c.status === 'DENIED').length;
  const rejected = claims.filter((c) => c.status === 'REJECTED').length;
  const owed = claims.filter((c) => c.waitingOn === 'PATIENT').length;
  const parts = [denied > 0 && `${denied} denied`, rejected > 0 && `${rejected} rejected`, owed > 0 && `${owed} patient owes`].filter(Boolean);
  return parts.length ? parts.join(', ') : undefined;
}

const CLAIM_STATUS: Record<ClaimStatus, { label: string; tone: 'good' | 'warn' | 'bad' | 'info' | 'muted' }> = {
  SUBMITTED: { label: 'Submitted', tone: 'info' },
  ACCEPTED: { label: 'Accepted by payer', tone: 'info' },
  PENDED: { label: 'Pended', tone: 'warn' },
  APPEALED: { label: 'Appealed', tone: 'warn' },
  PAID: { label: 'Paid', tone: 'good' },
  PARTIALLY_PAID: { label: 'Partially paid', tone: 'good' },
  DENIED: { label: 'Denied', tone: 'bad' },
  REJECTED: { label: 'Rejected', tone: 'bad' },
  VOIDED: { label: 'Voided', tone: 'muted' },
};

/** A few common claim adjustment reason codes, spelled out; anything else shows as its code. */
const REASONS: Record<string, string> = {
  'CO-16': 'missing information',
  'CO-18': 'duplicate claim',
  'CO-29': 'filed too late',
  'CO-45': 'over the fee schedule',
  'CO-50': 'not medically necessary',
  'CO-97': 'bundled into another service',
  'CO-197': 'no prior authorization',
  'PR-1': 'deductible',
  'PR-2': 'coinsurance',
  'PR-3': 'copay',
  'PR-96': 'not covered',
};

const WAITING: Record<ClaimWaitingOn, string> = {
  PAYER: 'Payer',
  PROVIDER: 'Provider',
  PATIENT: 'Patient',
  NOBODY: 'Nobody',
};

const money = (v: number | null | undefined) => (v === null || v === undefined ? '—' : Number(v).toFixed(2));

function daysSince(date: string, today = new Date()): number {
  const [y, m, d] = date.split('-').map(Number);
  return Math.max(0, Math.floor((Date.UTC(today.getFullYear(), today.getMonth(), today.getDate()) - Date.UTC(y!, m! - 1, d!)) / 86_400_000));
}

/** What the claim is waiting for, in words: who has to act, on what, and for how long. */
function waitingText(c: ClaimDetail): string {
  switch (c.waitingOn) {
    case 'PAYER':
      return c.status === 'APPEALED' ? 'appeal decision' : 'adjudication';
    case 'PROVIDER':
      return c.status === 'REJECTED' ? 'fix and resubmit' : 'appeal or correct';
    case 'PATIENT':
      return `owes ${money(c.patientResponsibility ?? c.billedAmount)}`;
    case 'NOBODY':
      return 'closed';
    default:
      return '';
  }
}

/**
 * The claims card has its own columns: a claim isn't a clinical fact but a billing record with a lifecycle — who has
 * it (payer), where it is (status + reason), who must act next and for how long, and what the money did.
 */
function ClaimsCard({ rows, highlight }: { rows: SilverRow[]; highlight: string }) {
  const table = 'claims';
  return (
    <section className="card" id={cardAnchor(table)}>
      <h2>
        Claims <span className="muted small">({rows.length})</span>
      </h2>
      <p className="muted small">
        Billing records: they stay in Silver — each diagnosis is also a condition above, which is what reaches Gold.
      </p>
      <table className="table">
        <thead>
          <tr>
            <th>Silver row</th>
            <th>Claim · payer</th>
            <th>Diagnosis</th>
            <th>Status</th>
            <th>Waiting on</th>
            <th className="num" title="billed / allowed / paid / patient owes">
              Amounts
            </th>
            <th>Dates</th>
            <th>From Bronze</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => {
            const c = r.claim;
            const status = c?.status ? CLAIM_STATUS[c.status] : undefined;
            return (
              <tr key={r.id} id={rowAnchor(table, r.id)} className={highlight === rowAnchor(table, r.id) ? 'row-highlight' : undefined}>
                <td>
                  silver.{table}/{r.id}
                </td>
                <td>
                  {c?.claimId ?? r.display}
                  <div className="muted small">{c?.payerName ? `${c.payerName}${c.payerId ? ` · ${c.payerId}` : ''}` : 'payer not given'}</div>
                </td>
                <td>
                  <span className="muted small">{r.codeSystem}</span> <code>{r.code ?? '—'}</code>
                </td>
                <td>
                  {status ? <Badge tone={status.tone}>{status.label}</Badge> : <span className="muted">not in feed</span>}
                  {c?.statusReason && (
                    <div className="muted small">
                      {c.statusReason}
                      {REASONS[c.statusReason] && ` · ${REASONS[c.statusReason]}`}
                    </div>
                  )}
                </td>
                <td>
                  {c?.waitingOn ? (
                    <>
                      <strong>{WAITING[c.waitingOn]}</strong> <span className="muted">· {waitingText(c)}</span>
                      {c.waitingSince && (
                        <div className="muted small">
                          since {fmtDate(c.waitingSince)} · {daysSince(c.waitingSince)} days
                        </div>
                      )}
                    </>
                  ) : (
                    <span className="muted">—</span>
                  )}
                </td>
                <td className="num claim-amounts">
                  {c ? (
                    <>
                      <div>billed {money(c.billedAmount)}</div>
                      {c.allowedAmount !== null && <div className="muted small">allowed {money(c.allowedAmount)}</div>}
                      {c.paidAmount !== null && <div className="muted small">paid {money(c.paidAmount)}</div>}
                      {c.patientResponsibility !== null && <div className="muted small">patient {money(c.patientResponsibility)}</div>}
                    </>
                  ) : (
                    r.value ?? '—'
                  )}
                </td>
                <td>
                  <div>service {fmtDate(r.date)}</div>
                  {c?.submittedDate && <div className="muted small">sent {fmtDate(c.submittedDate)}</div>}
                  {c?.statusDate && <div className="muted small">updated {fmtDate(c.statusDate)}</div>}
                </td>
                <td>{r.rawMessageId ? <Link to={`/lineage/bronze/${r.rawMessageId}`}>#{r.rawMessageId}</Link> : <span className="muted">seed</span>}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </section>
  );
}

/** The kinds this patient has no rows of, as one muted line instead of a "None." card each. */
function EmptyKinds({ rows }: { rows: SilverRow[] }) {
  const empty = KINDS.filter(({ kind }) => !rows.some((r) => r.kind === kind)).map(({ title }) => title.toLowerCase());
  if (empty.length === 0) return null;
  const list = empty.length === 1 ? empty[0] : `${empty.slice(0, -1).join(', ')} or ${empty.at(-1)}`;
  return <p className="muted empty-kinds">No {list}.</p>;
}

function RowsCard({ title, table, rows, highlight }: { title: string; table: string; rows: SilverRow[]; highlight: string }) {
  return (
    <section className="card" id={cardAnchor(table)}>
      <h2>
        {title} <span className="muted small">({rows.length})</span>
      </h2>
      <table className="table">
        <thead>
          <tr>
            <th>Silver row</th>
            <th>Code</th>
            <th>Description</th>
            <th>Status</th>
            <th>Value / detail</th>
            <th>Date</th>
            <th>From Bronze</th>
            <th>Gold FHIR</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.id} id={rowAnchor(table, r.id)} className={highlight === rowAnchor(table, r.id) ? 'row-highlight' : undefined}>
              <td>
                silver.{table}/{r.id}
              </td>
              <td>
                <span className="muted small">{r.codeSystem}</span> <code>{r.code ?? '—'}</code>
              </td>
              <td>{r.display ?? '—'}</td>
              <td>{r.status ?? '—'}</td>
              <td className="wrap">{r.value ?? '—'}</td>
              <td>{r.instant ? fmtDateTime(r.instant) : fmtDate(r.date)}</td>
              <td>{r.rawMessageId ? <Link to={`/lineage/bronze/${r.rawMessageId}`}>#{r.rawMessageId}</Link> : <span className="muted">seed</span>}</td>
              <td>
                {r.fhirResourceType && r.fhirId ? (
                  <>
                    <Link to={`/fhir/${r.fhirResourceType}/${encodeURIComponent(r.fhirId)}`}>
                      {r.fhirResourceType}/{r.fhirId}
                    </Link>{' '}
                    {r.usCoreCompliant !== null && (
                      <Badge tone={r.usCoreCompliant ? 'good' : 'bad'}>{r.usCoreCompliant ? 'compliant' : 'not compliant'}</Badge>
                    )}
                  </>
                ) : (
                  <span className="muted">{table === 'claims' ? 'stays in Silver (its diagnosis is a condition)' : 'not built yet'}</span>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}

/**
 * /lineage/silver/:table/:rowId — a Silver row of any table (as lineage refs name them, e.g. silver.allergies/165):
 * looks up its patient and moves on to that patient's page, scrolled to the row.
 */
export function SilverRowRedirect() {
  const { table = '', rowId = '' } = useParams();
  const api = useApi();
  const navigate = useNavigate();
  const owner = useQuery({
    queryKey: ['silver', 'row', table, rowId],
    queryFn: ({ signal }) => api.get<SilverRowOwner>(`/silver/rows/${encodeURIComponent(table)}/${encodeURIComponent(rowId)}`, undefined, signal),
  });
  useEffect(() => {
    if (owner.data) {
      const anchor = table === 'patients' ? '' : `#${rowAnchor(table, owner.data.id)}`;
      navigate(`/lineage/silver/${owner.data.silverPatientId}${anchor}`, { replace: true });
    }
  }, [owner.data, table, navigate]);
  return (
    <QueryState query={owner} context={`Finding silver.${table}/${rowId}`}>
      {() => <Loading label="Opening the Silver patient…" />}
    </QueryState>
  );
}
