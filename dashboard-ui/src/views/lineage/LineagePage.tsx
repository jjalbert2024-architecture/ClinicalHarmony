import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { Link, useSearchParams } from 'react-router';
import type { BronzeSummary, LineageGraph, Page, StepCount } from '../../api/types';
import { useApi } from '../../app/context';
import { Badge, Empty, PageHeader, Pager, QueryState } from '../../components/common';
import { fmtDateTime, fmtNumber } from '../../format';
import { LineageChain } from './LineageChain';

export function LineagePage() {
  const [params] = useSearchParams();
  const fhirResourceId = params.get('fhirResourceId') ?? '';
  const masterPatientId = params.get('masterPatientId') ?? '';
  return (
    <>
      <PageHeader title="Data Lineage" subtitle="Trace any Gold record back through Silver to the Bronze message it came from." />
      <TraceCard fhirResourceId={fhirResourceId} masterPatientId={masterPatientId} />
      <StepSummaryCard />
      <BronzeCard />
    </>
  );
}

function TraceCard({ fhirResourceId, masterPatientId }: { fhirResourceId: string; masterPatientId: string }) {
  const api = useApi();
  const [params, setParams] = useSearchParams();
  const [kind, setKind] = useState<'fhirResourceId' | 'masterPatientId'>(masterPatientId ? 'masterPatientId' : 'fhirResourceId');
  const [id, setId] = useState(fhirResourceId || masterPatientId);
  const active = fhirResourceId ? { fhirResourceId } : masterPatientId ? { masterPatientId } : null;

  const graph = useQuery({
    queryKey: ['lineage', fhirResourceId, masterPatientId],
    queryFn: ({ signal }) => api.get<LineageGraph>('/lineage', active ?? {}, signal),
    enabled: active !== null,
  });

  function submit(e: FormEvent) {
    e.preventDefault();
    const next = new URLSearchParams(params);
    next.delete('fhirResourceId');
    next.delete('masterPatientId');
    if (id.trim()) next.set(kind, id.trim());
    setParams(next);
  }

  return (
    <section className="card">
      <h2>Trace a record</h2>
      <form className="filters inline" onSubmit={submit}>
        <select aria-label="Record kind" value={kind} onChange={(e) => setKind(e.target.value as typeof kind)}>
          <option value="fhirResourceId">Gold FHIR resource id</option>
          <option value="masterPatientId">Master patient id</option>
        </select>
        <input aria-label="Record id" inputMode="numeric" value={id} onChange={(e) => setId(e.target.value.replace(/\D/g, ''))} placeholder="e.g. 46" />
        <button className="btn btn-primary" type="submit" disabled={!id.trim()}>
          Trace
        </button>
      </form>
      <p className="muted small">Tip: open lineage from any FHIR resource or patient page — the ids are filled in for you.</p>
      {active === null ? null : (
        <QueryState query={graph} context="Loading lineage">
          {(g) => (
            <>
              <p>
                <strong>{g.target}</strong> #{g.targetId}
                {g.target === 'gold.master_patients' && (
                  <>
                    {' '}
                    · <Link to={`/patients/${g.targetId}`}>open patient</Link>
                  </>
                )}{' '}
                · {g.records.length} lineage record(s)
              </p>
              {g.records.length === 0 ? <Empty>No lineage recorded for this record.</Empty> : <LineageChain records={g.records} />}
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

function StepSummaryCard() {
  const api = useApi();
  const summary = useQuery({
    queryKey: ['lineage', 'summary'],
    queryFn: ({ signal }) => api.get<StepCount[]>('/lineage/summary', undefined, signal),
  });
  return (
    <section className="card">
      <h2>Transformations recorded</h2>
      <QueryState query={summary} context="Loading the lineage summary">
        {(rows) => (
          <table className="table">
            <thead>
              <tr>
                <th>Step</th>
                <th>From</th>
                <th>Into</th>
                <th className="num">Records</th>
                <th>Last</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
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

function BronzeCard() {
  const api = useApi();
  const [params, setParams] = useSearchParams();
  const status = params.get('bronzeStatus') ?? '';
  const messageType = params.get('messageType') ?? '';
  const page = Number(params.get('bronzePage') ?? '0');
  const messages = useQuery({
    queryKey: ['bronze', status, messageType, page],
    queryFn: ({ signal }) => api.get<Page<BronzeSummary>>('/bronze/messages', { status, messageType, page, size: 15 }, signal),
    placeholderData: keepPreviousData,
  });
  function set(key: string, value: string) {
    const next = new URLSearchParams(params);
    if (value) next.set(key, value);
    else next.delete(key);
    if (key !== 'bronzePage') next.delete('bronzePage');
    setParams(next, { replace: true });
  }
  return (
    <section className="card">
      <div className="card-head">
        <h2>Bronze messages</h2>
        <div className="filters inline">
          <select aria-label="Processing status" value={status} onChange={(e) => set('bronzeStatus', e.target.value)}>
            <option value="">Any status</option>
            {['PENDING', 'PROCESSING', 'PROCESSED', 'FAILED', 'QUARANTINED'].map((s) => (
              <option key={s}>{s}</option>
            ))}
          </select>
          <select aria-label="Message type" value={messageType} onChange={(e) => set('messageType', e.target.value)}>
            <option value="">Any type</option>
            <option value="HL7V2">HL7 v2</option>
            <option value="FHIR_JSON">FHIR JSON</option>
            <option value="CLAIMS_CSV">Claims CSV</option>
          </select>
        </div>
      </div>
      <QueryState query={messages} context="Loading Bronze messages">
        {(data) =>
          data.items.length === 0 ? (
            <Empty>No messages match.</Empty>
          ) : (
            <>
              <BronzeTable items={data.items} />
              <Pager page={data} onPage={(p) => set('bronzePage', String(p))} />
            </>
          )
        }
      </QueryState>
    </section>
  );
}

function BronzeTable({ items }: { items: BronzeSummary[] }) {
  return (
    <table className="table">
      <thead>
        <tr>
          <th>Message</th>
          <th>Source</th>
          <th>Type</th>
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
            <td>{m.sourceSystem}</td>
            <td>{m.messageType}</td>
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
