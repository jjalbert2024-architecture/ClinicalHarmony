import type { TimelineEvent } from '../../api/types';
import { fmtDate, fmtDateTime } from '../../format';

const KIND_LABEL: Record<string, string> = { CONDITION: 'Condition', OBSERVATION: 'Observation', MEDICATION: 'Medication' };

export function eventDate(e: TimelineEvent): string {
  return e.datePrecision === 'day' ? fmtDate(e.occurredAt) : fmtDateTime(e.occurredAt);
}

/**
 * Hand-drawn SVG overview of the events over time (no chart library): one dot per event, colored by
 * kind, positioned by date. Each dot has a <title> tooltip.
 */
export function TimelineStrip({ events }: { events: TimelineEvent[] }) {
  const dated = events.filter((e) => e.occurredAt);
  if (dated.length === 0) return null;
  const times = dated.map((e) => new Date(e.occurredAt!).getTime());
  const min = Math.min(...times);
  const max = Math.max(...times);
  const span = Math.max(max - min, 86_400_000);
  const width = 1000;
  const rows: Record<string, number> = { CONDITION: 18, OBSERVATION: 38, MEDICATION: 58 };
  const x = (t: number) => 20 + ((t - min) / span) * (width - 40);
  return (
    <svg className="timeline-strip" viewBox={`0 0 ${width} 86`} role="img" aria-label={`${dated.length} events from ${fmtDate(new Date(min).toISOString())} to ${fmtDate(new Date(max).toISOString())}`}>
      {Object.entries(rows).map(([kind, y]) => (
        <g key={kind}>
          <line x1={20} x2={width - 20} y1={y} y2={y} className="strip-axis" />
        </g>
      ))}
      {dated.map((e, i) => (
        <circle key={`${e.kind}-${e.id}`} cx={x(times[i]!)} cy={rows[e.kind] ?? 38} r={6} className={`dot dot-${e.kind.toLowerCase()} ${e.codeKnown ? '' : 'dot-unknown'}`}>
          <title>
            {KIND_LABEL[e.kind]} · {eventDate(e)} · {e.display ?? e.code ?? '(no code)'}
          </title>
        </circle>
      ))}
      <text x={20} y={80} className="strip-label">
        {fmtDate(new Date(min).toISOString())}
      </text>
      <text x={width - 20} y={80} textAnchor="end" className="strip-label">
        {fmtDate(new Date(max).toISOString())}
      </text>
    </svg>
  );
}

export function kindLabel(kind: string): string {
  return KIND_LABEL[kind] ?? kind;
}

/** HL7 OBX-8 / FHIR interpretation codes in words; anything else shows as the bare code. */
const FLAGS: Record<string, { text: string; tone: 'good' | 'warn' | 'bad' | 'muted' }> = {
  N: { text: 'N · normal', tone: 'good' },
  H: { text: 'H · high', tone: 'warn' },
  L: { text: 'L · low', tone: 'warn' },
  HH: { text: 'HH · critical high', tone: 'bad' },
  LL: { text: 'LL · critical low', tone: 'bad' },
  A: { text: 'A · abnormal', tone: 'warn' },
  AA: { text: 'AA · critical abnormal', tone: 'bad' },
};

export function labFlag(flag: string) {
  return FLAGS[flag.toUpperCase()] ?? { text: flag, tone: 'muted' as const };
}

/** "ref 70–99" / "ref ≥ 40" / "ref ≤ 200", or null without a range. */
export function referenceRange(low: number | null | undefined, high: number | null | undefined): string | null {
  const has = (n: number | null | undefined): n is number => n !== null && n !== undefined;
  if (has(low) && has(high)) return `ref ${low}–${high}`;
  if (has(low)) return `ref ≥ ${low}`;
  if (has(high)) return `ref ≤ ${high}`;
  return null;
}
