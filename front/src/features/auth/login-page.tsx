import { Button } from '../../components/button';
import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { ArrowRight } from 'lucide-react';
import { AsyncState } from '../../components/ui';
import { config } from '../../lib/config';
import { authApi, demoProfiles } from '../../api/auth.api';
import { useSession, sessionKey } from '../../api/auth.queries';
import { roleLabels, type User } from './model';
import { beginLogin } from '../../api/oidc';

export function LoginPage() {
  const [role, setRole] = useState('admin');
  const client = useQueryClient();
  const navigate = useNavigate();
  const location = useLocation();
  const session = useSession();
  const state: unknown = location.state;
  const from =
    typeof state === 'object' &&
    state !== null &&
    'from' in state &&
    typeof state.from === 'string' &&
    state.from.startsWith('/') &&
    !state.from.startsWith('//') &&
    !state.from.startsWith('/login')
      ? state.from
      : '/';
  function complete(user: User) {
    client.removeQueries({ predicate: (query) => query.queryKey[0] !== 'session' });
    client.getMutationCache().clear();
    client.setQueryData(sessionKey, user);
    void navigate(from, { replace: true });
  }
  const login = useMutation({ mutationFn: () => beginLogin(from) });
  const demo = useMutation({ mutationFn: authApi.demoLogin, onSuccess: complete });
  if (session.isPending) return <AsyncState loading />;
  if (session.data && !session.error) return <Navigate to={from} replace />;
  const error = login.error ?? demo.error;
  return (
    <>
      <p className="eyebrow">Bienvenido a tu espacio</p>
      <h2 className="mt-3 text-3xl font-semibold tracking-tight">Iniciar sesión</h2>
      <p className="mt-3 text-sm leading-6 text-muted">
        Accede al seguimiento de tus proyectos de investigación.
      </p>
      {config.demo ? (
        <div className="mt-8">
          <div className="mb-6 rounded-xl border border-amber-200 bg-amber-50 p-4 text-sm leading-6 text-amber-900">
            <strong className="block">Entorno de demostración</strong>Datos ficticios. Selecciona un
            perfil para explorar sus permisos. La sesión se cierra al recargar.
          </div>
          <label htmlFor="role" className="field-label">
            Perfil de acceso
          </label>
          <select
            id="role"
            className="input"
            value={role}
            onChange={(event) => setRole(event.target.value)}
          >
            {demoProfiles.map((profile) => (
              <option key={profile.id} value={profile.value}>
                {roleLabels[profile.role]} · {profile.name}
              </option>
            ))}
          </select>
          <Button
            className="btn-primary mt-6 w-full justify-center"
            disabled={demo.isPending}
            onClick={() => demo.mutate(role)}
          >
            Entrar a la demostración
            <ArrowRight size={16} />
          </Button>
        </div>
      ) : (
        <Button
          className="btn-primary mt-8 w-full justify-center"
          disabled={login.isPending}
          onClick={() => login.mutate()}
        >
          Iniciar sesión con Keycloak
          <ArrowRight size={16} />
        </Button>
      )}
      {error && (
        <p role="alert" className="field-error mt-4">
          {error.message}
        </p>
      )}
      <p className="mt-8 border-t border-line pt-6 text-xs leading-6 text-muted">
        {config.demo
          ? 'Los proyectos creados se conservan únicamente en este navegador.'
          : 'Para solicitar acceso, contacta a la administración de tu institución.'}
      </p>
    </>
  );
}
