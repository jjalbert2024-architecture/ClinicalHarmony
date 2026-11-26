import { useEffect, useState, type FormEvent, type ReactNode } from 'react';
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

/** Rows per page every paged list offers (the gateway allows up to 200). */
export const PAGE_SIZES = [15, 25, 50, 100] as const;

/**
 * Paging for every list: "1–15 of 173", the first and last record on screen ({@code idOf}), First / Previous /
 * go-to-page / Next / Last, and rows per page ({@code onSize}).
 */
export function Pager<T>({
  page,
  onPage,
  onSize,
  idOf,
}: {
  page: Page<T>;
  onPage: (p: number) => void;
  onSize?: (size: number) => void;
  idOf?: (item: T) => string;
}) {
  const [jump, setJump] = useState('');
  if (page.total === 0) return null;
  const from = page.page * page.size + 1;
  const to = Math.min(page.total, from + page.items.length - 1);
  const lastPage = Math.max(0, page.totalPages - 1);
  const firstId = idOf && page.items.length > 0 ? idOf(page.items[0]!) : null;
  const lastId = idOf && page.items.length > 0 ? idOf(page.items[page.items.length - 1]!) : null;
  function go(e: FormEvent) {
    e.preventDefault();
    const n = Number.parseInt(jump, 10);
    if (Number.isFinite(n)) onPage(Math.min(lastPage, Math.max(0, n - 1)));
    setJump('');
  }
  return (
    <div className="pager">
      <span>
        {fmtNumber(from)}–{fmtNumber(to)} of {fmtNumber(page.total)}
        {firstId !== null && (
          <span className="pager-ids" title="First and last record on this page">
            {' · '}
            {firstId === lastId ? firstId : `${firstId} → ${lastId}`}
          </span>
        )}
      </span>
      {onSize && (
        <label className="pager-size">
          Rows
          <select aria-label="Rows per page" value={page.size} onChange={(e) => onSize(Number(e.target.value))}>
            {PAGE_SIZES.map((n) => (
              <option key={n} value={n}>
                {n}
              </option>
            ))}
          </select>
        </label>
      )}
      <button className="btn btn-small" aria-label="First page" disabled={page.page <= 0} onClick={() => onPage(0)}>
        First
      </button>
      <button className="btn btn-small" disabled={page.page <= 0} onClick={() => onPage(page.page - 1)}>
        Previous
      </button>
      {page.totalPages > 1 && (
        <form className="pager-jump" onSubmit={go}>
          Page
          <input
            aria-label="Go to page"
            inputMode="numeric"
            value={jump}
            placeholder={String(page.page + 1)}
            onChange={(e) => setJump(e.target.value.replace(/[^0-9]/g, ''))}
            size={3}
          />
          of {fmtNumber(page.totalPages)}
        </form>
      )}
      <button className="btn btn-small" disabled={page.page + 1 >= page.totalPages} onClick={() => onPage(page.page + 1)}>
        Next
      </button>
      <button className="btn btn-small" aria-label="Last page" disabled={page.page >= lastPage} onClick={() => onPage(lastPage)}>
        Last
      </button>
    </div>
  );
}

/**
 * The list at a glance, ONE line next to the search box: "Bronze messages | Total # messages: 1,039 | Range: #1
 * (oldest) → #1148 (newest)". The range is the lowest / highest number in the WHOLE result (not just this page), so the
 * user knows which numbers to type. Numbers can have gaps (a skipped duplicate still uses one), so the count can be
 * smaller than the span. What is on this page is the pager's job, below the list.
 */
export function ListSummary<T>({
  page,
  title,
  noun,
  unit,
}: {
  page: Page<T> | undefined;
  title?: string;
  noun: string;
  unit?: string;
}) {
  const span = unit && page && page.total > 0 && page.minId != null && page.maxId != null;
  return (
    <div className="list-summary-line">
      {title && <h2>{title}</h2>}
      {page && (
        <p className="list-summary" aria-label="List summary">
          {title && <span className="sep">{'| '}</span>}
          Total # {noun}: <strong>{fmtNumber(page.total)}</strong>
          {span && (
            <span title={`Lowest and highest ${unit} number in this result (numbers can have gaps): type one between them in the search or range boxes`}>
              <span className="sep">{' | '}</span>
              Range: <strong>#{page.minId}</strong> (oldest) → <strong>#{page.maxId}</strong> (newest)
            </span>
          )}
        </p>
      )}
    </div>
  );
}

/** Order choices: time-ordered lists, and lists of people (name order by default). */
export const TIME_ORDERS = [
  { value: '', label: 'Newest first (highest #)' },
  { value: 'oldest', label: 'Oldest first (lowest #)' },
];
export const PERSON_ORDERS = [
  { value: '', label: 'By name' },
  { value: 'newest', label: 'Newest first (highest #)' },
  { value: 'oldest', label: 'Oldest first (lowest #)' },
];

