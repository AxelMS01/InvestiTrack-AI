import { useEffect, useRef, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate } from 'react-router-dom';
import { authApi } from '../../api/auth.api';
import { sessionKey } from '../../api/auth.queries';
import { completeLogin } from '../../api/oidc';
import { AsyncState } from '../../components/ui';
import { config } from '../../lib/config';

export function CallbackPage() {
  const client = useQueryClient();
  const navigate = useNavigate();
  const started = useRef(false);
  const [error, setError] = useState<Error>();
  useEffect(() => {
    if (started.current || config.demo) return;
    started.current = true;
    void completeLogin()
      .then(async (target) => {
        await client.cancelQueries({ queryKey: sessionKey });
        client.setQueryData(sessionKey, await authApi.me());
        await navigate(target, { replace: true });
      })
      .catch((failure: unknown) =>
        setError(failure instanceof Error ? failure : new Error('No se pudo iniciar sesión.')),
      );
  }, [client, navigate]);

  if (config.demo || error) {
    return (
      <>
        <AsyncState
          error={error ?? new Error('El acceso institucional requiere el modo integrado.')}
        />{' '}
        <Link to="/login">Volver al inicio de sesión</Link>
      </>
    );
  }
  return <AsyncState loading />;
}
