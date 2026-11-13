import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router';
import type { LayerSummary } from '../../api/types';
import { useApi, useHealth, useMe } from '../../app/context';
import { Badge, PageHeader, QueryState, Stat } from '../../components/common';
import { fmtDateTime, fmtNumber } from '../../format';

const sum = (m: Record<string, number> | undefined) => Object.values(m ?? {}).reduce((a, b) => a + b, 0);

export function OverviewPage() {
  const api = useApi();
  const me = useMe();
  const summary = useQuery({
    queryKey: ['summary'],
    queryFn: ({ signal }) => api.get<LayerSummary>('/summary', undefined, signal),
  });
  const health = useHealth();

  return (
    <>
      <PageHeader
        title="Overview"
        subtitle={me.data ? `Signed in as ${me.data.username} · ${me.data.canAct ? 'data steward' : 'viewer'}` : undefined}
      />
      <section className="card">
        <h2>Medallion layers</h2>
        <QueryState query={summary} context="Loading the layer summary">
          {(s) => (
            <div className="layers">
              <Layer title="Bronze" subtitle="raw messages" to="/lineage">
                <Stat label="Messages" value={fmtNumber(sum(s.bronzeByStatus))} />
                <StatusCounts counts={s.bronzeByStatus} />
              </Layer>
              <Arrow />
              <Layer title="Silver" subtitle="parsed records" to="/patients">
                <Stat label="Patients" value={fmtNumber(s.silver.patients)} />
                <div className="mini">
                  {fmtNumber(s.silver.conditions)} conditions · {fmtNumber(s.silver.observations)} observations ·{' '}
                  {fmtNumber(s.silver.medications)} medications
                </div>
              </Layer>
              <Arrow />
              <Layer title="Gold" subtitle="US Core FHIR" to="/fhir">
                <Stat
                  label="FHIR resources"
                  value={fmtNumber(s.fhirResources)}
                  hint={`${fmtNumber(s.fhirUsCoreCompliant)} US Core compliant`}
                />
                <div className="mini">{fmtNumber(s.lineageRecords)} lineage records</div>
              </Layer>
              <Arrow />
              <Layer title="Patient index" subtitle="master patients" to="/pmi">
                <Stat label="Masters" value={fmtNumber(sum(s.mastersByStatus))} hint={`${fmtNumber(s.pendingReviews)} pending review`} />
                <StatusCounts counts={s.mastersByStatus} />
                {s.lastMatchRun && (
                  <div className="mini">
                    Last match run #{s.lastMatchRun.id} <Badge value={s.lastMatchRun.status} /> {fmtDateTime(s.lastMatchRun.startedAt)}
                  </div>
                )}
              </Layer>
            </div>
          )}
        </QueryState>
        {summary.data && (
          <p className="mini">
            Open findings:{' '}
            {Object.entries(summary.data.openFindingsBySeverity).map(([sev, n]) => (
              <Link key={sev} to={`/validation?severity=${sev}&resolved=false`} className="inline-badge">
                <Badge value={sev}>
                  {sev} {fmtNumber(n)}
                </Badge>
              </Link>
            ))}
          </p>
        )}
      </section>

      <section className="card" id="services">
        <h2>Service health</h2>
        <QueryState query={health} context="Checking service health">
          {(h) => (
            <>
              <p className="mini">
                <Badge value={h.status} /> checked {fmtDateTime(h.checkedAt)} · refreshes every 30 s
              </p>
              <table className="table">
                <thead>
                  <tr>
                    <th>Service</th>
                    <th>Status</th>
                    <th>HTTP</th>
                    <th className="num">Latency</th>
                    <th>Detail</th>
                  </tr>
                </thead>
                <tbody>
                  {h.services.map((s) => (
                    <tr key={s.service}>
                      <td>{s.service}</td>
                      <td>
                        <Badge value={s.status} />
                      </td>
                      <td>{s.httpStatus ?? '—'}</td>
                      <td className="num">{s.latencyMs} ms</td>
                      <td className="muted">{s.detail ?? ''}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </>
          )}
        </QueryState>
      </section>
    </>
  );
}

function Layer({ title, subtitle, to, children }: { title: string; subtitle: string; to: string; children: React.ReactNode }) {
  return (
    <Link to={to} className={`layer layer-${title.toLowerCase().replace(/\s+/g, '-')}`}>
      <div className="layer-title">
        {title} <span className="muted">{subtitle}</span>
      </div>
      {children}
    </Link>
  );
}

function Arrow() {
  return (
    <div className="layer-arrow" aria-hidden="true">
      →
    </div>
  );
}

function StatusCounts({ counts }: { counts: Record<string, number> }) {
  return (
    <div className="status-counts">
      {Object.entries(counts).map(([k, v]) => (
        <Badge key={k} value={k}>
          {k} {fmtNumber(v)}
        </Badge>
      ))}
    </div>
  );
}
