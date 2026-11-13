import { Link } from 'react-router';
import type { LineageRecord } from '../../api/types';
import { Badge } from '../../components/common';
import { fmtDateTime, shortHash } from '../../format';

/** Turns a lineage step ref like "gold.fhir_resources/46" or "bronze.raw_messages/12" into a link. */
function refLink(ref: string | undefined) {
  if (!ref) return null;
  const bronze = /^bronze\.raw_messages\/(\d+)$/.exec(ref);
  if (bronze) return <Link to={`/lineage/bronze/${bronze[1]}`}>{ref}</Link>;
  const master = /^gold\.master_patients\/(\d+)$/.exec(ref);
  if (master) return <Link to={`/patients/${master[1]}`}>{ref}</Link>;
  return <code>{ref}</code>;
}

/**
 * One card per lineage record (one per target + step), each showing its stages
 * (BRONZE → SILVER → GOLD, SEED → GOLD, API → GOLD …) with transformer and content hashes.
 */
export function LineageChain({ records }: { records: LineageRecord[] }) {
  return (
    <ol className="lineage">
      {records.map((r) => (
        <li key={r.id} className="lineage-record">
          <div className="lineage-head">
            <Badge tone="info">{r.step}</Badge>{' '}
            <span>
              {r.sourceSchema}.{r.sourceTable}
              {r.sourceId !== null ? `/${r.sourceId}` : ''} → {r.targetSchema}.{r.targetTable}/{r.targetId}
            </span>
            {r.details?.target && <strong> · {r.details.target}</strong>}
            <span className="muted"> · {fmtDateTime(r.transformedAt)}</span>
          </div>
          {r.details?.steps && r.details.steps.length > 0 && (
            <ol className="stages">
              {r.details.steps.map((s, i) => (
                <li key={i} className={`stage stage-${s.stage.toLowerCase()}`}>
                  <div className="stage-name">{s.stage}</div>
                  {s.ref && <div>{refLink(s.ref)}</div>}
                  {s.transformer && <div className="stage-transformer">{s.transformer}</div>}
                  <div className="muted small">
                    {s.timestamp && fmtDateTime(s.timestamp)}
                    {s.input_hash && (
                      <>
                        {' '}
                        · in <code title={s.input_hash}>{shortHash(s.input_hash)}</code>
                      </>
                    )}
                    {s.output_hash && (
                      <>
                        {' '}
                        · out <code title={s.output_hash}>{shortHash(s.output_hash)}</code>
                      </>
                    )}
                  </div>
                </li>
              ))}
            </ol>
          )}
        </li>
      ))}
    </ol>
  );
}
