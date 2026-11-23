import { Link } from 'react-router';
import type { LineageRecord } from '../../api/types';
import { Badge } from '../../components/common';
import { fmtDateTime, shortHash } from '../../format';

/**
 * Turns a lineage step ref into a link to that record's page: bronze.raw_messages/12, silver.<table>/7 (via the
 * Silver patient it belongs to), gold.master_patients/3, and gold.fhir_resources/46 (when the record names the
 * resource, e.g. "AllergyIntolerance/silver-allergy-165").
 */
export function refLink(ref: string | undefined, fhirTarget?: string) {
  if (!ref) return null;
  const bronze = /^bronze\.raw_messages\/(\d+)$/.exec(ref);
  if (bronze) return <Link to={`/lineage/bronze/${bronze[1]}`}>{ref}</Link>;
  const silverPatient = /^silver\.patients\/(\d+)$/.exec(ref);
  if (silverPatient) return <Link to={`/lineage/silver/${silverPatient[1]}`}>{ref}</Link>;
  const silverRow = /^silver\.(conditions|observations|medications|allergies|claims)\/(\d+)$/.exec(ref);
  if (silverRow) return <Link to={`/lineage/silver/${silverRow[1]}/${silverRow[2]}`}>{ref}</Link>;
  const master = /^gold\.master_patients\/(\d+)$/.exec(ref);
  if (master) return <Link to={`/patients/${master[1]}`}>{ref}</Link>;
  const fhir = /^gold\.fhir_resources\/\d+$/.test(ref) && fhirTarget && /^[A-Z][A-Za-z]+\/[^/]+$/.test(fhirTarget);
  if (fhir) {
    const [type, id] = fhirTarget!.split('/');
    return <Link to={`/fhir/${type}/${encodeURIComponent(id!)}`}>{ref}</Link>;
  }
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
                  {s.ref && <div>{refLink(s.ref, r.details?.target)}</div>}
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
