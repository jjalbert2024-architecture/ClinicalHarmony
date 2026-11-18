import { useQuery } from '@tanstack/react-query';
import { useEffect } from 'react';
import { Link, useLocation, useNavigate, useParams } from 'react-router';
import type { SilverPatientDetail, SilverRow, SilverRowKind, SilverRowOwner } from '../../api/types';
import { useApi } from '../../app/context';
import { Badge, Empty, Loading, PageHeader, QueryState } from '../../components/common';
import { fmtDate, fmtDateTime, fullName } from '../../format';
import { BronzeTable, VALIDATION_LABELS } from './LineagePage';

const KINDS: { kind: SilverRowKind; title: string; table: string }[] = [
  { kind: 'CONDITION', title: 'Conditions', table: 'conditions' },
  { kind: 'OBSERVATION', title: 'Observations', table: 'observations' },
  { kind: 'MEDICATION', title: 'Medications', table: 'medications' },
  { kind: 'ALLERGY', title: 'Allergies', table: 'allergies' },
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
  // Arriving from a lineage ref (#allergies-165): bring that row into view once it's rendered.
  useEffect(() => {
    if (detail.data && hash) document.getElementById(hash.slice(1))?.scrollIntoView?.({ block: 'center' });
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

            {KINDS.map(({ kind, title, table }) => (
              <RowsCard key={kind} title={title} table={table} rows={d.rows.filter((r) => r.kind === kind)} highlight={hash.slice(1)} />
            ))}

            <section className="card">
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

function RowsCard({ title, table, rows, highlight }: { title: string; table: string; rows: SilverRow[]; highlight: string }) {
  return (
    <section className="card">
      <h2>
        {title} <span className="muted small">({rows.length})</span>
      </h2>
      {rows.length === 0 ? (
        <Empty>None.</Empty>
      ) : (
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
                    <span className="muted">not built yet</span>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
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
