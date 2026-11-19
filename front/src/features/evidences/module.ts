import type { ModuleDefinition } from '../module.types';

export const evidencesModule = {
  id: 'evidences',
  label: 'Evidencias',
  screens: [
    { id: 'list', label: 'Evidencias', path: '/evidencias', status: 'MOCK_COMPLETE' },
    {
      id: 'create',
      label: 'Crear evidencia',
      path: '/evidencias/nueva',
      status: 'MOCK_COMPLETE',
      roles: ['student'],
    },
    {
      id: 'detail',
      label: 'Detalle de evidencia',
      path: '/evidencias/:id',
      status: 'MOCK_COMPLETE',
    },
    {
      id: 'grade',
      label: 'Evaluar evidencia',
      path: '/evidencias/:id/evaluar',
      status: 'MOCK_COMPLETE',
      roles: ['reviewer'],
    },
    {
      id: 'project',
      label: 'Evidencias del proyecto',
      path: '/proyectos/:id/evidencias',
      status: 'MOCK_COMPLETE',
    },
  ],
} as const satisfies ModuleDefinition;
