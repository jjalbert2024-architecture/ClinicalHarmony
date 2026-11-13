import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router';
import type { Rule } from '../../api/types';
import { useApi } from '../../app/context';
import { Badge, PageHeader, QueryState } from '../../components/common';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { StewardButton } from '../../components/StewardButton';
import { fmtDateTime, fmtNumber } from '../../format';

export function RulesPage() {
  const api = useApi();
  const queryClient = useQueryClient();
  const [toggling, setToggling] = useState<Rule | null>(null);
  const rules = useQuery({
    queryKey: ['validation', 'rules'],
    queryFn: ({ signal }) => api.get<Rule[]>('/validation/rules', undefined, signal),
  });
  const toggle = useMutation({
    mutationFn: (code: string) => api.put<Rule>(`/validation/rules/${encodeURIComponent(code)}/toggle`),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['validation'] }),
  });

  return (
    <>
      <PageHeader
        title="Rule catalog"
        subtitle="Every rule in ontology.clinical_rules, the service that evaluates it, and whether it can be switched on or off here."
        actions={
          <Link className="btn" to="/validation">
            Back to findings
          </Link>
        }
      />
      <section className="card">
        <QueryState query={rules} context="Loading rules">
          {(items) => (
            <table className="table">
              <thead>
                <tr>
                  <th>Rule</th>
                  <th>Category</th>
                  <th>Severity</th>
                  <th>Evaluated by</th>
                  <th className="num">Open findings</th>
                  <th>State</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {items.map((r) => (
                  <tr key={r.code} className={r.active ? '' : 'inactive'}>
                    <td>
                      <strong>{r.code}</strong>
                      <div>{r.name}</div>
                      {r.description && <div className="muted wrap">{r.description}</div>}
                    </td>
                    <td>{r.category}</td>
                    <td>
                      <Badge value={r.defaultSeverity} />
                    </td>
                    <td>{r.evaluatedBy}</td>
                    <td className="num">
                      <Link to={`/validation?ruleCode=${encodeURIComponent(r.code)}&resolved=false`}>{fmtNumber(r.openFindings)}</Link>
                    </td>
                    <td>
                      <Badge tone={r.active ? 'good' : 'muted'}>{r.active ? 'Active' : 'Off'}</Badge>
                      <div className="muted small">{fmtDateTime(r.updatedAt)}</div>
                    </td>
                    <td>
                      {r.toggleable ? (
                        <StewardButton service={r.evaluatedBy} className="btn btn-small" onClick={() => setToggling(r)}>
                          {r.active ? 'Turn off…' : 'Turn on…'}
                        </StewardButton>
                      ) : (
                        <span className="muted small" title={`${r.evaluatedBy} has no toggle endpoint for this rule`}>
                          not toggleable
                        </span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </QueryState>
      </section>
      {toggling && (
        <ConfirmDialog
          title={`${toggling.active ? 'Turn off' : 'Turn on'} ${toggling.code}?`}
          confirmLabel={toggling.active ? 'Turn off' : 'Turn on'}
          tone={toggling.active ? 'danger' : 'primary'}
          onConfirm={() => toggle.mutateAsync(toggling.code)}
          onClose={() => setToggling(null)}
        >
          <p>
            <strong>{toggling.name}</strong> is evaluated by <strong>{toggling.evaluatedBy}</strong>. The change takes effect on its next
            evaluation (rules are re-read on every call).
          </p>
          <p>
            {toggling.active
              ? 'While off, it raises no new findings. Existing findings stay as they are.'
              : 'Once on, it raises findings again on the next evaluation.'}
          </p>
        </ConfirmDialog>
      )}
    </>
  );
}
