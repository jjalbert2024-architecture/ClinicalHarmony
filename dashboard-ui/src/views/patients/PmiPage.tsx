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
const STATUSES = ['PENDING', 'ACCEPTED', 'REJECTED', 'AUTO_MERGED', 'SUPERSEDED', 'DECIDED', 'QUEUED', 'ALL'];
const STATUS_LABELS: Record<string, string> = {
  DECIDED: 'DECIDED (accepted + rejected)',
  QUEUED: 'QUEUED (sent to review)',
};

export function PmiPage() {
  const api = useApi();
  const queryClient = useQueryClient();
  const [params, setParams] = useSearchParams();
  const status = params.get('status') ?? 'PENDING';
  const runFilter = params.get('run') ?? '';
  const [confirmRun, setConfirmRun] = useState(false);
  const [lastResult, setLastResult] = useState<RunResult | null>(null);

  const candidates = useQuery({
    queryKey: ['pmi', 'candidates', status, runFilter],
    queryFn: ({ signal }) => api.get<MatchCandidate[]>('/pmi/candidates', { status, runId: runFilter, limit: 200 }, signal),
  });
  const decided = useQuery({
    queryKey: ['pmi', 'decided'],
    queryFn: ({ signal }) => api.get<MatchCandidate[]>('/pmi/candidates', { status: 'DECIDED', limit: 10 }, signal),
  });

  function setStatus(next: string) {
    const p = new URLSearchParams(params);
    if (next === 'PENDING') p.delete('status');
    else p.set('status', next);
    setParams(p);
  }
  function clearRun() {
    const p = new URLSearchParams(params);
    p.delete('run');
    setParams(p);
  }
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
      <section className="card" id={QUEUE_ID}>
        <div className="card-head">
          <h2 id={`${QUEUE_ID}-heading`} tabIndex={-1}>
            Review queue
          </h2>
          <label>
            Status{' '}
            <select value={status} onChange={(e) => setStatus(e.target.value)}>
              {STATUSES.map((s) => (
                <option key={s} value={s}>
                  {STATUS_LABELS[s] ?? s}
                </option>
              ))}
            </select>
          </label>
        </div>
        {runFilter && (
          <div className="active-filters">
            <button className="chip" onClick={clearRun}>
              run #{runFilter} ×
            </button>
          </div>
        )}
        <p className="muted small">
          {status === 'PENDING' ? 'Highest score first.' : 'Newest decision first.'}
        </p>
        <QueryState query={candidates} context="Loading the review queue">
          {(items) =>
            items.length === 0 ? (
              <Empty>
                {status === 'PENDING' && !runFilter ? 'Nothing waiting for review.' : `No ${status} candidates${runFilter ? ` from run #${runFilter}` : ''}.`}
              </Empty>
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
        <h2>Recently decided</h2>
        <QueryState query={decided} context="Loading recent decisions">
          {(items) =>
            items.length === 0 ? (
              <Empty>No review decisions yet.</Empty>
            ) : (
              <ul className="decision-list" aria-label="Recently decided">
                {items.map((c) => (
                  <li key={c.id}>
                    <div>
                      <Link to={`/pmi/candidates/${c.id}`}>#{c.id}</Link> <Badge value={c.status} />{' '}
                      <Link to={`/patients/${c.masterAId}`}>#{c.masterAId}</Link> ↔ <Link to={`/patients/${c.masterBId}`}>#{c.masterBId}</Link>{' '}
                      <span className="muted">· score {fmtScore(c.score)}</span>
                    </div>
                    <div className="muted small">
                      {c.reviewedBy ?? '—'} · {fmtDateTime(c.reviewedAt)}
                      {c.reviewNote ? <> · “{c.reviewNote}”</> : null}
                    </div>
                  </li>
                ))}
              </ul>
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
                      <td className="num">
                        <RunCount count={r.autoMerges} to={`/pmi?status=AUTO_MERGED&run=${r.id}`} label={`Auto-merged candidates of run #${r.id}`} />
                      </td>
                      <td className="num">
                        <RunCount count={r.queuedForReview} to={`/pmi?status=QUEUED&run=${r.id}`} label={`Review candidates of run #${r.id}`} />
                      </td>
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

/** A run's count, linking to that run's candidates when there are any. */
const QUEUE_ID = 'review-queue';

/**
 * The review queue is the top card and the runs table the bottom one: without this, clicking a run's count
 * changed the queue off screen and looked like nothing happened. Scroll there and move focus to its heading.
 */
function showQueue() {
  const section = document.getElementById(QUEUE_ID);
  if (!section) return;
  // The top bar is sticky: land the card just below it, not underneath it (scrollIntoView did — the heading and
  // the run chip were hidden and only "a bit of scrolling" was visible).
  const bar = document.querySelector('.topbar')?.getBoundingClientRect().height ?? 0;
  window.scrollTo({ top: Math.max(0, window.scrollY + section.getBoundingClientRect().top - bar - 12), behavior: 'smooth' });
  document.getElementById(`${QUEUE_ID}-heading`)?.focus({ preventScroll: true });
}

function RunCount({ count, to, label }: { count: number; to: string; label: string }) {
  return count > 0 ? (
    <Link to={to} aria-label={label} title={label} onClick={showQueue}>
      {count}
    </Link>
  ) : (
    <>{count}</>
  );
}
