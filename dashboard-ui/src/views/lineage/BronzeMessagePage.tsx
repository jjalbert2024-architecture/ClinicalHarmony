import { useQuery } from '@tanstack/react-query';
import { Link, useParams } from 'react-router';
import type { BronzeDetail } from '../../api/types';
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

export function BronzeMessagePage() {
  const { messageId = '' } = useParams();
  const api = useApi();
  const detail = useQuery({
    queryKey: ['bronze', 'message', messageId],
    queryFn: ({ signal }) => api.get<BronzeDetail>(`/bronze/messages/${encodeURIComponent(messageId)}`, undefined, signal),
  });
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
              <strong>Quarantine reason:</strong> {d.summary.errorMessage}
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
              </dl>
            </section>
            <section className="card">
              <h2>Silver records from this message</h2>
              {d.silverRows.length === 0 ? (
                <Empty>
                  None.{' '}
                  {d.summary.processingStatus === 'PENDING'
                    ? 'Waiting for the silver transformer (REST-intake messages stay here until Phase 9d moves REST onto Kafka).'
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
