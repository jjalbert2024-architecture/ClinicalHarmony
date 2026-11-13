import { useEffect, useId, useRef, useState, type ReactNode } from 'react';
import { ProblemView } from './ProblemView';

interface Props {
  title: string;
  children: ReactNode;
  confirmLabel: string;
  tone?: 'primary' | 'danger';
  /** A free-text field (note / reason) passed to onConfirm. */
  text?: { label: string; required: boolean; placeholder?: string; maxLength?: number };
  onConfirm: (text: string) => Promise<unknown>;
  onClose: () => void;
}

/**
 * Every steward action goes through this: it states what will happen, optionally takes a note or
 * reason, runs the action, and shows the gateway's problem in place if it fails (the dialog stays
 * open so the user can read the correlation id or retry).
 */
export function ConfirmDialog({ title, children, confirmLabel, tone = 'primary', text, onConfirm, onClose }: Props) {
  const [value, setValue] = useState('');
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const titleId = useId();
  const fieldId = useId();
  const firstField = useRef<HTMLTextAreaElement | HTMLButtonElement | null>(null);

  useEffect(() => {
    firstField.current?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !pending) onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose, pending]);

  const missing = text?.required === true && value.trim() === '';

  async function confirm() {
    setPending(true);
    setError(null);
    try {
      await onConfirm(value.trim());
      onClose();
    } catch (e) {
      setError(e);
      setPending(false);
    }
  }

  return (
    <div className="modal-backdrop" onMouseDown={(e) => e.target === e.currentTarget && !pending && onClose()}>
      <div className="modal" role="dialog" aria-modal="true" aria-labelledby={titleId}>
        <h2 id={titleId}>{title}</h2>
        <div className="modal-body">{children}</div>
        {text && (
          <div className="field">
            <label htmlFor={fieldId}>
              {text.label}
              {text.required ? ' (required)' : ' (optional)'}
            </label>
            <textarea
              id={fieldId}
              ref={(el) => {
                firstField.current = el;
              }}
              rows={3}
              maxLength={text.maxLength ?? 500}
              placeholder={text.placeholder}
              value={value}
              onChange={(e) => setValue(e.target.value)}
            />
          </div>
        )}
        {error !== null && <ProblemView error={error} />}
        <div className="modal-actions">
          <button className="btn" onClick={onClose} disabled={pending}>
            Cancel
          </button>
          <button
            className={`btn ${tone === 'danger' ? 'btn-danger' : 'btn-primary'}`}
            onClick={() => void confirm()}
            disabled={pending || missing}
            ref={(el) => {
              if (!text) firstField.current = el;
            }}
          >
            {pending ? 'Working…' : confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}
