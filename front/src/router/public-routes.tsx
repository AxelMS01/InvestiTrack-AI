import type { RouteObject } from 'react-router-dom';
import { AuthLayout } from '../layouts/auth-layout';
import { SessionExpiredPage } from '../features/auth/session-expired-page';

export const publicRoutes: RouteObject[] = [
  {
    element: <AuthLayout />,
    children: [
      {
        path: '/login',
        lazy: async () => ({ Component: (await import('../features/auth/login-page')).LoginPage }),
      },
      { path: '/sesion-expirada', element: <SessionExpiredPage /> },
    ],
  },
];
