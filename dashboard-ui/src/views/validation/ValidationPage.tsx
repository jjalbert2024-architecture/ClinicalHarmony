import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router';
import type { Bucket, Finding, Page, ValidationSummary } from '../../api/types';
import { useApi } from '../../app/context';
import { Badge, Bar, Empty, PageHeader, Pager, QueryState, Stat } from '../../components/common';
import { fmtDateTime, fmtNumber } from '../../format';

const FILTERS = ['ruleCode', 'severity', 'entityType', 'category', 'patientId', 'resolved'] as const;

export function ValidationPage() {
  const api = useApi();
  const [params, setParams] = useSearchParams();
  const filters = Object.fromEntries(FILTERS.map((k) => [k, params.get(k) ?? ''])) as Record<(typeof FILTERS)[number], string>;
  const page = Number(params.get('page') ?? '0');

  const summary = useQuery({
    queryKey: ['validation', 'summary'],
    queryFn: ({ signal }) => api.get<ValidationSummary>('/validation/summary', undefined, signal),
  });
  const findings = useQuery({
    queryKey: ['validation', 'findings', filters, page],
    queryFn: ({ signal }) => api.get<Page<Finding>>('/validation/findings', { ...filters, page, size: 25 }, signal),
    placeholderData: keepPreviousData,
  });

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
              <BucketCard title="By severity" buckets={s.bySeverity} filterKey="severity" onFilter={setFilter} active={filters.severity} />
              <BucketCard title="By rule" buckets={s.byRule} filterKey="ruleCode" onFilter={setFilter} active={filters.ruleCode} />
              <BucketCard title="By entity type" buckets={s.byEntityType} filterKey="entityType" onFilter={setFilter} active={filters.entityType} />
            </div>
          </>
        )}
      </QueryState>
      <section className="card">
        <div className="card-head">
          <h2>Findings</h2>
          <div className="filters inline">
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
        <ActiveFilters filters={filters} onClear={(k) => setFilter(k, '')} />
        <QueryState query={findings} context="Loading findings">
          {(data) =>
            data.items.length === 0 ? (
              <Empty>No findings match.</Empty>
            ) : (
              <>
                <table className="table">
                  <thead>
                    <tr>
                      <th>Severity</th>
                      <th>Rule</th>
                      <th>Entity</th>
                      <th>Patient</th>
                      <th>Message</th>
                      <th>Bad value → suggestion</th>
                      <th>Validated</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.items.map((f) => (
                      <tr key={f.id} className={f.resolved ? 'inactive' : ''}>
                        <td>
                          <Badge value={f.severity} />
                          {f.resolved && <Badge tone="muted">resolved</Badge>}
                        </td>
                        <td>
                          <button className="btn-link" onClick={() => setFilter('ruleCode', f.ruleCode)} title={f.ruleName}>
                            {f.ruleCode}
                          </button>
                          <div className="muted">{f.ruleCategory}</div>
                        </td>
                        <td>
                          {f.entityType} #{f.entityId}
                        </td>
                        <td>
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
}: {
  title: string;
  buckets: Bucket[];
  filterKey: string;
  onFilter: (k: string, v: string) => void;
  active: string;
}) {
  const max = Math.max(1, ...buckets.map((b) => b.open));
  return (
    <section className="card">
      <h2>{title}</h2>
      {buckets.length === 0 ? (
        <Empty>None.</Empty>
      ) : (
        <ul className="buckets">
          {buckets.map((b) => (
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
    </section>
  );
}

function ActiveFilters({ filters, onClear }: { filters: Record<string, string>; onClear: (k: string) => void }) {
  const active = Object.entries(filters).filter(([, v]) => v);
  if (active.length === 0) return null;
  return (
    <div className="active-filters">
      {active.map(([k, v]) => (
        <button key={k} className="chip" onClick={() => onClear(k)} title="Remove filter">
          {k}: {v} ×
        </button>
      ))}
    </div>
  );
}
