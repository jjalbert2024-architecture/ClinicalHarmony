import { useQuery } from '@tanstack/react-query';
import { Link, useParams } from 'react-router';
import type { ResourceDetail } from '../../api/types';
import { useApi } from '../../app/context';
import { Badge, Empty, JsonView, PageHeader, QueryState } from '../../components/common';
import { fmtDateTime } from '../../format';
import { LineageChain } from '../lineage/LineageChain';
import { Violations } from './Violations';

export function FhirResourcePage() {
  const { type = '', fhirId = '' } = useParams();
  const api = useApi();
  const detail = useQuery({
    queryKey: ['fhir', 'resource', type, fhirId],
    queryFn: ({ signal }) =>
      api.get<ResourceDetail>(`/fhir/resources/${encodeURIComponent(type)}/${encodeURIComponent(fhirId)}`, undefined, signal),
  });

  return (
    <QueryState query={detail} context={`Loading ${type}/${fhirId}`}>
      {(d) => {
        const notes = d.complianceNotes;
        return (
          <>
            <PageHeader
              title={`${d.summary.resourceType}/${d.summary.fhirId}`}
              subtitle={
                <>
                  <Badge tone={d.summary.usCoreCompliant ? 'good' : 'bad'}>
                    {d.summary.usCoreCompliant ? 'US Core compliant' : 'Not US Core compliant'}
                  </Badge>{' '}
                  Gold id {d.summary.id} · updated {fmtDateTime(d.summary.updatedAt)}
                  {d.summary.masterPatientId && (
                    <>
                      {' '}
                      · <Link to={`/patients/${d.summary.masterPatientId}`}>master #{d.summary.masterPatientId}</Link>
                    </>
                  )}
                </>
              }
              actions={
                <Link className="btn" to={`/lineage?fhirResourceId=${d.summary.id}`}>
                  Full lineage
                </Link>
              }
            />
            <div className="grid-2">
              <section className="card">
                <h2>Validation</h2>
                {notes ? (
                  <>
                    <dl className="kv">
                      {notes.profile && (
                        <>
                          <dt>Profile</dt>
                          <dd className="wrap">{notes.profile}</dd>
                        </>
                      )}
                      {notes.validator && (
                        <>
                          <dt>Validator</dt>
                          <dd className="wrap">{notes.validator}</dd>
                        </>
                      )}
                      {notes.status && (
                        <>
                          <dt>Status</dt>
                          <dd>
                            <Badge value={notes.status} />
                          </dd>
                        </>
                      )}
                    </dl>
                    <Violations violations={notes.violations} />
                  </>
                ) : (
                  <Empty>
                    No HL7 validator notes stored for this resource. It came from the structural US Core check (Phase 5), which records only
                    compliant / not compliant.
                  </Empty>
                )}
              </section>
              <section className="card">
                <h2>Lineage</h2>
                {d.lineage.length === 0 ? <Empty>No lineage recorded.</Empty> : <LineageChain records={d.lineage} />}
              </section>
            </div>
            <section className="card">
              <h2>Resource</h2>
              <JsonView value={d.resource} />
            </section>
          </>
        );
      }}
    </QueryState>
  );
}
