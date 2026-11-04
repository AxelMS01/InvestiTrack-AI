import { Link } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { sessionKey } from '../../api/auth.queries';

export function SessionExpiredPage() {
  const client = useQueryClient();
  return (
    <div>
      <p className="eyebrow">401 · Sesión expirada</p>
      <h2 className="mt-3 text-3xl font-semibold">Vuelve a iniciar sesión</h2>
      <p className="my-6 text-sm leading-6 text-muted">
        Tu sesión ya no está disponible. Ingresa de nuevo para continuar con tu investigación.
      </p>
      <Link
        to="/login"
        className="btn-primary"
        onClick={() => {
          client.removeQueries({ predicate: (query) => query.queryKey[0] !== 'session' });
          client.setQueryData(sessionKey, null);
        }}
      >
        Ir al inicio de sesión
      </Link>
    </div>
  );
}
