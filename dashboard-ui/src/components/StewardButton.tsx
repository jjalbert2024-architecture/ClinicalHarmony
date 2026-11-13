import type { ButtonHTMLAttributes } from 'react';
import { useCanAct, useServiceDown } from '../app/context';

interface Props extends ButtonHTMLAttributes<HTMLButtonElement> {
  /** The service that performs the action; disabled with a reason while it's down. */
  service: string;
}

/**
 * A button for a data-steward action. Viewers see it disabled with the reason, so the capability is
 * visible but not usable; the gateway enforces the role regardless (403 otherwise).
 */
export function StewardButton({ service, className, disabled, title, children, ...rest }: Props) {
  const canAct = useCanAct();
  const down = useServiceDown(service);
  const reason = !canAct ? 'Requires the data-steward role' : down ? `${service} is down` : undefined;
  return (
    <button
      {...rest}
      className={className ?? 'btn btn-primary'}
      disabled={disabled || reason !== undefined}
      title={reason ?? title}
      aria-description={reason}
    >
      {children}
    </button>
  );
}
