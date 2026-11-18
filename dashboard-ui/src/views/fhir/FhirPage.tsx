import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { Link, useSearchParams } from 'react-router';
import type { Page, ResourceOrigin, ResourceSummary, SourceCount, TypeCount } from '../../api/types';
import { useApi } from '../../app/context';
import { Badge, Empty, PageHeader, Pager, QueryState } from '../../components/common';
import { fmtDateTime } from '../../format';

/** Where a Gold resource came from (the gateway reads it from the resource's lineage). */
export const ORIGINS: { value: ResourceOrigin; label: string; title: string }[] = [
  { value: 'pipeline', label: 'HL7 pipeline', title: 'Built from Silver rows that trace back to a raw HL7 message (Bronze → Silver → Gold)' },
  { value: 'silver-seed', label: 'Silver seed', title: 'Built from seeded Silver data that has no raw Bronze message' },
  { value: 'api', label: 'API-generated', title: 'Sent to POST /api/fhir-compliance/generate (the caller chose the id)' },
  { value: 'gold-seed', label: 'Gold seed', title: 'Inserted straight into Gold (the CMS-0057 payer seed) and validated there' },
];
const originOf = (o: ResourceOrigin | null) => ORIGINS.find((x) => x.value === o);

/** Text filters: typed, then applied together by Search. Dropdowns apply at once. */
const TEXT_FILTERS = ['patient', 'code', 'q'] as const;
type TextFilter = (typeof TEXT_FILTERS)[number];

