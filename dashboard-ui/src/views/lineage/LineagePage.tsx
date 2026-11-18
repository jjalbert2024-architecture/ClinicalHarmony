import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useState, type FormEvent, type ReactNode } from 'react';
import { Link, useSearchParams } from 'react-router';
import type {
  BronzeSummary,
  LayerSummary,
  LineageGraph,
  MasterSummary,
  Page,
  SilverPatientSummary,
  SilverSourceCount,
  StepCount,
} from '../../api/types';
import { useApi } from '../../app/context';
import { Badge, Empty, PageHeader, Pager, QueryState, Stat } from '../../components/common';
import { fmtDate, fmtDateTime, fmtNumber, fullName } from '../../format';
import { LineageChain } from './LineageChain';

/** What the trace box can start from — one id kind per medallion step (URL parameter = gateway parameter). */
export const TRACE_KINDS = [
  { key: 'bronzeMessageId', label: 'Bronze message id', target: 'bronze.raw_messages' },
  { key: 'silverPatientId', label: 'Silver patient id', target: 'silver.patients' },
  { key: 'masterPatientId', label: 'Master patient id', target: 'gold.master_patients' },
  { key: 'fhirResourceId', label: 'Gold FHIR resource id', target: 'gold.fhir_resources' },
] as const;
type TraceKey = (typeof TRACE_KINDS)[number]['key'];

export type Layer = 'bronze' | 'silver' | 'gold';
const LAYERS: Layer[] = ['bronze', 'silver', 'gold'];

const sum = (m: Record<string, number> | undefined) => Object.values(m ?? {}).reduce((a, b) => a + b, 0);

export function LineagePage() {
  const [params] = useSearchParams();
  const layerParam = params.get('layer') as Layer | null;
  const layer: Layer = layerParam && LAYERS.includes(layerParam) ? layerParam : 'bronze';
  return (
    <>
      <PageHeader
        title="Data Lineage"
        subtitle="Browse each medallion layer, open any record, and follow it from the raw Bronze message through Silver to Gold — every id is a link."
      />
      <TraceCard />
      <LayerTabs layer={layer} />
      {layer === 'bronze' && <BronzeCard />}
      {layer === 'silver' && <SilverCard />}
      {layer === 'gold' && <GoldCard />}
      <StepSummaryCard />
    </>
  );
}

/** Updates the URL: empty values are removed; any change other than a page number goes back to page 1. */
function useUrlState(pageKey: string) {
  const [params, setParams] = useSearchParams();
  function set(changes: Record<string, string>) {
    const next = new URLSearchParams(params);
    for (const [key, value] of Object.entries(changes)) {
      if (value) next.set(key, value);
      else next.delete(key);
    }
    if (!(pageKey in changes)) next.delete(pageKey);
    setParams(next, { replace: true });
  }
  return { params, set };
}

/** Patient kinds also accept an MRN / source id (exact, any case), looked up in Silver. */
const MRN_KINDS: TraceKey[] = ['silverPatientId', 'masterPatientId'];

/** Several Silver patients share the MRN (one per source system): the user picks one. */
type MrnChoice = { silverPatientId: number; masterPatientId: number | null; label: string };

