import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import type { MatchCandidate, MatchRun, RunResult } from '../../api/types';
import { useApi } from '../../app/context';
import { Badge, Empty, PageHeader, QueryState } from '../../components/common';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { StewardButton } from '../../components/StewardButton';
import { fmtDateTime, fmtScore } from '../../format';

export const PMI_SERVICE = 'patient-index-service';
const STATUSES = ['PENDING', 'ACCEPTED', 'REJECTED', 'AUTO_MERGED', 'SUPERSEDED', 'ALL'];

export function PmiPage() {
  const api = useApi();
  const queryClient = useQueryClient();
  const [params, setParams] = useSearchParams();
  const status = params.get('status') ?? 'PENDING';
  const [confirmRun, setConfirmRun] = useState(false);
  const [lastResult, setLastResult] = useState<RunResult | null>(null);

  const candidates = useQuery({
    queryKey: ['pmi', 'candidates', status],
    queryFn: ({ signal }) => api.get<MatchCandidate[]>('/pmi/candidates', { status, limit: 200 }, signal),
  });
  const runs = useQuery({
    queryKey: ['pmi', 'runs'],
    queryFn: ({ signal }) => api.get<MatchRun[]>('/pmi/runs', { limit: 10 }, signal),
  });
  const run = useMutation({
    mutationFn: () => api.post<RunResult>('/pmi/match/run'),
    onSuccess: (result) => {
      setLastResult(result);
      void queryClient.invalidateQueries({ queryKey: ['pmi'] });
      void queryClient.invalidateQueries({ queryKey: ['patients'] });
      void queryClient.invalidateQueries({ queryKey: ['patient'] });
      void queryClient.invalidateQueries({ queryKey: ['summary'] });
    },
  });

  return (
    <>
      <PageHeader
        title="PMI Review"
        subtitle="Patient Master Index: matching runs and the queue of pairs that need a human decision."
        actions={
          <StewardButton service={PMI_SERVICE} onClick={() => setConfirmRun(true)}>
            Run matching…
          </StewardButton>
        }
      />
      {lastResult && (
        <div className="banner banner-info" role="status">
          Run #{lastResult.run.id} <Badge value={lastResult.run.status} />: {lastResult.run.pairsCompared} pair(s) compared,{' '}
          {lastResult.run.autoMerges} auto-merged, {lastResult.run.queuedForReview} queued for review,{' '}
          {lastResult.run.confirmedSingletons} confirmed as unique.
          {lastResult.skipped.length > 0 && <> Skipped: {lastResult.skipped.join('; ')}</>}
        </div>
      )}
      <section className="card">
        <div className="card-head">
          <h2>Review queue</h2>
          <label>
            Status{' '}
            <select value={status} onChange={(e) => setParams(e.target.value === 'PENDING' ? {} : { status: e.target.value })}>
              {STATUSES.map((s) => (
                <option key={s} value={s}>
                  {s}
                </option>
              ))}
            </select>
          </label>
        </div>
        <QueryState query={candidates} context="Loading the review queue">
          {(items) =>
            items.length === 0 ? (
              <Empty>{status === 'PENDING' ? 'Nothing waiting for review.' : `No ${status} candidates.`}</Empty>
            ) : (
              <table className="table">
                <thead>
                  <tr>
                    <th>Candidate</th>
                    <th>Masters</th>
                    <th className="num">Score</th>
                    <th>Method</th>
                    <th>Status</th>
                    <th>Created</th>
                    <th>Reviewed</th>
                  </tr>
                </thead>
                <tbody>
                  {items.map((c) => (
                    <tr key={c.id}>
                      <td>
                        <Link to={`/pmi/candidates/${c.id}`}>#{c.id}</Link>
                      </td>
                      <td>
                        <Link to={`/patients/${c.masterAId}`}>#{c.masterAId}</Link> ↔ <Link to={`/patients/${c.masterBId}`}>#{c.masterBId}</Link>
                      </td>
                      <td className="num">{fmtScore(c.score)}</td>
                      <td>
                        {c.matchMethod}
                        {c.deterministicRule && <div className="muted">{c.deterministicRule}</div>}
                      </td>
                      <td>
                        <Badge value={c.status} />
                      </td>
                      <td>{fmtDateTime(c.createdAt)}</td>
                      <td className="muted">{c.reviewedBy ? `${c.reviewedBy} · ${fmtDateTime(c.reviewedAt)}` : '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )
          }
        </QueryState>
      </section>
      <section className="card">
        <h2>Recent matching runs</h2>
        <QueryState query={runs} context="Loading matching runs">
          {(items) =>
            items.length === 0 ? (
              <Empty>No runs yet.</Empty>
            ) : (
              <table className="table">
                <thead>
                  <tr>
                    <th>Run</th>
                    <th>Status</th>
                    <th>Triggered by</th>
                    <th>Started</th>
                    <th className="num">Provisional</th>
                    <th className="num">Pairs</th>
                    <th className="num">Auto-merged</th>
                    <th className="num">To review</th>
                    <th className="num">Confirmed</th>
                  </tr>
                </thead>
                <tbody>
                  {items.map((r) => (
                    <tr key={r.id}>
                      <td>#{r.id}</td>
                      <td>
                        <Badge value={r.status} />
                        {r.errorMessage && <div className="muted">{r.errorMessage}</div>}
                      </td>
                      <td>{r.triggeredBy}</td>
                      <td>{fmtDateTime(r.startedAt)}</td>
                      <td className="num">{r.provisionalMasters}</td>
                      <td className="num">{r.pairsCompared}</td>
                      <td className="num">{r.autoMerges}</td>
                      <td className="num">{r.queuedForReview}</td>
                      <td className="num">{r.confirmedSingletons}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )
          }
        </QueryState>
      </section>
      {confirmRun && (
        <ConfirmDialog
          title="Run patient matching?"
          confirmLabel="Run matching"
          onConfirm={() => run.mutateAsync()}
          onClose={() => setConfirmRun(false)}
        >
          <p>The PMI compares every pair that involves a provisional master.</p>
          <ul>
            <li>Pairs at or above the auto-merge threshold are merged right away (into the older master).</li>
            <li>Pairs in the review band are queued here for a steward's decision.</li>
            <li>Remaining provisional masters are confirmed as unique patients.</li>
          </ul>
          <p className="muted">Merges can be undone later from the patient's merge history.</p>
        </ConfirmDialog>
      )}
    </>
  );
}
