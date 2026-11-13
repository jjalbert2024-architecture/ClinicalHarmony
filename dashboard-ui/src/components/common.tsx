import type { ReactNode } from 'react';
import type { Page } from '../api/types';
import { fmtNumber } from '../format';
import { ProblemView } from './ProblemView';

type Tone = 'good' | 'warn' | 'bad' | 'info' | 'muted';

const TONES: Record<string, Tone> = {
  UP: 'good', MATCHED: 'good', COMPLETED: 'good', ACCEPTED: 'good', PROCESSED: 'good', COMPLIANT: 'good',
  PROVISIONAL: 'warn', PENDING: 'warn', WARNING: 'warn', DEGRADED: 'warn', PROCESSING: 'warn',
  MERGED: 'muted', SUPERSEDED: 'muted', AUTO_MERGED: 'info', INFO: 'info',
  DOWN: 'bad', ERROR: 'bad', FAILED: 'bad', QUARANTINED: 'bad', REJECTED: 'bad', NON_COMPLIANT: 'bad',
};

export function Badge({ value, tone, children }: { value?: string | null; tone?: Tone; children?: ReactNode }) {
  const t = tone ?? (value ? TONES[value.toUpperCase()] : undefined) ?? 'muted';
  return <span className={`badge badge-${t}`}>{children ?? value ?? '—'}</span>;
}

export function Loading({ label = 'Loading…' }: { label?: string }) {
  return (
    <div className="loading" role="status">
      {label}
    </div>
  );
}

export function Empty({ children }: { children: ReactNode }) {
  return <div className="empty">{children}</div>;
}

/** Standard rendering of a query: spinner, problem (with retry), or the content. */
export function QueryState<T>({
  query,
  context,
  children,
}: {
  query: { isPending: boolean; error: unknown; data: T | undefined; refetch: () => unknown };
  context?: string;
  children: (data: T) => ReactNode;
}) {
  if (query.error) return <ProblemView error={query.error} context={context} onRetry={() => void query.refetch()} />;
  if (query.isPending || query.data === undefined) return <Loading />;
  return <>{children(query.data)}</>;
}

export function Pager<T>({ page, onPage }: { page: Page<T>; onPage: (p: number) => void }) {
  if (page.total === 0) return null;
  const from = page.page * page.size + 1;
  const to = Math.min(page.total, from + page.items.length - 1);
  return (
    <div className="pager">
      <span>
        {fmtNumber(from)}–{fmtNumber(to)} of {fmtNumber(page.total)}
      </span>
      <button className="btn btn-small" disabled={page.page <= 0} onClick={() => onPage(page.page - 1)}>
        Previous
      </button>
      <button className="btn btn-small" disabled={page.page + 1 >= page.totalPages} onClick={() => onPage(page.page + 1)}>
        Next
      </button>
    </div>
  );
}

export function PageHeader({ title, subtitle, actions }: { title: string; subtitle?: ReactNode; actions?: ReactNode }) {
  return (
    <div className="page-header">
      <div>
        <h1>{title}</h1>
        {subtitle && <p className="subtitle">{subtitle}</p>}
      </div>
      {actions && <div className="page-actions">{actions}</div>}
    </div>
  );
}

export function Stat({ label, value, hint }: { label: string; value: ReactNode; hint?: ReactNode }) {
  return (
    <div className="stat">
      <div className="stat-label">{label}</div>
      <div className="stat-value">{value}</div>
      {hint && <div className="stat-hint">{hint}</div>}
    </div>
  );
}

/** A horizontal proportion bar (no chart library): `value` of `max`. */
export function Bar({ value, max, tone = 'good', label }: { value: number; max: number; tone?: Tone; label: string }) {
  const pct = max > 0 ? Math.max(0, Math.min(100, (value / max) * 100)) : 0;
  return (
    <div className="bar" role="meter" aria-label={label} aria-valuemin={0} aria-valuemax={max} aria-valuenow={value}>
      <div className={`bar-fill bar-${tone}`} style={{ width: `${pct}%` }} />
    </div>
  );
}

export function JsonView({ value }: { value: unknown }) {
  const text = JSON.stringify(value, null, 2);
  return (
    <div className="json">
      <button className="btn btn-small json-copy" onClick={() => void navigator.clipboard?.writeText(text)}>
        Copy JSON
      </button>
      <pre>{text}</pre>
    </div>
  );
}