export interface ListWindow {
  from: string;
  to: string;
  order: string;
}

/** URL keys of a list's number range / order / rows per page ({@code prefix} keeps two lists on one page apart). */
export function windowKeys(prefix = '') {
  return prefix
    ? { from: `${prefix}From`, to: `${prefix}To`, order: `${prefix}Order`, size: `${prefix}Size` }
    : { from: 'idFrom', to: 'idTo', order: 'order', size: 'size' };
}

/** Reads a list's window from the URL, as the gateway parameters plus the page size. */
export function readWindow(params: URLSearchParams, prefix = '', defaultSize = 15) {
  const keys = windowKeys(prefix);
  const size = Number(params.get(keys.size));
  return {
    window: { from: params.get(keys.from) ?? '', to: params.get(keys.to) ?? '', order: params.get(keys.order) ?? '' },
    size: (PAGE_SIZES as readonly number[]).includes(size) ? size : defaultSize,
    keys,
  };
}

/** The gateway parameters of a window (empty ones are dropped by the API client). */
export const windowParams = (w: ListWindow) => ({ idFrom: w.from, idTo: w.to, order: w.order });

/** The changes that apply a window to the URL, under the list's keys. */
export function windowChanges(keys: ReturnType<typeof windowKeys>, w: ListWindow): Record<string, string> {
  return { [keys.from]: w.from, [keys.to]: w.to, [keys.order]: w.order };
}

/**
 * "<label> from … to …" (Apply / Enter) and the order (applies at once). A "#" or other non-digits typed in a box are
 * ignored, so "#996" works.
 */
export function WindowFilter({
  value,
  label,
  orders = TIME_ORDERS,
  onApply,
}: {
  value: ListWindow;
  label: string;
  orders?: { value: string; label: string }[];
  onApply: (w: ListWindow) => void;
}) {
  const [draft, setDraft] = useState({ from: value.from, to: value.to });
  useEffect(() => setDraft({ from: value.from, to: value.to }), [value.from, value.to]);
  const digits = (s: string) => s.replace(/[^0-9]/g, '').replace(/^0+(?=\d)/, '');
  return (
    <form
      className="filters inline window-filter"
      aria-label={`${label} range and order`}
      onSubmit={(e) => {
        e.preventDefault();
        onApply({ from: digits(draft.from), to: digits(draft.to), order: value.order });
      }}
    >
      <label>
        {label} from
        <input aria-label={`${label} from`} inputMode="numeric" size={8} value={draft.from} onChange={(e) => setDraft({ ...draft, from: e.target.value })} />
      </label>
      <label>
        to
        <input aria-label={`${label} to`} inputMode="numeric" size={8} value={draft.to} onChange={(e) => setDraft({ ...draft, to: e.target.value })} />
      </label>
      <button className="btn btn-small" type="submit">
        Apply
      </button>
      {(value.from || value.to) && (
        <button className="btn-link" type="button" onClick={() => onApply({ from: '', to: '', order: value.order })}>
          Clear range
        </button>
      )}
      <label>
        Order
        <select aria-label="Order" value={value.order} onChange={(e) => onApply({ ...value, order: e.target.value })}>
          {orders.map((o) => (
            <option key={o.value} value={o.value}>
              {o.label}
            </option>
          ))}
        </select>
      </label>
    </form>
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

/** Pretty-printed JSON with Copy and, when {@code downloadName} is given, Download buttons. */
export function JsonView({ value, downloadName, mimeType = 'application/json' }: { value: unknown; downloadName?: string; mimeType?: string }) {
  const text = JSON.stringify(value, null, 2);
  return (
    <div className="json">
      <div className="json-actions">
        <button className="btn btn-small" onClick={() => void navigator.clipboard?.writeText(text)}>
          Copy JSON
        </button>
        {downloadName && (
          <button className="btn btn-small" onClick={() => downloadText(text, downloadName, mimeType)}>
            Download .json
          </button>
        )}
      </div>
      <pre>{text}</pre>
    </div>
  );
}

/** Saves text as a file in the browser (Blob + temporary object URL; nothing leaves the page). */
export function downloadText(text: string, fileName: string, mimeType: string) {
  const url = URL.createObjectURL(new Blob([text], { type: mimeType }));
  const a = document.createElement('a');
  a.href = url;
  a.download = fileName;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}

/** A file-system-safe name: anything but letters, digits, dot, dash, underscore becomes "_". */
export function safeFileName(name: string): string {
  return name.replace(/[^A-Za-z0-9._-]+/g, '_').slice(0, 150);
}
