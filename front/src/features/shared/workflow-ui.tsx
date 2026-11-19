import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { AsyncState, EmptyState } from '../../components/ui';
import { config } from '../../lib/config';

export function WorkflowState({
  query,
  children,
}: {
  query: { isPending: boolean; error: Error | null; refetch: () => Promise<unknown> };
  children: ReactNode;
}) {
  if (query.isPending) return <AsyncState loading />;
  if (query.error) return <AsyncState error={query.error} retry={() => void query.refetch()} />;
  return children;
}

export function WorkflowList({
  children,
  empty = 'Todavía no hay registros para esta vista.',
}: {
  children: ReactNode[];
  empty?: string;
}) {
  return (
    <section className="panel divide-y divide-line">
      {children.length ? (
        children
      ) : (
        <EmptyState
          title={empty}
          description="Los registros autorizados aparecerán en esta sección."
        />
      )}
    </section>
  );
}

export function ActionLink({
  to,
  children,
  primary = false,
}: {
  to: string;
  children: ReactNode;
  primary?: boolean;
}) {
  return (
    <Link className={primary ? 'btn-primary' : 'btn-secondary'} to={to}>
      {children}
    </Link>
  );
}

export function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <label className="block">
      <span className="field-label">{label}</span>
      {children}
    </label>
  );
}

export function Feedback({ error, success }: { error?: Error | null; success?: string | false }) {
  return error ? (
    <p role="alert" className="field-error">
      {error.message}
    </p>
  ) : success ? (
    <p role="status" className="rounded-lg bg-sage p-3 text-sm text-forest">
      {success}
    </p>
  ) : null;
}

export function DemoOnly({ children }: { children: ReactNode }) {
  return config.demo ? (
    children
  ) : (
    <EmptyState
      title="Servicio pendiente de integración"
      description="Esta operación está disponible en el modo de demostración."
    />
  );
}
