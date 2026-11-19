import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useDemoQuery } from '../../api/demo.queries';
import { accountStatusLabels, userRoles } from '../../types/user';
import { config } from '../../lib/config';
import { useAuth } from '../auth/auth-context';
import { ActionLink, Field } from '../shared/workflow-ui';
import { Input } from '../../components/input';
import { ShieldCheck } from 'lucide-react';
import { AsyncState, EmptyState, PageHeading } from '../../components/ui';
import { initials } from '../../lib/format';
import { authApi } from '../../api/auth.api';
import { can, roleLabels } from '../auth/model';

export function UsersPage() {
  const query = useDemoQuery(['users'], authApi.users);
  const { user } = useAuth();
  const [search, setSearch] = useState('');
  const users =
    query.data?.filter((item) =>
      `${item.name} ${item.email}`.toLowerCase().includes(search.toLowerCase()),
    ) ?? [];
  return (
    <>
      <PageHeading
        eyebrow="Administración institucional"
        title="Directorio de usuarios"
        description="Consulta los perfiles y roles con acceso a la plataforma institucional."
        action={
          config.demo &&
          user &&
          can(user, 'user:create') && (
            <ActionLink to="/usuarios/nuevo" primary>
              Crear usuario
            </ActionLink>
          )
        }
      />
      <div className="mb-6 flex items-start gap-3 rounded-xl border border-line bg-white p-5">
        <ShieldCheck size={20} className="shrink-0 text-accent" />
        <p className="text-sm leading-6 text-muted">
          Este espacio está disponible para el perfil de administración. Los permisos de los demás
          módulos dependen del rol de la sesión.
        </p>
      </div>
      <div className="mb-4 max-w-sm">
        <Field label="Buscar por nombre o correo">
          <Input value={search} onChange={(event) => setSearch(event.target.value)} />
        </Field>
      </div>
      <section className="panel overflow-hidden">
        {query.isPending ? (
          <AsyncState loading />
        ) : query.error ? (
          <AsyncState error={query.error} retry={() => void query.refetch()} />
        ) : !users.length ? (
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
                    Roles
                  </th>
                  <th scope="col" className="p-5 font-medium">
                    Estado
                  </th>
                </tr>
              </thead>
              <tbody>
                {users.map((user) => (
                  <tr key={user.id} className="border-b border-line last:border-0">
                    <td className="p-5">
                      <span className="flex items-center gap-3">
                        <span className="avatar bg-sage text-forest">{initials(user.name)}</span>
                        {config.demo ? (
                          <Link className="text-accent underline" to={`/usuarios/${user.id}`}>
                            {user.name}
                          </Link>
                        ) : (
                          user.name
                        )}
                      </span>
                    </td>
                    <td className="p-5 text-muted">{user.email}</td>
                    <td className="p-5">
                      <span className="rounded-full bg-canvas px-3 py-1 text-xs text-forest">
                        {userRoles(user)
                          .map((role) => roleLabels[role])
                          .join(', ')}
                      </span>
                    </td>
                    <td className="p-5 text-muted">
                      {accountStatusLabels[user.status ?? 'ACTIVE']}
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
