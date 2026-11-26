import { Link } from 'react-router-dom';
import type { Role } from '../../types/user';
import { useAuth } from '../auth/auth-context';
import { can } from '../auth/model';

export function InstitutionalDashboardPage({ roleOverride }: { roleOverride?: Role }) {
  const { user, workspace } = useAuth();
  const role = roleOverride ?? workspace;
  if (!user || !role) return null;
  return (
    <section className="space-y-5">
      <h1 className="text-2xl font-semibold">Panel de {user.name}</h1>
      <div className="flex flex-wrap gap-3">
        {can(role, 'proposal:read') && (
          <Link className="btn-secondary" to="/propuestas">
            Propuestas
          </Link>
        )}
        {can(role, 'proposal:create') && (
          <Link className="btn-primary" to="/propuestas/nueva">
            Crear propuesta
          </Link>
        )}
        {can(role, 'evaluation:read') && (
          <Link className="btn-secondary" to="/evaluaciones">
            Evaluaciones
          </Link>
        )}
        {can(role, 'project:read') && (
          <Link className="btn-secondary" to="/proyectos">
            Proyectos
          </Link>
        )}
        {can(role, 'user:read') && (
          <Link className="btn-secondary" to="/usuarios">
            Usuarios
          </Link>
        )}
      </div>
    </section>
  );
}
