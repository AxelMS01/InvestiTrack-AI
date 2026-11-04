import { useQuery } from '@tanstack/react-query';
import { ShieldCheck } from 'lucide-react';
import { AsyncState, EmptyState, PageHeading } from '../../components/ui';
import { initials } from '../../lib/format';
import { authApi } from '../../api/auth.api';
import { roleLabels } from '../auth/model';

export function UsersPage() {
  const query = useQuery({ queryKey: ['users'], queryFn: authApi.users });
  return (
    <>
      <PageHeading
        eyebrow="Administración institucional"
        title="Directorio de usuarios"
        description="Consulta los perfiles y roles con acceso a la plataforma institucional."
      />
      <div className="mb-6 flex items-start gap-3 rounded-xl border border-line bg-white p-5">
        <ShieldCheck size={20} className="shrink-0 text-accent" />
        <p className="text-sm leading-6 text-muted">
          Este espacio está disponible para el perfil de administración. Los permisos de los demás
          módulos dependen del rol de la sesión.
        </p>
      </div>
      <section className="panel overflow-hidden">
        {query.isPending ? (
          <AsyncState loading />
        ) : query.error ? (
          <AsyncState error={query.error} retry={() => void query.refetch()} />
        ) : !query.data.length ? (
          <EmptyState
            title="No hay usuarios registrados"
            description="Los perfiles institucionales aparecerán aquí."
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[580px] text-left text-sm">
              <caption className="sr-only">Usuarios institucionales y roles</caption>
              <thead className="border-b border-line bg-canvas text-xs text-muted">
                <tr>
                  <th scope="col" className="p-5 font-medium">
                    Usuario
                  </th>
                  <th scope="col" className="p-5 font-medium">
                    Correo institucional
                  </th>
                  <th scope="col" className="p-5 font-medium">
                    Rol
                  </th>
                </tr>
              </thead>
              <tbody>
                {query.data.map((user) => (
                  <tr key={user.id} className="border-b border-line last:border-0">
                    <td className="p-5">
                      <span className="flex items-center gap-3">
                        <span className="avatar bg-sage text-forest">{initials(user.name)}</span>
                        {user.name}
                      </span>
                    </td>
                    <td className="p-5 text-muted">{user.email}</td>
                    <td className="p-5">
                      <span className="rounded-full bg-canvas px-3 py-1 text-xs text-forest">
                        {roleLabels[user.role]}
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </>
  );
}
