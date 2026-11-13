import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link, useParams } from 'react-router';
import type { CandidateView, PmiPatientRecord } from '../../api/types';
import { useApi } from '../../app/context';
import { Badge, PageHeader, QueryState } from '../../components/common';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { StewardButton } from '../../components/StewardButton';
import { fmtDate, fmtDateTime, fmtScore, fullName } from '../../format';
import { PMI_SERVICE } from './PmiPage';

type Decision = 'accept' | 'reject';

const FIELDS: { label: string; get: (r: PmiPatientRecord) => string | null }[] = [
  { label: 'Source', get: (r) => `${r.sourceSystem} · ${r.sourcePatientId}` },
  { label: 'MRN', get: (r) => r.mrn },
  { label: 'Name', get: (r) => fullName(r.firstName, r.lastName) },
  { label: 'Date of birth', get: (r) => (r.dateOfBirth ? fmtDate(r.dateOfBirth) : null) },
  { label: 'Sex', get: (r) => r.gender },
  { label: 'Address', get: (r) => [r.addressLine1, r.city, r.state, r.postalCode].filter(Boolean).join(', ') || null },
  { label: 'Phone', get: (r) => r.phone },
  { label: 'Master', get: (r) => (r.masterPatientId ? `#${r.masterPatientId} (${r.masterStatus ?? '?'})` : null) },
];

export function CandidatePage() {
  const { candidateId = '' } = useParams();
  const api = useApi();
  const queryClient = useQueryClient();
  const [deciding, setDeciding] = useState<Decision | null>(null);
  const view = useQuery({
    queryKey: ['pmi', 'candidate', candidateId],
    queryFn: ({ signal }) => api.get<CandidateView>(`/pmi/candidates/${encodeURIComponent(candidateId)}`, undefined, signal),
  });
  const decide = useMutation({
    mutationFn: ({ decision, note }: { decision: Decision; note: string }) =>
      // reviewer is NOT sent: the gateway records the signed-in user from the token.
      api.post(`/pmi/candidates/${encodeURIComponent(candidateId)}/${decision}`, note ? { note } : {}),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['pmi'] });
      void queryClient.invalidateQueries({ queryKey: ['patients'] });
      void queryClient.invalidateQueries({ queryKey: ['patient'] });
      void queryClient.invalidateQueries({ queryKey: ['summary'] });
    },
  });

  return (
    <QueryState query={view} context={`Loading candidate #${candidateId}`}>
      {({ candidate: c, recordA, recordB }) => (
        <>
          <PageHeader
            title={`Match candidate #${c.id}`}
            subtitle={
              <>
                Score {fmtScore(c.score)} · {c.matchMethod}
                {c.deterministicRule ? ` (${c.deterministicRule})` : ''} · run #{c.runId} · <Badge value={c.status} />
              </>
            }
            actions={
              c.status === 'PENDING' && (
                <>
                  <StewardButton service={PMI_SERVICE} className="btn btn-danger" onClick={() => setDeciding('reject')}>
                    Not the same person…
                  </StewardButton>
                  <StewardButton service={PMI_SERVICE} onClick={() => setDeciding('accept')}>
                    Same person — merge…
                  </StewardButton>
                </>
              )
            }
          />
          {c.status !== 'PENDING' && (
            <div className="banner banner-info">
              {c.status} {c.reviewedBy && <>by {c.reviewedBy} · {fmtDateTime(c.reviewedAt)}</>}
              {c.reviewNote && <> — “{c.reviewNote}”</>}
              {c.mergeId && <> · merge #{c.mergeId}</>}
            </div>
          )}
          <section className="card">
            <h2>Side by side</h2>
            <table className="table compare">
              <thead>
                <tr>
                  <th />
                  <th>
                    Record A · <Link to={`/patients/${c.masterAId}`}>master #{c.masterAId}</Link>
                  </th>
                  <th>
                    Record B · <Link to={`/patients/${c.masterBId}`}>master #{c.masterBId}</Link>
                  </th>
                </tr>
              </thead>
              <tbody>
                {FIELDS.map((f) => {
                  const a = f.get(recordA);
                  const b = f.get(recordB);
                  const differs = a !== null && b !== null && a.toLowerCase() !== b.toLowerCase();
                  return (
                    <tr key={f.label} className={differs ? 'differs' : ''}>
                      <th>{f.label}</th>
                      <td>{a ?? <span className="muted">missing</span>}</td>
                      <td>{b ?? <span className="muted">missing</span>}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </section>
          {c.breakdown && (
            <section className="card">
              <h2>How the score was reached</h2>
              <p className="muted">
                Decision {c.breakdown.decision}
                {c.breakdown.maxWeight ? ` · ${c.breakdown.totalWeight ?? '?'} of ${c.breakdown.maxWeight} weight` : ''}. Missing values
                count neither for nor against.
              </p>
              <table className="table">
                <thead>
                  <tr>
                    <th>Field</th>
                    <th>Outcome</th>
                    <th>A</th>
                    <th>B</th>
                    <th className="num">Similarity</th>
                    <th className="num">Weight</th>
                  </tr>
                </thead>
                <tbody>
                  {c.breakdown.fields.map((f) => (
                    <tr key={f.field}>
                      <td>{f.field}</td>
                      <td>
                        <Badge tone={f.outcome === 'DISAGREE' ? 'bad' : f.outcome === 'MISSING' ? 'muted' : f.outcome === 'EXACT' ? 'good' : 'warn'}>
                          {f.outcome}
                        </Badge>
                      </td>
                      <td>{f.valueA ?? '—'}</td>
                      <td>{f.valueB ?? '—'}</td>
                      <td className="num">{f.similarity !== undefined ? f.similarity.toFixed(3) : '—'}</td>
                      <td className="num">
                        {f.weight} / {f.maxWeight}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </section>
          )}
          {deciding && (
            <ConfirmDialog
              title={deciding === 'accept' ? 'Merge these two patients?' : 'Mark as different people?'}
              confirmLabel={deciding === 'accept' ? 'Merge' : 'Not the same person'}
              tone={deciding === 'accept' ? 'primary' : 'danger'}
              text={{
                label: 'Note',
                required: deciding === 'reject',
                placeholder: deciding === 'accept' ? 'e.g. confirmed by phone' : 'Why they are different people',
              }}
              onConfirm={(note) => decide.mutateAsync({ decision: deciding, note })}
              onClose={() => setDeciding(null)}
            >
              {deciding === 'accept' ? (
                <p>
                  Masters #{c.masterAId} and #{c.masterBId} become one (the older survives). Their source records and FHIR resources move
                  to the survivor. A steward can unmerge it later.
                </p>
              ) : (
                <p>
                  The pair is excluded from all future matching runs. Any provisional master with nothing else pending is confirmed as
                  unique.
                </p>
              )}
              <p className="muted">Recorded as your decision, under your username.</p>
            </ConfirmDialog>
          )}
        </>
      )}
    </QueryState>
  );
}
