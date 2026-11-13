import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router';
import type { MasterDetail, MergeEvent, Timeline, TimelineKind } from '../../api/types';
import { useApi } from '../../app/context';
import { Badge, Empty, PageHeader, QueryState } from '../../components/common';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { StewardButton } from '../../components/StewardButton';
import { fhirRoute, fmtDate, fmtDateTime, fmtScore, fullName } from '../../format';
import { eventDate, kindLabel, TimelineStrip } from './Timeline';

const KINDS: TimelineKind[] = ['CONDITION', 'OBSERVATION', 'MEDICATION'];
const PMI = 'patient-index-service';

export function PatientDetailPage() {
  const { masterId = '' } = useParams();
  const api = useApi();
  const detail = useQuery({
    queryKey: ['patient', masterId],
    queryFn: ({ signal }) => api.get<MasterDetail>(`/patients/${encodeURIComponent(masterId)}`, undefined, signal),
  });

  return (
    <QueryState query={detail} context={`Loading master patient #${masterId}`}>
      {(d) => (
        <>
          <PageHeader
            title={fullName(d.master.firstName, d.master.lastName)}
            subtitle={
              <>
                Master #{d.master.id} · born {fmtDate(d.master.dateOfBirth)} · {d.master.gender ?? 'sex unknown'} ·{' '}
                <Badge value={d.master.matchStatus} /> {!d.master.active && <Badge tone="muted">inactive</Badge>}
              </>
            }
            actions={
              <Link className="btn" to={`/lineage?masterPatientId=${d.master.id}`}>
                Lineage
              </Link>
            }
          />
          {d.mergedIntoMasterId !== null && (
            <div className="banner banner-info">
              This master was merged into <Link to={`/patients/${d.mergedIntoMasterId}`}>master #{d.mergedIntoMasterId}</Link>; its records
              live there now.
            </div>
          )}
          {d.pendingReviews > 0 && (
            <div className="banner banner-warn">
              {d.pendingReviews} PMI review{d.pendingReviews === 1 ? '' : 's'} pending for this patient —{' '}
              <Link to="/pmi">open the review queue</Link>.
            </div>
          )}
          <div className="grid-2">
            <SourcesCard detail={d} />
            <FhirCountsCard detail={d} />
          </div>
          <MergesCard detail={d} />
          <TimelineCard masterId={d.master.id} />
        </>
      )}
    </QueryState>
  );
}

