import { useMutation } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router';
import { ApiError } from '../../api/client';
import type { CcdaConversion, ValidationOutcome } from '../../api/types';
import { useApi, useServiceDown } from '../../app/context';
import { Badge, JsonView, PageHeader } from '../../components/common';
import { ProblemView } from '../../components/ProblemView';
import { Violations } from './Violations';

const FHIR = 'fhir-compliance-service';
const SAMPLE_PATIENT = JSON.stringify({ resourceType: 'Patient', id: 'example', gender: 'female', birthDate: '1980-02-14' }, null, 2);

/** Side-effect-free tools (allowed for viewers too): validate a FHIR resource, convert a C-CDA. Nothing is stored. */
export function FhirToolsPage() {
  const [tab, setTab] = useState<'validate' | 'convert'>('validate');
  return (
    <>
      <PageHeader
        title="Validate & convert"
        subtitle="Check a FHIR resource against US Core 6.1.0 with the HL7 validator, or convert a C-CDA document. Nothing is saved."
        actions={
          <Link className="btn" to="/fhir">
            Back to resources
          </Link>
        }
      />
      <div className="tabs" role="tablist">
        <button role="tab" aria-selected={tab === 'validate'} className={tab === 'validate' ? 'active' : ''} onClick={() => setTab('validate')}>
          Validate FHIR JSON
        </button>
        <button role="tab" aria-selected={tab === 'convert'} className={tab === 'convert' ? 'active' : ''} onClick={() => setTab('convert')}>
          Convert C-CDA
        </button>
      </div>
      {tab === 'validate' ? <ValidateTool /> : <ConvertTool />}
    </>
  );
}

function ValidateTool() {
  const api = useApi();
  const down = useServiceDown(FHIR);
  const [text, setText] = useState(SAMPLE_PATIENT);
  const [parseError, setParseError] = useState<string | null>(null);
  const validate = useMutation({
    mutationFn: (body: string) => api.post<ValidationOutcome>('/fhir/validate', body, { contentType: 'application/fhir+json' }),
  });
  function run() {
    try {
      JSON.parse(text);
      setParseError(null);
    } catch (e) {
      setParseError(e instanceof Error ? e.message : 'Not valid JSON');
      return;
    }
    validate.mutate(text);
  }
  return (
    <section className="card">
      <label className="block" htmlFor="fhir-json">
        FHIR resource (JSON)
      </label>
      <textarea id="fhir-json" className="code" rows={14} value={text} onChange={(e) => setText(e.target.value)} spellCheck={false} />
      <div className="actions">
        <button className="btn btn-primary" onClick={run} disabled={down || validate.isPending} title={down ? `${FHIR} is down` : undefined}>
          {validate.isPending ? 'Validating…' : 'Validate'}
        </button>
      </div>
      {parseError && <div className="problem">Not valid JSON: {parseError}</div>}
      {validate.error && <ProblemView error={validate.error} context="Validating" />}
      {validate.data && <Outcome outcome={validate.data} />}
    </section>
  );
}

function Outcome({ outcome }: { outcome: ValidationOutcome }) {
  return (
    <div className="result">
      <p>
        <Badge tone={outcome.compliant ? 'good' : 'bad'}>{outcome.compliant ? 'Compliant' : 'Not compliant'}</Badge> {outcome.resourceType}
        {outcome.resourceId ? `/${outcome.resourceId}` : ''} · {outcome.errorCount} error(s), {outcome.warningCount} warning(s)
        {outcome.profile && <span className="muted"> · {outcome.profile}</span>}
      </p>
      <Violations violations={outcome.violations} />
    </div>
  );
}

function ConvertTool() {
  const api = useApi();
  const down = useServiceDown(FHIR);
  const [xml, setXml] = useState('');
  const convert = useMutation({
    mutationFn: (body: string) => api.post<CcdaConversion>('/fhir/convert/ccda', body, { contentType: 'application/xml' }),
  });
  async function loadFile(file: File | undefined) {
    if (file) setXml(await file.text());
  }
  return (
    <section className="card">
      <label className="block" htmlFor="ccda-xml">
        C-CDA document (XML)
      </label>
      <input type="file" accept=".xml,text/xml,application/xml" aria-label="Load a C-CDA file" onChange={(e) => void loadFile(e.target.files?.[0])} />
      <textarea id="ccda-xml" className="code" rows={12} value={xml} onChange={(e) => setXml(e.target.value)} spellCheck={false} placeholder="<ClinicalDocument xmlns=&quot;urn:hl7-org:v3&quot;>…" />
      <div className="actions">
        <button className="btn btn-primary" onClick={() => convert.mutate(xml)} disabled={down || !xml.trim() || convert.isPending} title={down ? `${FHIR} is down` : undefined}>
          {convert.isPending ? 'Converting…' : 'Convert'}
        </button>
      </div>
      {convert.error && (
        <ProblemView
          error={convert.error}
          context={convert.error instanceof ApiError && convert.error.status === 400 ? 'The document was rejected' : 'Converting'}
        />
      )}
      {convert.data && <Conversion result={convert.data} />}
    </section>
  );
}

function Conversion({ result }: { result: CcdaConversion }) {
  return (
    <div className="result">
      <p>
        <strong>{result.documentTitle ?? 'Untitled document'}</strong> · {result.resourceCount} resource(s),{' '}
        <Badge tone={result.allCompliant ? 'good' : 'bad'}>
          {result.compliantCount}/{result.resourceCount} compliant
        </Badge>
      </p>
      <ul className="plain">
        {result.sections.map((s) => (
          <li key={s}>{s}</li>
        ))}
      </ul>
      {result.notes.length > 0 && (
        <div className="banner banner-info">
          <strong>Transformer notes</strong>
          <ul className="plain">
            {result.notes.map((n, i) => (
              <li key={i}>{n}</li>
            ))}
          </ul>
        </div>
      )}
      {result.resources.map((r) => (
        <details key={r.fullUrl} className="resource-result">
          <summary>
            <Badge tone={r.validation.compliant ? 'good' : 'bad'}>{r.validation.compliant ? 'compliant' : 'not compliant'}</Badge> {r.resourceType} ·{' '}
            {r.sourceSection} · {r.validation.errorCount} error(s), {r.validation.warningCount} warning(s)
          </summary>
          <Violations violations={r.validation.violations} />
        </details>
      ))}
      <details>
        <summary>Bundle JSON</summary>
        <JsonView value={result.bundle} />
      </details>
    </div>
  );
}
