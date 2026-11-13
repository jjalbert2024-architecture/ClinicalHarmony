import { useMutation, useQuery } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { Link } from 'react-router';
import type { CodeLookup, ConceptMapping, Coverage } from '../../api/types';
import { useApi, useServiceDown } from '../../app/context';
import { Badge, Bar, Empty, PageHeader, QueryState } from '../../components/common';
import { ProblemView } from '../../components/ProblemView';
import { fmtNumber, fmtPercent } from '../../format';

const ONTOLOGY = 'ontology-service';

export function OntologyPage() {
  const api = useApi();
  const coverage = useQuery({
    queryKey: ['ontology', 'coverage'],
    queryFn: ({ signal }) => api.get<Coverage>('/ontology/coverage', undefined, signal),
  });

  return (
    <>
      <PageHeader
        title="Ontology Coverage"
        subtitle="How much of the terminology Silver actually uses resolves in the reference sets, and where it doesn't."
      />
      <QueryState query={coverage} context="Loading coverage">
        {(c) => (
          <>
            <div className="grid-4">
              {c.terminologies.map((t) => (
                <section className="card" key={t.terminology}>
                  <h2>{t.terminology}</h2>
                  <div className="big">{fmtPercent(t.coveragePercent)}</div>
                  <Bar
                    value={t.knownDistinctCodes}
                    max={t.distinctCodes}
                    tone={t.coveragePercent === null ? 'muted' : t.coveragePercent >= 95 ? 'good' : t.coveragePercent >= 75 ? 'warn' : 'bad'}
                    label={`${t.terminology} coverage`}
                  />
                  <dl className="kv">
                    <dt>Distinct codes used</dt>
                    <dd>
                      {fmtNumber(t.knownDistinctCodes)} of {fmtNumber(t.distinctCodes)} known
                    </dd>
                    <dt>Usages</dt>
                    <dd>
                      {fmtNumber(t.knownUsages)} of {fmtNumber(t.usages)} known
                    </dd>
                    <dt>Reference set</dt>
                    <dd>{fmtNumber(t.referenceSetSize)} codes</dd>
                    <dt>Open {t.validationRule} findings</dt>
                    <dd>
                      <Link to={`/validation?ruleCode=${t.validationRule}&resolved=false`}>{fmtNumber(t.openFindings)}</Link>
                    </dd>
                  </dl>
                  {t.topUnknownCodes.length > 0 && (
                    <>
                      <h3>Top unknown codes</h3>
                      <ul className="plain">
                        {t.topUnknownCodes.map((u) => (
                          <li key={u.code}>
                            <code>{u.code || '(blank)'}</code> × {u.usages}
                          </li>
                        ))}
                      </ul>
                    </>
                  )}
                </section>
              ))}
            </div>
            <div className="grid-2">
              <section className="card">
                <h2>Cross-terminology mapping coverage</h2>
                {c.mappingCoverage.length === 0 ? (
                  <Empty>No mapping data.</Empty>
                ) : (
                  <table className="table">
                    <thead>
                      <tr>
                        <th>Used terminology</th>
                        <th>Maps to</th>
                        <th className="num">Codes with a mapping</th>
                        <th />
                      </tr>
                    </thead>
                    <tbody>
                      {c.mappingCoverage.map((m) => (
                        <tr key={`${m.usedTerminology}-${m.mapsTo}`}>
                          <td>{m.usedTerminology}</td>
                          <td>{m.mapsTo}</td>
                          <td className="num">
                            {m.withMapping} / {m.distinctUsedCodes}
                          </td>
                          <td className="bar-cell">
                            <Bar value={m.withMapping} max={m.distinctUsedCodes} label={`${m.usedTerminology} to ${m.mapsTo}`} />
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
              </section>
              <section className="card">
                <h2>Concept map contents</h2>
                <table className="table">
                  <thead>
                    <tr>
                      <th>Source</th>
                      <th>Target</th>
                      <th>Type</th>
                      <th className="num">Mappings</th>
                    </tr>
                  </thead>
                  <tbody>
                    {c.mappings.map((m) => (
                      <tr key={`${m.sourceSystem}-${m.targetSystem}-${m.mappingType}`}>
                        <td>{m.sourceSystem}</td>
                        <td>{m.targetSystem}</td>
                        <td>{m.mappingType}</td>
                        <td className="num">{m.count}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </section>
            </div>
          </>
        )}
      </QueryState>
      <div className="grid-2">
        <CodeLookupCard />
        <MapperCard />
      </div>
    </>
  );
}

function CodeLookupCard() {
  const api = useApi();
  const down = useServiceDown(ONTOLOGY);
  const [system, setSystem] = useState<'icd10' | 'loinc'>('icd10');
  const [code, setCode] = useState('');
  const lookup = useMutation({
    mutationFn: (v: { system: string; code: string }) =>
      api.get<CodeLookup>(`/ontology/codes/${v.system}/${encodeURIComponent(v.code)}`),
  });
  function submit(e: FormEvent) {
    e.preventDefault();
    if (code.trim()) lookup.mutate({ system, code: code.trim() });
  }
  return (
    <section className="card">
      <h2>Look up a code</h2>
      <form className="filters inline" onSubmit={submit}>
        <select value={system} onChange={(e) => setSystem(e.target.value as 'icd10' | 'loinc')} aria-label="Code system">
          <option value="icd10">ICD-10</option>
          <option value="loinc">LOINC</option>
        </select>
        <input value={code} onChange={(e) => setCode(e.target.value)} placeholder={system === 'icd10' ? 'E11.9' : '718-7'} aria-label="Code" maxLength={20} />
        <button className="btn btn-primary" disabled={down || !code.trim() || lookup.isPending} title={down ? `${ONTOLOGY} is down` : undefined}>
          Look up
        </button>
      </form>
      {lookup.error && (
        // 404 = not in the reference set: an answer, not a failure.
        <ProblemView error={lookup.error} />
      )}
      {lookup.data && (
        <div className="result">
          <Badge tone={lookup.data.valid ? 'good' : 'bad'}>{lookup.data.valid ? 'Valid' : 'Invalid'}</Badge> <code>{lookup.data.code}</code>{' '}
          {lookup.data.display}
          {lookup.data.category && <div className="muted">{lookup.data.category}</div>}
          {lookup.data.reason && <div className="muted">{lookup.data.reason}</div>}
        </div>
      )}
    </section>
  );
}

function MapperCard() {
  const api = useApi();
  const down = useServiceDown(ONTOLOGY);
  const [form, setForm] = useState({ source: 'SNOMED-CT', code: '', target: 'ICD-10' });
  const map = useMutation({
    mutationFn: (v: typeof form) => api.get<ConceptMapping[]>('/ontology/map', v),
  });
  function submit(e: FormEvent) {
    e.preventDefault();
    if (form.code.trim()) map.mutate({ ...form, code: form.code.trim() });
  }
  return (
    <section className="card">
      <h2>Map between terminologies</h2>
      <form className="filters inline" onSubmit={submit}>
        <select value={form.source} onChange={(e) => setForm({ ...form, source: e.target.value })} aria-label="Source terminology">
          <option value="SNOMED-CT">SNOMED CT</option>
          <option value="ICD-10">ICD-10</option>
        </select>
        <input value={form.code} onChange={(e) => setForm({ ...form, code: e.target.value })} placeholder="44054006" aria-label="Source code" maxLength={20} />
        <span>→</span>
        <select value={form.target} onChange={(e) => setForm({ ...form, target: e.target.value })} aria-label="Target terminology">
          <option value="ICD-10">ICD-10</option>
          <option value="SNOMED-CT">SNOMED CT</option>
        </select>
        <button className="btn btn-primary" disabled={down || !form.code.trim() || map.isPending} title={down ? `${ONTOLOGY} is down` : undefined}>
          Map
        </button>
      </form>
      {map.error && <ProblemView error={map.error} />}
      {map.data &&
        (map.data.length === 0 ? (
          <Empty>No mapping for that code.</Empty>
        ) : (
          <ul className="plain">
            {map.data.map((m) => (
              <li key={`${m.targetSystem}-${m.targetCode}`}>
                <code>{m.sourceCode}</code> → <code>{m.targetCode}</code> <Badge tone="info">{m.mappingType}</Badge>
                {m.notes && <div className="muted">{m.notes}</div>}
              </li>
            ))}
          </ul>
        ))}
    </section>
  );
}