function TraceCard() {
  const api = useApi();
  const [params, setParams] = useSearchParams();
  const activeKind = TRACE_KINDS.find((k) => params.get(k.key));
  const activeId = activeKind ? (params.get(activeKind.key) ?? '') : '';
  const [kind, setKind] = useState<TraceKey>(activeKind?.key ?? 'silverPatientId');
  const [id, setId] = useState(activeId);
  const [note, setNote] = useState<string | null>(null);
  const [choices, setChoices] = useState<MrnChoice[]>([]);
  const [looking, setLooking] = useState(false);

  const graph = useQuery({
    queryKey: ['lineage', activeKind?.key, activeId],
    queryFn: ({ signal }) => api.get<LineageGraph>('/lineage', activeKind ? { [activeKind.key]: activeId } : {}, signal),
    enabled: activeKind !== undefined,
  });

  function trace(key: TraceKey, value: string) {
    const next = new URLSearchParams(params);
    TRACE_KINDS.forEach((k) => next.delete(k.key));
    next.set(key, value);
    setParams(next);
    setId(value);
    setKind(key);
  }

  function pick(c: MrnChoice) {
    setChoices([]);
    if (kind === 'masterPatientId') {
      if (c.masterPatientId === null) {
        setNote(`${c.label} is not in Gold yet, so it has no master patient — trace it as a Silver patient instead.`);
        return;
      }
      setNote(`${c.label} → master patient #${c.masterPatientId}`);
      trace('masterPatientId', String(c.masterPatientId));
    } else {
      setNote(`${c.label} → Silver patient #${c.silverPatientId}`);
      trace('silverPatientId', String(c.silverPatientId));
    }
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    const value = id.trim();
    setNote(null);
    setChoices([]);
    if (!value) return;
    if (/^\d+$/.test(value)) {
      trace(kind, value);
      return;
    }
    const label = TRACE_KINDS.find((k) => k.key === kind)!.label;
    if (!MRN_KINDS.includes(kind)) {
      setNote(`A ${label.toLowerCase()} is a number. "${value}" looks like an MRN: pick Silver patient id or Master patient id to trace a patient by MRN.`);
      return;
    }
    // Not a number: an MRN / source patient id, matched EXACTLY (any case) — like Patient Timeline's search.
    setLooking(true);
    try {
      const page = await api.get<Page<SilverPatientSummary>>('/silver/patients', { q: value, size: 50 });
      const exact = page.items.filter(
        (p) => p.sourcePatientId.toUpperCase() === value.toUpperCase() || (p.mrn ?? '').toUpperCase() === value.toUpperCase(),
      );
      const found = exact.map((p) => ({
        silverPatientId: p.id,
        masterPatientId: p.masterPatientId,
        label: `${p.sourceSystem} · ${p.sourcePatientId} (${fullName(p.firstName, p.lastName)}, DOB ${fmtDate(p.dateOfBirth)})`,
      }));
      if (found.length === 0) {
        setNote(`No Silver patient has MRN or source id "${value}" (it must match exactly). Silver patients are listed under the Silver card below.`);
      } else if (found.length === 1) {
        pick(found[0]!);
      } else {
        setChoices(found);
        setNote(`"${value}" is the MRN of ${found.length} Silver patients (one per source system). Pick one:`);
      }
    } catch {
      setNote(`Couldn't look up "${value}" — try again, or search the Silver patients list below.`);
    } finally {
      setLooking(false);
    }
  }

  const mrnOk = MRN_KINDS.includes(kind);
  return (
    <section className="card">
      <h2>Trace a record</h2>
      <form className="filters inline" onSubmit={submit}>
        <select aria-label="Record kind" value={kind} onChange={(e) => setKind(e.target.value as TraceKey)}>
          {TRACE_KINDS.map((k) => (
            <option key={k.key} value={k.key}>
              {k.label}
            </option>
          ))}
        </select>
        <input
          aria-label="Record id"
          value={id}
          onChange={(e) => setId(e.target.value)}
          maxLength={100}
          placeholder={mrnOk ? 'Id, or MRN (e.g. 681 or NC700343)' : 'Id (e.g. 1866)'}
        />
        <button className="btn btn-primary" type="submit" disabled={!id.trim() || looking}>
          {looking ? 'Looking up…' : 'Trace'}
        </button>
      </form>
      <p className="muted small">
        Start from any layer: a Bronze message, a Silver patient, a master patient or a Gold FHIR resource — by its id, or a patient by MRN. Or
        browse the layers below and click any id.
      </p>
      {note && (
        <p className="banner banner-info" role="status">
          {note}
        </p>
      )}
      {choices.length > 0 && (
        <ul className="plain">
          {choices.map((c) => (
            <li key={c.silverPatientId}>
              <button type="button" className="btn-link" onClick={() => pick(c)}>
                {c.label}
              </button>
            </li>
          ))}
        </ul>
      )}
      {activeKind === undefined ? null : (
        <QueryState query={graph} context="Loading lineage">
          {(g) => (
            <>
              <p>
                <strong>{g.target}</strong> #{g.targetId} · <TargetLink target={g.target} id={g.targetId} /> · {g.records.length} lineage record(s)
              </p>
              {g.records.length === 0 ? <Empty>No Gold lineage recorded for this record yet.</Empty> : <LineageChain records={g.records} />}
              {g.bronzeMessages.length > 0 && (
                <>
                  <h3>Bronze source messages</h3>
                  <BronzeTable items={g.bronzeMessages} />
                </>
              )}
            </>
          )}
        </QueryState>
      )}
    </section>
  );
}

