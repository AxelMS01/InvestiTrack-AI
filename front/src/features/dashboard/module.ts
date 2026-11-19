import type { ModuleDefinition } from '../module.types';

export const dashboardModule = {
  id: 'dashboard',
  label: 'Panel de control',
  screens: [
    { id: 'general', label: 'Inicio', path: '/', status: 'MOCK_COMPLETE' },
    {
      id: 'admin',
      label: 'Resumen administrativo',
      path: '/panel/administrador',
      status: 'MOCK_COMPLETE',
      roles: ['admin'],
    },
    {
      id: 'researcher',
      label: 'Mi investigación',
      path: '/panel/investigador',
      status: 'MOCK_COMPLETE',
      roles: ['researcher'],
    },
    {
      id: 'coordinator',
      label: 'Centro de coordinación',
      path: '/panel/coordinador',
      status: 'MOCK_COMPLETE',
      roles: ['coordinator'],
    },
    {
      id: 'evaluator',
      label: 'Centro de evaluaciones',
      path: '/panel/evaluador',
      status: 'MOCK_COMPLETE',
      roles: ['reviewer'],
    },
    {
      id: 'student',
      label: 'Mis actividades de investigación',
      path: '/panel/estudiante',
      status: 'MOCK_COMPLETE',
      roles: ['student'],
    },
    {
      id: 'notifications',
      label: 'Notificaciones',
      path: '/notificaciones',
      status: 'MOCK_COMPLETE',
    },
    { id: 'profile', label: 'Mi perfil', path: '/perfil', status: 'MOCK_COMPLETE' },
  ],
} as const satisfies ModuleDefinition;
