import { Link } from 'react-router-dom';
import { EmptyState } from '../components/ui';

export function NotFoundPage() {
  return (
    <EmptyState
      title="Esta página no existe"
      description="Verifica la dirección o regresa al panel para continuar."
      action={
        <Link to="/" className="btn-primary">
          Volver al panel
        </Link>
      }
    />
  );
}
export function RouteErrorPage() {
  return (
    <EmptyState
      title="No fue posible abrir esta página"
      description="Puede haber un problema de conexión. Recarga la aplicación para volver a intentarlo."
      action={
        <button className="btn-primary" onClick={() => window.location.reload()}>
          Recargar
        </button>
      }
    />
  );
}
