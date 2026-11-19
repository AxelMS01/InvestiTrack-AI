import type { ModuleDefinition } from '../module.types';

export const reportsModule = {
  id: 'reports',
  label: 'Reportes',
  screens: [
    {
      id: 'indicators',
      label: 'Reportes',
      path: '/reportes',
      status: 'MOCK_COMPLETE',
      roles: ['coordinator', 'researcher'],
    },
    {
      id: 'export',
      label: 'Exportación',
      path: '/reportes/exportacion',
      status: 'MOCK_COMPLETE',
      roles: ['coordinator'],
    },
    {
      id: 'final-report',
      label: 'Informe final',
      path: '/reportes/proyectos/:id/informe-final',
      status: 'MOCK_COMPLETE',
      roles: ['coordinator', 'researcher'],
    },
    {
      id: 'publications',
      label: 'Publicaciones',
      path: '/reportes/publicaciones',
      status: 'MOCK_COMPLETE',
      roles: ['coordinator', 'researcher'],
    },
    {
      id: 'closure',
      label: 'Cierre de proyecto',
      path: '/reportes/proyectos/:id/cierre',
      status: 'MOCK_COMPLETE',
      roles: ['coordinator'],
    },
  ],
} as const satisfies ModuleDefinition;
