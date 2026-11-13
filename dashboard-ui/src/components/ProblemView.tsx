import { useState } from 'react';
import { ApiError } from '../api/client';

interface Props {
  error: unknown;
  onRetry?: () => void;
  /** What the user was doing, e.g. "Loading findings" — prefixed to the title. */
  context?: string;
}

/**
 * The one way errors are shown. Every gateway error is an RFC 7807 problem; this renders its title,
 * detail, the downstream service involved, parameter errors, and the correlation id (copyable) so a
 * user can hand it to whoever reads the gateway's and services' logs.
 */
export function ProblemView({ error, onRetry, context }: Props) {
  if (!(error instanceof ApiError)) {
    const message = error instanceof Error ? error.message : String(error);
    return (
      <div className="problem" role="alert">
        <div className="problem-title">{context ? `${context}: ` : ''}Unexpected error</div>
        <div className="problem-detail">{message}</div>
        {onRetry && <button className="btn btn-small" onClick={onRetry}>Try again</button>}
      </div>
    );
  }
  const p = error.problem;
  const title = error.status === 403 ? 'Not allowed' : p.title;
  return (
    <div className={`problem ${error.status >= 500 || error.status === 0 ? 'problem-server' : ''}`} role="alert">
      <div className="problem-title">
        {context ? `${context}: ` : ''}
        {title}
        {error.status > 0 && <span className="problem-status"> · HTTP {error.status}</span>}
      </div>
      {p.detail && <div className="problem-detail">{p.detail}</div>}
      {error.status === 403 && p.requiredRole && (
        <div className="problem-detail">
          This needs the <code>{p.requiredRole}</code> role. Ask an administrator, or sign in as a data steward.
        </div>
      )}
      {p.errors && p.errors.length > 0 && (
        <ul className="problem-errors">
          {p.errors.map((e, i) => (
            <li key={i}>
              {(e.parameter ?? e.field) && <code>{e.parameter ?? e.field}</code>} {e.message}
            </li>
          ))}
        </ul>
      )}
      <div className="problem-meta">
        {p.service && (
          <span>
            Service: <strong>{p.service}</strong>
            {p.downstreamStatus ? ` (answered ${p.downstreamStatus})` : ''}
          </span>
        )}
        <CorrelationId id={error.correlationId} />
        {onRetry && (
          <button className="btn btn-small" onClick={onRetry}>
            Try again
          </button>
        )}
      </div>
    </div>
  );
}

export function CorrelationId({ id }: { id: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <span className="correlation">
      Correlation id: <code data-testid="correlation-id">{id}</code>
      <button
        type="button"
        className="btn-link"
        onClick={() => {
          void navigator.clipboard?.writeText(id).then(() => setCopied(true), () => undefined);
        }}
      >
        {copied ? 'Copied' : 'Copy'}
      </button>
    </span>
  );
}
