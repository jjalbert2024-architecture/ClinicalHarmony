import type { Violation } from '../../api/types';
import { Badge, Empty } from '../../components/common';

export function Violations({ violations }: { violations: Violation[] | undefined }) {
  if (!violations || violations.length === 0) return <Empty>No validator messages.</Empty>;
  return (
    <table className="table">
      <thead>
        <tr>
          <th>Severity</th>
          <th>Location (FHIRPath)</th>
          <th>Message</th>
          <th>Suggested fix</th>
        </tr>
      </thead>
      <tbody>
        {violations.map((v, i) => (
          <tr key={i}>
            <td>
              <Badge value={v.severity} />
            </td>
            <td>
              <code>{v.location ?? '—'}</code>
              {v.rule && <div className="muted small">{v.rule}</div>}
            </td>
            <td className="wrap">{v.message}</td>
            <td className="wrap">{v.suggestedFix ?? '—'}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
