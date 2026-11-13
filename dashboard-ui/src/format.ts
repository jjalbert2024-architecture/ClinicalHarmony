// Display helpers. Timestamps are shown in the viewer's local time zone; DATE-precision values (the
// gateway reports them as UTC midnight, datePrecision "day") are shown as the calendar date in UTC so
// they never shift a day.

const dateTime = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' });
const dateOnlyUtc = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeZone: 'UTC' });
const integer = new Intl.NumberFormat();

export function fmtDateTime(value: string | null | undefined): string {
  if (!value) return '—';
  const d = new Date(value);
  return Number.isNaN(d.getTime()) ? value : dateTime.format(d);
}

/** A date with day precision: a plain `YYYY-MM-DD` or a UTC-midnight timestamp. */
export function fmtDate(value: string | null | undefined): string {
  if (!value) return '—';
  const iso = /^\d{4}-\d{2}-\d{2}$/.test(value) ? `${value}T00:00:00Z` : value;
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? value : dateOnlyUtc.format(d);
}

export function fmtNumber(value: number | null | undefined): string {
  return value === null || value === undefined ? '—' : integer.format(value);
}

export function fmtPercent(value: number | null | undefined, digits = 0): string {
  return value === null || value === undefined ? '—' : `${value.toFixed(digits)}%`;
}

export function fmtScore(value: number | null | undefined): string {
  return value === null || value === undefined ? '—' : value.toFixed(3);
}

export function fullName(first: string | null | undefined, last: string | null | undefined): string {
  const name = [first, last].filter((s) => s && s.trim()).join(' ');
  return name || '(no name)';
}

export function shortHash(hash: string | null | undefined): string {
  return hash ? `${hash.slice(0, 10)}…` : '—';
}

/** "Condition/silver-condition-5" → the FHIR viewer route for it. */
export function fhirRoute(reference: string | null | undefined): string | null {
  if (!reference) return null;
  const [type, id] = reference.split('/');
  return type && id ? `/fhir/${encodeURIComponent(type)}/${encodeURIComponent(id)}` : null;
}
