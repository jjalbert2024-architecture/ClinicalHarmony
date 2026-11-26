import type { AttributeFacet } from '../../api/types';

/** URL / gateway parameter prefix of the per-type filters. */
export const ATTR = 'attr.';

/** Friendlier names for values that are URIs (Condition.code.coding.system) or FHIR booleans. */
const VALUE_LABELS: Record<string, string> = {
  'http://hl7.org/fhir/sid/icd-10-cm': 'ICD-10-CM',
  'http://snomed.info/sct': 'SNOMED CT',
  'http://loinc.org': 'LOINC',
  'http://www.nlm.nih.gov/research/umls/rxnorm': 'RxNorm',
  true: 'yes',
  false: 'no',
};

/** Observation.interpretation codes (v3 ObservationInterpretation) in words. */
const INTERPRETATION: Record<string, string> = {
  N: 'N · normal',
  H: 'H · high',
  L: 'L · low',
  HH: 'HH · critical high',
  LL: 'LL · critical low',
  A: 'A · abnormal',
  AA: 'AA · critical abnormal',
};

export function valueLabel(facet: Pick<AttributeFacet, 'key'>, value: string) {
  if (facet.key === 'interpretation') return INTERPRETATION[value] ?? value;
  return VALUE_LABELS[value] ?? value;
}

/**
 * The selected type's own filters (Patient gender / birth date / state…, Observation status / interpretation…),
 * served by the gateway with the values Gold actually holds. Dropdowns apply at once (onPick); text and date
 * boxes are drafts applied by the form's Search, like the generic text filters.
 */
export function AttributeFilters({
  type,
  facets,
  applied,
  draft,
  onDraft,
  onPick,
}: {
  type: string;
  facets: AttributeFacet[];
  applied: (name: string) => string;
  draft: Record<string, string>;
  onDraft: (name: string, value: string) => void;
  onPick: (name: string, value: string) => void;
}) {
  if (facets.length === 0) return null;
  const box = (name: string, label: string, placeholder: string, title: string) => (
    <label key={name} title={title}>
      {label}
      <input
        aria-label={label}
        placeholder={placeholder}
        value={draft[name] ?? ''}
        onChange={(e) => onDraft(name, e.target.value)}
        maxLength={100}
        size={placeholder.length + 2}
      />
    </label>
  );
  return (
    <>
      <div className="filters-break" role="group" aria-label={`${type} attributes`}>
        {type} attributes
      </div>
      {facets.map((f) => {
        const name = ATTR + f.key;
        switch (f.kind) {
          case 'CHOICE':
            return (
              <label key={f.key} title={f.path}>
                {f.label}
                <select aria-label={f.label} value={applied(name)} onChange={(e) => onPick(name, e.target.value)}>
                  <option value="">Any</option>
                  {f.values.map((v) => (
                    <option key={v.value} value={v.value}>
                      {valueLabel(f, v.value)} ({v.count})
                    </option>
                  ))}
                  {/* A value from the URL that no resource has (any more) still shows as picked. */}
                  {applied(name) && !f.values.some((v) => v.value === applied(name)) && (
                    <option value={applied(name)}>{applied(name)} (0)</option>
                  )}
                </select>
              </label>
            );
          case 'FLAG':
            return (
              <label key={f.key} title={f.path}>
                {f.label}
                <select aria-label={f.label} value={applied(name)} onChange={(e) => onPick(name, e.target.value)}>
                  <option value="">Any</option>
                  <option value="yes">Yes</option>
                  <option value="no">No</option>
                </select>
              </label>
            );
          case 'TEXT':
            return box(name, f.label, 'contains…', f.path);
          case 'DATE':
            return [
              box(`${name}From`, `${f.label} from`, 'YYYY[-MM[-DD]]', `${f.path}, inclusive`),
              box(`${name}To`, `${f.label} to`, 'YYYY[-MM[-DD]]', `${f.path}, inclusive`),
            ];
        }
      })}
    </>
  );
}

/** Names of the draft (typed, applied by Search) attribute parameters of these facets. */
export function draftNames(facets: AttributeFacet[]): string[] {
  return facets.flatMap((f) =>
    f.kind === 'TEXT' ? [ATTR + f.key] : f.kind === 'DATE' ? [`${ATTR}${f.key}From`, `${ATTR}${f.key}To`] : [],
  );
}
