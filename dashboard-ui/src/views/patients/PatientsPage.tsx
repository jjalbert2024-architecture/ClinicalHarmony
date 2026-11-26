import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { Link, useSearchParams } from 'react-router';
import type { MasterSummary, Page } from '../../api/types';
import { useApi } from '../../app/context';
import { Badge, Empty, PageHeader, Pager, PERSON_ORDERS, QueryState, readWindow, WindowFilter, windowChanges, windowParams, ListSummary } from '../../components/common';
import { fmtDate, fmtScore, fullName } from '../../format';

export function PatientsPage() {
  const api = useApi();
  const [params, setParams] = useSearchParams();
  const q = params.get('q') ?? '';
  const dob = params.get('dob') ?? '';
  const status = params.get('status') ?? '';
  const includeMerged = params.get('includeMerged') === 'true';
  const page = Number(params.get('page') ?? '0');
  const { window: win, size, keys } = readWindow(params, '', 25);

  const query = useQuery({
    queryKey: ['patients', q, dob, status, includeMerged, win, size, page],
    queryFn: ({ signal }) =>
      api.get<Page<MasterSummary>>(
        '/patients',
        { q, dob, status, includeMerged: includeMerged || undefined, ...windowParams(win), page, size },
        signal,
      ),
    placeholderData: keepPreviousData,
  });

  const [draft, setDraft] = useState({ q, dob, status, includeMerged });
  function submit(e: FormEvent) {
    e.preventDefault();
    const next = new URLSearchParams();
    if (draft.q.trim()) next.set('q', draft.q.trim());
    if (draft.dob) next.set('dob', draft.dob);
    if (draft.status) next.set('status', draft.status);
    if (draft.includeMerged) next.set('includeMerged', 'true');
    // a new search keeps the list's range, order and rows per page
    for (const key of Object.values(keys)) {
      const value = params.get(key);
      if (value) next.set(key, value);
    }
    setParams(next);
  }
  function goPage(p: number) {
    const next = new URLSearchParams(params);
    next.set('page', String(p));
    setParams(next);
  }
  /** Range / order / rows per page: back to page 1. */
  function setWindow(changes: Record<string, string>) {
    const next = new URLSearchParams(params);
    for (const [key, value] of Object.entries(changes)) {
      if (value) next.set(key, value);
      else next.delete(key);
    }
    next.delete('page');
    setParams(next);
  }

  return (
    <>
      <PageHeader title="Patient Timeline" subtitle="Find a master patient, then see every source feed's records on one timeline." />
      <form className="filters card" onSubmit={submit} role="search">
        <label>
          Name or MRN
          <input value={draft.q} onChange={(e) => setDraft({ ...draft, q: e.target.value })} maxLength={100} placeholder="e.g. Garcia" />
        </label>
        <label>
          Date of birth
          <input type="date" value={draft.dob} onChange={(e) => setDraft({ ...draft, dob: e.target.value })} />
        </label>
        <label>
          Match status
          <select value={draft.status} onChange={(e) => setDraft({ ...draft, status: e.target.value })}>
            <option value="">Any</option>
            <option value="PROVISIONAL">Provisional</option>
            <option value="MATCHED">Matched</option>
            <option value="MERGED">Merged</option>
          </select>
        </label>
        <label className="check">
          <input
            type="checkbox"
            checked={draft.includeMerged}
            onChange={(e) => setDraft({ ...draft, includeMerged: e.target.checked })}
          />
          Include absorbed (merged-away) masters
        </label>
        <button className="btn btn-primary" type="submit">
          Search
        </button>
      </form>
      <section className="card">
        <ListSummary page={query.data} noun="masters" unit="master" />
        <WindowFilter value={win} label="Master #" orders={PERSON_ORDERS} onApply={(w) => setWindow(windowChanges(keys, w))} />
        <QueryState query={query} context="Searching patients">
          {(data) =>
            data.items.length === 0 ? (
              <Empty>No master patients match.</Empty>
            ) : (
              <>
                <table className="table">
                  <thead>
                    <tr>
                      <th>Master</th>
                      <th>Name</th>
                      <th>Date of birth</th>
                      <th>Sex</th>
                      <th>Status</th>
                      <th className="num">Score</th>
                      <th className="num">Source records</th>
                      <th>Source systems</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.items.map((m) => (
                      <tr key={m.id} className={m.active ? '' : 'inactive'}>
                        <td>
                          <Link to={`/patients/${m.id}`}>#{m.id}</Link>
                        </td>
                        <td>
                          <Link to={`/patients/${m.id}`}>{fullName(m.firstName, m.lastName)}</Link>
                        </td>
                        <td>{fmtDate(m.dateOfBirth)}</td>
                        <td>{m.gender ?? '—'}</td>
                        <td>
                          <Badge value={m.matchStatus} />
                          {!m.active && <Badge tone="muted">inactive</Badge>}
                        </td>
                        <td className="num">{fmtScore(m.matchConfidenceScore)}</td>
                        <td className="num">{m.sourcePatientCount}</td>
                        <td className="muted">{(m.sourceSystems ?? []).join(', ')}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                <Pager page={data} onPage={goPage} onSize={(n) => setWindow({ [keys.size]: String(n) })} idOf={(m) => `#${m.id}`} />
              </>
            )
          }
        </QueryState>
      </section>
    </>
  );
}