export function FhirPage() {
  const api = useApi();
  const [params, setParams] = useSearchParams();
  const type = params.get('type') ?? '';
  const compliant = params.get('compliant') ?? '';
  const masterPatientId = params.get('masterPatientId') ?? '';
  const origin = params.get('origin') ?? '';
  const sourceSystem = params.get('sourceSystem') ?? '';
  const patient = params.get('patient') ?? '';
  const code = params.get('code') ?? '';
  const q = params.get('q') ?? '';
  const page = Number(params.get('page') ?? '0');
  // Newest first by default: what the pipeline just built is on page 1.
  const sort = params.get('sort') ?? 'updated';
  const dir = params.get('dir') ?? (sort === 'updated' ? 'desc' : 'asc');
  const [text, setText] = useState<Record<TextFilter, string>>({ patient, code, q });

  const types = useQuery({
    queryKey: ['fhir', 'types'],
    queryFn: ({ signal }) => api.get<TypeCount[]>('/fhir/resource-types', undefined, signal),
  });
  const sources = useQuery({
    queryKey: ['fhir', 'source-systems'],
    queryFn: ({ signal }) => api.get<SourceCount[]>('/fhir/source-systems', undefined, signal),
  });
  const resources = useQuery({
    queryKey: ['fhir', 'resources', type, compliant, masterPatientId, origin, sourceSystem, patient, code, q, sort, dir, page],
    queryFn: ({ signal }) =>
      api.get<Page<ResourceSummary>>(
        '/fhir/resources',
        { type, compliant, masterPatientId, origin, sourceSystem, patient, code, q, sort, dir, page, size: 25 },
        signal,
      ),
    placeholderData: keepPreviousData,
  });

  function update(changes: Record<string, string>) {
    const next = new URLSearchParams(params);
    for (const [key, value] of Object.entries(changes)) {
      if (value) next.set(key, value);
      else next.delete(key);
    }
    if (!('page' in changes)) next.delete('page');
    setParams(next);
  }
  const set = (key: string, value: string) => update({ [key]: value });

  /** Click a header: same column flips the direction, a new column starts ascending (Updated: newest first). */
  function sortBy(column: string) {
    const next = new URLSearchParams(params);
    const nextDir = column === sort ? (dir === 'asc' ? 'desc' : 'asc') : column === 'updated' ? 'desc' : 'asc';
    if (column === 'updated' && nextDir === 'desc') {
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
    update({ patient: text.patient.trim(), code: text.code.trim(), q: text.q.trim() });
  }
  const anyFilter = [compliant, masterPatientId, origin, sourceSystem, patient, code, q].some(Boolean);
  function clearAll() {
    setText({ patient: '', code: '', q: '' });
    update({ compliant: '', masterPatientId: '', origin: '', sourceSystem: '', patient: '', code: '', q: '' });
  }
  const textInput = (key: TextFilter, label: string, placeholder: string) => (
    <label>
      {label}
      <input
        aria-label={label}
        placeholder={placeholder}
        value={text[key]}
        onChange={(e) => setText({ ...text, [key]: e.target.value })}
        maxLength={100}
      />
    </label>
  );

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
                    <span className="muted small" title="US Core compliant / total">
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
          </div>
          <form className="filters" onSubmit={submit} aria-label="Filter resources">
            {textInput('patient', 'Patient', 'MRN (exact) or name')}
            <label>
              Source system
              <select aria-label="Source system" value={sourceSystem} onChange={(e) => set('sourceSystem', e.target.value)}>
                <option value="">Any source</option>
                {(sources.data ?? []).map((s) => (
                  <option key={s.sourceSystem} value={s.sourceSystem}>
                    {s.sourceSystem} ({s.resources})
                  </option>
                ))}
              </select>
            </label>
            <label>
              Origin
              <select aria-label="Origin" value={origin} onChange={(e) => set('origin', e.target.value)}>
                <option value="">Any origin</option>
                {ORIGINS.map((o) => (
                  <option key={o.value} value={o.value} title={o.title}>
                    {o.label}
                  </option>
                ))}
              </select>
            </label>
            <label>
              US Core
              <select aria-label="Compliance" value={compliant} onChange={(e) => set('compliant', e.target.value)}>
                <option value="">Compliant or not</option>
                <option value="true">US Core compliant</option>
                <option value="false">Not compliant</option>
              </select>
            </label>
            {textInput('code', 'Code or name', 'e.g. 723 or Amoxicillin')}
            {textInput('q', 'Resource id contains', 'e.g. silver-allergy-165')}
            <button className="btn" type="submit">
              Search
            </button>
            {anyFilter && (
              <button className="btn-link" type="button" onClick={clearAll}>
                Clear filters
              </button>
            )}
          </form>
          <p className="muted small">
            Resource id = the part after the slash in <code>AllergyIntolerance/silver-allergy-165</code>. Patient: an MRN must match exactly (e.g.{' '}
            <code>NC700343</code>), a name may be partial.
          </p>
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
                        <th>Patient</th>
                        {header('master', 'Master')}
                        <th>Origin</th>
                        {header('compliance', 'US Core')}
                        <th className="num">Errors / warnings</th>
                        {header('updated', 'Updated')}
                      </tr>
                    </thead>
                    <tbody>
                      {data.items.map((r) => {
                        const o = originOf(r.origin);
                        return (
                          <tr key={r.id}>
                            <td>
                              <Link to={`/fhir/${r.resourceType}/${encodeURIComponent(r.fhirId)}`}>
                                {r.resourceType}/{r.fhirId}
                              </Link>
                            </td>
                            <td>{r.sourceSystem ? `${r.sourceSystem} · ${r.sourcePatientId ?? '—'}` : '—'}</td>
                            <td>{r.masterPatientId ? <Link to={`/patients/${r.masterPatientId}`}>#{r.masterPatientId}</Link> : '—'}</td>
                            <td>{o ? <span title={o.title}>{o.label}</span> : '—'}</td>
                            <td>
                              <Badge tone={r.usCoreCompliant ? 'good' : 'bad'}>{r.usCoreCompliant ? 'compliant' : 'not compliant'}</Badge>
                              {r.complianceStatus && <Badge value={r.complianceStatus} />}
                            </td>
                            <td className="num">
                              {r.errorCount ?? '—'} / {r.warningCount ?? '—'}
                            </td>
                            <td>{fmtDateTime(r.updatedAt)}</td>
                          </tr>
                        );
                      })}
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
