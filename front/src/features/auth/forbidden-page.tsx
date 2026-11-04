import { Link } from 'react-router-dom';
import { EmptyState } from '../../components/ui';

export function ForbiddenPage() {
  return (
    <EmptyState
      title="403 · Acceso denegado"
      description="El rol de tu sesión no tiene permisos para consultar este espacio."
      action={
        <Link to="/" className="btn-primary">
          Volver al panel
        </Link>
      }
    />
  );
}
