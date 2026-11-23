import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import type { Bucket, Finding, Page, ValidationSummary } from '../../api/types';
import { useApi } from '../../app/context';
import { Badge, Bar, Empty, PageHeader, Pager, QueryState, Stat } from '../../components/common';
import { fmtDateTime, fmtNumber } from '../../format';

const FILTERS = ['ruleCode', 'severity', 'entityType', 'category', 'patientId', 'resolved'] as const;
type FilterKey = (typeof FILTERS)[number];

/** Chip labels, and the Findings column each filter narrows (highlighted while it's on). */
const FILTER_LABELS: Record<FilterKey, string> = {
  ruleCode: 'Rule',
  severity: 'Severity',
  entityType: 'Entity',
  category: 'Category',
  patientId: 'Patient',
  resolved: 'Status',
};
const FILTER_COLUMN: Record<FilterKey, string> = {
  ruleCode: 'Rule',
  severity: 'Severity',
  entityType: 'Entity',
  category: 'Rule',
  patientId: 'Patient',
  resolved: 'Severity',
};
const COLUMNS = ['Severity', 'Rule', 'Entity', 'Patient', 'Message', 'Bad value → suggestion', 'Validated'];
/** The "By rule" card lists this many rules until "Show all" (it's much longer than the other two). */
const RULES_SHOWN = 6;

