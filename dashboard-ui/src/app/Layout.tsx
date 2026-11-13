import { NavLink, Outlet } from 'react-router';
import { Badge } from '../components/common';
import { fmtDateTime } from '../format';
import { useHealth, useMe, useServices } from './context';

const NAV = [
  { to: '/', label: 'Overview', end: true },
  { to: '/patients', label: 'Patient Timeline' },
  { to: '/pmi', label: 'PMI Review' },
  { to: '/ontology', label: 'Ontology Coverage' },
  { to: '/validation', label: 'Validation Report' },
  { to: '/fhir', label: 'FHIR Viewer' },
  { to: '/lineage', label: 'Data Lineage' },
];

export function Layout() {
  return (
    <div className="app">
      <header className="topbar">
        <div className="brand">
          <span className="brand-mark" aria-hidden="true">+</span>
          ClinicalHarmony
        </div>
        <nav className="nav" aria-label="Main">
          {NAV.map((n) => (
            <NavLink key={n.to} to={n.to} end={n.end ?? false} className={({ isActive }) => (isActive ? 'active' : '')}>
              {n.label}
            </NavLink>
          ))}
        </nav>
        <div className="topbar-right">
          <HealthPill />
          <UserMenu />
        </div>
      </header>
      <DegradedBanner />
      <main className="content">
        <Outlet />
      </main>
    </div>
  );
}

function HealthPill() {
  const { data, error } = useHealth();
  if (error) return <Badge tone="bad">Gateway unreachable</Badge>;
  if (!data) return <Badge tone="muted">Checking…</Badge>;
  return (
    <NavLink to="/#services" className="health-pill" title={`Checked ${fmtDateTime(data.checkedAt)}`}>
      <Badge value={data.status}>{data.status === 'UP' ? 'All services up' : `Degraded (${data.degraded.length})`}</Badge>
    </NavLink>
  );
}

/**
 * Reads come from the gateway's database projections, so they keep working while a service is down;
 * only that service's actions fail. This banner says which, and StewardButton disables those actions.
 */
function DegradedBanner() {
  const { data } = useHealth();
  if (!data || data.degraded.length === 0) return null;
  return (
    <div className="banner banner-warn" role="status">
      <strong>Degraded:</strong> {data.degraded.join(', ')} {data.degraded.length === 1 ? 'is' : 'are'} not responding.
      Views still show stored data; actions that need {data.degraded.length === 1 ? 'it' : 'them'} are disabled.
    </div>
  );
}

function UserMenu() {
  const { session } = useServices();
  const { data: me } = useMe();
  return (
    <div className="user">
      {me ? (
        <span className="user-name" title={`Roles: ${me.roles.join(', ') || 'none'}`}>
          {me.name ?? me.username}
          <Badge tone={me.canAct ? 'info' : 'muted'}>{me.canAct ? 'Data steward' : 'Viewer'}</Badge>
        </span>
      ) : (
        <span className="user-name">…</span>
      )}
      <button className="btn btn-small" onClick={() => void session.logout()}>
        Sign out
      </button>
    </div>
  );
}
