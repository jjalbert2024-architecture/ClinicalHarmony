import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { Link, useSearchParams } from 'react-router';
import type { Page, ResourceSummary, TypeCount } from '../../api/types';
import { useApi } from '../../app/context';
import { Badge, Empty, PageHeader, Pager, QueryState } from '../../components/common';
import { fmtDateTime } from '../../format';

export function FhirPage() {
  const api = useApi();
  const [params, setParams] = useSearchParams();
  const type = params.get('type') ?? '';
  const compliant = params.get('compliant') ?? '';
  const masterPatientId = params.get('masterPatientId') ?? '';
  const q = params.get('q') ?? '';
  const page = Number(params.get('page') ?? '0');
  const sort = params.get('sort') ?? 'resource';
  const dir = params.get('dir') === 'desc' ? 'desc' : 'asc';
  const [search, setSearch] = useState(q);

  const types = useQuery({
    queryKey: ['fhir', 'types'],
    queryFn: ({ signal }) => api.get<TypeCount[]>('/fhir/resource-types', undefined, signal),
  });
  const resources = useQuery({
    queryKey: ['fhir', 'resources', type, compliant, masterPatientId, q, sort, dir, page],
    queryFn: ({ signal }) =>
      api.get<Page<ResourceSummary>>('/fhir/resources', { type, compliant, masterPatientId, q, sort, dir, page, size: 25 }, signal),
    placeholderData: keepPreviousData,
  });

  function set(key: string, value: string) {
    const next = new URLSearchParams(params);
    if (value) next.set(key, value);
    else next.delete(key);
    if (key !== 'page') next.delete('page');
    setParams(next);
  }
  /** Click a header: same column flips the direction, a new column starts ascending (Updated: newest first). */
  function sortBy(column: string) {
    const next = new URLSearchParams(params);
    const nextDir = column === sort ? (dir === 'asc' ? 'desc' : 'asc') : column === 'updated' ? 'desc' : 'asc';
    if (column === 'resource' && nextDir === 'asc') {
      next.delete('sort');
      next.delete('dir');
    } else {
      next.set('sort', column);
      next.set('dir', nextDir);
    }
    next.delete('page');
    setParams(next);
  }
  const header = (column: string, label: string, className?: string) => (
    <th className={className} aria-sort={sort === column ? (dir === 'asc' ? 'ascending' : 'descending') : 'none'}>
      <button type="button" className={`th-sort${sort === column ? ' active' : ''}`} onClick={() => sortBy(column)}>
        {label}
        {sort === column ? (dir === 'asc' ? ' ▲' : ' ▼') : ''}
      </button>
    </th>
  );
  function submit(e: FormEvent) {
    e.preventDefault();
    set('q', search.trim());
  }

  return (
    <>
      <PageHeader
        title="FHIR Viewer"
        subtitle="Gold-layer FHIR R4 resources with their US Core validation results and lineage."
        actions={
          <Link className="btn" to="/fhir/tools">
            Validate / convert…
          </Link>
        }
      />
      <div className="split">
        <aside className="card side">
          <h2>Resource types</h2>
          <QueryState query={types} context="Loading types">
            {(list) => (
              <ul className="side-list">
                <li className={type === '' ? 'active' : ''}>
                  <button className="btn-link" onClick={() => set('type', '')}>
                    All types
                  </button>
                </li>
                {list.map((t) => (
                  <li key={t.resourceType} className={type === t.resourceType ? 'active' : ''}>
                    <button className="btn-link" onClick={() => set('type', t.resourceType)}>
                      {t.resourceType}
                    </button>
                    <span className="muted small">
                      {t.usCoreCompliant}/{t.total}
                    </span>
                  </li>
                ))}
              </ul>
            )}
          </QueryState>
        </aside>
        <section className="card grow">
          <div className="card-head">
            <h2>{type || 'All resources'}</h2>
            <form className="filters inline" onSubmit={submit}>
              <select aria-label="Compliance" value={compliant} onChange={(e) => set('compliant', e.target.value)}>
                <option value="">Compliant or not</option>
                <option value="true">US Core compliant</option>
                <option value="false">Not compliant</option>
              </select>
              <input aria-label="Search FHIR id" placeholder="FHIR id contains…" value={search} onChange={(e) => setSearch(e.target.value)} maxLength={100} />
              <button className="btn" type="submit">
                Search
              </button>
            </form>
          </div>
          {masterPatientId && (
            <div className="active-filters">
              <button className="chip" onClick={() => set('masterPatientId', '')}>
                master #{masterPatientId} ×
              </button>
            </div>
          )}
          <QueryState query={resources} context="Loading resources">
            {(data) =>
              data.items.length === 0 ? (
                <Empty>No resources match.</Empty>
              ) : (
                <>
                  <table className="table">
                    <thead>
                      <tr>
                        {header('resource', 'Resource')}
                        {header('master', 'Master')}
                        {header('compliance', 'US Core')}
                        <th className="num">Errors / warnings</th>
                        {header('updated', 'Updated')}
                      </tr>
                    </thead>
                    <tbody>
                      {data.items.map((r) => (
                        <tr key={r.id}>
                          <td>
                            <Link to={`/fhir/${r.resourceType}/${encodeURIComponent(r.fhirId)}`}>
                              {r.resourceType}/{r.fhirId}
                            </Link>
                          </td>
                          <td>{r.masterPatientId ? <Link to={`/patients/${r.masterPatientId}`}>#{r.masterPatientId}</Link> : '—'}</td>
                          <td>
                            <Badge tone={r.usCoreCompliant ? 'good' : 'bad'}>{r.usCoreCompliant ? 'compliant' : 'not compliant'}</Badge>
                            {r.complianceStatus && <Badge value={r.complianceStatus} />}
                          </td>
                          <td className="num">
                            {r.errorCount ?? '—'} / {r.warningCount ?? '—'}
                          </td>
                          <td>{fmtDateTime(r.updatedAt)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                  <Pager page={data} onPage={(p) => set('page', String(p))} />
                </>
              )
            }
          </QueryState>
        </section>
      </div>
    </>
  );
}