export function ValidationPage() {
  const api = useApi();
  const [params, setParams] = useSearchParams();
  const filters = Object.fromEntries(FILTERS.map((k) => [k, params.get(k) ?? ''])) as Record<(typeof FILTERS)[number], string>;
  const page = Number(params.get('page') ?? '0');
  /** Active filters in the order they were applied (URL order: a newly added filter goes to the end). */
  const applied = [...new Set([...params.keys()])].filter((k): k is FilterKey => (FILTERS as readonly string[]).includes(k) && !!params.get(k));
  const filteredColumns = new Set(applied.map((k) => FILTER_COLUMN[k]));

  const summary = useQuery({
    queryKey: ['validation', 'summary'],
    queryFn: ({ signal }) => api.get<ValidationSummary>('/validation/summary', undefined, signal),
  });
  const findings = useQuery({
    queryKey: ['validation', 'findings', filters, page],
    queryFn: ({ signal }) => api.get<Page<Finding>>('/validation/findings', { ...filters, page, size: 25 }, signal),
    placeholderData: keepPreviousData,
  });

  const findingsRef = useRef<HTMLElement>(null);

  /** A click on a summary card filters the Findings table below — and brings it into view, or nothing seems to happen. */
  function pickFromCard(key: string, value: string) {
    setFilter(key, value);
    findingsRef.current?.scrollIntoView?.({ behavior: 'smooth', block: 'start' });
  }

  function setFilter(key: string, value: string) {
    const next = new URLSearchParams(params);
    if (value) next.set(key, value);
    else next.delete(key);
    next.delete('page');
    setParams(next);
  }

  return (
    <>
      <PageHeader
        title="Validation Report"
        subtitle="Findings from code validation (ontology-service), clinical rules (rules-engine-service) and US Core checks (fhir-compliance-service)."
        actions={
          <Link className="btn" to="/validation/rules">
            Rule catalog
          </Link>
        }
      />
      <QueryState query={summary} context="Loading the summary">
        {(s) => (
          <>
            <div className="stats">
              <Stat label="Findings" value={fmtNumber(s.total)} />
              <Stat label="Open" value={fmtNumber(s.open)} />
              <Stat label="Resolved" value={fmtNumber(s.resolved)} />
              <Stat label="Last validated" value={fmtDateTime(s.lastValidatedAt)} />
            </div>
            <div className="grid-3">
              <BucketCard title="By severity" buckets={s.bySeverity} filterKey="severity" onFilter={pickFromCard} active={filters.severity} />
              <BucketCard title="By rule" buckets={s.byRule} filterKey="ruleCode" onFilter={pickFromCard} active={filters.ruleCode} limit={RULES_SHOWN} />
              <BucketCard title="By entity type" buckets={s.byEntityType} filterKey="entityType" onFilter={pickFromCard} active={filters.entityType} />
            </div>
          </>
        )}
      </QueryState>
      <section className="card" ref={findingsRef} aria-label="Findings">
        <div className="card-head">
          <h2>
            Findings{' '}
            {findings.data && (
              <span className="muted small">
                ({fmtNumber(findings.data.total)} {FILTERS.some((k) => filters[k]) ? 'matching' : 'in all'})
              </span>
            )}
          </h2>
          <div className="filters inline">
            {/* Rule and entity type here too, so a filter can be added without scrolling back up to the cards. */}
            <select aria-label="Rule" value={filters.ruleCode} onChange={(e) => setFilter('ruleCode', e.target.value)}>
              <option value="">Any rule</option>
              {(summary.data?.byRule ?? []).map((b) => (
                <option key={b.key} value={b.key}>
                  {b.key}
                </option>
              ))}
            </select>
            <select aria-label="Entity type" value={filters.entityType} onChange={(e) => setFilter('entityType', e.target.value)}>
              <option value="">Any entity type</option>
              {(summary.data?.byEntityType ?? []).map((b) => (
                <option key={b.key} value={b.key}>
                  {b.key}
                </option>
              ))}
            </select>
            <select aria-label="Severity" value={filters.severity} onChange={(e) => setFilter('severity', e.target.value)}>
              <option value="">Any severity</option>
              <option value="ERROR">ERROR</option>
              <option value="WARNING">WARNING</option>
              <option value="INFO">INFO</option>
            </select>
            <select aria-label="Resolved" value={filters.resolved} onChange={(e) => setFilter('resolved', e.target.value)}>
              <option value="">Open and resolved</option>
              <option value="false">Open only</option>
              <option value="true">Resolved only</option>
            </select>
            <input
              aria-label="Patient id"
              placeholder="Patient id"
              inputMode="numeric"
              value={filters.patientId}
              onChange={(e) => setFilter('patientId', e.target.value.replace(/\D/g, ''))}
            />
            {FILTERS.some((k) => filters[k]) && (
              <button className="btn btn-small" onClick={() => setParams({})}>
                Clear filters
              </button>
            )}
          </div>
        </div>
        <ActiveFilters applied={applied} filters={filters} onClear={(k) => setFilter(k, '')} onReset={() => setParams({})} />
        <QueryState query={findings} context="Loading findings">
          {(data) =>
            data.items.length === 0 ? (
              <Empty>No findings match.</Empty>
            ) : (
              <>
                <table className="table">
                  <thead>
                    <tr>
                      {COLUMNS.map((c) => (
                        <th key={c} className={filteredColumns.has(c) ? 'th-filtered' : undefined} title={filteredColumns.has(c) ? 'Filtered' : undefined}>
                          {c}
                          {filteredColumns.has(c) && <span className="sr-only"> (filtered)</span>}
                        </th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {data.items.map((f) => (
                      <tr key={f.id} className={f.resolved ? 'inactive' : ''}>
                        <td className={filteredColumns.has('Severity') ? 'td-filtered' : undefined}>
                          <Badge value={f.severity} />
                          {f.resolved && <Badge tone="muted">resolved</Badge>}
                        </td>
                        <td className={filteredColumns.has('Rule') ? 'td-filtered' : undefined}>
                          <button className="btn-link" onClick={() => setFilter('ruleCode', f.ruleCode)} title={f.ruleName}>
                            {f.ruleCode}
                          </button>
                          <div className="muted">{f.ruleCategory}</div>
                        </td>
                        <td className={filteredColumns.has('Entity') ? 'td-filtered' : undefined}>
                          {f.entityType} #{f.entityId}
                        </td>
                        <td className={filteredColumns.has('Patient') ? 'td-filtered' : undefined}>
                          {f.patientId !== null ? (
                            <button className="btn-link" onClick={() => setFilter('patientId', String(f.patientId))}>
                              {f.patientId}
                            </button>
                          ) : (
                            '—'
                          )}
                        </td>
                        <td className="wrap">{f.message}</td>
                        <td className="wrap">
                          {f.badValue && <code>{f.badValue}</code>}
                          {f.suggestedValue && <div className="muted">→ {f.suggestedValue}</div>}
                        </td>
                        <td>{fmtDateTime(f.validatedAt)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                <Pager
                  page={data}
                  onPage={(p) => {
                    const next = new URLSearchParams(params);
                    next.set('page', String(p));
                    setParams(next);
                  }}
                />
              </>
            )
          }
        </QueryState>
        <p className="muted small">
          Patient ids are the ids the validating call was made with (Silver patient ids or caller-assigned ids), not master ids.
        </p>
      </section>
    </>
  );
}

function BucketCard({
  title,
  buckets,
  filterKey,
  onFilter,
  active,
  limit,
}: {
  title: string;
  buckets: Bucket[];
  filterKey: string;
  onFilter: (k: string, v: string) => void;
  active: string;
  limit?: number;
}) {
  const [showAll, setShowAll] = useState(false);
  const max = Math.max(1, ...buckets.map((b) => b.open));
  const cut = limit !== undefined && !showAll && buckets.length > limit;
  // Collapsed: the top `limit`, plus the active one if it's further down (so the selection never disappears).
  const shown = cut ? buckets.filter((b, i) => i < limit! || b.key === active) : buckets;
  return (
    <section className="card">
      <h2>{title}</h2>
      {buckets.length === 0 ? (
        <Empty>None.</Empty>
      ) : (
        <ul className="buckets">
          {shown.map((b) => (
            <li key={b.key} className={active === b.key ? 'active' : ''}>
              <button className="btn-link" onClick={() => onFilter(filterKey, active === b.key ? '' : b.key)} title={b.label}>
                {b.key === b.label ? b.key : `${b.key} — ${b.label}`}
              </button>
              <span className="num">{fmtNumber(b.open)} open</span>
              <Bar value={b.open} max={max} tone={b.key === 'ERROR' ? 'bad' : b.key === 'WARNING' ? 'warn' : 'info'} label={`${b.key} open findings`} />
            </li>
          ))}
        </ul>
      )}
      {limit !== undefined && buckets.length > limit && (
        <button className="btn-link small" onClick={() => setShowAll(!showAll)}>
          {showAll ? `Show top ${limit}` : `Show all ${buckets.length} rules`}
        </button>
      )}
    </section>
  );
}

function ActiveFilters({
  applied,
  filters,
  onClear,
  onReset,
}: {
  applied: FilterKey[];
  filters: Record<string, string>;
  onClear: (k: string) => void;
  onReset: () => void;
}) {
  if (applied.length === 0) return null;
  return (
    <div className="active-filters">
      {applied.map((k) => (
        <button key={k} className="chip chip-filter" onClick={() => onClear(k)} title="Remove this filter">
          {FILTER_LABELS[k]}: {k === 'resolved' ? (filters[k] === 'true' ? 'resolved' : 'open') : filters[k]} ×
        </button>
      ))}
      <button className="btn btn-small" onClick={onReset}>
        Reset filters
      </button>
    </div>
  );
}
