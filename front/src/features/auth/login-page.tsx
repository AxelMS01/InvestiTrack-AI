import { Button } from '../../components/button';
import { Input } from '../../components/input';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { ArrowRight } from 'lucide-react';
import { AsyncState } from '../../components/ui';
import { config } from '../../lib/config';
import { authApi } from '../../api/auth.api';
import { useSession, sessionKey } from '../../api/auth.queries';
import {
  credentialsSchema,
  roleLabels,
  roleSchema,
  type Credentials,
  type Role,
  type User,
} from './model';

export function LoginPage() {
  const [role, setRole] = useState<Role>('admin');
  const client = useQueryClient();
  const navigate = useNavigate();
  const location = useLocation();
  const session = useSession();
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<Credentials>({ resolver: zodResolver(credentialsSchema) });
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
    client.setQueryData(sessionKey, user);
    void navigate(from, { replace: true });
  }
  const login = useMutation({ mutationFn: authApi.login, onSuccess: complete });
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
            onChange={(event) => setRole(roleSchema.parse(event.target.value))}
          >
            {roleSchema.options.map((item) => (
              <option key={item} value={item}>
                {roleLabels[item]}
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
        <form
          className="mt-8 space-y-5"
          onSubmit={handleSubmit((values) => login.mutate(values))}
          noValidate
        >
          <div>
            <label className="field-label" htmlFor="email">
              Correo institucional
            </label>
            <Input
              id="email"
              type="email"
              autoComplete="username"
              className="input"
              {...register('email')}
              aria-invalid={Boolean(errors.email)}
              aria-describedby={errors.email ? 'email-error' : undefined}
            />
            {errors.email && (
              <p id="email-error" className="field-error">
                {errors.email.message}
              </p>
            )}
          </div>
          <div>
            <label className="field-label" htmlFor="password">
              Contraseña
            </label>
            <Input
              id="password"
              type="password"
              autoComplete="current-password"
              className="input"
              {...register('password')}
              aria-invalid={Boolean(errors.password)}
              aria-describedby={errors.password ? 'password-error' : undefined}
            />
            {errors.password && (
              <p id="password-error" className="field-error">
                {errors.password.message}
              </p>
            )}
          </div>
          <Button
            type="submit"
            className="btn-primary w-full justify-center"
            disabled={login.isPending}
          >
            {login.isPending ? 'Ingresando…' : 'Iniciar sesión'}
            <ArrowRight size={16} />
          </Button>
        </form>
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
