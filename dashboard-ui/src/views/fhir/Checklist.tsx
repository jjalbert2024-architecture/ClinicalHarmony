import type { ChecklistItem } from '../../api/types';
import { Badge, Empty } from '../../components/common';

type Status = 'present' | 'missing' | 'not-applicable' | 'allowed-absent';

/** A depth-2 element only counts when its parent is in the resource ("name.family" needs a "name"). */
function statusOf(item: ChecklistItem, byPath: Map<string, ChecklistItem>): Status {
  if (item.present) return 'present';
  if (item.depth === 2) {
    const parent = byPath.get(item.path.slice(0, item.path.lastIndexOf('.')));
    if (parent && !parent.present) return 'not-applicable';
  }
  return item.required ? 'missing' : 'allowed-absent';
}

const STATUS_LABEL: Record<Status, { text: string; tone: 'good' | 'bad' | 'muted' }> = {
  present: { text: 'Present', tone: 'good' },
  missing: { text: 'Missing', tone: 'bad' },
  'not-applicable': { text: 'n/a (parent absent)', tone: 'muted' },
  'allowed-absent': { text: 'Not sent (allowed)', tone: 'muted' },
};

/**
 * "Why compliant": every element the profile requires, marks must-support or lists as a USCDI
 * requirement, and whether this resource has it. Recorded by fhir-compliance-service at validation time.
 */
export function Checklist({ items, compliant }: { items: ChecklistItem[] | undefined; compliant: boolean }) {
  if (!items || items.length === 0) {
    return <Empty>No element checklist was recorded for this resource (validated before the checklist existed, or no profile applies).</Empty>;
  }
  const byPath = new Map(items.map((i) => [i.path, i]));
  const rows = items.map((item) => ({ item, status: statusOf(item, byPath) }));
  const required = rows.filter((r) => r.item.required && r.status !== 'not-applicable');
  const optional = rows.filter((r) => !r.item.required && r.status !== 'not-applicable');
  const count = (list: typeof rows) => list.filter((r) => r.status === 'present').length;

  return (
    <>
      <p className="checklist-summary">
        {compliant ? 'Compliant because every required element is present: ' : 'Checked for: '}
        <strong>
          required {count(required)} of {required.length}
        </strong>{' '}
        present · must-support / USCDI {count(optional)} of {optional.length} present (the rest may be left out when the
        source has no data).
      </p>
      <table className="table checklist">
        <thead>
          <tr>
            <th>Element</th>
            <th>Profile says</th>
            <th>In this resource</th>
          </tr>
        </thead>
        <tbody>
          {rows.map(({ item, status }) => (
            <tr key={item.path} className={status === 'missing' ? 'checklist-missing' : undefined}>
              <td className={item.depth === 2 ? 'checklist-child' : undefined}>
                <code>{item.label}</code>
                <div className="muted small">{item.path}</div>
              </td>
              <td>
                {item.required && <Badge tone="info">Required</Badge>} {item.mustSupport && <Badge tone="muted">Must support</Badge>}{' '}
                {item.uscdi && <Badge tone="muted">USCDI</Badge>}
              </td>
              <td>
                <Badge tone={STATUS_LABEL[status].tone}>{STATUS_LABEL[status].text}</Badge>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <p className="muted small">
        Required = must be present to conform. Must support = send it whenever the source has it. USCDI = an Additional
        USCDI Requirement in US Core 6.1 (e.g. race, ethnicity). The validator checks deeper rules too (value sets,
        invariants); this list is the element-level view.
      </p>
    </>
  );
}
