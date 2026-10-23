import type { ModuleDefinition } from '../module.types';

export const evaluationsModule = {
  id: 'evaluations',
  label: 'Evaluaciones',
  screens: [
    { id: 'pending', label: 'Pendientes', path: '/evaluaciones', status: 'planned' },
    {
      id: 'assign',
      label: 'Asignar',
      path: '/evaluaciones/asignar',
      status: 'planned',
      roles: ['admin', 'coordinator'],
    },
    {
      id: 'rubric',
      label: 'Formulario de rúbrica',
      path: '/evaluaciones/:id/rubrica',
      status: 'planned',
      roles: ['reviewer'],
    },
    { id: 'results', label: 'Resultados', path: '/evaluaciones/:id/resultados', status: 'planned' },
    {
      id: 'decision',
      label: 'Decisión',
      path: '/evaluaciones/:id/decision',
      status: 'planned',
      roles: ['admin', 'coordinator'],
    },
  ],
} as const satisfies ModuleDefinition;