function SourcesCard({ detail }: { detail: MasterDetail }) {
  return (
    <section className="card">
      <h2>Source records ({detail.sources.length})</h2>
      <table className="table">
        <thead>
          <tr>
            <th>Source system</th>
            <th>Source id / MRN</th>
            <th>Name</th>
            <th>DOB</th>
            <th>Contact</th>
            <th>Bronze</th>
          </tr>
        </thead>
        <tbody>
          {detail.sources.map((s) => (
            <tr key={s.silverPatientId}>
              <td>{s.sourceSystem}</td>
              <td>
                {s.sourcePatientId}
                {s.mrn && <div className="muted">MRN {s.mrn}</div>}
              </td>
              <td>{fullName(s.firstName, s.lastName)}</td>
              <td>{fmtDate(s.dateOfBirth)}</td>
              <td className="muted">
                {[s.addressLine1, s.city, s.state, s.postalCode].filter(Boolean).join(', ') || '—'}
                {s.phone && <div>{s.phone}</div>}
              </td>
              <td>{s.rawMessageId ? <Link to={`/lineage/bronze/${s.rawMessageId}`}>#{s.rawMessageId}</Link> : <span className="muted">seed</span>}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}

function FhirCountsCard({ detail }: { detail: MasterDetail }) {
  return (
    <section className="card">
      <h2>Gold FHIR resources</h2>
      {detail.fhirResources.length === 0 ? (
        <Empty>No FHIR resources linked to this master yet.</Empty>
      ) : (
        <table className="table">
          <thead>
            <tr>
              <th>Type</th>
              <th className="num">Total</th>
              <th className="num">US Core compliant</th>
            </tr>
          </thead>
          <tbody>
            {detail.fhirResources.map((r) => (
              <tr key={r.resourceType}>
                <td>
                  <Link to={`/fhir?type=${r.resourceType}&masterPatientId=${detail.master.id}`}>{r.resourceType}</Link>
                </td>
                <td className="num">{r.total}</td>
                <td className="num">
                  {r.usCoreCompliant}
                  {r.usCoreCompliant < r.total && <Badge tone="bad">{r.total - r.usCoreCompliant} not</Badge>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}

function MergesCard({ detail }: { detail: MasterDetail }) {
  const [unmerging, setUnmerging] = useState<MergeEvent | null>(null);
  const api = useApi();
  const queryClient = useQueryClient();
  const unmerge = useMutation({
    mutationFn: ({ mergeId, reason }: { mergeId: number; reason: string }) =>
      api.post(`/pmi/merges/${mergeId}/unmerge`, { reason }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['patient'] });
      void queryClient.invalidateQueries({ queryKey: ['patients'] });
      void queryClient.invalidateQueries({ queryKey: ['timeline'] });
      void queryClient.invalidateQueries({ queryKey: ['summary'] });
    },
  });

  if (detail.merges.length === 0) return null;
  return (
    <section className="card">
      <h2>Merge history</h2>
      <table className="table">
        <thead>
          <tr>
            <th>Merge</th>
            <th>Survivor ← absorbed</th>
            <th>Method</th>
            <th className="num">Score</th>
            <th>Decided</th>
            <th>Status</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {detail.merges.map((m) => (
            <tr key={m.mergeId}>
              <td>#{m.mergeId}</td>
              <td>
                <Link to={`/patients/${m.survivorMasterId}`}>#{m.survivorMasterId}</Link> ←{' '}
                <Link to={`/patients/${m.absorbedMasterId}`}>#{m.absorbedMasterId}</Link>
                <div className="muted">{m.movedSilverPatients} source record(s) moved</div>
              </td>
              <td>{m.matchMethod}</td>
              <td className="num">{fmtScore(m.score)}</td>
              <td>
                {m.decidedBy}
                <div className="muted">{fmtDateTime(m.mergedAt)}</div>
              </td>
              <td>
                {m.unmergedAt ? (
                  <>
                    <Badge tone="muted">Unmerged</Badge>
                    <div className="muted">
                      by {m.unmergedBy} {fmtDateTime(m.unmergedAt)}
                      {m.unmergeReason && <> — “{m.unmergeReason}”</>}
                    </div>
                  </>
                ) : (
                  <Badge tone="good">Live</Badge>
                )}
              </td>
              <td>
                {!m.unmergedAt && m.survivorMasterId === detail.master.id && (
                  <StewardButton service={PMI} className="btn btn-small btn-danger" onClick={() => setUnmerging(m)}>
                    Unmerge…
                  </StewardButton>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {unmerging && (
        <ConfirmDialog
          title={`Unmerge merge #${unmerging.mergeId}?`}
          confirmLabel="Unmerge"
          tone="danger"
          text={{ label: 'Reason', required: true, placeholder: 'Why these are different people' }}
          onConfirm={(reason) => unmerge.mutateAsync({ mergeId: unmerging.mergeId, reason })}
          onClose={() => setUnmerging(null)}
        >
          <p>
            Master #{unmerging.absorbedMasterId} is restored and its {unmerging.movedSilverPatients} source record(s) and their FHIR resources
            move back off master #{unmerging.survivorMasterId}.
          </p>
          <p>The PMI also records these records as “not the same person”, so future matching runs won't merge them again.</p>
        </ConfirmDialog>
      )}
    </section>
  );
}

function TimelineCard({ masterId }: { masterId: number }) {
  const api = useApi();
  const [params, setParams] = useSearchParams();
  const types = (params.get('types')?.split(',').filter(Boolean) ?? KINDS) as TimelineKind[];
  const from = params.get('from') ?? '';
  const to = params.get('to') ?? '';
  const timeline = useQuery({
    queryKey: ['timeline', masterId, types.join(','), from, to],
    queryFn: ({ signal }) => api.get<Timeline>(`/patients/${masterId}/timeline`, { types, from, to }, signal),
    enabled: types.length > 0,
  });

  function set(key: string, value: string | null) {
    const next = new URLSearchParams(params);
    if (value) next.set(key, value);
    else next.delete(key);
    setParams(next, { replace: true });
  }
  function toggleKind(kind: TimelineKind) {
    const nextKinds = types.includes(kind) ? types.filter((k) => k !== kind) : [...types, kind];
    set('types', nextKinds.length === KINDS.length ? null : nextKinds.join(','));
  }

  return (
    <section className="card">
      <h2>Clinical timeline</h2>
      <div className="filters inline">
        {KINDS.map((k) => (
          <label key={k} className={`check chip chip-${k.toLowerCase()}`}>
            <input type="checkbox" checked={types.includes(k)} onChange={() => toggleKind(k)} />
            {kindLabel(k)}s
          </label>
        ))}
        <label>
          From <input type="date" value={from} onChange={(e) => set('from', e.target.value)} />
        </label>
        <label>
          To <input type="date" value={to} onChange={(e) => set('to', e.target.value)} />
        </label>
      </div>
      {types.length === 0 ? (
        <Empty>Pick at least one event type.</Empty>
      ) : (
        <QueryState query={timeline} context="Loading the timeline">
          {(t) =>
            t.events.length === 0 ? (
              <Empty>No events in this range.</Empty>
            ) : (
              <>
                <TimelineStrip events={t.events} />
                <ol className="timeline">
                  {t.events.map((e) => {
                    const fhir = fhirRoute(e.fhirReference);
                    return (
                      <li key={`${e.kind}-${e.id}`} className={`event event-${e.kind.toLowerCase()}`}>
                        <div className="event-date">{eventDate(e)}</div>
                        <div className="event-body">
                          <div className="event-title">
                            <Badge tone="info">{kindLabel(e.kind)}</Badge> <strong>{e.display ?? '(no display)'}</strong>
                            {e.value && <span className="event-value"> {e.value}</span>}
                          </div>
                          <div className="event-meta">
                            <span>
                              {e.codeSystem ?? '—'} <code>{e.code || '(blank)'}</code>{' '}
                              {e.codeKnown ? <Badge tone="good">known code</Badge> : <Badge tone="bad">not in reference set</Badge>}
                            </span>
                            {e.status && <span>status {e.status}</span>}
                            {e.endedAt && <span>ended {fmtDate(e.endedAt)}</span>}
                            <span>from {e.sourceSystem}</span>
                            {e.rawMessageId && <Link to={`/lineage/bronze/${e.rawMessageId}`}>Bronze #{e.rawMessageId}</Link>}
                            {fhir ? (
                              <Link to={fhir}>
                                {e.fhirReference} {e.usCoreCompliant === false && <Badge tone="bad">non-compliant</Badge>}
                              </Link>
                            ) : (
                              <span className="muted">no Gold resource yet</span>
                            )}
                          </div>
                        </div>
                      </li>
                    );
                  })}
                </ol>
              </>
            )
          }
        </QueryState>
      )}
    </section>
  );
}
