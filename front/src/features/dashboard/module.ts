import type { ModuleDefinition } from '../module.types';

export const dashboardModule = {
  id: 'dashboard',
  label: 'Panel de control',
  screens: [
    { id: 'general', label: 'General', path: '/', status: 'implemented' },
    {
      id: 'researcher',
      label: 'Investigador',
      path: '/panel/investigador',
      status: 'planned',
      roles: ['researcher'],
    },
    {
      id: 'coordinator',
      label: 'Coordinador',
      path: '/panel/coordinador',
      status: 'planned',
      roles: ['coordinator', 'admin'],
    },
    { id: 'notifications', label: 'Notificaciones', path: '/notificaciones', status: 'planned' },
    { id: 'profile', label: 'Perfil', path: '/perfil', status: 'planned' },
  ],
} as const satisfies ModuleDefinition;
