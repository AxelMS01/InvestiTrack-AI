import { Badge } from './badge';
import { ArrowLeft, FlaskConical, LoaderCircle, RotateCcw } from 'lucide-react';
import { Link } from 'react-router-dom';
import type { ReactNode } from 'react';
import { statusLabels, type ProjectStatus } from '../types/project';

export function Brand({ compact = false }: { compact?: boolean }) {
  return (
    <span className="flex items-center gap-3">
      <span className="brand-mark">
        <FlaskConical size={23} aria-hidden="true" />
      </span>
      <span className="font-semibold tracking-tight">
        Invesctic<span className="font-normal opacity-75">Track</span>
        {!compact && (
          <span className="mt-0.5 block text-[10px] font-normal uppercase tracking-[0.2em] opacity-60">
            Gestión de investigación
          </span>
        )}
      </span>
    </span>
  );
}
export function PageHeading({
  eyebrow,
  title,
  description,
  action,
}: {
  eyebrow: string;
  title: string;
  description: string;
  action?: ReactNode;
}) {
  return (
    <div className="mb-8 flex flex-wrap items-end justify-between gap-4">
      <div>
        <p className="eyebrow">{eyebrow}</p>
        <h1 className="mt-2 text-3xl font-semibold tracking-tight text-ink sm:text-4xl">{title}</h1>
        <p className="mt-3 max-w-2xl text-sm leading-6 text-muted">{description}</p>
      </div>
      {action}
    </div>
  );
}
export function StatusBadge({ status }: { status: ProjectStatus }) {
  return (
    <Badge className={`status-${status}`}>
      <span className="size-1.5 rounded-full bg-current" />
      {statusLabels[status]}
    </Badge>
  );
}
export function Progress({ value }: { value: number }) {
  return (
    <div className="flex items-center gap-3">
      <div
        role="progressbar"
        aria-label="Avance del proyecto"
        aria-valuenow={value}
        aria-valuemin={0}
        aria-valuemax={100}
        className="h-1.5 min-w-16 flex-1 overflow-hidden rounded-full bg-slate-100"
      >
        <div className="h-full rounded-full bg-accent" style={{ width: `${value}%` }} />
      </div>
      <span className="w-9 text-right text-xs font-medium text-muted">{value}%</span>
    </div>
  );
}
export function AsyncState({
  loading,
  error,
  retry,
}: {
  loading?: boolean;
  error?: Error;
  retry?: () => void;
}) {
  return (
    <div
      className="m-auto flex min-h-64 max-w-xl flex-col items-center justify-center gap-4 p-8 text-center"
      role={error ? 'alert' : 'status'}
    >
      {loading ? (
        <>
          <LoaderCircle className="animate-spin text-accent" aria-hidden="true" />
          <p className="text-sm text-muted">Cargando información…</p>
        </>
      ) : (
        <>
          <p className="font-medium">No pudimos cargar la información</p>
          <p className="text-sm text-muted">{error?.message}</p>
          {retry && (
            <button className="btn-secondary" onClick={retry}>
              <RotateCcw size={16} />
              Reintentar
            </button>
          )}
        </>
      )}
    </div>
  );
}
export function EmptyState({
  title,
  description,
  action,
}: {
  title: string;
  description: string;
  action?: ReactNode;
}) {
  return (
    <div className="px-6 py-16 text-center">
      <FlaskConical className="mx-auto mb-4 text-accent" size={32} aria-hidden="true" />
      <h2 className="font-semibold">{title}</h2>
      <p className="mx-auto mt-2 max-w-md text-sm leading-6 text-muted">{description}</p>
      {action && <div className="mt-5">{action}</div>}
    </div>
  );
}
export function BackLink() {
  return (
    <Link
      to="/proyectos"
      className="mb-6 inline-flex items-center gap-2 text-sm text-muted hover:text-ink"
    >
      <ArrowLeft size={16} />
      Volver a proyectos
    </Link>
  );
}
