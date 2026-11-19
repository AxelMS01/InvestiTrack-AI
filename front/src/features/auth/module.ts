import type { ModuleDefinition } from '../module.types';

export const authModule = {
  id: 'auth',
  label: 'Autenticación',
  screens: [
    { id: 'login', label: 'Iniciar sesión', path: '/login', status: 'MOCK_COMPLETE' },
    { id: 'forbidden', label: 'Acceso denegado', path: '/sin-acceso', status: 'MOCK_COMPLETE' },
    { id: 'expired', label: 'Sesión expirada', path: '/sesion-expirada', status: 'MOCK_COMPLETE' },
  ],
} as const satisfies ModuleDefinition;