/** "open …" link for a traced record, to its own page. */
function TargetLink({ target, id }: { target: string; id: number }) {
  switch (target) {
    case 'bronze.raw_messages':
      return <Link to={`/lineage/bronze/${id}`}>open Bronze message</Link>;
    case 'silver.patients':
      return <Link to={`/lineage/silver/${id}`}>open Silver patient</Link>;
    case 'gold.master_patients':
      return <Link to={`/patients/${id}`}>open patient</Link>;
    default:
      return <Link to={`/lineage?layer=gold`}>Gold</Link>;
  }
}

/** The three layer cards (counts, status chips) — they are the tabs that choose the list below. */
function LayerTabs({ layer }: { layer: Layer }) {
  const api = useApi();
  const { set } = useUrlState('page');
  const summary = useQuery({
    queryKey: ['summary'],
    queryFn: ({ signal }) => api.get<LayerSummary>('/summary', undefined, signal),
  });
  const s = summary.data;
  const tab = (key: Layer, title: string, subtitle: string, body: ReactNode) => (
    <button
      type="button"
      role="tab"
      aria-selected={layer === key}
      className={`layer layer-${key} layer-tab${layer === key ? ' selected' : ''}`}
      onClick={() => set({ layer: key === 'bronze' ? '' : key, page: '', q: '', sourceSystem: '', validationStatus: '', masterStatus: '' })}
    >
      <div className="layer-title">
        {title} <span className="muted">{subtitle}</span>
      </div>
      {body}
    </button>
  );
  return (
    <div className="layers layer-tabs" role="tablist" aria-label="Medallion layer">
      {tab(
        'bronze',
        'Bronze',
        'raw messages',
        <>
          <Stat label="Messages" value={s ? fmtNumber(sum(s.bronzeByStatus)) : '…'} />
          <Counts counts={s?.bronzeByStatus} />
        </>,
      )}
      <div className="layer-arrow" aria-hidden="true">
        →
      </div>
      {tab(
        'silver',
        'Silver',
        'parsed patients',
        <>
          <Stat label="Patients" value={s ? fmtNumber(s.silver.patients) : '…'} />
          <Counts counts={s?.silverByValidation} />
        </>,
      )}
      <div className="layer-arrow" aria-hidden="true">
        →
      </div>
      {tab(
        'gold',
        'Gold',
        'master patients',
        <>
          <Stat
            label="Masters"
            value={s ? fmtNumber(sum(s.mastersByStatus)) : '…'}
            hint={s ? `${fmtNumber(s.fhirResources)} FHIR resources` : undefined}
          />
          <Counts counts={s?.mastersByStatus} />
        </>,
      )}
    </div>
  );
}

function Counts({ counts }: { counts: Record<string, number> | undefined }) {
  return (
    <div className="status-counts">
      {Object.entries(counts ?? {}).map(([k, v]) => (
        <Badge key={k} value={k}>
          {k.replace(/_/g, ' ')} {fmtNumber(v)}
        </Badge>
      ))}
    </div>
  );
}

/** Silver validation states as the filter and badges show them. */
export const VALIDATION_LABELS: Record<string, string> = {
  VALIDATED: 'Validated',
  PENDING_VALIDATION: 'Pending validation',
  NOT_TRACKED: 'Not tracked (seed data)',
};

