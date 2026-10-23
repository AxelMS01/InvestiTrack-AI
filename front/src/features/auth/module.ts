import type { ModuleDefinition } from '../module.types';

export const authModule = {
  id: 'auth',
  label: 'Autenticación',
  screens: [
    { id: 'login', label: 'Login', path: '/login', status: 'implemented' },
    { id: 'forbidden', label: '403 · Acceso denegado', path: '/sin-acceso', status: 'implemented' },
    {
      id: 'expired',
      label: '401 · Sesión expirada',
      path: '/sesion-expirada',
      status: 'implemented',
    },
  ],
} as const satisfies ModuleDefinition;
