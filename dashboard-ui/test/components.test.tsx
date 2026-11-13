import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { ApiError } from '../src/api/client';
import { ConfirmDialog } from '../src/components/ConfirmDialog';
import { ProblemView } from '../src/components/ProblemView';

const downstream = new ApiError(
  'problem',
  {
    type: 'urn:clinicalharmony:problem:downstream-unavailable',
    title: 'Service Unavailable',
    status: 503,
    detail: 'patient-index-service is unavailable',
    service: 'patient-index-service',
    downstreamStatus: 0,
    correlationId: 'ui-abc123',
  },
  'ui-abc123',
);

describe('ProblemView', () => {
  it('shows title, status, detail, the service and the correlation id', () => {
    render(<ProblemView error={downstream} context="Running matching" />);
    const alert = screen.getByRole('alert');
    expect(alert).toHaveTextContent('Running matching: Service Unavailable · HTTP 503');
    expect(alert).toHaveTextContent('patient-index-service is unavailable');
    expect(alert).toHaveTextContent('Service: patient-index-service');
    expect(screen.getByTestId('correlation-id')).toHaveTextContent('ui-abc123');
  });

  it('explains a 403 with the role it needs', () => {
    const forbidden = new ApiError(
      'problem',
      { type: 'urn:clinicalharmony:problem:forbidden', title: 'Forbidden', status: 403, detail: 'lacks role', requiredRole: 'data-steward' },
      'ui-1',
    );
    render(<ProblemView error={forbidden} />);
    expect(screen.getByRole('alert')).toHaveTextContent('Not allowed');
    expect(screen.getByRole('alert')).toHaveTextContent('This needs the data-steward role');
  });

  it('lists parameter errors', () => {
    const bad = new ApiError(
      'problem',
      {
        type: 'urn:clinicalharmony:problem:invalid-request',
        title: 'Bad Request',
        status: 400,
        detail: 'size: must be less than or equal to 200',
        errors: [{ parameter: 'size', message: 'must be less than or equal to 200' }],
      },
      'ui-2',
    );
    render(<ProblemView error={bad} />);
    expect(screen.getByRole('listitem')).toHaveTextContent('size must be less than or equal to 200');
  });

  it('offers a retry', async () => {
    const retry = vi.fn();
    render(<ProblemView error={downstream} onRetry={retry} />);
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(retry).toHaveBeenCalled();
  });
});

describe('ConfirmDialog', () => {
  it('keeps the confirm button disabled until a required reason is typed, then passes it', async () => {
    const onConfirm = vi.fn(async () => undefined);
    const onClose = vi.fn();
    render(
      <ConfirmDialog title="Unmerge?" confirmLabel="Unmerge" text={{ label: 'Reason', required: true }} onConfirm={onConfirm} onClose={onClose}>
        body
      </ConfirmDialog>,
    );
    const confirm = screen.getByRole('button', { name: 'Unmerge' });
    expect(confirm).toBeDisabled();
    await userEvent.type(screen.getByLabelText(/Reason/), '  different DOB  ');
    expect(confirm).toBeEnabled();
    await userEvent.click(confirm);
    expect(onConfirm).toHaveBeenCalledWith('different DOB');
    expect(onClose).toHaveBeenCalled();
  });

  it('stays open and shows the problem when the action fails', async () => {
    const onClose = vi.fn();
    render(
      <ConfirmDialog title="Run?" confirmLabel="Run" onConfirm={() => Promise.reject(downstream)} onClose={onClose}>
        body
      </ConfirmDialog>,
    );
    await userEvent.click(screen.getByRole('button', { name: 'Run' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('patient-index-service is unavailable');
    expect(onClose).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: 'Run' })).toBeEnabled();
  });

  it('closes on Escape and Cancel without acting', async () => {
    const onConfirm = vi.fn(async () => undefined);
    const onClose = vi.fn();
    render(
      <ConfirmDialog title="Run?" confirmLabel="Run" onConfirm={onConfirm} onClose={onClose}>
        body
      </ConfirmDialog>,
    );
    await userEvent.keyboard('{Escape}');
    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(onClose).toHaveBeenCalledTimes(2);
    expect(onConfirm).not.toHaveBeenCalled();
  });
});
