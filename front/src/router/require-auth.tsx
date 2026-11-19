import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { ApiError } from '../api/http-client';
import { AsyncState } from '../components/ui';
import { useAuth } from '../features/auth/auth-context';
import { Fragment } from 'react';
import { subjectKey } from '../features/auth/access-policy';
import { userRoles } from '../types/user';
import { can, type Capability } from '../features/auth/model';
import type { Role } from '../features/auth/model';

export function RequireAuth({
  roles,
  capability,
}: {
  roles?: readonly Role[];
  capability?: Capability;
}) {
  const auth = useAuth();
  const location = useLocation();
  if (auth.loading || auth.switching) return <AsyncState loading />;
  if (auth.error instanceof ApiError && auth.error.status === 401) {
    return <Navigate to="/sesion-expirada" replace />;
  }
  if (auth.error) {
    return <AsyncState error={auth.error} retry={auth.retry} />;
  }
  if (!auth.user)
    return <Navigate to="/login" state={{ from: location.pathname + location.search }} replace />;
  if (
    (auth.user.status && auth.user.status !== 'ACTIVE' && location.pathname !== '/sin-acceso') ||
    (roles && !userRoles(auth.user).some((role) => roles.includes(role))) ||
    (capability && !can(auth.user, capability))
  )
    return <Navigate to="/sin-acceso" replace />;
  return (
    <Fragment key={subjectKey(auth.user)}>
      <Outlet />
    </Fragment>
  );
}