function SilverCard() {
  const api = useApi();
  const { params, set } = useUrlState('page');
  const q = params.get('q') ?? '';
  const sourceSystem = params.get('sourceSystem') ?? '';
  const validationStatus = params.get('validationStatus') ?? '';
  const page = Number(params.get('page') ?? '0');
  const [text, setText] = useState(q);
  const sources = useQuery({
    queryKey: ['silver', 'source-systems'],
    queryFn: ({ signal }) => api.get<SilverSourceCount[]>('/silver/source-systems', undefined, signal),
  });
  const patients = useQuery({
    queryKey: ['silver', 'patients', q, sourceSystem, validationStatus, page],
    queryFn: ({ signal }) =>
      api.get<Page<SilverPatientSummary>>('/silver/patients', { q, sourceSystem, validationStatus, page, size: 15 }, signal),
    placeholderData: keepPreviousData,
  });
  function submit(e: FormEvent) {
    e.preventDefault();
    set({ q: text.trim() });
  }
  return (
    <section className="card">
      <div className="card-head">
        <h2>Silver patients</h2>
        <form className="filters inline" onSubmit={submit}>
          <input aria-label="Silver patient search" placeholder="Name, MRN (exact) or Silver id" value={text} onChange={(e) => setText(e.target.value)} maxLength={100} />
          <select aria-label="Silver source system" value={sourceSystem} onChange={(e) => set({ sourceSystem: e.target.value })}>
            <option value="">Any source</option>
            {(sources.data ?? []).map((s) => (
              <option key={s.sourceSystem} value={s.sourceSystem}>
                {s.sourceSystem} ({s.patients})
              </option>
            ))}
          </select>
          <select aria-label="Validation status" value={validationStatus} onChange={(e) => set({ validationStatus: e.target.value })}>
            <option value="">Any validation status</option>
            {Object.entries(VALIDATION_LABELS).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
          <button className="btn" type="submit">
            Search
          </button>
        </form>
      </div>
      <p className="muted small">Newest first. One row per patient per source system; the same person at two hospitals is two Silver patients and one Gold master.</p>
      <QueryState query={patients} context="Loading Silver patients">
        {(data) =>
          data.items.length === 0 ? (
            <Empty>No Silver patients match.</Empty>
          ) : (
            <>
              <table className="table">
                <thead>
                  <tr>
                    <th>Silver patient</th>
                    <th>Source · MRN</th>
                    <th>Name</th>
                    <th>DOB</th>
                    <th>Validation</th>
                    <th className="num" title="conditions / observations / medications / allergies">
                      Records
                    </th>
                    <th className="num">Open findings</th>
                    <th>Gold master</th>
                    <th>Updated</th>
                  </tr>
                </thead>
                <tbody>
                  {data.items.map((p) => (
                    <tr key={p.id}>
                      <td>
                        <Link to={`/lineage/silver/${p.id}`}>#{p.id}</Link>
                      </td>
                      <td>
                        {p.sourceSystem} · {p.sourcePatientId}
                      </td>
                      <td>{fullName(p.firstName, p.lastName)}</td>
                      <td>{fmtDate(p.dateOfBirth)}</td>
                      <td>
                        <Badge value={p.validationStatus ?? 'NOT_TRACKED'}>{VALIDATION_LABELS[p.validationStatus ?? 'NOT_TRACKED']}</Badge>
                      </td>
                      <td className="num" title="conditions / observations / medications / allergies">
                        {p.conditions} / {p.observations} / {p.medications} / {p.allergies}
                      </td>
                      <td className="num">
                        {p.openFindings > 0 ? (
                          <Link to={`/validation?patientId=${p.id}&resolved=false`}>{p.openFindings}</Link>
                        ) : (
                          <span className="muted">0</span>
                        )}
                      </td>
                      <td>
                        {p.masterPatientId ? (
                          <>
                            <Link to={`/patients/${p.masterPatientId}`}>#{p.masterPatientId}</Link> <Badge value={p.masterMatchStatus} />
                          </>
                        ) : (
                          <span className="muted">not in Gold yet</span>
                        )}
                      </td>
                      <td>{fmtDateTime(p.updatedAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <Pager page={data} onPage={(n) => set({ page: String(n) })} />
            </>
          )
        }
      </QueryState>
    </section>
  );
}

function GoldCard() {
  const api = useApi();
  const { params, set } = useUrlState('page');
  const q = params.get('q') ?? '';
  const status = params.get('masterStatus') ?? '';
  const page = Number(params.get('page') ?? '0');
  const [text, setText] = useState(q);
  const masters = useQuery({
    queryKey: ['gold', 'masters', q, status, page],
    queryFn: ({ signal }) => api.get<Page<MasterSummary>>('/patients', { q, status, page, size: 15 }, signal),
    placeholderData: keepPreviousData,
  });
  function submit(e: FormEvent) {
    e.preventDefault();
    set({ q: text.trim() });
  }
  return (
    <section className="card">
      <div className="card-head">
        <h2>Gold master patients</h2>
        <form className="filters inline" onSubmit={submit}>
          <input aria-label="Master search" placeholder="Name or MRN (exact)" value={text} onChange={(e) => setText(e.target.value)} maxLength={100} />
          <select aria-label="Match status" value={status} onChange={(e) => set({ masterStatus: e.target.value })}>
            <option value="">Any match status</option>
            {['PROVISIONAL', 'MATCHED'].map((v) => (
              <option key={v}>{v}</option>
            ))}
          </select>
          <button className="btn" type="submit">
            Search
          </button>
        </form>
      </div>
      <p className="muted small">One master per person, linking every source record the patient index matched to it. FHIR resources open in the FHIR Viewer.</p>
      <QueryState query={masters} context="Loading master patients">
        {(data) =>
          data.items.length === 0 ? (
            <Empty>No master patients match.</Empty>
          ) : (
            <>
              <table className="table">
                <thead>
                  <tr>
                    <th>Master</th>
                    <th>Name</th>
                    <th>DOB</th>
                    <th>Match status</th>
                    <th>Sources</th>
                    <th className="num">FHIR resources</th>
                    <th>Lineage</th>
                  </tr>
                </thead>
                <tbody>
                  {data.items.map((m) => (
                    <tr key={m.id}>
                      <td>
                        <Link to={`/patients/${m.id}`}>#{m.id}</Link>
                      </td>
                      <td>{fullName(m.firstName, m.lastName)}</td>
                      <td>{fmtDate(m.dateOfBirth)}</td>
                      <td>
                        <Badge value={m.matchStatus} />
                      </td>
                      <td>
                        {m.sourcePatientCount} · <span className="muted">{(m.sourceSystems ?? []).join(', ')}</span>
                      </td>
                      <td className="num">
                        {m.fhirResourceCount > 0 ? <Link to={`/fhir?masterPatientId=${m.id}`}>{m.fhirResourceCount}</Link> : <span className="muted">0</span>}
                      </td>
                      <td>
                        <Link to={`/lineage?layer=gold&masterPatientId=${m.id}`}>trace</Link>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <Pager page={data} onPage={(n) => set({ page: String(n) })} />
            </>
          )
        }
      </QueryState>
    </section>
  );
}

type StepSort = 'pipeline' | 'records' | 'last';

/**
 * Every transformation step GoldWriter has recorded. Default order = the pipeline's (as the gateway returns it):
 * Silver → Gold mapping, then the patient-index steps on the masters, then the side entrances into Gold (seed
 * load, API generate) — so an older step can sit above a newer one. Records and Last sort on click.
 */
function StepSummaryCard() {
  const api = useApi();
  const [sort, setSort] = useState<StepSort>('pipeline');
  const [desc, setDesc] = useState(false);
  const summary = useQuery({
    queryKey: ['lineage', 'summary'],
    queryFn: ({ signal }) => api.get<StepCount[]>('/lineage/summary', undefined, signal),
  });
  function sortBy(column: StepSort) {
    if (column === sort) {
      if (column === 'pipeline') return;
      setDesc(!desc);
    } else {
      setSort(column);
      setDesc(column !== 'pipeline'); // Records / Last: biggest / newest first
    }
  }
  const sorted = (rows: StepCount[]) => {
    if (sort === 'pipeline') return rows;
    const key = (r: StepCount) => (sort === 'records' ? r.count : new Date(r.lastTransformedAt ?? 0).getTime());
    return [...rows].sort((a, b) => (desc ? key(b) - key(a) : key(a) - key(b)));
  };
  const header = (column: StepSort, label: string, className?: string) => (
    <th className={className} aria-sort={sort === column ? (column === 'pipeline' || !desc ? 'ascending' : 'descending') : 'none'}>
      <button type="button" className={`th-sort${sort === column ? ' active' : ''}`} onClick={() => sortBy(column)}>
        {label}
        {sort === column && column !== 'pipeline' ? (desc ? ' ▼' : ' ▲') : ''}
      </button>
    </th>
  );
  return (
    <section className="card">
      <h2>Transformations recorded</h2>
      <p className="muted small">
        {sort === 'pipeline'
          ? 'In pipeline order, not by date: Silver → Gold mapping, then the patient-index steps, then the side entrances into Gold (seed data, API). Click Records or Last to sort by them.'
          : 'Sorted by ' + (sort === 'records' ? 'records' : 'date') + '. Click Step to go back to pipeline order.'}
      </p>
      <QueryState query={summary} context="Loading the lineage summary">
        {(rows) => (
          <table className="table">
            <thead>
              <tr>
                {header('pipeline', 'Step')}
                <th>From</th>
                <th>Into</th>
                {header('records', 'Records', 'num')}
                {header('last', 'Last')}
              </tr>
            </thead>
            <tbody>
              {sorted(rows).map((r) => (
                <tr key={`${r.step}-${r.sourceSchema}-${r.targetTable}`}>
                  <td>
                    <Badge tone="info">{r.step}</Badge>
                  </td>
                  <td>{r.sourceSchema}</td>
                  <td>{r.targetTable}</td>
                  <td className="num">{fmtNumber(r.count)}</td>
                  <td>{fmtDateTime(r.lastTransformedAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </QueryState>
    </section>
  );
}

/** Bronze payload formats (`message_type`), labelled as the filter shows them. */
export const PAYLOAD_FORMATS: Record<string, string> = { HL7V2: 'HL7 v2', FHIR_JSON: 'FHIR JSON', CLAIMS_CSV: 'Claims CSV' };

/** HL7 v2 message codes (MSH-9.1) the pipeline receives. */
export const HL7_TYPES: Record<string, string> = {
  ADT: 'ADT (admit / update)',
  ORU: 'ORU (lab results)',
  RDE: 'RDE (pharmacy order)',
  OMP: 'OMP (pharmacy order)',
};

function BronzeCard() {
  const api = useApi();
  const [params, setParams] = useSearchParams();
  const status = params.get('bronzeStatus') ?? '';
  const messageType = params.get('messageType') ?? '';
  const page = Number(params.get('bronzePage') ?? '0');
  // an HL7 message type only exists in HL7 v2 payloads (a hand-edited URL with both is read as format only)
  const hl7TypeDisabled = messageType !== '' && messageType !== 'HL7V2';
  const hl7Type = hl7TypeDisabled ? '' : (params.get('hl7Type') ?? '');
  const q = params.get('bronzeQ') ?? '';
  const [text, setText] = useState(q);
  const messages = useQuery({
    queryKey: ['bronze', status, messageType, hl7Type, q, page],
    queryFn: ({ signal }) =>
      api.get<Page<BronzeSummary>>('/bronze/messages', { status, messageType, hl7Type, q, page, size: 15 }, signal),
    placeholderData: keepPreviousData,
  });
  /** Applies filter changes to the URL (empty = removed); any filter change goes back to page 1. */
  function set(changes: Record<string, string>) {
    const next = new URLSearchParams(params);
    for (const [key, value] of Object.entries(changes)) {
      if (value) next.set(key, value);
      else next.delete(key);
    }
    if (!('bronzePage' in changes)) next.delete('bronzePage');
    setParams(next, { replace: true });
  }
  function setFormat(value: string) {
    // leaving HL7 v2 clears the HL7 type, which would otherwise match nothing
    set(value === 'HL7V2' ? { messageType: value } : { messageType: value, hl7Type: '' });
  }
  function setHl7Type(value: string) {
    // picking an HL7 type implies the HL7 v2 format
    set(value ? { hl7Type: value, messageType: 'HL7V2' } : { hl7Type: '' });
  }
  return (
    <section className="card">
      <div className="card-head">
        <h2>Bronze messages</h2>
        <form
          className="filters inline"
          onSubmit={(e) => {
            e.preventDefault();
            set({ bronzeQ: text.trim() });
          }}
        >
          <input
            aria-label="Bronze search"
            placeholder="Patient name, MRN (exact) or message id"
            value={text}
            onChange={(e) => setText(e.target.value)}
            maxLength={100}
          />
          <select aria-label="Processing status" value={status} onChange={(e) => set({ bronzeStatus: e.target.value })}>
            <option value="">Any status</option>
            {['PENDING', 'PROCESSING', 'PROCESSED', 'FAILED', 'QUARANTINED'].map((s) => (
              <option key={s}>{s}</option>
            ))}
          </select>
          <select aria-label="Payload format" value={messageType} onChange={(e) => setFormat(e.target.value)}>
            <option value="">Any payload format</option>
            {Object.entries(PAYLOAD_FORMATS).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
          <select
            aria-label="HL7 message type"
            value={hl7Type}
            disabled={hl7TypeDisabled}
            title={hl7TypeDisabled ? 'Only HL7 v2 messages have an HL7 message type' : undefined}
            onChange={(e) => setHl7Type(e.target.value)}
          >
            <option value="">Any HL7 message type</option>
            {Object.entries(HL7_TYPES).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
          <button className="btn" type="submit">
            Search
          </button>
        </form>
      </div>
      <p className="muted small">Newest first. Patient = the PID segment as the message carries it; the name links to that Silver patient.</p>
      <QueryState query={messages} context="Loading Bronze messages">
        {(data) =>
          data.items.length === 0 ? (
            <Empty>No messages match.</Empty>
          ) : (
            <>
              <BronzeTable items={data.items} />
              <Pager page={data} onPage={(p) => set({ bronzePage: String(p) })} />
            </>
          )
        }
      </QueryState>
    </section>
  );
}

/** Who a Bronze message is about: the PID name (+ MRN), linked to its Silver patient when there is one. */
export function BronzePatient({ message: m }: { message: BronzeSummary }) {
  if (!m.patientName && !m.patientMrn) return <span className="muted">—</span>;
  const name = m.patientName ?? m.patientMrn!;
  return (
    <>
      {m.silverPatientId ? <Link to={`/lineage/silver/${m.silverPatientId}`}>{name}</Link> : name}
      {m.patientMrn && <div className="muted small">{m.patientMrn}</div>}
      {!m.silverPatientId && <div className="muted small">not in Silver</div>}
    </>
  );
}

export function BronzeTable({ items }: { items: BronzeSummary[] }) {
  return (
    <table className="table">
      <thead>
        <tr>
          <th>Message</th>
          <th>Patient</th>
          <th>Source</th>
          <th>Format</th>
          <th>HL7 type</th>
          <th>Status</th>
          <th className="num">Bytes</th>
          <th>Received</th>
        </tr>
      </thead>
      <tbody>
        {items.map((m) => (
          <tr key={m.id}>
            <td>
              <Link to={`/lineage/bronze/${m.id}`}>#{m.id}</Link>
            </td>
            <td>
              <BronzePatient message={m} />
            </td>
            <td>{m.sourceSystem}</td>
            <td>{PAYLOAD_FORMATS[m.messageType] ?? m.messageType}</td>
            <td>{m.hl7MessageType ?? <span className="muted">—</span>}</td>
            <td>
              <Badge value={m.processingStatus} />
              {m.errorMessage && <div className="muted small wrap">{m.errorMessage}</div>}
            </td>
            <td className="num">{fmtNumber(m.payloadLength)}</td>
            <td>{fmtDateTime(m.receivedAt)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
