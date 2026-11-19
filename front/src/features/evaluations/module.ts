import type { ModuleDefinition } from '../module.types';

export const evaluationsModule = {
  id: 'evaluations',
  label: 'Evaluaciones',
  screens: [
    {
      id: 'pending',
      label: 'Evaluaciones',
      path: '/evaluaciones',
      status: 'MOCK_COMPLETE',
      roles: ['coordinator', 'reviewer'],
    },
    {
      id: 'assign',
      label: 'Asignar evaluador',
      path: '/evaluaciones/asignar',
      status: 'MOCK_COMPLETE',
      roles: ['coordinator'],
    },
    {
      id: 'rubric',
      label: 'Rúbrica',
      path: '/evaluaciones/:id/rubrica',
      status: 'MOCK_COMPLETE',
      roles: ['reviewer'],
    },
    {
      id: 'results',
      label: 'Resultados de evaluación',
      path: '/evaluaciones/:id/resultados',
      status: 'MOCK_COMPLETE',
      roles: ['coordinator', 'reviewer'],
    },
    {
      id: 'decision',
      label: 'Decisión institucional',
      path: '/evaluaciones/:id/decision',
      status: 'MOCK_COMPLETE',
      roles: ['coordinator'],
    },
  ],
} as const satisfies ModuleDefinition;
