import { useEffect, useId, useRef, useState, type ReactNode } from 'react';
import { ApiError } from '../api/client';

export function Toggle({ checked, onChange, disabled, label, size = 'md' }: {
  checked: boolean;
  onChange: (next: boolean) => void;
  disabled?: boolean;
  label: string;
  size?: 'sm' | 'md';
}) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      title={label}
      disabled={disabled}
      className={`toggle toggle-${size} ${checked ? 'on' : ''}`}
      onClick={() => onChange(!checked)}
    >
      <span className="toggle-thumb" />
    </button>
  );
}

export function Modal({ title, onClose, children, footer, wide }: {
  title: string;
  onClose: () => void;
  children: ReactNode;
  footer?: ReactNode;
  wide?: boolean;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  useEffect(() => {
    const dialog = ref.current;
    if (dialog && !dialog.open) dialog.showModal?.();
  }, []);
  return (
    <dialog ref={ref} className={`modal ${wide ? 'modal-wide' : ''}`} aria-labelledby={titleId} onClose={onClose}
            onCancel={(e) => { e.preventDefault(); onClose(); }}>
      <header className="modal-header">
        <h2 id={titleId}>{title}</h2>
        <button type="button" className="icon-btn" aria-label="Close" onClick={onClose}>×</button>
      </header>
      <div className="modal-body">{children}</div>
      {footer && <footer className="modal-footer">{footer}</footer>}
    </dialog>
  );
}

export function ErrorBanner({ error }: { error: unknown }) {
  if (!error) return null;
  const apiError = error instanceof ApiError ? error : null;
  const message = error instanceof Error ? error.message : String(error);
  return (
    <div className="banner banner-error" role="alert">
      <strong>{apiError?.title ?? 'Something went wrong'}</strong>
      {apiError?.errors.length ? (
        <ul>{apiError.errors.map((e) => <li key={e}>{e}</li>)}</ul>
      ) : (
        <span> {message}</span>
      )}
    </div>
  );
}

export function Badge({ children, tone = 'neutral', title }: {
  children: ReactNode;
  tone?: 'neutral' | 'success' | 'warning' | 'danger' | 'info' | 'accent';
  title?: string;
}) {
  return <span className={`badge badge-${tone}`} title={title}>{children}</span>;
}

export function EmptyState({ title, children, action }: { title: string; children?: ReactNode; action?: ReactNode }) {
  return (
    <div className="empty">
      <h3>{title}</h3>
      {children && <p>{children}</p>}
      {action}
    </div>
  );
}

export function Spinner({ label = 'Loading' }: { label?: string }) {
  return <div className="spinner" role="status" aria-label={label} />;
}

export function EnvDot({ color }: { color: string }) {
  return <span className="env-dot" style={{ background: color }} aria-hidden />;
}

export function CopyButton({ text, label = 'Copy' }: { text: string; label?: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <button type="button" className="btn btn-ghost btn-sm" onClick={async () => {
      try {
        await navigator.clipboard.writeText(text);
        setCopied(true);
        setTimeout(() => setCopied(false), 1500);
      } catch {
        /* clipboard unavailable */
      }
    }}>
      {copied ? 'Copied' : label}
    </button>
  );
}

/**
 * Chip-style multi value input: Enter or comma adds a value, Backspace on empty removes the
 * last one, and pasting a comma/newline separated list adds them all.
 */
export function ValuesInput({ values, onChange, placeholder, ariaLabel, suggestions }: {
  values: string[];
  onChange: (values: string[]) => void;
  placeholder?: string;
  ariaLabel: string;
  suggestions?: string[];
}) {
  const [draft, setDraft] = useState('');
  const listId = useId();
  const add = (raw: string) => {
    const parts = raw.split(/[,\n]/).map((s) => s.trim()).filter(Boolean);
    if (parts.length === 0) return;
    onChange([...values, ...parts.filter((p) => !values.includes(p))]);
    setDraft('');
  };
  return (
    <div className="values-input">
      {values.map((v) => (
        <span key={v} className="chip">
          {v}
          <button type="button" aria-label={`Remove ${v}`} onClick={() => onChange(values.filter((x) => x !== v))}>×</button>
        </span>
      ))}
      <input
        aria-label={ariaLabel}
        value={draft}
        list={suggestions ? listId : undefined}
        placeholder={values.length === 0 ? placeholder : ''}
        onChange={(e) => {
          const next = e.target.value;
          if (suggestions?.includes(next)) add(next);
          else setDraft(next);
        }}
        onKeyDown={(e) => {
          if (e.key === 'Enter' || e.key === ',') {
            e.preventDefault();
            add(draft);
          } else if (e.key === 'Backspace' && draft === '' && values.length > 0) {
            onChange(values.slice(0, -1));
          }
        }}
        onBlur={() => add(draft)}
        onPaste={(e) => {
          const text = e.clipboardData.getData('text');
          if (/[,\n]/.test(text)) {
            e.preventDefault();
            add(text);
          }
        }}
      />
      {suggestions && (
        <datalist id={listId}>{suggestions.filter((s) => !values.includes(s)).map((s) => <option key={s} value={s} />)}</datalist>
      )}
    </div>
  );
}

export function Field({ label, hint, children, error }: { label: string; hint?: ReactNode; children: ReactNode; error?: string }) {
  return (
    <label className="field">
      <span className="field-label">{label}</span>
      {children}
      {error ? <span className="field-error">{error}</span> : hint && <span className="field-hint">{hint}</span>}
    </label>
  );
}
