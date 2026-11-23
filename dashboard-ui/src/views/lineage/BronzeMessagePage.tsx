import { useQuery } from '@tanstack/react-query';
import { Link, useParams } from 'react-router';
import type { BronzeDetail, GatewayConfig } from '../../api/types';
import { useApi } from '../../app/context';
import { Badge, Empty, PageHeader, QueryState } from '../../components/common';
import { fmtDateTime, fmtNumber } from '../../format';

/** HL7 v2 uses \r between segments; show one segment per line. JSON is pretty-printed when it parses. */
export function displayPayload(type: string, raw: string): string {
  if (type === 'HL7V2') return raw.replace(/\r\n?/g, '\n');
  if (type === 'FHIR_JSON') {
    try {
      return JSON.stringify(JSON.parse(raw), null, 2);
    } catch {
      return raw;
    }
  }
  return raw;
}

const INTAKE: Record<string, string> = {
  MLLP_KAFKA: 'MLLP → Kafka',
  REST_KAFKA: 'REST → Kafka',
  REST: 'REST, written directly (before Phase 9d)',
};

/**
 * The Splunk Trace dashboard for one correlation id, its time range opened an hour before the payload arrived (the
 * dashboard's default is the last 24 h, which would miss an older message).
 */
export function splunkTraceLink(traceUrl: string, correlationId: string, receivedAt: string | null): string {
  const q = new URLSearchParams({ 'form.cid': correlationId });
  const t = receivedAt ? Date.parse(receivedAt) : NaN;
  q.set('form.t.earliest', Number.isNaN(t) ? '-7d@d' : String(Math.floor(t / 1000) - 3600));
  q.set('form.t.latest', 'now');
  return `${traceUrl}?${q.toString()}`;
}

export function BronzeMessagePage() {
  const { messageId = '' } = useParams();
  const api = useApi();
  const detail = useQuery({
    queryKey: ['bronze', 'message', messageId],
    queryFn: ({ signal }) => api.get<BronzeDetail>(`/bronze/messages/${encodeURIComponent(messageId)}`, undefined, signal),
  });
  // Public and cached for the session: where Splunk's Trace dashboard is (9e), if configured.
  const config = useQuery({
    queryKey: ['config'],
    queryFn: () => api.request<GatewayConfig>('GET', '/config', { anonymous: true }),
    staleTime: Infinity,
  });
  const traceUrl = config.data?.observability?.splunkTraceUrl;
  return (
    <QueryState query={detail} context={`Loading Bronze message #${messageId}`}>
      {(d) => (
        <>
          <PageHeader
            title={`Bronze message #${d.summary.id}`}
            subtitle={
              <>
                {d.summary.messageType}
                {d.summary.hl7MessageType && ` ${d.summary.hl7MessageType}`} from {d.summary.sourceSystem} · <Badge value={d.summary.processingStatus} /> · received{' '}
                {fmtDateTime(d.summary.receivedAt)}
              </>
            }
            actions={
              <Link className="btn" to="/lineage">
                Back to lineage
              </Link>
            }
          />
          {(d.summary.patientName || d.summary.patientMrn) && (
            <p className="medallion-path">
              <span className="path-step path-bronze">
                <strong>Patient</strong> {d.summary.patientName ?? '—'}
                {d.summary.patientMrn && <span className="muted"> · {d.summary.patientMrn}</span>}
              </span>
              <span aria-hidden="true"> → </span>
              <span className="path-step path-silver">
                {d.summary.silverPatientId ? (
                  <Link to={`/lineage/silver/${d.summary.silverPatientId}`}>Silver patient #{d.summary.silverPatientId}</Link>
                ) : (
                  <span className="muted">not in Silver</span>
                )}
              </span>
            </p>
          )}
          {d.summary.errorMessage && (
            <div className="banner banner-bad">
              <strong>{d.summary.processingStatus === 'QUARANTINED' ? 'Quarantine reason:' : 'Why it failed:'}</strong> {d.summary.errorMessage}
            </div>
          )}
          <div className="grid-2">
            <section className="card">
              <h2>Integrity</h2>
              <dl className="kv">
                <dt>SHA-256</dt>
                <dd>
                  <code className="wrap">{d.summary.payloadChecksum}</code>
                </dd>
                <dt>Size</dt>
                <dd>{fmtNumber(d.summary.payloadLength)} bytes</dd>
                <dt>Format</dt>
                <dd>{d.summary.messageFormat}</dd>
                <dt>Processed</dt>
                <dd>{fmtDateTime(d.summary.processedAt)}</dd>
                <dt>Intake</dt>
                <dd>{INTAKE[d.intakeChannel ?? ''] ?? d.intakeChannel ?? '—'}</dd>
                <dt>Correlation id</dt>
                <dd>
                  {d.correlationId ? (
                    <>
                      <code>{d.correlationId}</code>
                      {traceUrl && (
                        <>
                          {' · '}
                          <a href={splunkTraceLink(traceUrl, d.correlationId, d.summary.receivedAt)} target="_blank" rel="noreferrer">
                            Open trace in Splunk
                          </a>
                        </>
                      )}
                    </>
                  ) : (
                    <span className="muted">none — written straight to Bronze before Phase 9a/9d, so never logged with one</span>
                  )}
                </dd>
              </dl>
            </section>
            <section className="card">
              <h2>Silver records from this message</h2>
              {d.silverRows.length === 0 ? (
                <Empty>
                  None.{' '}
                  {d.summary.processingStatus === 'PENDING'
                    ? 'Waiting for the silver transformer (a row still pending after 2 minutes is picked up by its backlog sweep).'
                    : d.summary.processingStatus === 'PROCESSED'
                      ? 'This message added nothing new: everything in it was already in Silver.'
                      : 'Quarantined and failed messages never reach Silver; the reason is shown above.'}
                </Empty>
              ) : (
                <>
                  <ul className="plain">
                    {d.silverRows.map((r) => (
                      <li key={`${r.table}-${r.id}`}>
                        <Link to={r.table === 'silver.patients' ? `/lineage/silver/${r.id}` : `/lineage/silver/${r.silverPatientId}#${r.table.replace('silver.', '')}-${r.id}`}>
                          {r.table} #{r.id}
                        </Link>
                        {r.table !== 'silver.patients' && <span className="muted small"> · Silver patient #{r.silverPatientId}</span>}
                      </li>
                    ))}
                  </ul>
                  <p className="muted small">
                    <Link to={`/lineage?bronzeMessageId=${d.summary.id}`}>Trace this message into Gold</Link>
                  </p>
                </>
              )}
            </section>
          </div>
          <section className="card">
            <h2>Raw payload</h2>
            <pre className="payload">{displayPayload(d.summary.messageType, d.rawPayload)}</pre>
          </section>
        </>
      )}
    </QueryState>
  );
}
