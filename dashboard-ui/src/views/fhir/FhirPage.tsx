import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useEffect, useState, type FormEvent } from 'react';
import { Link, useSearchParams } from 'react-router';
import type { AttributeFacet, Page, ResourceOrigin, ResourceSummary, SourceCount, TypeCount } from '../../api/types';
import { useApi } from '../../app/context';
import { Badge, Empty, ListSummary, PageHeader, Pager, QueryState, readWindow } from '../../components/common';
import { fmtDateTime } from '../../format';
import { ATTR, AttributeFilters, draftNames, valueLabel } from './AttributeFilters';

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
  // FHIR ids are text: no number range here — the column headers sort instead
  const { size } = readWindow(params, '', 25);
  // Newest first by default: what the pipeline just built is on page 1.
  const sort = params.get('sort') ?? 'updated';
  const dir = params.get('dir') ?? (sort === 'updated' ? 'desc' : 'asc');
  const [text, setText] = useState<Record<TextFilter, string>>({ patient, code, q });
  // The selected type's own filters (attr.*), straight from the URL.
  const attrs = Object.fromEntries([...params.entries()].filter(([key]) => key.startsWith(ATTR)));
  const attrKey = JSON.stringify(attrs);
  const [attrDraft, setAttrDraft] = useState<Record<string, string>>(attrs);
  // Another type (or Clear filters, or Back) changes the attr.* params: the typed boxes follow the URL.
  useEffect(() => setAttrDraft(JSON.parse(attrKey) as Record<string, string>), [attrKey]);

  const types = useQuery({
    queryKey: ['fhir', 'types'],
    queryFn: ({ signal }) => api.get<TypeCount[]>('/fhir/resource-types', undefined, signal),
  });
  const sources = useQuery({
    queryKey: ['fhir', 'source-systems'],
    queryFn: ({ signal }) => api.get<SourceCount[]>('/fhir/source-systems', undefined, signal),
  });
  const facets = useQuery({
    queryKey: ['fhir', 'attributes', type],
    queryFn: ({ signal }) => api.get<AttributeFacet[]>('/fhir/attributes', { type }, signal),
    enabled: type !== '',
  });
  const resources = useQuery({
    queryKey: ['fhir', 'resources', type, compliant, masterPatientId, origin, sourceSystem, patient, code, q, attrKey, sort, dir, page, size],
    queryFn: ({ signal }) =>
      api.get<Page<ResourceSummary>>(
        '/fhir/resources',
        { type, compliant, masterPatientId, origin, sourceSystem, patient, code, q, ...attrs, sort, dir, page, size },
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
  /** Attribute filters belong to one type: picking another type drops them. */
  const noAttrs = () => Object.fromEntries(Object.keys(attrs).map((key) => [key, '']));
  const setType = (value: string) => update({ type: value, ...noAttrs() });

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
    const typed = Object.fromEntries(draftNames(facets.data ?? []).map((name) => [name, (attrDraft[name] ?? '').trim()]));
    update({ patient: text.patient.trim(), code: text.code.trim(), q: text.q.trim(), ...typed });
  }
  // Each row's own values of the type's attributes: a filter proves itself in the list.
  const showAttributes = type !== '' && (facets.data?.length ?? 0) > 0;
  const anyFilter = [compliant, masterPatientId, origin, sourceSystem, patient, code, q, ...Object.values(attrs)].some(Boolean);
  function clearAll() {
    setText({ patient: '', code: '', q: '' });
    update({ compliant: '', masterPatientId: '', origin: '', sourceSystem: '', patient: '', code: '', q: '', ...noAttrs() });
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
        <aside className="card side side-wide">
          <h2>Resource types</h2>
          <QueryState query={types} context="Loading types">
            {(list) => {
              const sum = totals(list);
              return (
                <table className="table type-table" aria-label="Resource types">
                  <thead>
                    <tr>
                      <th>Type</th>
                      <th className="num" title="US Core compliant">Compliant</th>
                      <th className="num" title="Not US Core compliant">Not</th>
                      <th className="num">Total</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr className={type === '' ? 'active' : ''}>
                      <td colSpan={4}>
                        <button className="btn-link" onClick={() => setType('')}>
                          All types
                        </button>
                      </td>
                    </tr>
                    {list.map((t) => {
                      const not = t.total - t.usCoreCompliant;
                      return (
                        <tr key={t.resourceType} className={type === t.resourceType ? 'active' : ''}>
                          <td>
                            <button className="btn-link" onClick={() => setType(t.resourceType)}>
                              {t.resourceType}
                            </button>
                          </td>
                          <td className="num">{t.usCoreCompliant}</td>
                          <td className="num">
                            {not > 0 ? (
                              <button
                                className="btn-link not-compliant"
                                title={`Show the ${not} non-compliant ${t.resourceType} resource${not === 1 ? '' : 's'}`}
                                onClick={() => update({ type: t.resourceType, compliant: 'false', ...noAttrs() })}
                              >
                                {not}
                              </button>
                            ) : (
                              <span className="muted">0</span>
                            )}
                          </td>
                          <td className="num">{t.total}</td>
                        </tr>
                      );
                    })}
                  </tbody>
                  <tfoot>
                    <tr>
                      <th scope="row">Total</th>
                      <td className="num">{fmtCount(sum.compliant)}</td>
                      <td className="num">{fmtCount(sum.total - sum.compliant)}</td>
                      <td className="num">{fmtCount(sum.total)}</td>
                    </tr>
                  </tfoot>
                </table>
              );
            }}
          </QueryState>
        </aside>
        <section className="card grow">
          <div className="card-head">
            <div>
              <ListSummary title={type || 'All resources'} page={resources.data} noun="resources" />
            </div>
          </div>
          {types.data && <GoldSummary counts={type ? types.data.filter((t) => t.resourceType === type) : types.data} type={type} />}
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
                <option value="">Any</option>
                <option value="true">US Core compliant</option>
                <option value="false">Not compliant</option>
              </select>
            </label>
            {textInput('code', 'Code or name', 'e.g. 723 or Amoxicillin')}
            {textInput('q', 'Resource id contains', 'e.g. silver-allergy-165')}
            {type && facets.data && (
              <AttributeFilters
                type={type}
                facets={facets.data}
                applied={(name) => params.get(name) ?? ''}
                draft={attrDraft}
                onDraft={(name, value) => setAttrDraft({ ...attrDraft, [name]: value })}
                onPick={set}
              />
            )}
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
            <code>NC700343</code>), a name may be partial.{' '}
            {type ? (
              <>
                The <strong>{type} attributes</strong> row filters on that resource's own elements (hover a box for its FHIR path);
                dropdowns list only values present in Gold, with counts.
              </>
            ) : (
              <>Pick a resource type on the left for its own filters (e.g. Patient gender and birth date, Observation interpretation).</>
            )}
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
                        {showAttributes && <th>Key attributes</th>}
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
                            {showAttributes && (
                              <td className="attr-cell">
                                {(facets.data ?? [])
                                  .filter((f) => r.attributes?.[f.key] !== undefined)
                                  .map((f) => {
                                    const value = r.attributes![f.key]!;
                                    const filtered = Boolean(attrs[ATTR + f.key] || attrs[`${ATTR}${f.key}From`] || attrs[`${ATTR}${f.key}To`]);
                                    return (
                                      <span key={f.key} className={`attr${filtered ? ' attr-filtered' : ''}`} title={f.path}>
                                        {f.label}: {valueLabel(f, value)}
                                      </span>
                                    );
                                  })}
                              </td>
                            )}
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
                  <Pager
                    page={data}
                    onPage={(p) => set('page', String(p))}
                    onSize={(n) => set('size', String(n))}
                    idOf={(r) => `${r.resourceType}/${r.fhirId}`}
                  />
                </>
              )
            }
          </QueryState>
        </section>
      </div>
    </>
  );
}

const fmtCount = (n: number) => n.toLocaleString('en-US');

function totals(list: TypeCount[]) {
  return list.reduce((acc, t) => ({ total: acc.total + t.total, compliant: acc.compliant + t.usCoreCompliant }), { total: 0, compliant: 0 });
}

/** What Gold holds for the selected type (or all types), before any filter: the live numbers behind the list. */
function GoldSummary({ counts, type }: { counts: TypeCount[]; type: string }) {
  const sum = totals(counts);
  const not = sum.total - sum.compliant;
  return (
    <p className="gold-summary" aria-label="Gold totals">
      In Gold: <strong>{fmtCount(sum.total)}</strong> {type || 'resources'} · <span className="good">{fmtCount(sum.compliant)} US Core compliant</span> ·{' '}
      <span className={not > 0 ? 'bad' : 'muted'}>{fmtCount(not)} not compliant</span>
    </p>
  );
}
